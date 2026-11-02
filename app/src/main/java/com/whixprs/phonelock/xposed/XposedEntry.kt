package com.whixprs.phonelock.xposed

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module entry. Runs inside each app ticked in the module's LSPosed scope.
 *
 * For apps switched on in Phone Lock's "Hide in Recents" list it forces
 * FLAG_SECURE on every window – the same thing the camera and banking apps do
 * themselves – so Android shows a black card in recents instead of the last
 * screen.
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
        if (lpparam.packageName == "android") return

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
