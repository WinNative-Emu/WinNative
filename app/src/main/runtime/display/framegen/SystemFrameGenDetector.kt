package com.winlator.cmod.runtime.display.framegen

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

data class SystemFrameGenState(
    val vendorSupported: Boolean = false,
    val active: Boolean = false,
    val signal: String = "",
    val multiplier: Int = 1,
)

object SystemFrameGenDetector {
    private const val TAG = "SystemFrameGen"

    const val KEY_INTERP_RATE = "vendor.gpp.gfrc.interp.rate"
    const val KEY_FRC_ENABLE = "vendor.gpp.frc.enable"

    private val CAPABILITY_KEYS =
        listOf("vendor.gpp.create_frc_extension", "ro.vendor.feature.zte_feature_gfrc")

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
            "memc",
            "motionsmooth",
            "motion_smooth",
        )

    private val SCOPE_TOKENS = VENDOR_TOKENS + listOf("gpp", "game", "display")

    private val EXCLUDED_TOKENS =
        listOf(
            "feature",
            "support",
            "capable",
            "available",
            "version",
            "list",
            "whitelist",
            "extension",
            "upscale",
            "resolution",
            "sharpness",
        )

    private val PROPERTY_LINE = Regex("^\\[(.+?)]: \\[(.*)]$")

    private const val MAX_MULTIPLIER = 8

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
    fun detect(maxAgeMs: Long = 1500L): SystemFrameGenState {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAtMs < maxAgeMs) return it }

        val state =
            if (!isVendorDevice()) {
                SystemFrameGenState()
            } else {
                evaluate(
                    interpRate = readProperty(KEY_INTERP_RATE),
                    frcEnable = readProperty(KEY_FRC_ENABLE),
                    capable = CAPABILITY_KEYS.any { isTruthy(readProperty(it)) },
                    fallback = { readAllProperties() },
                )
            }
        cached = state
        cachedAtMs = now
        return state
    }

    @JvmStatic
    fun invalidate() {
        cached = null
    }

    internal fun evaluate(
        interpRate: String?,
        frcEnable: String?,
        capable: Boolean,
        fallback: () -> Map<String, String>,
    ): SystemFrameGenState {
        val inserted = parseNumber(interpRate)
        val enable = parseNumber(frcEnable)
        val enableMultiplier = enable?.let { (it and 0xF).coerceIn(1, MAX_MULTIPLIER) } ?: 1

        if (inserted != null && inserted > 0) {
            val multiplier = (inserted + 1).coerceIn(1, MAX_MULTIPLIER)
            return SystemFrameGenState(
                vendorSupported = true,
                active = true,
                signal = "$KEY_INTERP_RATE=$interpRate",
                multiplier = multiplier,
            )
        }

        if (enable != null && enableMultiplier > 1) {
            return SystemFrameGenState(
                vendorSupported = true,
                active = true,
                signal = "$KEY_FRC_ENABLE=$frcEnable",
                multiplier = enableMultiplier,
            )
        }

        if (inserted != null || enable != null) {
            val signal =
                if (inserted != null) "$KEY_INTERP_RATE=$interpRate" else "$KEY_FRC_ENABLE=$frcEnable"
            return SystemFrameGenState(
                vendorSupported = true,
                active = false,
                signal = signal,
                multiplier = 1,
            )
        }

        return scan(fallback(), capable)
    }

    internal fun scan(
        entries: Map<String, String>,
        capable: Boolean,
    ): SystemFrameGenState {
        val candidates =
            entries.entries
                .filter { isStateKey(it.key) }
                .sortedByDescending { if (isRateKey(it.key)) 1 else 0 }

        for ((key, value) in candidates) {
            if (!isTruthy(value)) continue
            val multiplier =
                if (isRateKey(key)) {
                    ((parseNumber(value) ?: 1) + 1).coerceIn(2, MAX_MULTIPLIER)
                } else {
                    2
                }
            return SystemFrameGenState(
                vendorSupported = true,
                active = true,
                signal = "$key=$value",
                multiplier = multiplier,
            )
        }

        val first = candidates.firstOrNull()
        return SystemFrameGenState(
            vendorSupported = capable || first != null,
            active = false,
            signal = if (first != null) "${first.key}=${first.value}" else "",
            multiplier = 1,
        )
    }

    internal fun isRateKey(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        return lower.contains("rate") || lower.contains("interp")
    }

    internal fun isStateKey(key: String): Boolean {
        if (key == KEY_INTERP_RATE || key == KEY_FRC_ENABLE) return false
        val lower = key.lowercase(Locale.ROOT)
        if (lower.startsWith("ro.")) return false
        if (EXCLUDED_TOKENS.any { lower.contains(it) }) return false
        if (FEATURE_TOKENS.none { lower.contains(it) }) return false
        return SCOPE_TOKENS.any { lower.contains(it) }
    }

    internal fun parseNumber(value: String?): Int? {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (v.isEmpty()) return null
        return if (v.startsWith("0x")) {
            v.substring(2).toIntOrNull(16)
        } else {
            v.toIntOrNull()
        }
    }

    internal fun isTruthy(value: String?): Boolean {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (v.isEmpty()) return false
        parseNumber(v)?.let { return it > 0 }
        return v == "true" || v == "on" || v == "enabled" || v == "enable"
    }

    private fun readProperty(key: String): String? {
        val viaFramework = readPropertyReflective(key)
        if (viaFramework != null) return viaFramework.ifEmpty { null }
        return runCommand(listOf("/system/bin/getprop", key))?.trim()?.ifEmpty { null }
    }

    private fun readPropertyReflective(key: String): String? =
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java).invoke(null, key) as? String
        } catch (e: Throwable) {
            Log.w(TAG, "SystemProperties unavailable, falling back to getprop", e)
            null
        }

    private fun readAllProperties(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val dump = runCommand(listOf("/system/bin/getprop")) ?: return out
        for (line in dump.lineSequence()) {
            val m = PROPERTY_LINE.find(line.trim()) ?: continue
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    private fun runCommand(command: List<String>): String? =
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text =
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            text
        } catch (e: Exception) {
            Log.w(TAG, "Command failed: ${command.joinToString(" ")}", e)
            null
        }
}
