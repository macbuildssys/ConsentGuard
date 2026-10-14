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
    /* Letters that translation tools and different keyboards write in more
       than one way. Both the screen text and the phrase lists go through the
       same folding, so the spelling on either side does not matter. Accents
       and other combining marks are dropped (so "Zurückweisen" and
       "Zuruckweisen" are the same word, and so are the Yoruba and Igbo words
       written with or without their dots and tone marks), Arabic vowel marks
       and the stretching dash are dropped, and a few look alike letters are
       joined. Plain English text never goes through any of this. */
    private val FOLD = mapOf(
        '\u0671' to '\u0627', // Arabic alef wasla
        '\u0649' to '\u064A', // Arabic alef maksura
        '\u0199' to 'k', // Hausa hooked k
        '\u0257' to 'd', // Hausa hooked d
        '\u0253' to 'b', // Hausa hooked b
        '\u01B4' to 'y' // Hausa hooked y
    )

    private fun hasNonAscii(input: String): Boolean {
        for (c in input) if (c.code > 0x7F) return true
        return false
    }

    private fun fold(input: String): String {
        val decomposed = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            val code = c.code
            when {
                code in 0x0300..0x036F -> {}
                code in 0x064B..0x065F -> {}
                code == 0x0640 -> {}
                else -> out.append(FOLD[c] ?: c)
            }
        }
        return java.text.Normalizer.normalize(out, java.text.Normalizer.Form.NFC)
    }

    fun normalize(rawInput: String): String {
        val input = if (hasNonAscii(rawInput)) fold(rawInput) else rawInput
        val out = StringBuilder(input.length)
        var lastWasSpace = true
        for (raw in input) {
            when {
                raw == '\'' || raw == '\u2019' || raw == '\u2018' || raw == '`' || raw == '\u02BC' -> {
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

    /* The phrases in the same written form the JSON files use, so a list can
       be combined with another one (see the language packs). */
    fun rawPatterns(): List<String> = substrings + exacts.map { "=$it" }

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
    val statsPurposeTextPatterns: PatternList,
    /* Words that, in a button label, mark the button as one that deals with
       everything at once ("all", "necessary", "only"), as opposed to a bare
       "Reject" that belongs to one purpose in a long list. */
    val topLevelWords: Set<String> = DEFAULT_TOP_LEVEL_WORDS,
    /* Single words that make a label read as accept. A word ending in a star
       is a beginning only ("akzept*" covers akzeptieren, akzeptiere). */
    val acceptWords: List<String> = DEFAULT_ACCEPT_WORDS,
    /* Anchor phrases too common to count on their own (the word cookie in
       any language). They only count together with one of the context
       words below. */
    val contextualAnchors: List<String> = DEFAULT_CONTEXTUAL_ANCHORS,
    val cookieContextWords: List<String> = COOKIE_CONTEXT_WORDS,
    /* Wording of card, bank and payment entry screens. Nothing is tapped on
       a screen that shows any of it. */
    val sensitiveScreenTextPatterns: PatternList = PatternList.EMPTY
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
            if (phrase in contextualAnchors) continue
            if (text.contains(phrase)) return true
        }
        var hasContextual = false
        for (phrase in contextualAnchors) {
            if (text.contains(phrase)) {
                hasContextual = true
                break
            }
        }
        if (!hasContextual) return false
        for (word in cookieContextWords) {
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

        val DEFAULT_TOP_LEVEL_WORDS = setOf(
            "all", "everything", "cookies", "necessary", "essential", "optional", "tracking", "only"
        )
        val DEFAULT_ACCEPT_WORDS = listOf("accept", "agree", "allow", "consent")
        val DEFAULT_CONTEXTUAL_ANCHORS = listOf("cookie")

        val COOKIE_CONTEXT_WORDS = listOf(
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

/* Language packs. Every file in assets/lang is one language, with the same
   kinds of phrase lists as the two main files. They are all read at the same
   time and added to the English lists, because the text on a screen can be in
   any language no matter what the phone is set to: a page translated by
   Google Translate or by Firefox shows the translated words, a site shows its
   own language, and a banner inside an app follows the app. Matching every
   language at once is what makes all of those work.

   A pack with "enabled": false is skipped. A pack that cannot be read is
   skipped on its own and never stops the others. */
private const val LANG_DIR = "lang"

private fun Context.loadLanguagePacks(): List<JSONObject> {
    val names = try {
        assets.list(LANG_DIR)?.filter { it.endsWith(".json") }?.sorted()
    } catch (e: Exception) {
        null
    } ?: return emptyList()
    val packs = ArrayList<JSONObject>()
    for (name in names) {
        try {
            val json = assets.open("$LANG_DIR/$name").bufferedReader().use { it.readText() }
            val pack = JSONObject(json)
            if (pack.optBoolean("enabled", true)) packs.add(pack)
        } catch (e: Exception) {
            // A broken pack is left out.
        }
    }
    return packs
}

private fun List<JSONObject>.words(key: String): List<String> =
    flatMap { it.optJSONArray(key).toStringList() }

/* The rule that carries the plain wording lists. Its name stays english_wording
   because that is what tools/build_english.py writes; the language packs are
   added to it so there is still only one rule, and so one walk over the
   screen, for all languages together. */
private const val WORDING_RULE = "english_wording"
private const val REJECT_BUTTON_RULE = "sourcepoint_style_reject_buttons"

fun Context.loadConsentRules(): List<ConsentRule> {
    val json = assets.open("consent_rules.json").bufferedReader().use { it.readText() }
    val array = JSONArray(json)
    val packs = loadLanguagePacks()
    val rules = mutableListOf<ConsentRule>()

    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val name = obj.getString("name")
        val isWording = name == WORDING_RULE
        val isRejectButtons = name == REJECT_BUTTON_RULE

        fun list(key: String, packKey: String?, merge: Boolean): PatternList {
            val base = obj.optJSONArray(key).toStringList()
            val extra = if (merge && packKey != null) packs.words(packKey) else emptyList()
            return PatternList(base + extra)
        }

        rules.add(
            ConsentRule(
                name = name,
                rejectResourceIds = obj.optJSONArray("rejectResourceIds").toStringList(),
                rejectTextPatterns = list("rejectTextPatterns", "reject", isWording),
                necessaryResourceIds = obj.optJSONArray("necessaryResourceIds").toStringList(),
                necessaryTextPatterns = list("necessaryTextPatterns", "necessary", isWording),
                manageResourceIds = obj.optJSONArray("manageResourceIds").toStringList(),
                manageTextPatterns = list("manageTextPatterns", "manage", isWording),
                toggleResourceIdPrefixes = obj.optJSONArray("toggleResourceIdPrefixes").toStringList(),
                rejectButtonTextPatterns = list("rejectButtonTextPatterns", "rejectButton", isRejectButtons),
                expandSectionTextPatterns = list("expandSectionTextPatterns", "expandSection", isRejectButtons),
                confirmResourceIds = obj.optJSONArray("confirmResourceIds").toStringList(),
                confirmTextPatterns = list("confirmTextPatterns", "confirm", isWording),
                fallbackRejectTextPatterns = list("fallbackRejectTextPatterns", "fallbackReject", isWording)
            )
        )
    }

    return rules
}

fun Context.loadConsentLexicon(): ConsentLexicon {
    val json = assets.open("consent_lexicon.json").bufferedReader().use { it.readText() }
    val obj = JSONObject(json)
    val fallback = ConsentLexicon.DEFAULT
    val packs = loadLanguagePacks()

    /* A missing or empty list in the file falls back to the built in
       wording for that one list, so a half edited file never leaves the
       service without any anchor or any safety guard. The language packs
       are added on top of whichever of the two is used. */
    fun listOrDefault(key: String, packKey: String, default: PatternList): PatternList {
        val loaded = obj.optJSONArray(key).toStringList()
        val base = if (loaded.isEmpty()) default.rawPatterns() else loaded
        return PatternList(base + packs.words(packKey))
    }

    fun normalizedWords(packKey: String): List<String> =
        packs.words(packKey).map { ConsentText.normalize(it) }.filter { it.isNotEmpty() }

    /* Words: the plain word lists keep a trailing star as it is, since it
       marks a beginning only. */
    val acceptWords = fallback.acceptWords + packs.words("acceptWords").map { raw ->
        val star = raw.trim().endsWith("*")
        val body = ConsentText.normalize(raw.trim().removeSuffix("*"))
        if (star) "$body*" else body
    }.filter { it.isNotEmpty() && it != "*" }

    val bareAnchors = normalizedWords("bareAnchor")
    val anchorBase = obj.optJSONArray("anchorTextPatterns").toStringList()
        .ifEmpty { fallback.anchorTextPatterns.rawPatterns() }

    return ConsentLexicon(
        anchorTextPatterns = PatternList(anchorBase + packs.words("anchor") + bareAnchors),
        strongAnchorTextPatterns = listOrDefault("strongAnchorTextPatterns", "strongAnchor", fallback.strongAnchorTextPatterns),
        neverClickTextPatterns = listOrDefault("neverClickTextPatterns", "neverClick", fallback.neverClickTextPatterns),
        acceptGuardTextPatterns = listOrDefault("acceptGuardTextPatterns", "acceptGuard", fallback.acceptGuardTextPatterns),
        switchEssentialTextPatterns = listOrDefault("switchEssentialTextPatterns", "switchEssential", fallback.switchEssentialTextPatterns),
        switchNonEssentialTextPatterns = listOrDefault("switchNonEssentialTextPatterns", "switchNonEssential", fallback.switchNonEssentialTextPatterns),
        switchKeepOnTextPatterns = listOrDefault("switchKeepOnTextPatterns", "switchKeepOn", fallback.switchKeepOnTextPatterns),
        adsPurposeTextPatterns = listOrDefault("adsPurposeTextPatterns", "adsPurpose", fallback.adsPurposeTextPatterns),
        statsPurposeTextPatterns = listOrDefault("statsPurposeTextPatterns", "statsPurpose", fallback.statsPurposeTextPatterns),
        topLevelWords = fallback.topLevelWords + normalizedWords("topLevelWords"),
        acceptWords = acceptWords,
        contextualAnchors = fallback.contextualAnchors + bareAnchors,
        cookieContextWords = fallback.cookieContextWords + normalizedWords("cookieContext"),
        sensitiveScreenTextPatterns = listOrDefault("sensitiveScreenTextPatterns", "sensitiveScreen", fallback.sensitiveScreenTextPatterns)
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
    val hasSwitch: Boolean = false,
    /* The screen offers to accept or to pay, and nothing to choose or reject
       (a "pay or okay" wall). There is nothing to tap there, so the service
       stops looking instead of retrying for a long time. */
    val cookieWall: Boolean = false,
    /* A card, bank or payment entry screen. Never acted on. */
    val sensitive: Boolean = false
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
    private var payButton = false
    private var sensitive = false

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
        if (text.length <= ConsentLexicon.SHORT_LABEL_LENGTH &&
            lexicon.sensitiveScreenTextPatterns.matchesWords(text)
        ) {
            sensitive = true
        }
        if (mayBeButton && text.length <= ConsentLexicon.SHORT_LABEL_LENGTH) {
            if (lexicon.acceptGuardTextPatterns.matchesWords(text)) {
                acceptButton = true
                kind = kind or ACCEPT_BUTTON
            }
            if (lexicon.neverClickTextPatterns.matchesWords(text)) payButton = true
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
        val wall = consentWording && acceptButton && payButton && !choiceButton && !hasSwitch
        return ScreenEvidence(
            looksLikeConsent && !sensitive, acceptButton, consentWording, choiceButton, strong.size,
            hasSwitch, cookieWall = wall, sensitive = sensitive
        )
    }
}

const val ACCEPT_BUTTON = 1
const val CHOICE_BUTTON = 2
