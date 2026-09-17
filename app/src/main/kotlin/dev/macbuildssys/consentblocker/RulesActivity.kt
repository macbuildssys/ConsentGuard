package dev.macbuildssys.consentblocker

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Switch

class RulesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        val prefs = getSharedPreferences(ConsentBlockerService.PREFS_NAME, MODE_PRIVATE)

        bindSwitch(R.id.rejectAdsSwitch, ConsentBlockerService.KEY_REJECT_ADS, prefs)
        bindSwitch(R.id.rejectAnalyticsSwitch, ConsentBlockerService.KEY_REJECT_ANALYTICS, prefs)
        bindSwitch(R.id.rejectOtherSwitch, ConsentBlockerService.KEY_REJECT_OTHER, prefs)
    }

    /* Every category defaults to true (reject it) so a fresh install
       behaves exactly like before this screen existed. */
    private fun bindSwitch(viewId: Int, prefKey: String, prefs: SharedPreferences) {
        val switchView = findViewById<Switch>(viewId)
        switchView.isChecked = prefs.getBoolean(prefKey, true)
        switchView.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(prefKey, checked).apply()
        }
    }
}
