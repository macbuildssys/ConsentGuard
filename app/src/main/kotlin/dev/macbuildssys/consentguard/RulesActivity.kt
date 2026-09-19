package dev.macbuildssys.consentguard

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

    /* Every switch on this screen defaults to on, so every optional
       category is rejected until the person chooses otherwise. */
    private fun bindSwitch(viewId: Int, prefKey: String, prefs: SharedPreferences) {
        val switchView = findViewById<Switch>(viewId)
        switchView.isChecked = prefs.getBoolean(prefKey, true)
        switchView.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(prefKey, checked).apply()
        }
    }
}
