package com.winlator.cmod.runtime.display.framegen

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

data class SystemFrameGenState(
    val vendorSupported: Boolean = false,
    val active: Boolean = false,
    val signal: String = "",
    val multiplier: Int = 0,
)

object SystemFrameGenDetector {
    private const val TAG = "SystemFrameGen"

    private val VENDOR_TOKENS = listOf("nubia", "redmagic", "red magic", "zte")

    private val FEATURE_TOKENS =
        listOf(
            "frc",
            "framegen",
            "frame_gen",
            "frameinsert",
            "frame_insert",
            "frameinterp",
            "frame_interp",
            "frameinterpolation",
            "frame_interpolation",
            "frameboost",
            "frame_boost",
            "framerateboost",
            "memc",
            "motionsmooth",
            "motion_smooth",
        )

    private val SCOPE_TOKENS = VENDOR_TOKENS + listOf("game", "display", "video")

    private val NEGATIVE_TOKENS = listOf("support", "capable", "available", "version", "list", "whitelist")

    private val PROPERTY_LINE = Regex("^\\[(.+?)]: \\[(.*)]$")

    private val MULTIPLIER_HINT = Regex("(?:^|[^0-9])([234])\\s*[xX]|[xX]\\s*([234])(?:[^0-9]|$)")

    @Volatile
    private var cached: SystemFrameGenState? = null

    @Volatile
    private var cachedAtMs = 0L

    @JvmStatic
    fun isVendorDevice(): Boolean {
        val haystack =
            listOf(
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                Build.HARDWARE,
            ).joinToString(" ") { it.orEmpty() }.lowercase(Locale.ROOT)
        return VENDOR_TOKENS.any { haystack.contains(it) }
    }

    @JvmStatic
    @JvmOverloads
    fun detect(
        context: Context,
        maxAgeMs: Long = 2000L,
    ): SystemFrameGenState {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAtMs < maxAgeMs) return it }

        val state =
            if (!isVendorDevice()) {
                SystemFrameGenState()
            } else {
                evaluate(readProperties() + readSettings(context))
            }
        cached = state
        cachedAtMs = now
        return state
    }

    @JvmStatic
    fun invalidate() {
        cached = null
    }

    internal fun evaluate(entries: Map<String, String>): SystemFrameGenState {
        var best: Pair<String, String>? = null
        for ((key, value) in entries) {
            if (!isFrameGenKey(key)) continue
            if (!isEnabledValue(value)) {
                if (best == null) best = key to value
                continue
            }
            best = key to value
            break
        }

        val match = best ?: return SystemFrameGenState(vendorSupported = true)
        val active = isEnabledValue(match.second)
        return SystemFrameGenState(
            vendorSupported = true,
            active = active,
            signal = "${match.first}=${match.second}",
            multiplier = if (active) parseMultiplier(match.second) else 0,
        )
    }

    internal fun isFrameGenKey(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        if (NEGATIVE_TOKENS.any { lower.contains(it) }) return false
        if (FEATURE_TOKENS.none { lower.contains(it) }) return false
        return SCOPE_TOKENS.any { lower.contains(it) }
    }

    internal fun isEnabledValue(value: String): Boolean {
        val v = value.trim().lowercase(Locale.ROOT)
        if (v.isEmpty()) return false
        if (v == "0" || v == "false" || v == "off" || v == "none" || v == "null") return false
        v.toIntOrNull()?.let { return it > 0 }
        return v == "true" || v == "on" || v == "enable" || v == "enabled" || MULTIPLIER_HINT.containsMatchIn(v)
    }

    internal fun parseMultiplier(value: String): Int {
        val v = value.trim().lowercase(Locale.ROOT)
        v.toIntOrNull()?.let { if (it in 2..4) return it }
        val m = MULTIPLIER_HINT.find(v) ?: return 0
        val digits = m.groupValues[1].ifEmpty { m.groupValues[2] }
        return digits.toIntOrNull() ?: 0
    }

    private fun readProperties(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val process = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val m = PROPERTY_LINE.find(line.trim()) ?: continue
                    out[m.groupValues[1]] = m.groupValues[2]
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate system properties", e)
        }
        return out
    }

    private fun readSettings(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val resolver = context.contentResolver ?: return out
        for (uri in listOf(Settings.Global.CONTENT_URI, Settings.System.CONTENT_URI, Settings.Secure.CONTENT_URI)) {
            try {
                resolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    val valueIndex = cursor.getColumnIndex("value")
                    if (nameIndex < 0 || valueIndex < 0) return@use
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex) ?: continue
                        out[name] = cursor.getString(valueIndex).orEmpty()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not enumerate $uri", e)
            }
        }
        return out
    }
}
