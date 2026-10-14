package dev.macbuildssys.consentguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/* The apps ConsentGuard leaves alone. Apps are always shown and typed by the
   name on the home screen, never by package name. Adding or removing an app
   is saved at once. */
class RulesActivity : Activity() {

    private lateinit var nameInput: AutoCompleteTextView
    private lateinit var listBox: LinearLayout

    /* Home screen name of every installed app, as shown to the person. */
    private var installedNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        nameInput = findViewById(R.id.appNameInput)
        listBox = findViewById(R.id.skippedAppsList)

        installedNames = loadInstalledNames()
        nameInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, installedNames))

        findViewById<Button>(R.id.addAppButton).setOnClickListener { addTypedApp() }
        findViewById<Button>(R.id.showProtectedButton).setOnClickListener { showProtectedApps() }
        nameInput.setOnEditorActionListener { _, _, _ ->
            addTypedApp()
            true
        }

        showSkippedApps()
    }

    private fun loadInstalledNames(): List<String> {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = HashSet<String>()
        for (info in packageManager.queryIntentActivities(launch, 0)) {
            if (info.activityInfo.packageName == packageName) continue
            names.add(info.loadLabel(packageManager).toString())
        }
        return names.sortedBy { SafeZoneWords.fold(it) }
    }

    private fun savedNames(): MutableList<String> {
        val raw = getSharedPreferences(ConsentBlockerService.PREFS_NAME, MODE_PRIVATE)
            .getString(ConsentBlockerService.KEY_SKIPPED_APPS, "") ?: ""
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveNames(names: List<String>) {
        getSharedPreferences(ConsentBlockerService.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(ConsentBlockerService.KEY_SKIPPED_APPS, names.joinToString("\n"))
            .apply()
    }

    /* Only a name that matches an installed app is accepted, so a typing
       mistake can never end up on the list looking as if it worked. */
    private fun addTypedApp() {
        val typed = nameInput.text.toString().trim()
        if (typed.isEmpty()) return
        val folded = SafeZoneWords.fold(typed)
        val match = installedNames.firstOrNull { SafeZoneWords.fold(it) == folded }
        if (match == null) {
            Toast.makeText(this, "No installed app is named \"$typed\". Pick a name from the suggestions.", Toast.LENGTH_LONG).show()
            return
        }
        val names = savedNames()
        if (names.none { SafeZoneWords.fold(it) == folded }) {
            names.add(match)
            saveNames(names)
        }
        nameInput.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(nameInput.windowToken, 0)
        showSkippedApps()
        Toast.makeText(this, "Saved. ConsentGuard will skip $match.", Toast.LENGTH_SHORT).show()
    }

    private fun showSkippedApps() {
        listBox.removeAllViews()
        for (name in savedNames()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val label = TextView(this)
            label.text = name
            label.textSize = 16f
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val remove = Button(this)
            remove.text = "Remove"
            remove.minWidth = (104 * resources.displayMetrics.density).toInt()
            remove.setOnClickListener {
                val names = savedNames()
                names.removeAll { SafeZoneWords.fold(it) == SafeZoneWords.fold(name) }
                saveNames(names)
                showSkippedApps()
            }
            row.addView(remove)
            listBox.addView(row)
        }
    }

    /* Lets the person check, on their own phone, which installed apps
       ConsentGuard will never touch: the built in ones and the ones they
       added themselves. It uses the same checks the service uses, so the
       list is the real behaviour and not a promise. */
    private fun showProtectedApps() {
        Thread {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val protectedNames = HashSet<String>()
            val addedByPerson = savedNames().map { SafeZoneWords.fold(it) }.toSet()
            for (info in packageManager.queryIntentActivities(launch, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) continue
                val label = info.loadLabel(packageManager).toString()
                if (SafeZoneWords.fold(label) in addedByPerson) {
                    protectedNames.add("$label (added by you)")
                } else if (SafeZoneWords.isSafeZone(this, pkg)) {
                    protectedNames.add(label)
                }
            }
            val sorted = protectedNames.sortedBy { SafeZoneWords.fold(it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val message = if (sorted.isEmpty()) {
                    "No banking, payment, email or chat apps were found on this phone. If you have one, add it by name."
                } else {
                    sorted.joinToString("\n")
                }
                AlertDialog.Builder(this)
                    .setTitle("Never touched by ConsentGuard")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }
}
