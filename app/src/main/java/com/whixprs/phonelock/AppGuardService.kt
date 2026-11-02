package com.whixprs.phonelock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Accessibility service that shows [LockActivity] whenever a locked app comes
 * to the foreground. It only looks at which app is in front; nothing is stored
 * or sent anywhere. (Black recents cards are done by the LSPosed module, see
 * [com.whixprs.phonelock.xposed.XposedEntry].)
 */
class AppGuardService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val ANDROID = "android"

        /** Marker in [unlocked] for an app that is unlocked and still on screen. */
        private const val IN_USE = Long.MAX_VALUE

        @Volatile
        private var instance: AppGuardService? = null

        /** Called by [LockActivity] after a successful fingerprint check. */
        fun markUnlocked(pkg: String) {
            instance?.unlocked?.put(pkg, IN_USE)
        }

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, AppGuardService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    /** Unlocked apps -> time the user left them ([IN_USE] while still open). */
    private val unlocked = HashMap<String, Long>()
    private var foregroundPackage: String? = null
    private var relockMode = Prefs.RelockMode.BACKGROUND
    private var relockMillis = 5 * 60_000L
    private var lastLockPackage: String? = null
    private var lastLockTime = 0L

    private var lockedApps: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()
    private val activityCache = HashMap<String, Boolean>()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Turning the screen off always re-locks everything.
            unlocked.clear()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadSettings()
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(this)
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        instance = null
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = reloadSettings()

    private fun reloadSettings() {
        lockedApps = Prefs.get(this, Prefs.KEY_LOCKED)
        relockMode = Prefs.relockMode(this)
        relockMillis = Prefs.relockMinutes(this) * 60_000L
        imePackages = getSystemService(InputMethodManager::class.java)
            ?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        onWindowChanged(pkg, event.className?.toString())
    }

    private fun onWindowChanged(pkg: String, className: String?) {
        if (pkg == packageName || pkg == SYSTEM_UI || pkg == ANDROID || pkg in imePackages) return
        if (!isActivity(pkg, className)) return

        val now = SystemClock.elapsedRealtime()
        val previous = foregroundPackage
        foregroundPackage = pkg

        // The user left an unlocked app.
        if (previous != null && previous != pkg && unlocked[previous] == IN_USE) {
            if (relockMode == Prefs.RelockMode.BACKGROUND) unlocked.remove(previous)
            else unlocked[previous] = now
        }

        if (pkg in lockedApps && !isStillUnlocked(pkg, now)) showLock(pkg)
    }

    private fun isStillUnlocked(pkg: String, now: Long): Boolean {
        val leftAt = unlocked[pkg] ?: return false
        val valid = when {
            leftAt == IN_USE -> true
            relockMode == Prefs.RelockMode.TIMEOUT -> now - leftAt < relockMillis
            relockMode == Prefs.RelockMode.SCREEN_OFF -> true
            else -> false
        }
        if (valid) unlocked[pkg] = IN_USE else unlocked.remove(pkg)
        return valid
    }

    private fun isActivity(pkg: String, className: String?): Boolean {
        if (className == null) return false
        return activityCache.getOrPut("$pkg/$className") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, className), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun showLock(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (pkg == lastLockPackage && now - lastLockTime < 700) return
        lastLockPackage = pkg
        lastLockTime = now
        startActivity(
            Intent(this, LockActivity::class.java)
                .putExtra(LockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
        )
    }
}
