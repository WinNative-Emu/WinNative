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
        false,
    ),
    SESSION_MENU_SIZES(
        R.string.device_profile_feature_session_menu,
        R.string.device_profile_feature_session_menu_summary,
        false,
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
}
