package dev.macbuildssys.consentguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        forgetOldHistory()

        findViewById<Button>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.openRulesButton).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = if (isServiceEnabled()) {
            "Service is enabled"
        } else {
            "Service is disabled. Tap below, find ConsentGuard, and turn it on."
        }
    }

    /* Earlier versions kept a short list of recent results. Nothing is kept
       any more, and this clears whatever an older version left behind. */
    private fun forgetOldHistory() {
        val editor = getSharedPreferences(ConsentBlockerService.PREFS_NAME, MODE_PRIVATE).edit()
        ConsentBlockerService.LEGACY_HISTORY_KEYS.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${ConsentBlockerService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next() == expected) return true
        }
        return false
    }
}
