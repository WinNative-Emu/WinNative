package com.winlator.cmod.app.config

import android.content.Context
import androidx.preference.PreferenceManager
import com.winlator.cmod.R

enum class DeviceProfileFeature(
    val titleRes: Int,
    val summaryRes: Int,
    val integrated: Boolean,
) {
    INPUT_CONTROL_LAYOUTS(
        R.string.device_profile_feature_input_layouts,
        R.string.device_profile_feature_input_layouts_summary,
        true,
    ),
    ADAPTIVE_JOYSTICKS(
        R.string.device_profile_feature_adaptive_joysticks,
        R.string.device_profile_feature_adaptive_joysticks_summary,
        true,
    ),
    LIBRARY_ICONS(
        R.string.device_profile_feature_library_icons,
        R.string.device_profile_feature_library_icons_summary,
        true,
    ),
    SESSION_MENU_SIZES(
        R.string.device_profile_feature_session_menu,
        R.string.device_profile_feature_session_menu_summary,
        true,
    ),
    SHORTCUT_RECOMMENDATIONS(
        R.string.device_profile_feature_shortcut_defaults,
        R.string.device_profile_feature_shortcut_defaults_summary,
        false,
    ),
}

object DeviceProfileSettings {
    const val KEY_PROFILE = "device_profile"
    const val KEY_DETECTED = "device_profile_detected"
    const val STEAM_HEADER_ASPECT = 460f / 215f
    const val LIBRARY_TITLE_STRIP_DP = 24f
    const val STOCK_LIBRARY_CARD_FACTOR = 1.25f

    @Volatile
    private var cached: DeviceProfile? = null

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    @JvmStatic
    fun current(context: Context): DeviceProfile {
        cached?.let { return it }
        val resolved = DeviceProfile.fromPrefValue(prefs(context).getString(KEY_PROFILE, null))
        cached = resolved
        return resolved
    }

    @JvmStatic
    fun setCurrent(
        context: Context,
        profile: DeviceProfile,
    ) {
        prefs(context).edit().putString(KEY_PROFILE, profile.prefValue).apply()
        cached = profile
    }

    @JvmStatic
    fun detected(context: Context): DeviceProfile =
        DeviceProfile.fromPrefValue(prefs(context).getString(KEY_DETECTED, null))

    @JvmStatic
    fun isUserOverridden(context: Context): Boolean = current(context) != detected(context)

    @JvmStatic
    fun seedFromDetection(context: Context) {
        val preferences = prefs(context)
        val detected = DeviceProfile.detect()
        val editor = preferences.edit().putString(KEY_DETECTED, detected.prefValue)
        if (!preferences.contains(KEY_PROFILE)) {
            editor.putString(KEY_PROFILE, detected.prefValue)
            cached = detected
        }
        editor.apply()
    }

    @JvmStatic
    fun invalidate() {
        cached = null
    }

    @JvmStatic
    fun assetProfilesToken(context: Context): String = current(context).assetToken

    @JvmStatic
    fun adaptiveJoysticksDefault(context: Context): Boolean = current(context) == DeviceProfile.ASTRA_2

    @JvmStatic
    fun adaptiveJoysticksDefaultExtra(context: Context): String = if (adaptiveJoysticksDefault(context)) "1" else "0"

    @JvmStatic
    fun preferWideArtwork(context: Context): Boolean = preferWideArtwork(current(context))

    @JvmStatic
    fun libraryImageAspect(context: Context): Float? = libraryImageAspect(current(context))

    @JvmStatic
    fun libraryTitleStripDp(): Float = LIBRARY_TITLE_STRIP_DP

    @JvmStatic
    fun libraryColumns(
        context: Context,
        widthDp: Int,
        portrait: Boolean,
    ): Int = libraryColumns(current(context), widthDp, portrait)

    @JvmStatic
    fun sessionActionCardMaxAspect(context: Context): Float = sessionActionCardMaxAspect(current(context))

    internal fun preferWideArtwork(profile: DeviceProfile): Boolean = profile == DeviceProfile.ASTRA_2

    internal fun libraryImageAspect(profile: DeviceProfile): Float? =
        when (profile) {
            DeviceProfile.ASTRA_2 -> STEAM_HEADER_ASPECT
            DeviceProfile.DEFAULT -> null
        }

    internal fun libraryColumns(
        profile: DeviceProfile,
        widthDp: Int,
        portrait: Boolean,
    ): Int =
        when (profile) {
            DeviceProfile.ASTRA_2 -> if (portrait) 2 else 4
            DeviceProfile.DEFAULT -> stockLibraryColumns(widthDp)
        }

    internal fun stockLibraryColumns(widthDp: Int): Int =
        when {
            widthDp <= 0 -> 4
            widthDp < 480 -> 2
            widthDp < 700 -> 3
            else -> 4
        }

    internal fun sessionActionCardMaxAspect(profile: DeviceProfile): Float =
        when (profile) {
            DeviceProfile.ASTRA_2 -> 1.0f
            DeviceProfile.DEFAULT -> Float.MAX_VALUE
        }

    internal fun actionCardRowHeight(
        availableHeight: Float,
        cardWidth: Float,
        rows: Int,
        spacing: Float,
        minHeight: Float,
        maxAspect: Float,
    ): Float {
        val safeRows = rows.coerceAtLeast(1)
        val fill = (availableHeight - spacing * (safeRows - 1)) / safeRows
        val capped = if (maxAspect == Float.MAX_VALUE) fill else minOf(fill, cardWidth * maxAspect)
        return maxOf(capped, minHeight)
    }
}
