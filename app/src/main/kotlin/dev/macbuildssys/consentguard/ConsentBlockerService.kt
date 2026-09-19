package dev.macbuildssys.consentguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ConsentBlockerService : AccessibilityService() {

    private var rules: List<ConsentRule> = emptyList()
    private var lexicon: ConsentLexicon = ConsentLexicon.DEFAULT
    /* Every rule's confirm wording in one place. A label that reads as a
       confirm button is never treated as a manage button, so "Save
       preferences" cannot be mistaken for "Preferences". */
    private var allConfirmPatterns: List<PatternList> = emptyList()

    /* Every resource id any rule knows about, used to recognise a consent
       screen that has no readable wording. */
    private var screenResourceIds: List<String> = emptyList()

    /* Set by each scan: true when an accept everything button is on
       screen, which is what a real banner has. The last resort reject
       words are only tried then. */
    private var bannerHasAcceptButton = false
    private val recentActions = HashMap<String, Long>()

    /* How many times each plain button (reject, necessary only, manage,
       confirm, fallback) has been tapped while the current consent screen
       has been on display. A button that keeps getting tapped without the
       screen changing is not doing anything, and tapping it forever helps
       nobody, so each one is capped. Cleared as soon as a scan finds no
       consent screen at all. */
    private val labelClickCounts = HashMap<String, Int>()

    /* State for the generic switch handler (see handleGenericSwitchScreen),
       the one that works on ordinary toggles and checkboxes exposed by
       Chrome, WebViews and native views, with no resource id needed.
       genericSwitchClicks caps how often any one switch is tapped,
       genericLastActivityTime lets a stale session expire on its own. */
    private val genericSwitchClicks = HashMap<String, Int>()
    private var genericScrollAttempts = 0
    private var genericEmptyRetries = 0
    private var genericToggledCount = 0
    private var genericVerifyBackAttempts = 0
    private var genericAlreadyVerified = false
    private var genericGaveUp = false
    private var genericLimitHit = false

    /* Sections of a cookie panel that were opened to reach the switches
       inside, and how often each was tried. */
    private var genericExpandCount = 0
    private val genericExpandTries = HashMap<String, Int>()
    private var genericLastActivityTime = 0L
    private var lastActionWasGenericSwitch = false

    /* Tracks how many occurrences of each toggle resource id have already
       been clicked. A plain seen it set isn't enough: the same purpose's
       Consent slider and its Legitimate interest slider share the exact
       same resource id (confirmed from a real dump, both are literally
       "fc-preference-slider-purpose-2"), so identity alone can't tell
       them apart. Counting occurrences in document order can: the first
       time "fc-preference-slider-purpose-2" appears is Consent, the
       second time is Legitimate interest, and this map remembers we're
       up to occurrence N for that id. Cleared once confirm is
       successfully clicked, since that ends the session. */
    private val toggledOccurrenceCounts = HashMap<String, Int>()
    private var scrollAttempts = 0
    /* Set when the scroll limit was reached with a scrolling panel still on
       screen, so it is not known that everything was covered. Stops the
       specific handlers from saving, see stopWithoutSaving. */
    private var scrollLimitHit = false
    private var emptyScanRetries = 0

    /* These three fields above are only ever cleared on success (a toggle
       click, a scroll, or a confirm click), never simply because a
       different app is now in the foreground. That let state leak
       across apps: if a second app happens to use the same CMP with the
       same resource ids as one tested moments earlier (both use Google
       Funding Choices, say), its toggles would look already handled
       from the very first scan and never get clicked at all, while the
       inherited scroll count silently ate into this app's own scroll
       budget. Observed in practice: a fresh app's first scan already
       showing scrollAttempts equal to 26 and never successfully
       clicking a single toggle. This tracks which package the current
       state belongs to, so the toggle handling functions can reset
       everything the moment they're asked to act on a different one. */
    private var activeSessionPackage: String? = null
    private var toggledThisSession = 0
    /* For the reject button pair mechanism (see rejectButtonTextPatterns):
       a simple sequential count, since there's no resource id to key on.
       expandedSections remembers which collapsed section labels have
       already been tapped open, so a scan later never taps (and thereby
       collapses again) the same one. */
    private var rejectButtonsClickedThisSession = 0
    private val expandedSections = mutableSetOf<String>()
    /* Once the forward pass finds nothing left to toggle, this scrolls
       back to the top and runs one more complete forward pass before
       confirming, in case anything was missed the first time through
       (a purpose that loaded late, or was briefly off screen when
       checked). alreadyVerified makes sure this only happens once per
       session, not every time the forward pass comes up empty. */
    private var verifyScrollBackAttempts = 0
    private var alreadyVerified = false
    /* For the very first screen, before any specific rule handler has
       engaged. Reset alongside everything else on a package change, see
       resetSessionStateIfNewPackage. */
    private var initialScreenRetries = 0

    /* Enforced separately from the per target debounce in clickNode. That
       one stops us clicking the same target again too soon; this stops
       us acting again at all, on any target, before the screen has had
       time to settle from our last tap. Needed because dispatchGesture
       targets fixed screen coordinates, not a specific element: if we
       act again while a screen transition from our last tap is still in
       flight, the new tap can land on whatever now occupies that same
       spot on the new screen, not what we intended. */
    private var lastActionTime = 0L
    private var lastActionWasToggle = false

    /* When the page was last scrolled by us. A scroll is still moving for a
       moment afterwards, and the positions the accessibility system reports
       lag behind, so nothing is tapped until it has settled. Tapping too
       soon after a scroll lands on whatever has moved under that spot. */
    private var lastScrollTime = 0L

    /* How many scans in a row waited for a reject option that exists but is
       not on screen yet. */
    private var waitForTargetRetries = 0

    /* When the current consent screen was first recognised, for the debug
       log. */
    private var bannerFirstSeen = 0L

    /* Where the accept everything buttons are on screen right now. A banner
       keeps its buttons together, so a manage or last resort button that is
       far away from every one of them is on the page behind the banner and
       is left alone. Empty when no accept button was seen. */
    private var acceptRects: List<Rect> = emptyList()

    /* State for the plus and minus buttons (see handleMinusButtons). */
    private var minusClickedCount = 0
    private var minusScrollAttempts = 0
    private var minusLimitHit = false

    /* The wording of the reject and necessary only buttons of every rule.
       Used with isTopLevelTarget, see below. */
    private var topLevelRejectPatterns: List<PatternList> = emptyList()

    /* Self scheduling backup: after any successful action, checks again a
       short while later regardless of whether a new accessibility event
       arrives. Needed because a single toggle flip may not reliably
       trigger a fresh content changed notification from the OS, observed
       in practice as the service going silent for a full minute after
       successfully clicking one toggle, with no further events at all
       for that screen. Purely a backup: real accessibility events still
       drive things immediately when they do arrive; this just stops the
       whole sequence from stalling silently when they don't. */
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFollowUp: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        rules = try {
            loadConsentRules()
        } catch (e: Exception) {
            debugLog("Failed to load consent_rules.json" + " (" + e.message + ")")
            emptyList()
        }
        allConfirmPatterns = rules.map { it.confirmTextPatterns }.filter { !it.isEmpty() }
        screenResourceIds = rules.flatMap {
            it.toggleResourceIdPrefixes + it.rejectResourceIds + it.necessaryResourceIds +
                it.manageResourceIds + it.confirmResourceIds
        }.filter { it.isNotBlank() }
        topLevelRejectPatterns = rules.flatMap { listOf(it.rejectTextPatterns, it.necessaryTextPatterns) }
            .filter { !it.isEmpty() }
        forgetOldHistory()
        lexicon = try {
            loadConsentLexicon()
        } catch (e: Exception) {
            debugLog("Failed to load consent_lexicon.json, using built in wording" + " (" + e.message + ")")
            ConsentLexicon.DEFAULT
        }
        debugLog("Service connected with ${rules.size} rules loaded")
    }

    override fun onInterrupt() {
        debugLog("Service interrupted by the system")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        /* Our own screens are never something to scan. */
        if (event?.packageName?.toString() == this.packageName) return

        val root = rootInActiveWindow ?: return
        val packageName = event?.packageName?.toString()
            ?: root.packageName?.toString()
            ?: return

        if (isOffLimits(packageName)) return

        try {
            scanAndAct(root, packageName)
        } catch (e: Exception) {
            debugLog("Error scanning window content for $packageName" + " (" + e.message + ")")
        }
    }

    /* Gate everything below on the screen actually looking like a consent
       dialog. Individual button and menu phrases keep colliding with
       ordinary Android UI (we've hit this twice already), so instead of
       chasing each collision one at a time, nothing gets clicked at all
       unless the screen also contains one of these consent or privacy
       specific phrases. Real IAB TCF style banners are full of this
       exact boilerplate; system settings, launchers, and ordinary apps
       never say "legitimate interest" or "list of partners". */
    /* ConsentGuard never acts inside itself, and never in parts of the phone
       itself: system screens, settings, permission and install dialogs, the
       launcher, the dialer and the keyboard. */
    private fun isOffLimits(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        return NEVER_ACT_PACKAGES.any { packageName.startsWith(it) }
    }

    /* One look over the screen to decide whether it is a consent screen at
       all, see ConsentLexicon.judgeScreen for the rules. The cheap anchor
       check runs first, so an ordinary screen costs almost nothing. */
    private fun judgeConsentScreen(root: AccessibilityNodeInfo, packageName: String): ScreenEvidence {
        if (!hasConsentAnchor(root)) return ScreenEvidence(false, false)

        val judge = ScreenJudge(lexicon, rules)
        val acceptList = ArrayList<Rect>()
        val choiceList = ArrayList<Rect>()
        /* A stack, so the last children of every node are looked at first.
           A cookie banner is usually added at the very end of a page, and a
           long article can have thousands of nodes before it. Walking the
           other way round (level by level) reached the node limit before it
           reached the banner, and a banner that was clearly there was not
           recognised. */
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_SCREEN_NODES) {
            val node = stack.removeLast()
            visited++
            if (node.isCheckable) judge.hasSwitch = true
            if (!judge.resourceIdHit) {
                val id = node.viewIdResourceName
                if (id != null && screenResourceIds.any {
                        id.startsWith(it, ignoreCase = true) ||
                            id.substringAfterLast('/').equals(it.substringAfterLast('/'), ignoreCase = true)
                    }
                ) {
                    judge.resourceIdHit = true
                }
            }
            normalizedLabel(node, MAX_ANCHOR_TEXT_LENGTH)?.let {
                if (it.isNotEmpty()) {
                    /* An icon (the three dots, a gear, a menu) is not a button
                       of a banner, whatever it is called. */
                    val kind = judge.addText(it, !isIconOnly(node))
                    if (kind != 0 && node.isVisibleToUser) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (!rect.isEmpty) {
                            if (kind and ACCEPT_BUTTON != 0 && acceptList.size < 20) acceptList.add(rect)
                            if (kind and CHOICE_BUTTON != 0 && choiceList.size < 40) choiceList.add(rect)
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        acceptRects = acceptList

        val screenHeight = resources.displayMetrics.heightPixels
        judge.buttonsClose = acceptList.any { accept ->
            choiceList.any { Math.abs(accept.centerY() - it.centerY()) <= screenHeight * BANNER_SPAN }
        }

        val evidence = judge.result()
        if (!evidence.looksLikeConsent) {
            debugLog(
                "Consent wording seen in $packageName but not treated as a banner: " +
                    "wording=${evidence.consentWording} choiceButton=${evidence.choiceButton} " +
                    "acceptButton=${evidence.acceptButton} strongPhrases=${evidence.strongPhrases} " +
                    "switch=${evidence.hasSwitch} nodesLooked=$visited"
            )
        }
        return evidence
    }

    /* Earlier versions kept a short list of recent results and some
       counters. Nothing is kept any more, and this clears whatever an
       older version left behind. */
    private fun forgetOldHistory() {
        val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        LEGACY_HISTORY_KEYS.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun hasConsentAnchor(root: AccessibilityNodeInfo): Boolean {
        return findNodeByText(root, lexicon.anchorTextPatterns) != null
    }

    /* Tries every rule's reject option first, then every rule's necessary
       only option, then every rule's manage or settings option, then a
       generic language based fallback. This ordering means a two step
       banner (an initial screen with only "manage options", and a second
       screen where the real reject button lives) is handled without any
       extra state: the manage click just opens a new window, which
       triggers a fresh event and a fresh pass that finds the reject
       button on the next screen. */
    /* Times every scan, so a debug log shows where the time goes. */
    private fun scanAndAct(root: AccessibilityNodeInfo, packageName: String) {
        val started = System.nanoTime()
        try {
            scanAndActInner(root, packageName)
        } finally {
            val ms = (System.nanoTime() - started) / 1_000_000
            if (BuildConfig.DEBUG && ms >= SLOW_SCAN_MS) debugLog("Scan took $ms ms in $packageName")
        }
    }

    private fun scanAndActInner(root: AccessibilityNodeInfo, packageName: String) {
        /* Checked here as well as when an event arrives, because the follow
           up scans call this directly and whatever is on screen by then may
           be a different app. */
        if (isOffLimits(packageName)) return

        val cooldown = when {
            lastActionWasToggle -> TOGGLE_STEP_DELAY_MS
            lastActionWasGenericSwitch -> GENERIC_SWITCH_STEP_DELAY_MS
            else -> ACTION_COOLDOWN_MS
        }
        if (System.currentTimeMillis() - lastActionTime < cooldown ||
            System.currentTimeMillis() - lastScrollTime < SCROLL_SETTLE_MS
        ) {
            debugLog("In cooldown, skipping scan for $packageName")
            return
        }
        val evidence = judgeConsentScreen(root, packageName)
        if (!evidence.looksLikeConsent) {
            bannerFirstSeen = 0L
            labelClickCounts.clear()
            waitForTargetRetries = 0
            minusClickedCount = 0
            minusScrollAttempts = 0
            minusLimitHit = false
            return
        }
        if (bannerFirstSeen == 0L) bannerFirstSeen = System.currentTimeMillis()
        bannerHasAcceptButton = evidence.acceptButton
        resetSessionStateIfNewPackage(packageName)
        debugLog("Consent screen recognised in $packageName, evaluating rules")

        for (rule in rules) {
            val node = findMatch(root, rule.rejectResourceIds, rule.rejectTextPatterns) ?: continue
            if (clickNode(node, packageName, "${rule.name}:reject")) return
        }

        for (rule in rules) {
            val node = findMatch(root, rule.necessaryResourceIds, rule.necessaryTextPatterns) ?: continue
            if (clickNode(node, packageName, "${rule.name}:necessary")) return
        }

        /* A reject option exists but was not tapped this time: it is not on
           screen yet, or is still moving. Waiting is right. What must not
           happen is scrolling the page blindly to look for something else,
           which moves the very button that is about to be tapped. */
        if (waitForTargetRetries < MAX_WAIT_FOR_TARGET && topLevelTargetPresent(root)) {
            waitForTargetRetries++
            debugLog("A reject option exists in $packageName but is not tappable yet, waiting ($waitForTargetRetries/$MAX_WAIT_FOR_TARGET)")
            scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
            return
        }

        /* Checked before manage on purpose, not after. Both handlers below
           refuse to act at all unless the current screen genuinely has
           toggle or reject button content on it (see the evidence checks
           inside each), so this is safe even when neither applies. Without
           this ordering, a screen that has already been navigated to and
           genuinely has real toggles on it, but which also still contains
           some leftover text matching a manage pattern (confirmed in
           practice: two different rules share "cookie settings" and
           "privacy settings" as manage patterns, and the real toggle
           screen still had one of those phrases on it somewhere), would
           keep re-matching manage forever and never reach the toggles at
           all, since manage was being checked first every single scan. */
        for (rule in rules) {
            if (rule.toggleResourceIdPrefixes.isEmpty()) continue
            if (handleToggleScreen(root, packageName, rule)) return
        }

        for (rule in rules) {
            if (rule.rejectButtonTextPatterns.isEmpty()) continue
            if (handleRejectButtonScreen(root, packageName, rule)) return
        }

        /* Ordinary switches and checkboxes, the kind Chrome and most native
           screens expose without any resource id. Sits after the specific
           handlers above (which know their own CMP) and before manage
           (which would otherwise keep tapping a panel title that happens to
           read like a settings button). Refuses to act unless the screen
           really is a cookie preference panel, see the checks inside. */
        if (handleMinusButtons(root, packageName)) return

        if (handleGenericSwitchScreen(root, packageName)) return

        for (rule in rules) {
            val node = findMatch(
                root, rule.manageResourceIds, rule.manageTextPatterns, allConfirmPatterns, nearBanner = true
            ) ?: continue
            if (clickNode(node, packageName, "${rule.name}:manage")) return
        }

        /* The last resort reject words (decline, deny, refuse and the
           like) are only tried on a screen that also has an accept
           everything button, the way a real banner does. Without that, a
           calendar invite or a phone call would be a place to tap
           "Decline". */
        if (bannerHasAcceptButton) {
            for (rule in rules) {
                if (rule.fallbackRejectTextPatterns.isEmpty()) continue
                val node = findButtonByText(
                    root, rule.fallbackRejectTextPatterns,
                    maxLength = FALLBACK_MAX_LENGTH, skipAcceptWords = true, nearBanner = true
                ) ?: continue
                if (clickNode(node, packageName, "${rule.name}:fallback")) return
            }

            val fallback = findButtonByText(
                root, GENERIC_REJECT_PATTERNS,
                maxLength = FALLBACK_MAX_LENGTH, skipAcceptWords = true, nearBanner = true
            )
            if (fallback != null) {
                clickNode(fallback, packageName, "generic:fallback")
                return
            }
        }

        /* Every other handler in this file has its own retry logic for
           content that has not finished rendering yet (see emptyScanRetries
           in handleToggleScreen and handleRejectButtonScreen). This screen,
           the very first one, before any specific rule has engaged at all,
           had no equivalent: if the anchor matched but nothing was
           clickable yet because the page had not finished rendering, and
           the page never sent a second accessibility event on its own,
           nothing would ever try again. Observed in practice as a person
           having to tap "Manage options" by hand every single time, with
           everything downstream working fine once they did, since only
           the downstream handlers had a safety net. */
        if (initialScreenRetries < MAX_EMPTY_RETRIES) {
            initialScreenRetries++
            debugLog("Anchor matched but nothing clickable yet in $packageName, retrying ($initialScreenRetries/$MAX_EMPTY_RETRIES)")
            scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
        } else {
            debugLog("Giving up on initial screen retries in $packageName")
        }
    }

    /* Shared by both toggle handling mechanisms below. Resets everything
       the moment a scan is asked to act on a different app than
       whichever one currently owns this state, so a second app can
       never inherit a first app's counts (see the state leak note
       above). */
    private fun resetSessionStateIfNewPackage(packageName: String) {
        if (activeSessionPackage == packageName) return
        debugLog("Session switching from $activeSessionPackage to $packageName, resetting state")
        toggledOccurrenceCounts.clear()
        scrollAttempts = 0
        scrollLimitHit = false
        emptyScanRetries = 0
        toggledThisSession = 0
        rejectButtonsClickedThisSession = 0
        expandedSections.clear()
        verifyScrollBackAttempts = 0
        alreadyVerified = false
        initialScreenRetries = 0
        resetGenericState()
        labelClickCounts.clear()
        waitForTargetRetries = 0
        minusClickedCount = 0
        minusScrollAttempts = 0
        minusLimitHit = false
        activeSessionPackage = packageName
    }

    private fun resetGenericState() {
        genericSwitchClicks.clear()
        genericScrollAttempts = 0
        genericEmptyRetries = 0
        genericToggledCount = 0
        genericVerifyBackAttempts = 0
        genericAlreadyVerified = false
        genericGaveUp = false
        genericLimitHit = false
        genericExpandCount = 0
        genericExpandTries.clear()
    }

    /* Clicks one not yet handled toggle if a visible one exists. If none
       are visible, scrolls the list to reveal more, up to a bounded
       number of attempts. Once nothing is left to toggle and scrolling
       makes no more progress, runs one verification pass, then clicks
       the confirm button and resets session state. */
    private fun handleToggleScreen(root: AccessibilityNodeInfo, packageName: String, rule: ConsentRule): Boolean {
        resetSessionStateIfNewPackage(packageName)

        /* Passing the anchor check only means the screen mentions consent
           or privacy wording somewhere, it doesn't mean this specific
           rule's toggle type is actually present. Without this check, a
           screen that merely talks about cookies or GDPR but has no
           Funding Choices toggles at all would still fall into no toggle
           found, try scrolling and scroll blindly, observed in practice
           as more than 30 scroll attempts on an unrelated screen in
           another app. */
        if (!treeHasResourceIdPrefix(root, rule.toggleResourceIdPrefixes)) {
            debugLog("No ${rule.name} toggle resource ids anywhere in $packageName, not this rule's screen")
            return false
        }

        val toggle = findUntoggledSwitch(root, rule.toggleResourceIdPrefixes)
        if (toggle != null) {
            val resourceId = toggle.viewIdResourceName ?: "unknown"
            val occurrence = toggledOccurrenceCounts.getOrDefault(resourceId, 0)
            val clicked = clickNode(toggle, packageName, "${rule.name}:toggle:$resourceId:$occurrence")
            if (clicked) {
                toggledOccurrenceCounts[resourceId] = occurrence + 1
                scrollAttempts = 0
                scrollLimitHit = false
                emptyScanRetries = 0
                toggledThisSession++
            }
            return clicked
        }

        debugLog("No visible untoggled switch in $packageName (scrollAttempts=$scrollAttempts)")
        val scrollable = findScrollable(root)
        if (scrollable != null && scrollAttempts < MAX_SCROLL_ATTEMPTS) {
            val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            debugLog("Scroll attempt in $packageName: performed=$scrolled")
            if (scrolled) {
                lastScrollTime = System.currentTimeMillis()
                scrollAttempts++
                emptyScanRetries = 0
                scheduleFollowUpScan(packageName, SCROLL_SETTLE_MS + 50)
                return true
            }
        } else {
            debugLog("No scrollable found or attempts exhausted in $packageName")
            if (scrollable != null) scrollLimitHit = true
        }

        /* Forward pass found nothing left, but before confirming, make one
           pass back to the top and one fresh pass forward again, since a
           purpose that loaded late or was briefly off screen the first
           time could otherwise be missed entirely. toggledOccurrenceCounts
           is untouched by this, so anything already clicked is correctly
           skipped again; only a genuinely missed one gets caught. */
        if (!alreadyVerified) {
            val scrollBack = findScrollable(root)
            if (scrollBack != null && verifyScrollBackAttempts < MAX_SCROLL_ATTEMPTS) {
                val scrolledBack = scrollBack.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                debugLog("Verification scroll back in $packageName: performed=$scrolledBack")
                if (scrolledBack) {
                    verifyScrollBackAttempts++
                    emptyScanRetries = 0
                    return true
                }
            }
            debugLog("Verification reached the top in $packageName, running one fresh forward pass")
            alreadyVerified = true
            scrollAttempts = 0
            emptyScanRetries = 0
            return true
        }

        val confirm = findMatch(root, rule.confirmResourceIds, rule.confirmTextPatterns)
        if (confirm != null && scrollLimitHit) {
            stopWithoutSaving(packageName, "the panel was too long to check completely")
            toggledOccurrenceCounts.clear()
            scrollAttempts = 0
            emptyScanRetries = 0
            toggledThisSession = 0
            verifyScrollBackAttempts = 0
            alreadyVerified = false
            scrollLimitHit = false
            return false
        }
        if (confirm != null) {
            val done = clickNode(confirm, packageName, "${rule.name}:confirm")
            if (done) {
                debugLog("Session COMPLETE in $packageName: $toggledThisSession toggle(s) switched off, then confirmed")
                toggledOccurrenceCounts.clear()
                scrollAttempts = 0
                emptyScanRetries = 0
                toggledThisSession = 0
                verifyScrollBackAttempts = 0
                alreadyVerified = false
            }
            return done
        }

        /* Nothing to click and nowhere to scroll right now, but the CMP's
           own content can take several seconds to finish rendering
           (observed in practice: a toggle only appeared after roughly
           3.5s of otherwise empty scans, an unrelated event happened to
           trigger a re scan right then). scheduleFollowUpScan only gets
           called again automatically after a successful click, so
           without this, a slow loading screen with no other qualifying
           event in the meantime would stall permanently rather than
           just needing a few more seconds. */
        if (emptyScanRetries < MAX_EMPTY_RETRIES) {
            emptyScanRetries++
            debugLog("Nothing to do yet in $packageName, retrying ($emptyScanRetries/$MAX_EMPTY_RETRIES)")
            scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
            return true
        }

        /* Distinguishable from a normal nothing to do outcome: this
           specifically means the session ended without ever finding a
           confirm button, so some toggles this session may still be on.
           Recorded to a separate, persistent counter, not just a log
           line, because relying on someone to catch this happening live
           on their phone, or to fully trust a quickly scrolling log,
           isn't realistic. The app's own numbers should say plainly
           whether a run finished. */
        debugLog("Session INCOMPLETE in $packageName: gave up after $MAX_EMPTY_RETRIES empty retries, only $toggledThisSession toggle(s) switched off")
        toggledThisSession = 0
        verifyScrollBackAttempts = 0
        alreadyVerified = false
        return false
    }

    /* For CMPs (confirmed on spiegel.de: Sourcepoint style) that render an
       explicit "Reject" button per purpose instead of a toggle switch,
       with no resource id at all to key on, and some sections genuinely
       collapsed until tapped (real dump: "Special Purposes" and
       "Special Features for Third-Party-Vendors" show as bare buttons
       with no reject option visible until expanded). Mirrors
       handleToggleScreen's shape: find one thing to click, or scroll, or
       expand a section, or confirm, but keyed by a plain sequential
       count rather than by resource id, since there's nothing to key on
       here. */
    private fun handleRejectButtonScreen(root: AccessibilityNodeInfo, packageName: String, rule: ConsentRule): Boolean {
        resetSessionStateIfNewPackage(packageName)

        val hasRejectButtonSomewhere =
            findNodeByText(root, rule.rejectButtonTextPatterns, ::isTopLevelTarget) != null
        val hasExpandableSection = findNodeByText(root, rule.expandSectionTextPatterns) != null
        /* The confirm button also counts as evidence: once every reject
           button is clicked and every section expanded, neither of the
           two checks above will find anything, and the only remaining
           evidence this is still the same screen is the confirm button
           itself. Without this, the earlier version had a loophole:
           once rejectButtonsClickedThisSession went above zero, the gate
           was skipped for the rest of the session regardless of what
           screen came next, and a completely unrelated later screen in
           the same app (observed in practice: this scrolled something
           in Chrome's own UI, not a consent dialog at all) would still
           get scrolled and acted on. */
        val hasConfirmSomewhere = findMatch(root, rule.confirmResourceIds, rule.confirmTextPatterns) != null
        if (!hasRejectButtonSomewhere && !hasExpandableSection && !hasConfirmSomewhere) {
            debugLog("No ${rule.name} reject buttons, expandable sections, or confirm button anywhere in $packageName, not this rule's screen")
            return false
        }

        val reject = findUnclickedTextMatch(
            root, rule.rejectButtonTextPatterns, rejectButtonsClickedThisSession, ::isTopLevelTarget
        )
        if (reject != null) {
            val clicked = clickNode(reject, packageName, "${rule.name}:reject:$rejectButtonsClickedThisSession")
            if (clicked) {
                rejectButtonsClickedThisSession++
                scrollAttempts = 0
                scrollLimitHit = false
                emptyScanRetries = 0
            }
            return clicked
        }

        /* No more visible Reject buttons right now. Try an unexpanded
           section next, since expanding one commonly reveals more Reject
           buttons inside it. */
        val expandable = findUnexpandedSection(root, rule.expandSectionTextPatterns)
        if (expandable != null) {
            val label = ConsentText.labelOf(expandable)?.lowercase() ?: "section"
            val clicked = clickNode(expandable, packageName, "${rule.name}:expand:$label")
            if (clicked) {
                expandedSections.add(label)
                scrollAttempts = 0
                scrollLimitHit = false
                emptyScanRetries = 0
            }
            return clicked
        }

        debugLog("No visible reject button or unexpanded section in $packageName (scrollAttempts=$scrollAttempts)")
        val scrollable = findScrollable(root)
        if (scrollable != null && scrollAttempts < MAX_SCROLL_ATTEMPTS) {
            val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            debugLog("Scroll attempt in $packageName: performed=$scrolled")
            if (scrolled) {
                lastScrollTime = System.currentTimeMillis()
                scrollAttempts++
                emptyScanRetries = 0
                scheduleFollowUpScan(packageName, SCROLL_SETTLE_MS + 50)
                return true
            }
        } else {
            debugLog("No scrollable found or attempts exhausted in $packageName")
            if (scrollable != null) scrollLimitHit = true
        }

        val confirm = findMatch(root, rule.confirmResourceIds, rule.confirmTextPatterns)
        if (confirm != null && scrollLimitHit) {
            stopWithoutSaving(packageName, "the panel was too long to check completely")
            rejectButtonsClickedThisSession = 0
            expandedSections.clear()
            scrollAttempts = 0
            emptyScanRetries = 0
            scrollLimitHit = false
            return false
        }
        if (confirm != null) {
            val done = clickNode(confirm, packageName, "${rule.name}:confirm")
            if (done) {
                debugLog("Session COMPLETE in $packageName: $rejectButtonsClickedThisSession reject button(s) clicked, then confirmed")
                rejectButtonsClickedThisSession = 0
                expandedSections.clear()
                scrollAttempts = 0
                emptyScanRetries = 0
            }
            return done
        }

        if (emptyScanRetries < MAX_EMPTY_RETRIES) {
            emptyScanRetries++
            debugLog("Nothing to do yet in $packageName, retrying ($emptyScanRetries/$MAX_EMPTY_RETRIES)")
            scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
            return true
        }

        debugLog("Session INCOMPLETE in $packageName: gave up after $MAX_EMPTY_RETRIES empty retries, only $rejectButtonsClickedThisSession reject button(s) clicked")
        rejectButtonsClickedThisSession = 0
        expandedSections.clear()
        return false
    }

    /* What one walk over the screen learned, for handleGenericSwitchScreen.
       strongAnchorHits counts different cookie panel phrases seen so far,
       checkableCount counts every toggle or checkbox on screen, next is the
       first switch that is on and should be turned off, and anySwitch is
       any toggle at all, used to find the scrolling container that holds
       them. */
    private class SwitchScan {
        val strongAnchors = HashSet<String>()
        var checkableCount = 0
        var next: AccessibilityNodeInfo? = null
        var nextKey = ""
        var anySwitch: AccessibilityNodeInfo? = null

        /* Switches that are on and should be off, counted whether or not
           they can be tapped right now. Anything above zero when the panel
           is about to be saved means the save must not happen. */
        var remainingOn = 0
    }

    private fun rawLabel(node: AccessibilityNodeInfo): String =
        ConsentText.labelOf(node)?.trim()?.lowercase().orEmpty()

    private fun isMinusButton(node: AccessibilityNodeInfo): Boolean = rawLabel(node) in MINUS_LABELS

    private fun isPlusButton(node: AccessibilityNodeInfo): Boolean = rawLabel(node) in PLUS_LABELS

    /* A minus button only counts when a plus button sits next to it (in the
       same row or the one above it), which is how a consent choice with an
       accept plus and a reject minus is laid out. A lone hyphen or a text
       size control is left alone. */
    private fun hasPlusPartner(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var level = 0
        while (parent != null && level < 2) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                if (isPlusButton(child)) return true
                for (j in 0 until child.childCount) {
                    val grandChild = child.getChild(j) ?: continue
                    if (isPlusButton(grandChild)) return true
                }
            }
            parent = parent.parent
            level++
        }
        return false
    }

    /* The minus buttons on screen, from the top of the page down. */
    private fun collectMinusButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val screenHeight = resources.displayMetrics.heightPixels
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_SCREEN_NODES) {
            val node = stack.removeLast()
            visited++
            if (isMinusButton(node) && hasPlusPartner(node)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val nearBanner = acceptRects.isEmpty() ||
                    acceptRects.any { Math.abs(it.centerY() - bounds.centerY()) <= screenHeight * MINUS_SPAN }
                if (nearBanner) result.add(node)
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return result
    }

    /* Panels where every choice has a plus (accept) and a minus (reject)
       button. Rejecting means tapping every minus, one at a time, from the
       top down, scrolling for the rest, and then saving if the panel has a
       Save or Confirm button. Never taps a plus. */
    private fun handleMinusButtons(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val buttons = collectMinusButtons(root)
        if (buttons.isEmpty()) return false
        resetSessionStateIfNewPackage(packageName)

        if (minusClickedCount < buttons.size) {
            val next = buttons[minusClickedCount]
            val bounds = Rect()
            next.getBoundsInScreen(bounds)
            if (next.isVisibleToUser && !bounds.isEmpty) {
                val clicked = clickNode(next, packageName, "generic:minus:$minusClickedCount")
                if (clicked) {
                    minusClickedCount++
                    minusScrollAttempts = 0
                    minusLimitHit = false
                } else {
                    scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
                }
                return true
            }

            val scrollable = findScrollableAncestor(next) ?: findScrollable(root)
            if (scrollable != null && minusScrollAttempts < MAX_SCROLL_ATTEMPTS) {
                val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                debugLog("Minus button scroll in $packageName: performed=$scrolled")
                if (scrolled) {
                    lastScrollTime = System.currentTimeMillis()
                    minusScrollAttempts++
                    scheduleFollowUpScan(packageName, SCROLL_SETTLE_MS + 50)
                    return true
                }
            } else if (scrollable != null) {
                minusLimitHit = true
            }
        }

        val confirm = findConfirmNode(root)
        if (confirm != null) {
            /* Fail closed, as everywhere else: not every minus button was
               reached, so the choices are not saved. */
            if (minusLimitHit || minusClickedCount < buttons.size) {
                stopWithoutSaving(packageName, "not every minus button could be reached")
                minusClickedCount = 0
                minusScrollAttempts = 0
                minusLimitHit = false
                return false
            }
            val done = clickNode(confirm, packageName, "generic:confirm")
            if (done) {
                debugLog("Session COMPLETE in $packageName: $minusClickedCount minus button(s) tapped, then confirmed")
                minusClickedCount = 0
                minusScrollAttempts = 0
                minusLimitHit = false
            }
            return done
        }
        return false
    }

    /* Turns off every ordinary toggle or checkbox on a cookie preference
       panel, then confirms. This is the part that handles the common
       "Manage options" screen: the first tap opens a panel of switches (one
       per purpose, or one per category like marketing and analytics), and
       every one that is on has to go off.

       It works on whatever the accessibility tree reports as a checkable
       node that is currently checked, which is how Chrome, WebViews and
       native switches all describe a real toggle. Reading the checked state
       back means a switch is only tapped while it is actually on, so a tap
       that did not take is simply tried again, and one that did take is
       never touched twice.

       Three things keep it from firing on the wrong screen. It needs two
       different cookie panel phrases on screen (strongAnchorTextPatterns),
       so an app settings page that mentions cookies once is ignored. It
       only starts if there is something to turn off or a confirm button to
       press, so a first screen with a stray "remember my choice" checkbox
       does not hold up the manage button. And it leaves alone any switch
       that is disabled (always active ones are), any switch whose label
       says it is required, and any switch that is on to protect the person
       (see the lexicon).

       Switches that a site draws as plain styled boxes with no checkable
       state in the accessibility tree cannot be seen here at all. Those
       need a reject all button (handled earlier in scanAndAct) or a
       resource id rule like google_funding_choices. */
    private fun handleGenericSwitchScreen(root: AccessibilityNodeInfo, packageName: String): Boolean {
        resetSessionStateIfNewPackage(packageName)

        val now = System.currentTimeMillis()
        if (genericLastActivityTime != 0L && now - genericLastActivityTime > GENERIC_SESSION_TIMEOUT_MS) {
            debugLog("Generic switch session in $packageName went quiet, starting fresh")
            resetGenericState()
        }
        if (genericGaveUp) return false

        val scan = scanSwitches(root)
        if (scan.checkableCount == 0) return false

        val midSession = genericToggledCount > 0
        if (!midSession && scan.strongAnchors.size < MIN_STRONG_ANCHORS) {
            debugLog("Only ${scan.strongAnchors.size} strong anchor(s) in $packageName, not a cookie panel")
            return false
        }

        val confirmNode = findConfirmNode(root)
        /* Panels hide switches, legitimate interest ones above all, inside
           collapsed sections that have to be opened first. */
        val collapsed = findCollapsedSection(root, scan.anySwitch)
        if (!midSession && scan.next == null && confirmNode == null && collapsed == null) return false

        genericLastActivityTime = now

        val next = scan.next
        if (next != null) {
            val clicked = clickNode(next, packageName, "generic:gswitch:${scan.nextKey}")
            if (clicked) {
                genericSwitchClicks[scan.nextKey] = genericSwitchClicks.getOrDefault(scan.nextKey, 0) + 1
                genericToggledCount++
                genericScrollAttempts = 0
                genericLimitHit = false
                genericEmptyRetries = 0
            } else {
                scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
            }
            return true
        }

        /* Nothing left to turn off in view. Open a collapsed section first, if
           one is showing, since the switches inside it are not in the tree
           until it is open. */
        if (collapsed != null && genericExpandCount < MAX_SECTION_EXPANSIONS) {
            val bounds = Rect()
            collapsed.getBoundsInScreen(bounds)
            val label = normalizedLabel(collapsed, MAX_ANCHOR_TEXT_LENGTH).orEmpty()
            val key = "$label|${bounds.top}"
            val tries = genericExpandTries.getOrDefault(key, 0)
            if (tries < 2) {
                genericExpandTries[key] = tries + 1
                val opened = collapsed.performAction(AccessibilityNodeInfo.ACTION_EXPAND) ||
                    clickWithAction(collapsed)
                debugLog("Opening the collapsed section \"$label\" in $packageName: performed=$opened")
                if (opened) {
                    genericExpandCount++
                    genericEmptyRetries = 0
                    lastActionTime = System.currentTimeMillis()
                    scheduleFollowUpScan(packageName, GENERIC_SWITCH_FOLLOW_UP_MS)
                    return true
                }
            }
        }

        /* Nothing left to turn off in view. Scroll for more, preferring the
           scrolling container that actually holds the switches (a cookie
           panel is usually a scrolling box on top of the page, and scrolling
           the page behind it would achieve nothing). */
        val scrollable = scan.anySwitch?.let { findScrollableAncestor(it) } ?: findScrollable(root)
        if (scrollable != null && genericScrollAttempts < MAX_SCROLL_ATTEMPTS) {
            val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            debugLog("Generic switch scroll in $packageName: performed=$scrolled")
            if (scrolled) {
                lastScrollTime = System.currentTimeMillis()
                genericScrollAttempts++
                genericEmptyRetries = 0
                scheduleFollowUpScan(packageName, SCROLL_SETTLE_MS + 50)
                return true
            }
        } else if (scrollable != null) {
            /* Ran out of scrolls with more panel possibly still below. */
            genericLimitHit = true
        }

        /* The bottom is reached. Go back to the top once and look again,
           in case a switch loaded late or was off screen when passed. */
        if (!genericAlreadyVerified) {
            val back = scan.anySwitch?.let { findScrollableAncestor(it) } ?: findScrollable(root)
            if (back != null && genericVerifyBackAttempts < MAX_SCROLL_ATTEMPTS) {
                val scrolledBack = back.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (scrolledBack) {
                    genericVerifyBackAttempts++
                    scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
                    return true
                }
            }
            debugLog("Generic switch verification reached the top in $packageName, one more pass")
            genericAlreadyVerified = true
            genericScrollAttempts = 0
            scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
            return true
        }

        /* Fail closed. Saving with a switch still on would record consent
           the person never gave, which is worse than leaving the panel
           unsaved. So the save button is only pressed when the last full
           look found nothing left to turn off and the whole panel was
           covered. Otherwise the panel is left as it is and the visit is
           recorded as not completed. */
        if (confirmNode != null && (genericLimitHit || scan.remainingOn > 0)) {
            val why = if (genericLimitHit) "the panel was too long to check completely"
            else "${scan.remainingOn} switch(es) could not be turned off"
            stopWithoutSaving(packageName, why)
            resetGenericState()
            genericGaveUp = true
            return false
        }

        if (confirmNode != null) {
            val done = clickNode(confirmNode, packageName, "generic:confirm")
            if (done) {
                debugLog("Session COMPLETE in $packageName: $genericToggledCount switch(es) turned off, then confirmed")
                resetGenericState()
                genericLastActivityTime = 0L
                return true
            }
        }

        if (genericEmptyRetries < MAX_EMPTY_RETRIES) {
            genericEmptyRetries++
            debugLog("Generic switch screen in $packageName has nothing to do yet, retrying ($genericEmptyRetries/$MAX_EMPTY_RETRIES)")
            scheduleFollowUpScan(packageName, FOLLOW_UP_DELAY_MS)
            return true
        }

        debugLog("Session INCOMPLETE in $packageName: gave up after $MAX_EMPTY_RETRIES empty retries, $genericToggledCount switch(es) turned off, no confirm button found")
        resetGenericState()
        genericGaveUp = true
        return false
    }

    /* One depth first walk in document order. Keeps track of the most recent
       short piece of text, since a switch's name is often a separate text
       node right before it rather than part of the switch itself. */
    private fun scanSwitches(root: AccessibilityNodeInfo): SwitchScan {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val scan = SwitchScan()
        val occurrences = HashMap<String, Int>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var lastText = ""
        var lastHeading = ""

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = normalizedLabel(node, MAX_ANCHOR_TEXT_LENGTH)

            if (text != null && scan.strongAnchors.size < MIN_STRONG_ANCHORS) {
                for (pattern in lexicon.strongAnchorTextPatterns.substrings) {
                    if (text.contains(pattern)) scan.strongAnchors.add(pattern)
                }
            }

            if (node.isCheckable && node.className?.toString() != "android.widget.RadioButton") {
                scan.checkableCount++
                if (scan.anySwitch == null) scan.anySwitch = node

                val ownLabel = text.orEmpty()
                val name = if (ownLabel.isNotEmpty()) ownLabel else lastText
                val occurrence = occurrences.getOrDefault(name, 0)
                occurrences[name] = occurrence + 1
                val key = "$name#$occurrence"

                if (node.isChecked && node.isEnabled && shouldTurnOff(name, lastText, lastHeading, prefs)) {
                    scan.remainingOn++
                    if (scan.next == null && genericSwitchClicks.getOrDefault(key, 0) < MAX_SWITCH_CLICKS) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) {
                            scan.next = node
                            scan.nextKey = key
                        }
                    }
                }
            } else if (text != null && text.isNotEmpty()) {
                if (text.length <= MAX_SWITCH_NAME_LENGTH) lastText = text
                if (text.length > HEADING_MIN_LENGTH) lastHeading = text
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return scan
    }

    /* The decision for one switch that is currently on. Turn it off unless
       it is required for the site to work, is on to protect the person, or
       belongs to a category the person chose to leave alone in the app's
       own settings. name is the switch's own label, or the text just before
       it when it has none. */
    private fun shouldTurnOff(
        name: String,
        lastText: String,
        lastHeading: String,
        prefs: android.content.SharedPreferences
    ): Boolean {
        val reason = lexicon.leavesSwitchOn(name, lastText)
        if (reason != null) {
            debugLog("Leaving \"$name\" on, $reason")
            return false
        }
        val category = classifyPurpose("$name $lastText $lastHeading")
        if (!isCategoryEnabled(category, prefs)) {
            debugLog("Leaving \"$name\" on ($category disabled by user)")
            return false
        }
        return true
    }

    /* The first section in the panel that is showing and can be opened. A
       collapsed section offers the expand action. Only the scrolling box
       that holds the switches is looked through, so a menu elsewhere on the
       page is left alone. */
    @Suppress("DEPRECATION")
    private fun findCollapsedSection(root: AccessibilityNodeInfo, near: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val container = near?.let { findScrollableAncestor(it) } ?: root
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(container)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_SCREEN_NODES) {
            val node = stack.removeLast()
            visited++
            if (node.actions and AccessibilityNodeInfo.ACTION_EXPAND != 0 && node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val label = normalizedLabel(node, MAX_ANCHOR_TEXT_LENGTH).orEmpty()
                val key = "$label|${bounds.top}"
                if (!bounds.isEmpty && genericExpandTries.getOrDefault(key, 0) < 2 &&
                    (label.isEmpty() || isSafeLabel(label))
                ) {
                    return node
                }
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun findConfirmNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (rule in rules) {
            val node = findMatch(root, rule.confirmResourceIds, rule.confirmTextPatterns)
            if (node != null) return node
        }
        return null
    }

    /* Walks up from a node to the nearest container that scrolls. */
    private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isScrollable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /* Document order walk for a plain sequential count (no resource id to
       key on here, unlike findUntoggledSwitch). The Nth match in
       document order equals however many we've already clicked. */
    private fun findUnclickedTextMatch(
        root: AccessibilityNodeInfo,
        patterns: PatternList,
        alreadyClicked: Int,
        excludeIf: ((String) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        if (patterns.isEmpty()) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var index = 0
        var found: AccessibilityNodeInfo? = null

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = normalizedLabel(node, MAX_ANCHOR_TEXT_LENGTH)

            if (text != null && patterns.matches(text) && excludeIf?.invoke(text) != true) {
                if (found == null && index == alreadyClicked) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        found = node
                    }
                }
                index++
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return found
    }

    /* A section already in expandedSections was tapped open once already
       this session, so it's skipped even if it still matches; expanding
       it again would just collapse it back. */
    private fun findUnexpandedSection(
        root: AccessibilityNodeInfo,
        patterns: PatternList
    ): AccessibilityNodeInfo? {
        if (patterns.isEmpty()) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = normalizedLabel(node, MAX_ANCHOR_TEXT_LENGTH)
            val label = ConsentText.labelOf(node)?.lowercase()

            if (text != null && patterns.matches(text) && label != null && label !in expandedSections) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && node.isClickable) {
                    return node
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return null
    }

    /* Depth first, document order walk (not breadth first; BFS only
       preserves relative order among direct siblings, not across the
       whole tree, and getting the Nth occurrence of a repeated resource
       id right depends on true document order). Counts occurrences of
       each matching resource id as it walks; the first one whose local
       occurrence index equals how many of that id we've already
       clicked, and which is actually laid out on screen right now, is
       the next one to act on. Also tracks the most recent purpose
       heading seen (a longer text node that isn't itself a "Consent (N
       vendors)" or "Legitimate interest (N vendors)" label), used to
       classify each toggle by keyword and skip it without clicking if
       the user has that category turned off. A skipped toggle still
       advances the occurrence count, so it's never revisited, it's just
       left alone rather than turned off. */
    private fun findUntoggledSwitch(
        root: AccessibilityNodeInfo,
        resourceIdPrefixes: List<String>
    ): AccessibilityNodeInfo? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val occurrenceIndex = HashMap<String, Int>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var found: AccessibilityNodeInfo? = null
        var currentHeading = ""

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            val text = node.text?.toString()
            if (!text.isNullOrBlank() && text.length > HEADING_MIN_LENGTH) {
                val lower = text.lowercase()
                if (!lower.startsWith("consent") && !lower.startsWith("legitimate interest")) {
                    currentHeading = text
                }
            }

            val resourceId = node.viewIdResourceName
            if (resourceId != null && resourceIdPrefixes.any { resourceId.startsWith(it, ignoreCase = true) }) {
                val index = occurrenceIndex.getOrDefault(resourceId, 0)
                occurrenceIndex[resourceId] = index + 1

                if (found == null && index == toggledOccurrenceCounts.getOrDefault(resourceId, 0)) {
                    val category = classifyPurpose(currentHeading)
                    if (isCategoryEnabled(category, prefs)) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) {
                            found = node
                        }
                    } else {
                        debugLog("Skipping \"$currentHeading\" ($category disabled by user)")
                        toggledOccurrenceCounts[resourceId] = index + 1
                    }
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return found
    }

    /* Plain language grouping based on keywords in the purpose's own
       heading text, not an official IAB category system, a best effort
       heuristic, since the exact wording varies per CMP. */
    private fun classifyPurpose(heading: String): String {
        val text = ConsentText.normalize(heading)
        return when {
            lexicon.adsPurposeTextPatterns.matches(text) -> CATEGORY_ADS
            lexicon.statsPurposeTextPatterns.matches(text) -> CATEGORY_STATS
            else -> CATEGORY_OTHER
        }
    }

    private fun isCategoryEnabled(category: String, prefs: android.content.SharedPreferences): Boolean {
        val key = when (category) {
            CATEGORY_ADS -> KEY_REJECT_ADS
            CATEGORY_STATS -> KEY_REJECT_ANALYTICS
            else -> KEY_REJECT_OTHER
        }
        return prefs.getBoolean(key, true)
    }

    /* Unbounded existence check (visible or not), deliberately simpler
       than findUntoggledSwitch, which also cares about visibility and
       click history. This only answers whether this rule's toggle type
       exists anywhere on this screen at all, used to gate scrolling. */
    private fun treeHasResourceIdPrefix(root: AccessibilityNodeInfo, resourceIdPrefixes: List<String>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val resourceId = node.viewIdResourceName
            if (resourceId != null && resourceIdPrefixes.any { resourceId.startsWith(it, ignoreCase = true) }) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return false
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
    }

    /* Finds the node for one kind of button (reject, necessary only,
       manage, confirm). A resource id match wins if the rule has one. Text
       matching only looks at short labels and skips anything unsafe to tap,
       see findButtonByText. Anything in exclude is refused even if the
       patterns match it. */
    private fun findMatch(
        root: AccessibilityNodeInfo,
        resourceIds: List<String>,
        textPatterns: PatternList,
        exclude: List<PatternList> = emptyList(),
        nearBanner: Boolean = false
    ): AccessibilityNodeInfo? {
        for (id in resourceIds) {
            val matches = root.findAccessibilityNodeInfosByViewId(id)
            if (matches.isNotEmpty()) return matches[0]
            /* The system lookup above is exact, capital letters included.
               Sites are not consistent about them, so look again by hand,
               ignoring case, before giving up on this id. */
            findNodeByResourceId(root, id)?.let { return it }
        }
        if (!textPatterns.isEmpty()) {
            return findButtonByText(root, textPatterns, exclude = exclude, nearBanner = nearBanner)
        }
        return null
    }

    private fun normalizedLabel(node: AccessibilityNodeInfo, maxRawLength: Int): String? {
        val label = ConsentText.labelOf(node) ?: return null
        if (label.length > maxRawLength) return null
        return ConsentText.normalize(label)
    }

    /* Finds a node by resource id, ignoring capital letters. Matches the
       whole id, or just the part after the last slash, since ids come
       either as "name" or as "package:id/name". */
    private fun findNodeByResourceId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val resourceId = node.viewIdResourceName
            if (resourceId != null &&
                (resourceId.equals(id, ignoreCase = true) ||
                    resourceId.substringAfterLast('/').equals(id.substringAfterLast('/'), ignoreCase = true))
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
    }

    /* Presence check only, with no length limit and no safety filtering,
       used for spotting that a consent screen is showing at all. Breadth
       first walk of the node tree, since consent buttons are usually only
       a few levels deep and this keeps memory bounded. */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        patterns: PatternList,
        excludeIf: ((String) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        if (patterns.isEmpty()) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = normalizedLabel(node, MAX_ANCHOR_TEXT_LENGTH)

            if (text != null && patterns.matches(text) && excludeIf?.invoke(text) != true) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
    }

    /* Finds something worth tapping. Unlike findNodeByText this only
       considers short labels (a button says "Reject all", a paragraph
       that mentions rejecting cookies is not a button), only nodes that are
       actually laid out on screen, and never a label that is unsafe to tap:
       payment and subscription wording, or accept everything wording. Those
       two safety lists are the reason a broad phrase list can be used
       without the risk of tapping the wrong thing. */
    private fun findButtonByText(
        root: AccessibilityNodeInfo,
        patterns: PatternList,
        maxLength: Int = MAX_BUTTON_TEXT_LENGTH,
        exclude: List<PatternList> = emptyList(),
        skipAcceptWords: Boolean = false,
        nearBanner: Boolean = false
    ): AccessibilityNodeInfo? {
        if (patterns.isEmpty()) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        val screenHeight = resources.displayMetrics.heightPixels

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = normalizedLabel(node, MAX_RAW_BUTTON_TEXT_LENGTH)

            if (text != null && text.isNotEmpty() && text.length <= maxLength && patterns.matches(text)) {
                val blockedByExclude = exclude.any { it.matches(text) }
                val blockedByAcceptWord = skipAcceptWords && containsAcceptWord(text)
                if (!blockedByExclude && !blockedByAcceptWord && isSafeLabel(text)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        if (!node.isVisibleToUser) {
                            /* Only a button that is really showing. A copy of
                               the same wording that is hidden or off screen
                               has bounds too, and tapping those lands on
                               whatever visible thing is at that spot. */
                            debugLog("Found \"$text\" but it is not visible, skipping it")
                        } else if (isIconOnly(node)) {
                            /* A round menu, three dots or a gear is an icon
                               of the app, not a button of a banner. */
                            debugLog("Found \"$text\" but it is only an icon, skipping it")
                        } else if (nearBanner &&
                            (acceptRects.isEmpty() ||
                                acceptRects.none { Math.abs(it.centerY() - bounds.centerY()) <= screenHeight * BANNER_SPAN })
                        ) {
                            debugLog("Found \"$text\" but it is not next to an accept button, skipping it")
                        } else {
                            val score = candidateScore(node, bounds, text, patterns)
                            if (score > bestScore) {
                                best = node
                                bestScore = score
                            }
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return best
    }

    private fun isIconOnly(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return className.endsWith("ImageButton") || className.endsWith("ImageView")
    }

    /* Several nodes can carry the same wording: the real button, the text
       inside it, and inline links in a paragraph that happen to use the same
       words ("partners", "purposes"). The real button wins: a node that is a
       button, is clickable and is big, in that order of importance. */
    private fun candidateScore(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        text: String,
        patterns: PatternList
    ): Int {
        var score = 0
        val className = node.className?.toString().orEmpty()
        if (className.endsWith("Button")) score += 100
        if (node.isClickable) score += 30
        if (patterns.exacts.contains(text)) score += 10
        score += minOf(bounds.width() * bounds.height() / 2000, 40)
        return score
    }

    /* A button that rejects everything at once ("Reject all", "Only
       necessary cookies"), as opposed to a bare "Reject" that belongs to one
       purpose in a long list (Sourcepoint style). The two must not be
       confused, or a plain banner is mistaken for a long purpose list and
       the page gets scrolled for no reason. A bare "Reject", "Decline" or
       "Refuse" is not counted here. */
    private fun isTopLevelTarget(text: String): Boolean {
        if (!topLevelRejectPatterns.any { it.matches(text) }) return false
        return text.split(' ').any { it in TOP_LEVEL_WORDS }
    }

    /* True when some rule's reject or necessary only wording is on the
       screen as a short, safe label, whether or not it can be tapped yet. */
    private fun topLevelTargetPresent(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = normalizedLabel(node, MAX_RAW_BUTTON_TEXT_LENGTH)
            if (text != null && text.isNotEmpty() && text.length <= MAX_BUTTON_TEXT_LENGTH &&
                isTopLevelTarget(text) && isSafeLabel(text)
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isSafeLabel(normalizedText: String): Boolean {
        if (lexicon.neverClickTextPatterns.matchesWords(normalizedText)) return false
        if (lexicon.acceptGuardTextPatterns.matchesWords(normalizedText)) return false
        return true
    }

    private fun containsAcceptWord(normalizedText: String): Boolean {
        val padded = " $normalizedText "
        return padded.contains(" accept ") || padded.contains(" agree ") ||
            padded.contains(" allow ") || padded.contains(" consent ")
    }

    /* Simulates an actual finger tap at the node's on screen coordinates,
       rather than asking the accessibility framework to simulate a
       click. This matters in practice: ACTION_CLICK on the Funding
       Choices toggle switches was accepted (performAction returned
       true, logged as success) but did nothing visible, because those
       switches are custom WebView rendered widgets wired to real touch
       and pointer events rather than a generic click handler. A
       synthesized touch is indistinguishable from a real finger tap at
       the input level, so it triggers whatever the widget is actually
       listening for. */
    /* Presses the node itself, or the nearest thing around it that can be
       pressed (the text inside a button is not the button). */
    private fun clickWithAction(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 4) {
            if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target.parent
            depth++
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo, packageName: String, ruleLabel: String): Boolean {
        val key = "$packageName:$ruleLabel"
        val now = System.currentTimeMillis()
        val last = recentActions[key] ?: 0L
        if (now - last < DEBOUNCE_MS) return false

        /* The node may be a few moments old. Ask for its current state, and
           give up on it if it no longer exists, so a tap is never aimed at
           where something used to be. */
        if (!node.refresh()) {
            debugLog("The node for $ruleLabel in $packageName is stale, not tapping it")
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        if (bounds.centerX() < 0 || bounds.centerY() < 0 ||
            bounds.centerX() > screenWidth || bounds.centerY() > screenHeight
        ) {
            debugLog("The target for $ruleLabel in $packageName is off screen, not tapping it")
            return false
        }

        /* Last line of defence, checked here as well as when matching. A
           switch is exempt because its label is a purpose name ("Pay per
           view measurement" is a real purpose heading), and a switch is only
           ever turned off, never on. Everything else is a button, and a
           button that reads as payment or accept everything is not tapped. */
        val isSwitch = ruleLabel.contains(":toggle:") || ruleLabel.contains(":gswitch:")
        val nodeText = normalizedLabel(node, MAX_ANCHOR_TEXT_LENGTH).orEmpty()
        if (!isSwitch && nodeText.isNotEmpty() && !isSafeLabel(nodeText)) {
            debugLog("Refusing to tap unsafe label \"$nodeText\" for $ruleLabel in $packageName")
            return false
        }

        /* Plain buttons are capped, see labelClickCounts. */
        val isPlainButton = PLAIN_BUTTON_SUFFIXES.any { ruleLabel.endsWith(it) }
        val countKey = "$packageName|$ruleLabel|$nodeText"
        if (isPlainButton) {
            val taps = labelClickCounts.getOrDefault(countKey, 0)
            if (taps >= MAX_LABEL_CLICKS) {
                debugLog("Already tapped \"$nodeText\" $taps times for $ruleLabel in $packageName, not again")
                return false
            }
        }

        debugLog(
            "Tapping \"$nodeText\" for $ruleLabel in $packageName at " +
                "${bounds.centerX()},${bounds.centerY()} (bounds $bounds, screen ${screenWidth}x$screenHeight), " +
                "${if (bannerFirstSeen == 0L) 0 else now - bannerFirstSeen} ms after the banner was recognised"
        )
        /* A plain button is first pressed the way an assistive tool would
           press it, on the element itself, with no screen position involved.
           That cannot land on the wrong thing. If the same button is still
           there on a later try, the tap by screen position is used instead. */
        val firstTry = isPlainButton && labelClickCounts.getOrDefault(countKey, 0) == 0
        var pressedByAction = false
        val dispatched = if (firstTry && clickWithAction(node)) {
            debugLog("Pressed \"$nodeText\" for $ruleLabel by action")
            pressedByAction = true
            true
        } else {
            val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build()
            dispatchGesture(gesture, null, null)
        }
        if (dispatched) {
            /* A press by action is checked quickly: if the button is still
               there shortly after, the tap by screen position follows,
               instead of waiting out the full pause. */
            recentActions[key] = if (pressedByAction) now - (DEBOUNCE_MS - ACTION_RETRY_MS) else now
            lastActionTime = now
            if (isPlainButton) {
                labelClickCounts[countKey] = labelClickCounts.getOrDefault(countKey, 0) + 1
            }
            lastActionWasToggle = ruleLabel.contains(":toggle:") ||
                ruleLabel.contains(":reject:") || ruleLabel.contains(":expand:")
            lastActionWasGenericSwitch = ruleLabel.contains(":gswitch:")
            debugLog("Rejected via $ruleLabel in $packageName")
            val followUpDelay = when {
                lastActionWasToggle -> TOGGLE_FOLLOW_UP_DELAY_MS
                lastActionWasGenericSwitch -> GENERIC_SWITCH_FOLLOW_UP_MS
                else -> FOLLOW_UP_DELAY_MS
            }
            scheduleFollowUpScan(packageName, if (pressedByAction) ACTION_RETRY_MS + 50 else followUpDelay)
        }
        return dispatched
    }

    /* Fetches a fresh window snapshot at execution time rather than
       reusing the one captured when scheduled, since an
       AccessibilityNodeInfo can go stale once the underlying view
       changes. */
    private fun scheduleFollowUpScan(packageName: String, delayMs: Long) {
        pendingFollowUp?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            val freshRoot = rootInActiveWindow
            if (freshRoot != null) {
                val currentPackage = freshRoot.packageName?.toString() ?: packageName
                try {
                    scanAndAct(freshRoot, currentPackage)
                } catch (e: Exception) {
                    debugLog("Error in follow up scan for $currentPackage" + " (" + e.message + ")")
                }
            } else {
                debugLog("Follow up scan found no active window (was $packageName)")
            }
        }
        pendingFollowUp = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /* The only place anything is logged. Every message goes through here,
       so a release build writes nothing at all to the system log
       (BuildConfig.DEBUG is false there), while a debug build still shows
       every step for troubleshooting a new, unfamiliar site. */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    /* The fail closed stop. The panel is left unsaved on purpose. */
    private fun stopWithoutSaving(packageName: String, reason: String) {
        debugLog("Session NOT SAVED in $packageName: $reason. Left unconfirmed on purpose.")
    }

    companion object {
        const val TAG = "ConsentBlocker"
        const val PREFS_NAME = "consent_blocker_prefs"

        /* Keys older versions used for their result list and for the cover
           panel setting. Only used to delete them. */
        val LEGACY_HISTORY_KEYS = listOf(
            "rejected_count", "last_event", "action_history", "incomplete_session_count",
            "hide_banners"
        )

        /* Apps and system screens that are never touched, matched by the
           start of the package name. */
        val NEVER_ACT_PACKAGES = listOf(
            "com.android.systemui", "com.android.settings", "com.samsung.android.app.settings",
            "com.samsung.android.settings", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.google.android.packageinstaller",
            "com.android.packageinstaller", "com.sec.android.app.launcher", "com.android.launcher",
            "com.google.android.apps.nexuslauncher", "com.google.android.dialer", "com.android.dialer",
            "com.samsung.android.dialer", "com.android.phone", "com.samsung.android.incallui",
            "com.google.android.inputmethod", "com.samsung.android.honeyboard", "com.android.inputmethod",
            /* Chat apps. A conversation about cookie banners is full of the
               very words a banner uses. */
            "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard",
            "com.microsoft.copilot", "ai.perplexity.app.android"
        )

        /* Words that mark a reject or necessary only button as one that
           deals with everything at once. */
        val TOP_LEVEL_WORDS = setOf(
            "all", "everything", "cookies", "necessary", "essential", "optional", "tracking", "only"
        )

        /* A manage or last resort button must be within this share of the
           screen height from an accept button to count as part of the
           banner. */
        const val BANNER_SPAN = 0.35f

        /* Plus and minus buttons may be spread over a long panel, so they
           are allowed further from the accept button. */
        const val MINUS_SPAN = 0.6f

        val MINUS_LABELS = setOf("-", "\u2212", "\u2013", "\u2012", "\uFF0D", "minus")
        val PLUS_LABELS = setOf("+", "\uFF0B", "plus")

        /* Most collapsed sections opened in one panel. */
        const val MAX_SECTION_EXPANSIONS = 80

        /* How long after a scroll of ours nothing is tapped. */
        const val SCROLL_SETTLE_MS = 500L

        /* How many scans in a row wait for a reject option that is on the
           page but not tappable yet. */
        const val MAX_WAIT_FOR_TARGET = 8

        /* A scan that takes longer than this is written to the debug log. */
        const val SLOW_SCAN_MS = 50

        /* How soon after pressing a button by action the app checks whether
           it worked, and falls back to a tap by position if it did not. */
        const val ACTION_RETRY_MS = 450L

        /* Most nodes looked at when judging one screen. */
        const val MAX_SCREEN_NODES = 20000
        /* Timing. These are kept short so a banner is gone quickly. The
           pause after a tap only has to be long enough for the page to
           react, and each follow up scan then checks for the next
           screen. Switches inside one panel do not move around, so they
           can follow each other quickly too. */
        const val DEBOUNCE_MS = 800L
        const val ACTION_COOLDOWN_MS = 300L
        const val FOLLOW_UP_DELAY_MS = 350L
        const val TOGGLE_STEP_DELAY_MS = 450L
        const val TOGGLE_FOLLOW_UP_DELAY_MS = 550L
        /* How many scrolls in a row, without a switch being turned off in
           between, before the panel counts as too long to check. The count
           starts again after every switch that is tapped, so a long list
           is fine as long as it keeps making progress. */
        const val MAX_SCROLL_ATTEMPTS = 150
        /* Counted per scan, and scans now come around faster, so this
           is higher than it used to be to give a slow loading banner
           about the same amount of time to show up. */
        const val MAX_EMPTY_RETRIES = 40
        const val TAP_DURATION_MS = 50L
        const val HEADING_MIN_LENGTH = 20

        const val KEY_REJECT_ADS = "reject_ads"
        const val KEY_REJECT_ANALYTICS = "reject_analytics"
        const val KEY_REJECT_OTHER = "reject_other"
        const val CATEGORY_ADS = "ads"
        const val CATEGORY_STATS = "stats"
        const val CATEGORY_OTHER = "other"

        /* Timing for the generic switch handler, same pace as the
           resource id toggles above. */
        const val GENERIC_SWITCH_STEP_DELAY_MS = 450L
        const val GENERIC_SWITCH_FOLLOW_UP_MS = 550L
        /* If nothing happened on a generic switch screen for this long,
           the next visit starts a fresh session instead of continuing. */
        const val GENERIC_SESSION_TIMEOUT_MS = 60_000L

        /* A screen must show at least this many different strong cookie
           panel phrases before the generic switch handler acts on it. One
           phrase can turn up in ordinary settings, two together almost
           never do. */
        const val MIN_STRONG_ANCHORS = 2

        /* Taps on one switch before it is called stubborn. A switch that is
           still on after this many taps blocks the save. */
        const val MAX_SWITCH_CLICKS = 3
        const val MAX_SWITCH_NAME_LENGTH = 120
        const val MAX_ANCESTOR_DEPTH = 12

        /* Length limits. The raw limits apply to the text as the app gives
           it, before cleaning. The button limits are on the cleaned text,
           because a button says "Reject all", a long paragraph that
           happens to mention rejecting is not something to tap. */
        const val MAX_ANCHOR_TEXT_LENGTH = 2000
        const val MAX_RAW_BUTTON_TEXT_LENGTH = 120
        const val MAX_BUTTON_TEXT_LENGTH = 90
        const val FALLBACK_MAX_LENGTH = 40

        /* Buttons that are tapped by label rather than switched, and how
           many times the same label may be tapped in one session. Stops a
           button that does nothing from being hammered forever. */
        val PLAIN_BUTTON_SUFFIXES = listOf(":reject", ":necessary", ":manage", ":fallback", ":confirm")
        const val MAX_LABEL_CLICKS = 3

        /* Last resort wording, only tried after every rule's own fallback
           list, on short labels that contain no accept, agree or allow. */
        val GENERIC_REJECT_PATTERNS = PatternList(
            listOf("reject", "decline", "refuse", "disagree", "deny")
        )
    }
}
