package com.whixprs.phonelock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Accessibility service that
 *  1. shows [LockActivity] whenever a locked app comes to the foreground, and
 *  2. covers the recent-apps cards of "hidden" apps with black boxes.
 *
 * It only looks at window/package names and, while the launcher is showing,
 * at the labels of recents cards. Nothing is stored or sent anywhere.
 */
class AppGuardService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val ANDROID = "android"
        private const val MASK_THROTTLE_MS = 30L
        private const val MAX_NODES = 2500

        @Volatile
        private var instance: AppGuardService? = null

        /** Called by [LockActivity] after a successful fingerprint check. */
        fun markUnlocked(pkg: String) {
            instance?.unlocked?.put(pkg, IN_USE)
        }

        /** Marker in [unlocked] for an app that is unlocked and still on screen. */
        private const val IN_USE = Long.MAX_VALUE

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, AppGuardService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var masker: RecentsMasker

    /** Unlocked apps -> time the user left them ([IN_USE] while still open). */
    private val unlocked = HashMap<String, Long>()
    private var foregroundPackage: String? = null
    private var relockMode = Prefs.RelockMode.BACKGROUND
    private var relockMillis = 5 * 60_000L
    private var lastLockPackage: String? = null
    private var lastLockTime = 0L

    private var lockedApps: Set<String> = emptySet()
    private var hiddenLabels: Map<String, String> = emptyMap() // label -> package
    private var homePackages: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()
    private val activityCache = HashMap<String, Boolean>()

    private var maskPending = false
    private val maskRunnable = Runnable {
        maskPending = false
        updateRecentsMask()
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Turning the screen off always re-locks everything.
            unlocked.clear()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        masker = RecentsMasker(this)
        reloadSettings()
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(this)
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::masker.isInitialized) masker.clear()
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        reloadSettings()
        scheduleMaskUpdate()
    }

    private fun reloadSettings() {
        lockedApps = Prefs.get(this, Prefs.KEY_LOCKED)
        relockMode = Prefs.relockMode(this)
        relockMillis = Prefs.relockMinutes(this) * 60_000L
        val pm = packageManager
        hiddenLabels = Prefs.get(this, Prefs.KEY_HIDDEN).mapNotNull { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrNull()?.let { it to pkg }
        }.toMap()
        homePackages = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }.toSet()
        imePackages = getSystemService(InputMethodManager::class.java)
            ?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            onWindowChanged(pkg, event.className?.toString())
        }
        if (hiddenLabels.isNotEmpty() || maskVisible()) {
            if (pkg == null || pkg in homePackages || pkg == SYSTEM_UI ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                scheduleMaskUpdate()
            }
        }
    }

    // ---------------------------------------------------------------- app lock

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
        val key = "$pkg/$className"
        return activityCache.getOrPut(key) {
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

    // --------------------------------------------------------- recents masking

    private var maskShowing = false
    private fun maskVisible() = maskShowing

    private fun scheduleMaskUpdate() {
        if (maskPending) return
        maskPending = true
        handler.postDelayed(maskRunnable, MASK_THROTTLE_MS)
    }

    private fun updateRecentsMask() {
        if (hiddenLabels.isEmpty()) {
            setMasks(emptyList())
            return
        }
        val roots = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString().let { p -> p in homePackages || p == SYSTEM_UI } }

        if (roots.isEmpty()) {
            setMasks(emptyList())
            return
        }

        val screen = screenBounds()
        // Recents cards are big; home-screen icons with the same label are not.
        val minW = screen.width() / 4
        val minH = screen.height() / 4

        val masks = ArrayList<RecentsMasker.Mask>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.add(it) }
        var visited = 0
        val bounds = Rect()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val label = matchHiddenLabel(node.contentDescription) ?: matchHiddenLabel(node.text)
            if (label != null) {
                node.getBoundsInScreen(bounds)
                if (bounds.width() >= minW && bounds.height() >= minH && bounds.intersect(screen)) {
                    masks += RecentsMasker.Mask(Rect(bounds), label)
                    continue
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        setMasks(masks)
    }

    private fun screenBounds(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(wm.maximumWindowMetrics.bounds)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun setMasks(masks: List<RecentsMasker.Mask>) {
        if (!::masker.isInitialized) return
        masker.show(masks)
        maskShowing = masks.isNotEmpty()
    }

    /** Returns the hidden app label if [text] is that label (optionally followed by extra info). */
    private fun matchHiddenLabel(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val s = text.toString().trim()
        for (label in hiddenLabels.keys) {
            if (s.equals(label, ignoreCase = true)) return label
            if (s.length > label.length && s.startsWith(label, ignoreCase = true) &&
                !s[label.length].isLetterOrDigit()
            ) return label
            // Work-profile style titles, e.g. "Work Chrome".
            if (s.endsWith(" $label", ignoreCase = true) && s.length - label.length <= 12) return label
        }
        return null
    }
}
