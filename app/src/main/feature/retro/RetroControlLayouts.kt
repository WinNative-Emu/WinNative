package com.winlator.cmod.feature.retro

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

data class RetroControlOverride(
    var x: Float,
    var y: Float,
    var scale: Float = 1f,
    var visible: Boolean = true,
)

data class RetroCustomColors(
    var body: Int? = null,
    var button: Int? = null,
    var text: Int? = null,
    var shadow: Int? = null,
    var opacity: Float = 0.55f,
)

object RetroControlLayouts {
    private fun layoutKey(
        systemId: String,
        portrait: Boolean,
    ) = "retro_layout_v1_${systemId}_${if (portrait) "port" else "land"}"

    private fun colorsKey(systemId: String) = "retro_colors_v1_$systemId"

    fun shellBackgroundKey(systemId: String?) = "retro_shell_bg_v1_" + (systemId ?: "default")

    fun shellBackground(
        context: Context,
        systemId: String?,
    ): Boolean =
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean(shellBackgroundKey(systemId), true)

    fun saveShellBackground(
        context: Context,
        systemId: String?,
        enabled: Boolean,
    ) {
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(shellBackgroundKey(systemId), enabled)
            .apply()
    }

    fun load(
        context: Context,
        systemId: String?,
        portrait: Boolean,
    ): MutableMap<String, RetroControlOverride> {
        val result = mutableMapOf<String, RetroControlOverride>()
        if (systemId == null) return result
        val json =
            PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(layoutKey(systemId, portrait), null) ?: return result
        runCatching {
            val obj = JSONObject(json)
            obj.keys().forEach { id ->
                val c = obj.getJSONObject(id)
                result[id] =
                    RetroControlOverride(
                        c.optDouble("x", 0.5).toFloat(),
                        c.optDouble("y", 0.5).toFloat(),
                        c.optDouble("s", 1.0).toFloat(),
                        c.optBoolean("v", true),
                    )
            }
        }
        return result
    }

    fun save(
        context: Context,
        systemId: String?,
        portrait: Boolean,
        map: Map<String, RetroControlOverride>,
    ) {
        if (systemId == null) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (map.isEmpty()) {
            prefs.edit().remove(layoutKey(systemId, portrait)).apply()
            return
        }
        val obj = JSONObject()
        map.forEach { (id, o) ->
            obj.put(
                id,
                JSONObject()
                    .put("x", o.x.toDouble())
                    .put("y", o.y.toDouble())
                    .put("s", o.scale.toDouble())
                    .put("v", o.visible),
            )
        }
        prefs.edit().putString(layoutKey(systemId, portrait), obj.toString()).apply()
    }

    fun reset(
        context: Context,
        systemId: String?,
        portrait: Boolean,
    ) {
        if (systemId == null) return
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .remove(layoutKey(systemId, portrait))
            .apply()
    }

    fun loadColors(
        context: Context,
        systemId: String?,
    ): RetroCustomColors {
        val colors = RetroCustomColors()
        if (systemId == null) return colors
        val json =
            PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(colorsKey(systemId), null) ?: return colors
        runCatching {
            val obj = JSONObject(json)
            if (obj.has("body")) colors.body = obj.getInt("body")
            if (obj.has("button")) colors.button = obj.getInt("button")
            if (obj.has("text")) colors.text = obj.getInt("text")
            if (obj.has("shadow")) colors.shadow = obj.getInt("shadow")
            if (obj.has("opacity")) colors.opacity = obj.getDouble("opacity").toFloat().coerceIn(0.2f, 1.0f)
        }
        return colors
    }

    fun saveColors(
        context: Context,
        systemId: String?,
        colors: RetroCustomColors,
    ) {
        if (systemId == null) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (colors.body == null && colors.button == null && colors.text == null && colors.shadow == null && colors.opacity == 0.55f) {
            prefs.edit().remove(colorsKey(systemId)).apply()
            return
        }
        val obj = JSONObject()
        colors.body?.let { obj.put("body", it) }
        colors.button?.let { obj.put("button", it) }
        colors.text?.let { obj.put("text", it) }
        colors.shadow?.let { obj.put("shadow", it) }
        obj.put("opacity", colors.opacity.toDouble())
        prefs.edit().putString(colorsKey(systemId), obj.toString()).apply()
    }
}