package com.whixprs.phonelock

import android.content.Context

/** Whether the Phone Lock LSPosed module (root mode) is active. */
object ModuleStatus {

    /** Replaced with `true` by [com.whixprs.phonelock.xposed.XposedEntry] when LSPosed hooks this app. */
    @JvmStatic
    fun isHooked(): Boolean = false

    /**
     * Active when LSPosed hooks us, or when LSPosed's shared-preferences bridge
     * works (it lets MODE_WORLD_READABLE through only for enabled modules).
     */
    fun isActive(ctx: Context): Boolean = isHooked() || Prefs.xposedPrefs(ctx) != null
}
