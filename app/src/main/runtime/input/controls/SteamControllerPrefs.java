package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public final class SteamControllerPrefs {
  public static final String KEY_ENABLED = "steam_controller_sdl_enabled";
  public static final String KEY_TRACKPAD_MODE = "steam_controller_trackpad_mode";

  private static final String[] KEY_PADDLES = {
    "steam_controller_paddle_l4",
    "steam_controller_paddle_l5",
    "steam_controller_paddle_r4",
    "steam_controller_paddle_r5",
    "steam_controller_button_qam",
  };

  private SteamControllerPrefs() {}

  public static boolean isEnabled(Context context) {
    return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, false);
  }

  public static void setEnabled(Context context, boolean enabled) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply();
  }

  public static int getTrackpadMouseMode(Context context) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    int mode = prefs.getInt(KEY_TRACKPAD_MODE, SteamControllerBackend.TRACKPAD_MOUSE_RIGHT);
    if (mode < SteamControllerBackend.TRACKPAD_MOUSE_OFF
        || mode > SteamControllerBackend.TRACKPAD_MOUSE_BOTH) {
      return SteamControllerBackend.TRACKPAD_MOUSE_RIGHT;
    }
    return mode;
  }

  public static void setTrackpadMouseMode(Context context, int mode) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putInt(KEY_TRACKPAD_MODE, mode)
        .apply();
  }

  private static Binding parse(String name) {
    if (name == null || name.isEmpty()) return Binding.NONE;
    try {
      return Binding.fromString(name);
    } catch (IllegalArgumentException e) {
      return Binding.NONE;
    }
  }

  public static Binding[] getPaddleBindings(Context context) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    Binding[] out = new Binding[KEY_PADDLES.length];
    for (int i = 0; i < KEY_PADDLES.length; i++) {
      out[i] = parse(prefs.getString(KEY_PADDLES[i], null));
    }
    return out;
  }

  public static Binding getPaddleBinding(Context context, int index) {
    if (index < 0 || index >= KEY_PADDLES.length) return Binding.NONE;
    return parse(
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_PADDLES[index], null));
  }

  public static void setPaddleBinding(Context context, int index, Binding binding) {
    if (index < 0 || index >= KEY_PADDLES.length) return;
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putString(KEY_PADDLES[index], binding == null ? Binding.NONE.name() : binding.name())
        .apply();
  }
}
