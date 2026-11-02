package com.whixprs.phonelock

import android.content.Context
import android.content.SharedPreferences

/** Simple storage for the two app lists the user configures. */
object Prefs {
    private const val FILE = "phone_lock"
    const val KEY_LOCKED = "locked_apps"
    const val KEY_HIDDEN = "hidden_recents_apps"
    const val KEY_RELOCK_MODE = "relock_mode"
    const val KEY_RELOCK_MINUTES = "relock_minutes"

    /** When an unlocked app locks again. */
    enum class RelockMode { BACKGROUND, TIMEOUT, SCREEN_OFF }

    fun relockMode(ctx: Context): RelockMode =
        runCatching { RelockMode.valueOf(prefs(ctx).getString(KEY_RELOCK_MODE, null)!!) }
            .getOrDefault(RelockMode.BACKGROUND)

    fun setRelockMode(ctx: Context, mode: RelockMode) =
        prefs(ctx).edit().putString(KEY_RELOCK_MODE, mode.name).apply()

    fun relockMinutes(ctx: Context): Int = prefs(ctx).getInt(KEY_RELOCK_MINUTES, 5)

    fun setRelockMinutes(ctx: Context, minutes: Int) =
        prefs(ctx).edit().putInt(KEY_RELOCK_MINUTES, minutes).apply()

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
