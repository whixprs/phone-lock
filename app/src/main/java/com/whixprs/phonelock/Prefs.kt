package com.whixprs.phonelock

import android.content.Context
import android.content.SharedPreferences

/** Simple storage for the two app lists the user configures. */
object Prefs {
    private const val FILE = "phone_lock"
    const val KEY_LOCKED = "locked_apps"
    const val KEY_HIDDEN = "hidden_recents_apps"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet(key, emptySet()) ?: emptySet())

    fun set(ctx: Context, key: String, pkg: String, enabled: Boolean) {
        val current = get(ctx, key)
        if (enabled) current.add(pkg) else current.remove(pkg)
        prefs(ctx).edit().putStringSet(key, current).apply()
    }
}
