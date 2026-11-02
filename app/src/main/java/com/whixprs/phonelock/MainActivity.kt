package com.whixprs.phonelock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var currentKey = Prefs.KEY_LOCKED
    private lateinit var adapter: AppAdapter
    private lateinit var root: View
    private var unlocked = false
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep this screen out of screenshots and the recents thumbnail too.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        unlocked = savedInstanceState?.getBoolean("unlocked") ?: false

        adapter = AppAdapter(
            isEnabled = { pkg -> pkg in Prefs.get(this, currentKey) },
            onToggle = { pkg, on -> Prefs.set(this, currentKey, pkg, on) },
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<TextInputEditText>(R.id.search).doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        val hint = findViewById<TextView>(R.id.tabHint)
        hint.setText(R.string.lock_hint)
        val appsSection = findViewById<View>(R.id.appsSection)
        val settingsSection = findViewById<View>(R.id.settingsSection)
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            val isSettings = item.itemId == R.id.nav_settings
            appsSection.visibility = if (isSettings) View.GONE else View.VISIBLE
            settingsSection.visibility = if (isSettings) View.VISIBLE else View.GONE
            if (item.itemId == R.id.nav_recents) {
                currentKey = Prefs.KEY_HIDDEN
                hint.setText(R.string.recents_hint)
            } else if (item.itemId == R.id.nav_lock) {
                currentKey = Prefs.KEY_LOCKED
                hint.setText(R.string.lock_hint)
            }
            adapter.refresh()
            true
        }
        setUpSettings()

        findViewById<Button>(R.id.enableButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        loadApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("unlocked", unlocked)
    }

    override fun onResume() {
        super.onResume()
        updateServiceCard()
        ensureUnlocked()
    }

    override fun onStop() {
        super.onStop()
        // Ask again next time the settings are opened (but not on rotation).
        if (!isChangingConfigurations && !authInProgress) unlocked = false
    }

    /** Protect the settings themselves so nobody can just switch the locks off. */
    private fun ensureUnlocked() {
        val hasProtection = Prefs.get(this, Prefs.KEY_LOCKED).isNotEmpty() ||
            Prefs.get(this, Prefs.KEY_HIDDEN).isNotEmpty()
        if (unlocked || !hasProtection || !Auth.canAuthenticate(this)) {
            root.visibility = View.VISIBLE
            unlocked = true
            return
        }
        root.visibility = View.INVISIBLE
        if (authInProgress) return
        authInProgress = true
        Auth.prompt(
            this,
            getString(R.string.unlock_settings_title),
            getString(R.string.unlock_subtitle),
            onSuccess = {
                authInProgress = false
                unlocked = true
                root.visibility = View.VISIBLE
            },
            onError = { _, _ ->
                authInProgress = false
                finish()
            },
        )
    }

    private fun setUpSettings() {
        val group = findViewById<RadioGroup>(R.id.relockGroup)
        val timeoutRow = findViewById<View>(R.id.timeoutRow)
        val label = findViewById<TextView>(R.id.timeoutLabel)
        val slider = findViewById<Slider>(R.id.timeoutSlider)

        val ids = mapOf(
            Prefs.RelockMode.BACKGROUND to R.id.relockBackground,
            Prefs.RelockMode.TIMEOUT to R.id.relockTimeout,
            Prefs.RelockMode.SCREEN_OFF to R.id.relockScreenOff,
        )
        val mode = Prefs.relockMode(this)
        group.check(ids.getValue(mode))
        timeoutRow.visibility = if (mode == Prefs.RelockMode.TIMEOUT) View.VISIBLE else View.GONE
        group.setOnCheckedChangeListener { _, checkedId ->
            val newMode = ids.entries.first { it.value == checkedId }.key
            Prefs.setRelockMode(this, newMode)
            timeoutRow.visibility = if (newMode == Prefs.RelockMode.TIMEOUT) View.VISIBLE else View.GONE
        }

        val minutes = Prefs.relockMinutes(this).coerceIn(1, 60)
        slider.value = minutes.toFloat()
        label.text = getString(R.string.relock_minutes, minutes)
        slider.setLabelFormatter { getString(R.string.relock_minutes, it.toInt()) }
        slider.addOnChangeListener { _, value, fromUser ->
            label.text = getString(R.string.relock_minutes, value.toInt())
            if (fromUser) Prefs.setRelockMinutes(this, value.toInt())
        }
    }

    private fun updateServiceCard() {
        val on = AppGuardService.isEnabled(this)
        findViewById<TextView>(R.id.serviceTitle).setText(if (on) R.string.service_on_title else R.string.service_off_title)
        findViewById<TextView>(R.id.serviceBody).setText(if (on) R.string.service_on_body else R.string.service_off_body)
        findViewById<Button>(R.id.enableButton).visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun loadApps() {
        val progress = findViewById<ProgressBar>(R.id.progress)
        progress.visibility = View.VISIBLE
        val pm = packageManager
        Executors.newSingleThreadExecutor().execute {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                adapter.submit(apps)
            }
        }
    }
}
