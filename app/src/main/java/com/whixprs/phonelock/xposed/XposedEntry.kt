package com.whixprs.phonelock.xposed

import android.app.Activity
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module entry.
 *
 * Apps in Phone Lock's "Hide in Recents" list are treated as secure windows –
 * the same thing the camera and banking apps do themselves – so Android shows
 * a black card in recents instead of the last screen.
 *
 * Main path: the System Framework hook ([hookSystemFramework]). Fallback: if
 * individual apps are ticked in the LSPosed scope, FLAG_SECURE is also forced
 * from inside those apps.
 */
class XposedEntry : IXposedHookLoadPackage {

    companion object {
        private const val MODULE = "com.whixprs.phonelock"
        private const val PREFS_FILE = "xposed"
        private const val KEY_HIDDEN = "hidden_recents_apps"
        private const val SECURE = WindowManager.LayoutParams.FLAG_SECURE
    }

    @Volatile
    private var hide = false
    private var prefs: XSharedPreferences? = null
    private lateinit var packageName: String

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == MODULE) {
            // Let the Phone Lock UI know root mode is running.
            XposedHelpers.findAndHookMethod(
                "com.whixprs.phonelock.ModuleStatus", lpparam.classLoader, "isHooked",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = param.setResult(true)
                },
            )
            return
        }
        if (lpparam.packageName == "android") {
            hookSystemFramework(lpparam.classLoader)
            return
        }

        packageName = lpparam.packageName
        prefs = runCatching { XSharedPreferences(MODULE, PREFS_FILE) }.getOrNull()
        hide = shouldHide()

        // Every window flag change goes through Window.setFlags: keep FLAG_SECURE on.
        XposedHelpers.findAndHookMethod(
            Window::class.java, "setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!hide) return
                    param.args[0] = (param.args[0] as Int) or SECURE
                    param.args[1] = (param.args[1] as Int) or SECURE
                }
            },
        )

        // Pick up list changes whenever an activity comes back, and apply/remove the flag.
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val window = (param.thisObject as Activity).window ?: return
                    val wasHidden = hide
                    hide = shouldHide()
                    if (hide) window.addFlags(SECURE) else if (wasHidden) window.clearFlags(SECURE)
                }
            },
        )
    }

    // ------------------------------------------------------------ System Framework

    private var systemHidden: Set<String> = emptySet()
    private var systemLastCheck = 0L

    /**
     * Runs in system_server ("System Framework" in the LSPosed scope). Android
     * decides whether a window is "secure" – the thing that makes the camera's
     * recents card black – in WindowState.isSecureLocked(). Returning true for
     * every window of a hidden app gives it the same black card, without having
     * to tick each app in LSPosed.
     */
    private fun hookSystemFramework(classLoader: ClassLoader) {
        val p = runCatching { XSharedPreferences(MODULE, PREFS_FILE) }.getOrNull() ?: return
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.WindowState", classLoader, "isSecureLocked",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == true) return
                        val attrs = XposedHelpers.getObjectField(param.thisObject, "mAttrs")
                            as? WindowManager.LayoutParams ?: return
                        if (attrs.packageName in systemHiddenApps(p)) param.result = true
                    }
                },
            )
            XposedBridge.log("PhoneLock: hooked WindowState.isSecureLocked")
        } catch (t: Throwable) {
            XposedBridge.log("PhoneLock: could not hook System Framework: $t")
        }
    }

    /** The hidden list, re-read at most once a second (this is called very often). */
    private fun systemHiddenApps(p: XSharedPreferences): Set<String> {
        val now = SystemClock.uptimeMillis()
        if (now - systemLastCheck > 1000) {
            systemLastCheck = now
            try {
                if (p.hasFileChanged()) p.reload()
                systemHidden = HashSet(p.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())
            } catch (t: Throwable) {
                // Keep the last known list.
            }
        }
        return systemHidden
    }

    // ------------------------------------------------------------ Per-app fallback

    /**
     * True when this app is in Phone Lock's list. If Phone Lock's settings can't
     * be read, every app in the LSPosed scope is hidden so the module still works.
     */
    private fun shouldHide(): Boolean {
        val p = prefs ?: return true
        return try {
            if (p.hasFileChanged()) p.reload()
            if (!p.file.canRead()) return true
            packageName in (p.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())
        } catch (t: Throwable) {
            XposedBridge.log("PhoneLock: could not read settings: $t")
            true
        }
    }
}
