package com.winlator.cmod.shared.io

import android.content.Context
import androidx.preference.PreferenceManager

object DownloadSource {
    private const val PREF_KEY = "download_source_base"
    private const val PREF_CHINA_MIRROR = "use_china_mirror"
    private const val PREF_CHINA_MIRROR_BASE = "china_mirror_base"

    const val DEFAULT_CHINA_MIRROR_BASE = "https://gh-proxy.com"

    fun isChineseLocale(): Boolean = java.util.Locale.getDefault().language.startsWith("zh")

    fun chinaMirrorEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return if (prefs.contains(PREF_CHINA_MIRROR)) {
            prefs.getBoolean(PREF_CHINA_MIRROR, false)
        } else {
            isChineseLocale()
        }
    }

    fun chinaMirrorBase(context: Context): String {
        val stored =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_CHINA_MIRROR_BASE, "")
                ?.trim()
                ?.trimEnd('/')
                .orEmpty()
        return stored.ifBlank { DEFAULT_CHINA_MIRROR_BASE }
    }

    private fun customBase(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, "")
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()

    fun mirroredUrl(context: Context, url: String): String {
        if (url.isBlank()) return url

        if (chinaMirrorEnabled(context)) {
            val base = chinaMirrorBase(context)
            if (base.isNotEmpty() &&
                !url.startsWith(base) &&
                (url.startsWith("https://github.com/") ||
                    url.startsWith("https://raw.githubusercontent.com/"))
            ) {
                return base.trimEnd('/') + "/" + url
            }
            return url
        }

        val custom = customBase(context)
        if (custom.isEmpty()) return url
        val rawPrefix = "https://raw.githubusercontent.com/"
        if (url.startsWith(rawPrefix)) {
            val rest = url.substring(rawPrefix.length)
            val parts = rest.split('/')
            if (parts.size >= 3) {
                val ownerRepo = parts[0] + "/" + parts[1]
                val branch = parts[2]
                val path = parts.drop(3).joinToString("/")
                return "$custom/$ownerRepo/raw/$branch/$path"
            }
        }
        if (url.startsWith("https://github.com/")) {
            return custom + "/" + url.substring("https://github.com/".length)
        }
        return url
    }
}
