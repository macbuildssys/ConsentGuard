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
    private val recentActions = HashMap<String, Long>()

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
            Log.e(TAG, "Failed to load consent_rules.json", e)
            emptyList()
        }
        Log.i(TAG, "Service connected with ${rules.size} rules loaded")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted by the system")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val packageName = event?.packageName?.toString()
            ?: root.packageName?.toString()
            ?: return

        try {
            scanAndAct(root, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning window content for $packageName", e)
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
    private fun hasConsentAnchor(root: AccessibilityNodeInfo): Boolean {
        return findNodeByText(root, ANCHOR_PATTERNS) != null
    }

    /* Tries every rule's reject option first, then every rule's necessary
       only option, then every rule's manage or settings option, then a
       generic language based fallback. This ordering means a two step
       banner (an initial screen with only "manage options", and a second
       screen where the real reject button lives) is handled without any
       extra state: the manage click just opens a new window, which
       triggers a fresh event and a fresh pass that finds the reject
       button on the next screen. */
    private fun scanAndAct(root: AccessibilityNodeInfo, packageName: String) {
        val cooldown = if (lastActionWasToggle) TOGGLE_STEP_DELAY_MS else ACTION_COOLDOWN_MS
        if (System.currentTimeMillis() - lastActionTime < cooldown) {
            debugLog("In cooldown, skipping scan for $packageName")
            return
        }
        if (!hasConsentAnchor(root)) return
        resetSessionStateIfNewPackage(packageName)
        debugLog("Anchor matched in $packageName, evaluating rules")

        for (rule in rules) {
            val node = findMatch(root, rule.rejectResourceIds, rule.rejectTextPatterns) ?: continue
            if (clickNode(node, packageName, "${rule.name}:reject")) return
        }

        for (rule in rules) {
            val node = findMatch(root, rule.necessaryResourceIds, rule.necessaryTextPatterns) ?: continue
            if (clickNode(node, packageName, "${rule.name}:necessary")) return
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

        for (rule in rules) {
            val node = findMatch(root, rule.manageResourceIds, rule.manageTextPatterns) ?: continue
            if (clickNode(node, packageName, "${rule.name}:manage")) return
        }

        val fallback = findNodeByText(root, GENERIC_REJECT_PATTERNS)
        if (fallback != null) {
            clickNode(fallback, packageName, "generic:reject")
            return
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
        emptyScanRetries = 0
        toggledThisSession = 0
        rejectButtonsClickedThisSession = 0
        expandedSections.clear()
        verifyScrollBackAttempts = 0
        alreadyVerified = false
        initialScreenRetries = 0
        activeSessionPackage = packageName
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
                scrollAttempts++
                emptyScanRetries = 0
                return true
            }
        } else {
            debugLog("No scrollable found or attempts exhausted in $packageName")
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
        if (confirm != null) {
            val done = clickNode(confirm, packageName, "${rule.name}:confirm")
            if (done) {
                Log.i(TAG, "Session COMPLETE in $packageName: $toggledThisSession toggle(s) switched off, then confirmed")
                recordSessionOutcome(packageName, complete = true, toggledCount = toggledThisSession)
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
        Log.w(TAG, "Session INCOMPLETE in $packageName: gave up after $MAX_EMPTY_RETRIES empty retries, only $toggledThisSession toggle(s) switched off")
        recordSessionOutcome(packageName, complete = false, toggledCount = toggledThisSession)
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

        val hasRejectButtonSomewhere = findNodeByText(root, rule.rejectButtonTextPatterns) != null
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

        val reject = findUnclickedTextMatch(root, rule.rejectButtonTextPatterns, rejectButtonsClickedThisSession)
        if (reject != null) {
            val clicked = clickNode(reject, packageName, "${rule.name}:reject:$rejectButtonsClickedThisSession")
            if (clicked) {
                rejectButtonsClickedThisSession++
                scrollAttempts = 0
                emptyScanRetries = 0
            }
            return clicked
        }

        /* No more visible Reject buttons right now. Try an unexpanded
           section next, since expanding one commonly reveals more Reject
           buttons inside it. */
        val expandable = findUnexpandedSection(root, rule.expandSectionTextPatterns)
        if (expandable != null) {
            val label = (expandable.text?.toString() ?: expandable.contentDescription?.toString() ?: "section").lowercase()
            val clicked = clickNode(expandable, packageName, "${rule.name}:expand:$label")
            if (clicked) {
                expandedSections.add(label)
                scrollAttempts = 0
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
                scrollAttempts++
                emptyScanRetries = 0
                return true
            }
        } else {
            debugLog("No scrollable found or attempts exhausted in $packageName")
        }

        val confirm = findMatch(root, rule.confirmResourceIds, rule.confirmTextPatterns)
        if (confirm != null) {
            val done = clickNode(confirm, packageName, "${rule.name}:confirm")
            if (done) {
                Log.i(TAG, "Session COMPLETE in $packageName: $rejectButtonsClickedThisSession reject button(s) clicked, then confirmed")
                recordSessionOutcome(packageName, complete = true, toggledCount = rejectButtonsClickedThisSession)
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

        Log.w(TAG, "Session INCOMPLETE in $packageName: gave up after $MAX_EMPTY_RETRIES empty retries, only $rejectButtonsClickedThisSession reject button(s) clicked")
        recordSessionOutcome(packageName, complete = false, toggledCount = rejectButtonsClickedThisSession)
        rejectButtonsClickedThisSession = 0
        expandedSections.clear()
        return false
    }

    /* Document order walk for a plain sequential count (no resource id to
       key on here, unlike findUntoggledSwitch). The Nth match in
       document order equals however many we've already clicked. */
    private fun findUnclickedTextMatch(
        root: AccessibilityNodeInfo,
        patterns: List<String>,
        alreadyClicked: Int
    ): AccessibilityNodeInfo? {
        if (patterns.isEmpty()) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var index = 0
        var found: AccessibilityNodeInfo? = null

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()

            if (text != null && patterns.any { text.contains(it) }) {
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
        patterns: List<String>
    ): AccessibilityNodeInfo? {
        if (patterns.isEmpty()) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()

            if (text != null && patterns.any { text.contains(it) } && text !in expandedSections) {
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
            if (resourceId != null && resourceIdPrefixes.any { resourceId.startsWith(it) }) {
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
        val text = heading.lowercase()
        return when {
            ADS_KEYWORDS.any { text.contains(it) } -> CATEGORY_ADS
            STATS_KEYWORDS.any { text.contains(it) } -> CATEGORY_STATS
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
            if (resourceId != null && resourceIdPrefixes.any { resourceId.startsWith(it) }) {
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

    private fun findMatch(
        root: AccessibilityNodeInfo,
        resourceIds: List<String>,
        textPatterns: List<String>
    ): AccessibilityNodeInfo? {
        for (id in resourceIds) {
            val matches = root.findAccessibilityNodeInfosByViewId(id)
            if (matches.isNotEmpty()) return matches[0]
        }
        if (textPatterns.isNotEmpty()) {
            return findNodeByText(root, textPatterns)
        }
        return null
    }

    /* Breadth first walk of the node tree, since consent buttons are
       usually only a few levels deep and this keeps memory bounded. */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        patterns: List<String>
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()

            if (text != null && patterns.any { text.contains(it) }) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
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
    private fun clickNode(node: AccessibilityNodeInfo, packageName: String, ruleLabel: String): Boolean {
        val key = "$packageName:$ruleLabel"
        val now = System.currentTimeMillis()
        val last = recentActions[key] ?: 0L
        if (now - last < DEBOUNCE_MS) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        if (dispatched) {
            recentActions[key] = now
            lastActionTime = now
            lastActionWasToggle = ruleLabel.contains(":toggle:") ||
                ruleLabel.contains(":reject:") || ruleLabel.contains(":expand:")
            Log.i(TAG, "Rejected via $ruleLabel in $packageName")
            recordRejection(packageName, ruleLabel)
            scheduleFollowUpScan(packageName, if (lastActionWasToggle) TOGGLE_FOLLOW_UP_DELAY_MS else FOLLOW_UP_DELAY_MS)
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
                    Log.e(TAG, "Error in follow up scan for $currentPackage", e)
                }
            } else {
                debugLog("Follow up scan found no active window (was $packageName)")
            }
        }
        pendingFollowUp = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /* Wraps every verbose step by step log line so they compile out of a
       release build entirely (BuildConfig.DEBUG is false there) while
       staying fully available in a debug build for troubleshooting a
       new, unfamiliar site. Log.i (an actual click) and Log.w (a
       problem worth knowing about) stay unwrapped, since those are
       infrequent enough to leave in either build. */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    private fun recordRejection(packageName: String, ruleLabel: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0) + 1
        val timestamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        val entry = "$timestamp  $ruleLabel in $packageName"
        val previousHistory = prefs.getString(KEY_HISTORY, "") ?: ""
        val updatedHistory = (listOf(entry) + previousHistory.split("\n").filter { it.isNotBlank() })
            .take(MAX_HISTORY_ENTRIES)
            .joinToString("\n")
        prefs.edit()
            .putInt(KEY_COUNT, count)
            .putString(KEY_LAST_EVENT, "$ruleLabel in $packageName")
            .putString(KEY_HISTORY, updatedHistory)
            .apply()
    }

    /* A toggle flipping run ending without ever reaching confirm is
       recorded as its own persistent counter, not just a log line. The
       app's own numbers should say plainly whether a run finished,
       rather than requiring anyone to catch it happening live or fully
       trust a quickly scrolling log. */
    private fun recordSessionOutcome(packageName: String, complete: Boolean, toggledCount: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val timestamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        val label = if (complete) "COMPLETE" else "INCOMPLETE"
        val entry = "$timestamp  $label: $toggledCount toggle(s) in $packageName"
        val previousHistory = prefs.getString(KEY_HISTORY, "") ?: ""
        val updatedHistory = (listOf(entry) + previousHistory.split("\n").filter { it.isNotBlank() })
            .take(MAX_HISTORY_ENTRIES)
            .joinToString("\n")

        val editor = prefs.edit().putString(KEY_HISTORY, updatedHistory)
        if (!complete) {
            editor.putInt(KEY_INCOMPLETE_COUNT, prefs.getInt(KEY_INCOMPLETE_COUNT, 0) + 1)
        }
        editor.apply()
    }

    companion object {
        const val TAG = "ConsentBlocker"
        const val PREFS_NAME = "consent_blocker_prefs"
        const val KEY_COUNT = "rejected_count"
        const val KEY_LAST_EVENT = "last_event"
        const val KEY_HISTORY = "action_history"
        const val KEY_INCOMPLETE_COUNT = "incomplete_session_count"
        const val MAX_HISTORY_ENTRIES = 10
        const val DEBOUNCE_MS = 1500L
        const val ACTION_COOLDOWN_MS = 1000L
        const val FOLLOW_UP_DELAY_MS = 1300L
        /* Deliberately much slower than the general cooldown above, so a
           person watching the screen can actually see each toggle flip
           one at a time rather than a blur. The general cooldown is
           tuned for single action button clicks (reject, manage,
           confirm), which don't need to be watchable the same way. */
        const val TOGGLE_STEP_DELAY_MS = 1600L
        const val TOGGLE_FOLLOW_UP_DELAY_MS = 1900L
        const val MAX_SCROLL_ATTEMPTS = 40
        const val MAX_EMPTY_RETRIES = 15
        const val TAP_DURATION_MS = 50L
        const val HEADING_MIN_LENGTH = 20

        const val KEY_REJECT_ADS = "reject_ads"
        const val KEY_REJECT_ANALYTICS = "reject_analytics"
        const val KEY_REJECT_OTHER = "reject_other"
        const val CATEGORY_ADS = "ads"
        const val CATEGORY_STATS = "stats"
        const val CATEGORY_OTHER = "other"

        /* Keyword based, not an official category system, good enough to
           sort most real purpose headings, not guaranteed for every
           CMP's exact wording. */
        val ADS_KEYWORDS = listOf("advert", "marketing")
        val STATS_KEYWORDS = listOf("measure", "audience", "analytics", "performance", "statistic")

        val GENERIC_REJECT_PATTERNS = listOf(
            "reject", "decline", "refuse", "disagree",
            "ablehnen", "refuser", "rechazar", "rifiuta"
        )

        /* Phrases that only show up in actual consent or privacy dialogs,
           never in ordinary Android UI. At least one of these must be
           present before scanAndAct considers clicking anything at all.
           Deliberately narrow: earlier, more generic entries here
           ("privacy policy", "datenschutz") turned out to collide with
           ordinary OS text (every app links a privacy policy; Samsung's
           own Settings has a "Datenschutz und Sicherheit" section in
           German). These are standardized IAB TCF banner boilerplate
           instead, which is template language shared by most real world
           cookie banners but essentially never appears outside one. */
        val ANCHOR_PATTERNS = listOf(
            "legitimate interest", "list of partners",
            "advertising and content", "store and/or access information",
            "cookie policy", "cookie consent", "gdpr",
            "cookie", "cookies", "we value your privacy",
            "we use cookies", "this site uses cookies",
            "this website uses cookies", "we and our partners"
        )
    }
}
