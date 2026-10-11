package com.winlator.cmod.app.update

import android.content.Context
import androidx.preference.PreferenceManager

object UpdateStore {
    const val PREF_ENABLED = "check_for_updates"
    private const val PREF_IGNORED_KEY = "update_ignored_key"
    private const val PREF_LAST_CHECK = "update_last_check"

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_ENABLED, true)

    fun ignoredKey(context: Context): String? = prefs(context).getString(PREF_IGNORED_KEY, null)

    fun ignore(
        context: Context,
        key: String,
    ) {
        prefs(context).edit().putString(PREF_IGNORED_KEY, key).apply()
    }

    fun lastCheck(context: Context): Long = prefs(context).getLong(PREF_LAST_CHECK, 0L)

    fun markChecked(context: Context) {
        prefs(context).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
    }
}
