package com.winlator.cmod.feature.retro

import android.content.Context
import androidx.preference.PreferenceManager

object RetroDefaults {
    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    private fun key(
        setting: String,
        systemId: String,
    ) = "retro_def_${setting}_$systemId"

    private fun varKey(
        systemId: String,
        optionKey: String,
    ) = "retro_def_var_${systemId}_$optionKey"

    fun romsDir(context: Context): String? =
        prefs(context).getString("retro_roms_dir", null)?.takeIf { it.isNotBlank() }

    fun setRomsDir(context: Context, value: String?) =
        prefs(context).edit().putString("retro_roms_dir", value).apply()

    fun shader(context: Context, systemId: String): String =
        prefs(context).getString(key("shader", systemId), "default") ?: "default"

    fun setShader(context: Context, systemId: String, value: String) =
        prefs(context).edit().putString(key("shader", systemId), value).apply()

    fun sgsr(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("sgsr", systemId), false)

    fun setSgsr(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("sgsr", systemId), value).apply()

    fun upscale(context: Context, systemId: String): String =
        prefs(context).getString(key("upscale", systemId), "native") ?: "native"

    fun setUpscale(context: Context, systemId: String, value: String) =
        prefs(context).edit().putString(key("upscale", systemId), value).apply()

    fun audio(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("audio", systemId), true)

    fun setAudio(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("audio", systemId), value).apply()

    fun touchControls(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("touch", systemId), true)

    fun setTouchControls(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("touch", systemId), value).apply()

    fun adaptiveSticks(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("adaptive", systemId), false)

    fun setAdaptiveSticks(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("adaptive", systemId), value).apply()

    fun hud(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean("retro_def_hud_global", false)

    fun setHud(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean("retro_def_hud_global", value).apply()

    fun frameGen(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("framegen", systemId), false)

    fun setFrameGen(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("framegen", systemId), value).apply()

    fun frameGenMultiplier(context: Context, systemId: String): Int =
        prefs(context).getInt(key("framegen_multiplier", systemId), 2)

    fun setFrameGenMultiplier(context: Context, systemId: String, value: Int) =
        prefs(context).edit().putInt(key("framegen_multiplier", systemId), value).apply()

    fun frameGenTargetRate(context: Context, systemId: String): Int =
        prefs(context).getInt(key("framegen_target", systemId), 0)

    fun setFrameGenTargetRate(context: Context, systemId: String, value: Int) =
        prefs(context).edit().putInt(key("framegen_target", systemId), value).apply()

    fun frameGenFlowScale(context: Context, systemId: String): Int =
        prefs(context).getInt(key("framegen_flow", systemId), 70)

    fun setFrameGenFlowScale(context: Context, systemId: String, value: Int) =
        prefs(context).edit().putInt(key("framegen_flow", systemId), value).apply()

    fun netplayEnabled(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("netplay", systemId), false)

    fun setNetplayEnabled(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("netplay", systemId), value).apply()

    fun clearNetplayArm(
        context: Context,
        systemId: String,
    ) {
        prefs(context)
            .edit()
            .putBoolean(key("netplay", systemId), false)
            .putString(key("netplay_mode", systemId), "off")
            .putBoolean(key("netplay_host_mode", systemId), false)
            .apply()
    }

    fun netplayHost(context: Context, systemId: String): String =
        prefs(context).getString(key("netplay_host", systemId), "") ?: ""

    fun setNetplayHost(context: Context, systemId: String, value: String) =
        prefs(context).edit().putString(key("netplay_host", systemId), value).apply()

    fun netplayPort(context: Context, systemId: String): Int {
        val fallback =
            when {
                RetroOnlineSupport.supportsGameLink(systemId) -> RetroGameLink.DEFAULT_PORT
                RetroOnlineSupport.supportsDolphinNetplay(systemId) -> 2626
                else -> 55435
            }
        return prefs(context).getInt(key("netplay_port", systemId), fallback)
    }

    fun setNetplayPort(context: Context, systemId: String, value: Int) =
        prefs(context).edit().putInt(key("netplay_port", systemId), value.coerceIn(1, 65535)).apply()

    fun netplayHostMode(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("netplay_host_mode", systemId), true)

    fun setNetplayHostMode(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("netplay_host_mode", systemId), value).apply()

    fun netplayTraversal(context: Context, systemId: String): Boolean =
        prefs(context).getBoolean(key("netplay_traversal", systemId), false)

    fun setNetplayTraversal(context: Context, systemId: String, value: Boolean) =
        prefs(context).edit().putBoolean(key("netplay_traversal", systemId), value).apply()

    fun netplayHostCode(context: Context, systemId: String): String =
        prefs(context).getString(key("netplay_host_code", systemId), "") ?: ""

    fun setNetplayHostCode(context: Context, systemId: String, value: String) =
        prefs(context).edit().putString(key("netplay_host_code", systemId), value.trim()).apply()

    fun netplayPlayerName(context: Context): String =
        prefs(context).getString("retro_netplay_player_name", "") ?: ""

    fun setNetplayPlayerName(context: Context, value: String) =
        prefs(context).edit().putString("retro_netplay_player_name", value.trim().take(24)).apply()

    fun netplayLaunchMode(context: Context, systemId: String): String {
        val raw = prefs(context).getString(key("netplay_mode", systemId), null)
        return when (raw?.lowercase()) {
            "join" -> "join"
            "host" -> "host"
            "off", "manual", null -> "off"
            else -> "off"
        }
    }

    fun setNetplayLaunchMode(context: Context, systemId: String, value: String) {
        val mode =
            when (value.lowercase()) {
                "join" -> "join"
                "host" -> "host"
                else -> "off"
            }
        prefs(context)
            .edit()
            .putString(key("netplay_mode", systemId), mode)
            .putBoolean(key("netplay_host_mode", systemId), mode == "host")
            .apply()
    }

    fun coreOption(
        context: Context,
        systemId: String,
        optionKey: String,
        fallback: String,
    ): String = prefs(context).getString(varKey(systemId, optionKey), fallback) ?: fallback

    fun setCoreOption(
        context: Context,
        systemId: String,
        optionKey: String,
        value: String,
    ) = prefs(context).edit().putString(varKey(systemId, optionKey), value).apply()
}
