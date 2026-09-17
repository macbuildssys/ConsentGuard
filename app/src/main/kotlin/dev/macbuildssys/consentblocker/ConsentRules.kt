package dev.macbuildssys.consentblocker

import android.content.Context
import org.json.JSONArray

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
    val rejectTextPatterns: List<String>,
    val necessaryResourceIds: List<String>,
    val necessaryTextPatterns: List<String>,
    val manageResourceIds: List<String>,
    val manageTextPatterns: List<String>,
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
    val rejectButtonTextPatterns: List<String> = emptyList(),
    /* Section headers that are genuinely collapsed and need a tap to
       reveal more content inside (confirmed: "Special Purposes" and
       "Special Features for Third-Party-Vendors" on spiegel.de render
       as buttons with no reject option visible until expanded). Each
       is expanded at most once per session. */
    val expandSectionTextPatterns: List<String> = emptyList(),
    val confirmResourceIds: List<String> = emptyList(),
    val confirmTextPatterns: List<String> = emptyList()
)

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
                rejectTextPatterns = obj.optJSONArray("rejectTextPatterns").toLowercaseStringList(),
                necessaryResourceIds = obj.optJSONArray("necessaryResourceIds").toStringList(),
                necessaryTextPatterns = obj.optJSONArray("necessaryTextPatterns").toLowercaseStringList(),
                manageResourceIds = obj.optJSONArray("manageResourceIds").toStringList(),
                manageTextPatterns = obj.optJSONArray("manageTextPatterns").toLowercaseStringList(),
                toggleResourceIdPrefixes = obj.optJSONArray("toggleResourceIdPrefixes").toStringList(),
                rejectButtonTextPatterns = obj.optJSONArray("rejectButtonTextPatterns").toLowercaseStringList(),
                expandSectionTextPatterns = obj.optJSONArray("expandSectionTextPatterns").toLowercaseStringList(),
                confirmResourceIds = obj.optJSONArray("confirmResourceIds").toStringList(),
                confirmTextPatterns = obj.optJSONArray("confirmTextPatterns").toLowercaseStringList()
            )
        )
    }

    return rules
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

/* Text patterns are matched against node text that's already lowercased
   at comparison time, so these are normalized the same way up front. */
private fun JSONArray?.toLowercaseStringList(): List<String> {
    if (this == null) return emptyList()
    val list = mutableListOf<String>()
    for (i in 0 until length()) {
        list.add(getString(i).lowercase())
    }
    return list
}
