package com.winlator.cmod.app.config

import android.os.Build
import android.util.Log
import com.winlator.cmod.R
import java.util.Locale

enum class DeviceProfile(
    val prefValue: String,
    val assetToken: String,
    val titleRes: Int,
    val summaryRes: Int,
) {
    DEFAULT("default", "", R.string.device_profile_default, R.string.device_profile_default_summary),
    ASTRA_2("astra2", "astra2", R.string.device_profile_astra_2, R.string.device_profile_astra_2_summary),
    ;

    companion object {
        private const val TAG = "DeviceProfile"

        private val MARKETING_NAME_KEYS =
            listOf(
                "ro.vendor.product.ztename",
                "ro.vendor.feature.nubia.exif.module",
                "ro.product.marketname",
                "ro.vendor.product.marketname",
            )

        private val ASTRA_2_NAME_TOKENS = listOf("astra 2", "astra2")

        private val ASTRA_2_BUILD_TOKENS = listOf("pq85p01", "np06j", "np06p")

        @JvmStatic
        fun fromPrefValue(value: String?): DeviceProfile =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: DEFAULT

        @JvmStatic
        fun detect(): DeviceProfile =
            classify(buildHaystack(), marketingName())

        internal fun classify(
            buildHaystack: String,
            marketingName: String,
        ): DeviceProfile {
            val name = marketingName.lowercase(Locale.ROOT)
            val build = buildHaystack.lowercase(Locale.ROOT)
            if (ASTRA_2_NAME_TOKENS.any { name.contains(it) }) return ASTRA_2
            if (ASTRA_2_BUILD_TOKENS.any { build.contains(it) }) return ASTRA_2
            if (ASTRA_2_NAME_TOKENS.any { build.contains(it) }) return ASTRA_2
            return DEFAULT
        }

        @JvmStatic
        fun deviceLabel(): String {
            val marketing = marketingName().trim()
            if (marketing.isNotEmpty()) return marketing
            val model = Build.MODEL.orEmpty().trim()
            val manufacturer = Build.MANUFACTURER.orEmpty().trim()
            if (model.isEmpty()) return manufacturer
            if (manufacturer.isEmpty()) return model
            if (model.lowercase(Locale.ROOT).startsWith(manufacturer.lowercase(Locale.ROOT))) return model
            return "$manufacturer $model"
        }

        private fun buildHaystack(): String =
            listOf(
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                Build.HARDWARE,
            ).joinToString(" ") { it.orEmpty() }

        private fun marketingName(): String {
            for (key in MARKETING_NAME_KEYS) {
                val value = readProperty(key)
                if (!value.isNullOrBlank()) return value
            }
            return ""
        }

        private fun readProperty(key: String): String? =
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                (clazz.getMethod("get", String::class.java).invoke(null, key) as? String)?.ifEmpty { null }
            } catch (e: Throwable) {
                Log.w(TAG, "SystemProperties unavailable for $key", e)
                null
            }
    }
}
