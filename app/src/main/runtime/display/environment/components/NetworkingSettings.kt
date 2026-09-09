package com.winlator.cmod.runtime.display.environment.components

import android.content.Context
import android.provider.Settings
import com.winlator.cmod.runtime.wine.EnvVars

object NetworkingSettings {
    const val EXTRA_DRIVER = "netDriver"
    const val EXTRA_MAC = "netMac"
    const val DRIVER_WINNATIVE = "winnative"
    const val DRIVER_NONE = "none"
    const val DEFAULT_DRIVER = DRIVER_WINNATIVE
    const val ENV_SHIM = "WN_NET_SHIM"
    const val ENV_MAC = "WN_NET_MAC"
    const val ENV_MAC_SEED = "WN_NET_MAC_SEED"
    private const val FALLBACK_SEED = "winnative"
    private val VENDOR_OUIS = arrayOf(
        intArrayOf(0x3c, 0x5a, 0xb4), intArrayOf(0x00, 0x1a, 0x11), intArrayOf(0xac, 0x5f, 0x3e), intArrayOf(0x8c, 0xf5, 0xa3),
        intArrayOf(0x94, 0x65, 0x2d), intArrayOf(0xc0, 0xee, 0xfb), intArrayOf(0x64, 0xa2, 0xf9), intArrayOf(0x00, 0xa0, 0xc6)
    )

    @JvmStatic
    fun macSeed(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return if (id.isNullOrEmpty()) FALLBACK_SEED else id
    }

    @JvmStatic
    fun automaticMac(context: Context, iface: String = "wlan0"): String {
        var h = 0x811c9dc5uL
        for (b in (macSeed(context) + iface).toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff).toULong()
            h *= 16777619uL
        }
        val oui = VENDOR_OUIS[((h shr 40) % VENDOR_OUIS.size.toULong()).toInt()]
        val bytes = intArrayOf(
            oui[0], oui[1], oui[2],
            ((h shr 16) and 0xffuL).toInt(),
            ((h shr 8) and 0xffuL).toInt(),
            (h and 0xffuL).toInt()
        )
        return bytes.joinToString(":") { "%02x".format(it) }
    }

    @JvmStatic
    fun normalizeMac(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val hex = input.filter { it.isLetterOrDigit() }.lowercase()
        if (hex.length != 12 || hex.any { it !in "0123456789abcdef" }) return ""
        val first = hex.substring(0, 2).toInt(16)
        if (first and 0x03 != 0) return ""
        return hex.chunked(2).joinToString(":")
    }

    @JvmStatic
    fun isValidMac(input: String?): Boolean = input.isNullOrBlank() || normalizeMac(input).isNotEmpty()

    @JvmStatic
    fun driverOrDefault(value: String?): String =
        if (value == DRIVER_NONE) DRIVER_NONE else DRIVER_WINNATIVE

    @JvmStatic
    fun applyEnv(envVars: EnvVars, context: Context, driver: String?, mac: String?) {
        envVars.put(ENV_MAC_SEED, macSeed(context))
        if (driverOrDefault(driver) == DRIVER_NONE) {
            envVars.put(ENV_SHIM, "0")
            envVars.remove(ENV_MAC)
            return
        }
        envVars.put(ENV_SHIM, "1")
        val fixed = normalizeMac(mac)
        if (fixed.isEmpty()) envVars.remove(ENV_MAC) else envVars.put(ENV_MAC, fixed)
    }
}
