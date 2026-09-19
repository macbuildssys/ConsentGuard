package dev.macbuildssys.consentguard

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/* Text handling shared by everything that compares screen text with the
   phrases in consent_rules.json and consent_lexicon.json.

   Both sides are put through the same normalize function before any
   comparison: lowercase, apostrophes removed, "&" turned into "and", and
   every other symbol or run of symbols collapsed into a single space. That
   means "Don't accept", "DON'T ACCEPT" and "dont accept" all end up as
   "dont accept", "Save & Exit" and "Save and exit" end up identical, and
   "Opt-out" and "opt out" end up identical. Phrase lists therefore only
   need one spelling of each phrase. */
object ConsentText {
    fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        var lastWasSpace = true
        for (raw in input) {
            when {
                raw == '\'' || raw == '\u2019' || raw == '\u2018' || raw == '`' -> {
                    // Apostrophes vanish so "don't" and "dont" are the same word.
                }
                raw == '&' -> {
                    if (!lastWasSpace) out.append(' ')
                    out.append("and ")
                    lastWasSpace = true
                }
                raw.isLetterOrDigit() -> {
                    out.append(raw.lowercaseChar())
                    lastWasSpace = false
                }
                else -> {
                    if (!lastWasSpace) {
                        out.append(' ')
                        lastWasSpace = true
                    }
                }
            }
        }
        return out.toString().trim()
    }

    /* Visible text, or the content description when a node has no text of
       its own (a lot of web content puts the label there). Blank counts as
       missing, so an empty text does not hide a usable description. */
    fun labelOf(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) return text
        val description = node.contentDescription?.toString()
        if (!description.isNullOrBlank()) return description
        return null
    }
}

/* A list of phrases in one of two forms, as written in the JSON files.

   A plain phrase matches when the screen text contains it anywhere.
   A phrase starting with "=" matches only when the whole screen text is
   exactly that phrase. Exact phrases are what keep short, common words
   safe: "=settings" matches a button that says just "Settings" but not a
   sentence that happens to mention settings, and "=save" matches a button
   that says just "Save" but not "Save 20 percent".

   Exact phrases are held in a set, so having hundreds of them costs almost
   nothing per comparison. */
class PatternList(patterns: List<String>) {
    val substrings: List<String>
    val exacts: Set<String>
    private val paddedSubstrings: List<String>

    init {
        val subs = mutableListOf<String>()
        val exact = HashSet<String>()
        for (pattern in patterns) {
            if (pattern.startsWith("=")) {
                val body = ConsentText.normalize(pattern.substring(1))
                if (body.isNotEmpty()) exact.add(body)
            } else {
                val body = ConsentText.normalize(pattern)
                if (body.isNotEmpty()) subs.add(body)
            }
        }
        substrings = subs
        exacts = exact
        paddedSubstrings = subs.map { " $it " }
    }

    fun isEmpty(): Boolean = substrings.isEmpty() && exacts.isEmpty()

    /* A page shows the same short labels scan after scan, and a list with
       hundreds of phrases is slow to run against every one of them each
       time. So the answer for each short label is remembered. Long texts
       (paragraphs) are not remembered, they rarely repeat and would only
       fill the memory. Only ever used from the main thread. */
    private val matchCache = HashMap<String, Boolean>()
    private val wordCache = HashMap<String, Boolean>()

    /* Expects text that has already been through ConsentText.normalize. */
    fun matches(normalizedText: String): Boolean {
        if (normalizedText.isEmpty()) return false
        val cacheable = normalizedText.length <= CACHE_TEXT_LIMIT
        if (cacheable) matchCache[normalizedText]?.let { return it }

        var result = exacts.contains(normalizedText)
        if (!result) {
            for (pattern in substrings) {
                if (normalizedText.contains(pattern)) {
                    result = true
                    break
                }
            }
        }

        if (cacheable) {
            if (matchCache.size >= CACHE_LIMIT) matchCache.clear()
            matchCache[normalizedText] = result
        }
        return result
    }

    /* Same as matches, except a plain phrase only counts when it lines up
       with whole words. Used for the safety lists, where "pay" must catch
       "Pay now" but never a word that merely contains those letters. */
    fun matchesWords(normalizedText: String): Boolean {
        if (normalizedText.isEmpty()) return false
        val cacheable = normalizedText.length <= CACHE_TEXT_LIMIT
        if (cacheable) wordCache[normalizedText]?.let { return it }

        var result = exacts.contains(normalizedText)
        if (!result) {
            val padded = " $normalizedText "
            for (pattern in paddedSubstrings) {
                if (padded.contains(pattern)) {
                    result = true
                    break
                }
            }
        }

        if (cacheable) {
            if (wordCache.size >= CACHE_LIMIT) wordCache.clear()
            wordCache[normalizedText] = result
        }
        return result
    }

    companion object {
        val EMPTY = PatternList(emptyList())
        private const val CACHE_TEXT_LIMIT = 200
        private const val CACHE_LIMIT = 4000
    }
}

/* One entry per consent flow the service knows how to handle.
   resourceIds match native Android views by their view ID (only
   meaningful for fully native consent SDKs, since Chrome and WebView
   content never exposes an Android view ID for its DOM elements).
   textPatterns match against a node's visible text or content
   description, which works for both native views and web content
   rendered through Chrome or a WebView, since Android's accessibility
   tree exposes the DOM as text nodes too. */
data class ConsentRule(
    val name: String,
    val rejectResourceIds: List<String>,
    val rejectTextPatterns: PatternList,
    val necessaryResourceIds: List<String>,
    val necessaryTextPatterns: PatternList,
    val manageResourceIds: List<String>,
    val manageTextPatterns: PatternList,
    /* For CMPs with no single reject all button, only a scrollable list
       of per purpose toggles. Any resource id starting with one of
       these prefixes is treated as a toggle to switch off (see
       google_funding_choices in consent_rules.json for the concrete
       example: Google's Funding Choices SDK exposes each IAB TCF
       purpose as "fc-preference-slider-purpose-<n>"). */
    val toggleResourceIdPrefixes: List<String> = emptyList(),
    /* For CMPs that use explicit "Accept" and "Reject" button pairs per
       purpose instead of a toggle switch (Sourcepoint style, confirmed
       from a real dump of spiegel.de: literal "Reject" buttons, no
       resource id at all). Matched by text or content description,
       tracked by a simple sequential count rather than by resource id,
       since there's no id to key on. */
    val rejectButtonTextPatterns: PatternList = PatternList.EMPTY,
    /* Section headers that are genuinely collapsed and need a tap to
       reveal more content inside (confirmed: "Special Purposes" and
       "Special Features for Third-Party-Vendors" on spiegel.de render
       as buttons with no reject option visible until expanded). Each
       is expanded at most once per session. */
    val expandSectionTextPatterns: PatternList = PatternList.EMPTY,
    val confirmResourceIds: List<String> = emptyList(),
    val confirmTextPatterns: PatternList = PatternList.EMPTY,
    /* Last resort reject wording, tried only after reject, necessary only,
       every toggle handler, and manage have all found nothing on the
       screen. Matched against short labels only. */
    val fallbackRejectTextPatterns: PatternList = PatternList.EMPTY
)

/* Shared vocabulary that is not tied to any one consent flow: what counts
   as a consent screen at all, what must never be tapped, and how to tell
   a switch that has to stay on from one that gets turned off. Lives in
   consent_lexicon.json so it can be extended without touching code. */
data class ConsentLexicon(
    /* At least one of these must be on screen before anything is tapped. */
    val anchorTextPatterns: PatternList,
    /* Phrases that only appear in a real cookie preference panel. The
       generic switch handler needs two different ones on screen before it
       flips a single switch, so an ordinary app settings screen that
       happens to say "cookies" somewhere is left alone. */
    val strongAnchorTextPatterns: PatternList,
    /* Payment, subscription and account wording. A label containing any
       of these is never tapped. */
    val neverClickTextPatterns: PatternList,
    /* Accept everything wording. A button label containing any of these is
       never tapped, even if some other phrase matched it by accident. */
    val acceptGuardTextPatterns: PatternList,
    /* A switch whose own label says one of these is required for the site
       to work, and is left alone. */
    val switchEssentialTextPatterns: PatternList,
    /* Overrides the list above: "non essential" and "unnecessary" contain
       the word essential or necessary, but describe switches to turn off. */
    val switchNonEssentialTextPatterns: PatternList,
    /* A switch that is on to protect the person ("do not sell my data",
       "block third party cookies", "remember my choice"). Left as it is. */
    val switchKeepOnTextPatterns: PatternList,
    val adsPurposeTextPatterns: PatternList,
    val statsPurposeTextPatterns: PatternList
) {
    /* True when a switch label says the switch is required for the site to
       work, so it is left on. Two things cancel that. "Non essential" and
       "unnecessary" describe switches to turn off. And a label that says
       required but also names an advertising or measurement purpose is not
       trusted, since a truly required switch does not need to say it is
       for ads. The person's privacy comes first when a label is doubtful. */
    /* Decides whether a screen really is a cookie or tracking consent
       screen, from the cleaned up text on it. Nothing is tapped anywhere
       else. Mentioning cookies is not enough, since recipes, news and
       settings pages do that too. A screen counts when it has consent
       wording and also looks like a banner: a button that chooses (reject,
       necessary only or manage) together with either an accept
       everything button or at least MIN_STRONG_PHRASES of the stronger
       cookie phrases; or several of those phrases together with a switch,
       for the preference panels whose Save button is off screen. A rule's
       own resource ids also count. */
    fun judgeScreen(
        texts: List<String>,
        hasSwitch: Boolean,
        resourceIdHit: Boolean,
        rules: List<ConsentRule>
    ): ScreenEvidence {
        val judge = ScreenJudge(this, rules)
        judge.hasSwitch = hasSwitch
        judge.resourceIdHit = resourceIdHit
        for (text in texts) judge.addText(text)
        return judge.result()
    }

    /* Consent wording, with one extra rule. The bare word "cookie" alone
       is not enough, since "Cookie baking class" is a calendar entry, not
       a banner. It only counts when the same text also says something a
       banner says: we use, accept, consent, policy, settings, privacy and
       the like. Every other anchor phrase counts on its own. */
    fun isConsentWording(text: String): Boolean {
        if (!anchorTextPatterns.matches(text)) return false
        if (anchorTextPatterns.exacts.contains(text)) return true
        for (phrase in anchorTextPatterns.substrings) {
            if (phrase != "cookie" && text.contains(phrase)) return true
        }
        if (!text.contains("cookie")) return false
        for (word in COOKIE_CONTEXT_WORDS) {
            if (text.contains(word)) return true
        }
        return false
    }

    /* Why a switch is left on, or null when it should be turned off. Two
       things matter. A long text is a description, not a label: a
       sentence that happens to say "required" or "protect" somewhere must
       not exempt the switch under it, so only short labels count. And the
       text before a switch is only trusted for wording that clearly makes
       a switch protective ("do not sell my data"); everyday words like
       "prevent" or "block" in a purpose description are not. */
    fun leavesSwitchOn(name: String, lastText: String): String? {
        val label = if (name.length <= MAX_SWITCH_LABEL_LENGTH) name else ""
        if (readsAsRequired(label)) return "it is required"
        if (switchKeepOnTextPatterns.matches(label) || protectiveByContext(lastText)) {
            return "it protects the person"
        }
        return null
    }

    private fun protectiveByContext(text: String): Boolean {
        if (text.isEmpty()) return false
        if (switchKeepOnTextPatterns.exacts.contains(text)) return true
        for (phrase in switchKeepOnTextPatterns.substrings) {
            if (phrase in GENERIC_KEEP_ON_WORDS) continue
            if (text.contains(phrase)) return true
        }
        return false
    }

    fun readsAsRequired(name: String): Boolean {
        if (switchNonEssentialTextPatterns.matches(name)) return false
        if (!switchEssentialTextPatterns.matches(name)) return false
        if (adsPurposeTextPatterns.matches(name)) return false
        if (statsPurposeTextPatterns.matches(name)) return false
        return true
    }

    companion object {
        const val SHORT_LABEL_LENGTH = 60
        const val MIN_STRONG_PHRASES = 2
        const val MAX_SWITCH_LABEL_LENGTH = 70

        /* Words that are too everyday to count as protective when they only
           appear in the text before a switch. */
        private val GENERIC_KEEP_ON_WORDS = setOf("block", "prevent", "protect", "restrict", "opt out")

        private val COOKIE_CONTEXT_WORDS = listOf(
            "we use", "use cookies", "uses cookies", "using cookies", "accept", "consent",
            "polic", "setting", "preference", "privacy", "partner", "personalis",
            "personaliz", "track", "advertis", "data"
        )

        /* Used if consent_lexicon.json is missing or unreadable, so the
           service keeps working with the wording it shipped with. */
        val DEFAULT = ConsentLexicon(
            anchorTextPatterns = PatternList(
                listOf(
                    "legitimate interest", "list of partners",
                    "advertising and content", "store and/or access information",
                    "cookie policy", "cookie consent", "gdpr",
                    "cookie", "cookies", "we value your privacy",
                    "we use cookies", "this site uses cookies",
                    "this website uses cookies", "we and our partners"
                )
            ),
            strongAnchorTextPatterns = PatternList(
                listOf(
                    "strictly necessary", "necessary cookies", "essential cookies",
                    "functional cookies", "performance cookies", "analytics cookies",
                    "marketing cookies", "targeting cookies", "advertising cookies",
                    "legitimate interest", "list of partners", "always active"
                )
            ),
            neverClickTextPatterns = PatternList(
                listOf("pay", "subscribe", "subscription", "purchase", "buy", "sign in", "log in", "login")
            ),
            acceptGuardTextPatterns = PatternList(
                listOf("accept all", "allow all", "agree to all", "accept and continue", "accept cookies")
            ),
            switchEssentialTextPatterns = PatternList(
                listOf("strictly necessary", "necessary", "essential", "required", "always active")
            ),
            switchNonEssentialTextPatterns = PatternList(
                listOf("non essential", "unnecessary", "not necessary", "optional")
            ),
            switchKeepOnTextPatterns = PatternList(
                listOf("opt out", "do not sell", "do not share", "remember my choice")
            ),
            adsPurposeTextPatterns = PatternList(listOf("advert", "marketing")),
            statsPurposeTextPatterns = PatternList(
                listOf("measure", "audience", "analytics", "performance", "statistic")
            )
        )
    }
}

fun Context.loadConsentRules(): List<ConsentRule> {
    val json = assets.open("consent_rules.json").bufferedReader().use { it.readText() }
    val array = JSONArray(json)
    val rules = mutableListOf<ConsentRule>()

    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        rules.add(
            ConsentRule(
                name = obj.getString("name"),
                rejectResourceIds = obj.optJSONArray("rejectResourceIds").toStringList(),
                rejectTextPatterns = obj.optJSONArray("rejectTextPatterns").toPatternList(),
                necessaryResourceIds = obj.optJSONArray("necessaryResourceIds").toStringList(),
                necessaryTextPatterns = obj.optJSONArray("necessaryTextPatterns").toPatternList(),
                manageResourceIds = obj.optJSONArray("manageResourceIds").toStringList(),
                manageTextPatterns = obj.optJSONArray("manageTextPatterns").toPatternList(),
                toggleResourceIdPrefixes = obj.optJSONArray("toggleResourceIdPrefixes").toStringList(),
                rejectButtonTextPatterns = obj.optJSONArray("rejectButtonTextPatterns").toPatternList(),
                expandSectionTextPatterns = obj.optJSONArray("expandSectionTextPatterns").toPatternList(),
                confirmResourceIds = obj.optJSONArray("confirmResourceIds").toStringList(),
                confirmTextPatterns = obj.optJSONArray("confirmTextPatterns").toPatternList(),
                fallbackRejectTextPatterns = obj.optJSONArray("fallbackRejectTextPatterns").toPatternList()
            )
        )
    }

    return rules
}

fun Context.loadConsentLexicon(): ConsentLexicon {
    val json = assets.open("consent_lexicon.json").bufferedReader().use { it.readText() }
    val obj = JSONObject(json)
    val fallback = ConsentLexicon.DEFAULT

    /* A missing or empty list in the file falls back to the built in
       wording for that one list, so a half edited file never leaves the
       service without any anchor or any safety guard. */
    fun listOrDefault(key: String, default: PatternList): PatternList {
        val loaded = obj.optJSONArray(key).toPatternList()
        return if (loaded.isEmpty()) default else loaded
    }

    return ConsentLexicon(
        anchorTextPatterns = listOrDefault("anchorTextPatterns", fallback.anchorTextPatterns),
        strongAnchorTextPatterns = listOrDefault("strongAnchorTextPatterns", fallback.strongAnchorTextPatterns),
        neverClickTextPatterns = listOrDefault("neverClickTextPatterns", fallback.neverClickTextPatterns),
        acceptGuardTextPatterns = listOrDefault("acceptGuardTextPatterns", fallback.acceptGuardTextPatterns),
        switchEssentialTextPatterns = listOrDefault("switchEssentialTextPatterns", fallback.switchEssentialTextPatterns),
        switchNonEssentialTextPatterns = listOrDefault("switchNonEssentialTextPatterns", fallback.switchNonEssentialTextPatterns),
        switchKeepOnTextPatterns = listOrDefault("switchKeepOnTextPatterns", fallback.switchKeepOnTextPatterns),
        adsPurposeTextPatterns = listOrDefault("adsPurposeTextPatterns", fallback.adsPurposeTextPatterns),
        statsPurposeTextPatterns = listOrDefault("statsPurposeTextPatterns", fallback.statsPurposeTextPatterns)
    )
}

/* Resource IDs are case sensitive Android identifiers, so these are kept
   exactly as written in the JSON. */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val list = mutableListOf<String>()
    for (i in 0 until length()) {
        list.add(getString(i))
    }
    return list
}

private fun JSONArray?.toPatternList(): PatternList = PatternList(toStringList())

/* What judgeScreen found. acceptButton is kept because the last resort
   reject words (decline, deny, refuse) are only tried on screens that also
   have an accept everything button, the way a real banner does. The other
   fields only say why a screen was or was not accepted. */
data class ScreenEvidence(
    val looksLikeConsent: Boolean,
    val acceptButton: Boolean,
    val consentWording: Boolean = false,
    val choiceButton: Boolean = false,
    val strongPhrases: Int = 0,
    val hasSwitch: Boolean = false
)

/* Collects the evidence for one screen a piece of text at a time, so the
   caller can walk the screen in whatever order suits it. */
class ScreenJudge(private val lexicon: ConsentLexicon, private val rules: List<ConsentRule>) {
    private val strong = HashSet<String>()
    private var consentWording = false
    private var acceptButton = false
    private var choiceButton = false
    var hasSwitch = false
    var resourceIdHit = false

    /* Whether the accept and the choosing buttons are close together on
       screen, as they are in a banner. The caller works that out from
       positions. */
    var buttonsClose = true

    /* Tells the caller what kind of text this was: ACCEPT_BUTTON for accept
       everything wording, CHOICE_BUTTON for reject, necessary only or manage
       wording, both only ever for short labels. */
    fun addText(text: String, mayBeButton: Boolean = true): Int {
        var kind = 0
        if (!consentWording && lexicon.isConsentWording(text)) consentWording = true
        for (phrase in lexicon.strongAnchorTextPatterns.substrings) {
            if (text.contains(phrase)) strong.add(phrase)
        }
        if (mayBeButton && text.length <= ConsentLexicon.SHORT_LABEL_LENGTH) {
            if (lexicon.acceptGuardTextPatterns.matchesWords(text)) {
                acceptButton = true
                kind = kind or ACCEPT_BUTTON
            }
            for (rule in rules) {
                if (rule.rejectTextPatterns.matches(text) ||
                    rule.necessaryTextPatterns.matches(text) ||
                    rule.manageTextPatterns.matches(text)
                ) {
                    choiceButton = true
                    kind = kind or CHOICE_BUTTON
                    break
                }
            }
        }
        return kind
    }

    fun result(): ScreenEvidence {
        val enoughStrong = strong.size >= ConsentLexicon.MIN_STRONG_PHRASES
        /* A banner always offers to accept. Consent words together with only
           a "More options" or "Settings" button are not enough: a long text
           about cookies (an article, a chat) has the words, and an app has
           menu buttons with those very names. */
        val looksLikeConsent = resourceIdHit ||
            (consentWording && ((choiceButton && acceptButton && buttonsClose) || (hasSwitch && enoughStrong)))
        return ScreenEvidence(
            looksLikeConsent, acceptButton, consentWording, choiceButton, strong.size, hasSwitch
        )
    }
}

const val ACCEPT_BUTTON = 1
const val CHOICE_BUTTON = 2
