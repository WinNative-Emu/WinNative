package com.winlator.cmod.shared.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;

public final class RefreshRateUtils {
  private static final String TAG = "RefreshRateUtils";
  private static final float DEFAULT_REFRESH_RATE = 60f;
  private static final float FRAME_CADENCE_EPSILON = 0.01f;
  private static final Map<Activity, ViewTreeObserver.OnWindowFocusChangeListener>
      WINDOW_FOCUS_LISTENERS = new WeakHashMap<>();

  private RefreshRateUtils() {}

  public static int getSavedGlobalRefreshRateOverride(Context context) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    return Math.max(0, prefs.getInt("refresh_rate_override", 0));
  }

  public static List<Integer> getSupportedRefreshRates(Activity activity) {
    TreeSet<Integer> rates = new TreeSet<>();
    Display display = getDisplay(activity);
    if (display == null) {
      rates.add(Math.round(DEFAULT_REFRESH_RATE));
      return new ArrayList<>(rates);
    }

    for (Display.Mode mode : display.getSupportedModes()) {
      float refreshRate = mode.getRefreshRate();
      if (refreshRate > 0f) {
        rates.add(Math.round(refreshRate));
      }
    }
    if (rates.isEmpty()) {
      rates.add(Math.round(DEFAULT_REFRESH_RATE));
    }
    return new ArrayList<>(rates);
  }

  public static int getMaxSupportedRefreshRate(Activity activity) {
    List<Integer> rates = getSupportedRefreshRates(activity);
    return rates.isEmpty() ? Math.round(DEFAULT_REFRESH_RATE) : rates.get(rates.size() - 1);
  }

  public static List<String> buildRefreshRateEntryLabels(
      Activity activity, @Nullable String leadingEntry) {
    List<String> entries = new ArrayList<>();
    if (leadingEntry != null && !leadingEntry.isEmpty()) {
      entries.add(leadingEntry);
    }

    for (int rate : getSupportedRefreshRates(activity)) {
      entries.add(rate + " Hz");
    }
    return entries;
  }

  public static int parseRefreshRateLabel(@Nullable String value) {
    if (value == null) return 0;
    String normalized = value.trim();
    if (normalized.endsWith(" Hz")) {
      normalized = normalized.substring(0, normalized.length() - 3).trim();
    }
    try {
      int parsed = Integer.parseInt(normalized);
      return Math.max(parsed, 0);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  public static float resolvePreferredRefreshRate(Activity activity, int requestedHz) {
    Display display = getDisplay(activity);
    if (display == null) {
      return DEFAULT_REFRESH_RATE;
    }

    Display.Mode[] modes = display.getSupportedModes();
    float maxRefreshRate = DEFAULT_REFRESH_RATE;
    float exactMatch = 0f;
    float closestMatch = 0f;
    float closestDelta = Float.MAX_VALUE;

    for (Display.Mode mode : modes) {
      float refreshRate = mode.getRefreshRate();
      if (refreshRate <= 0f) continue;

      if (refreshRate > maxRefreshRate) {
        maxRefreshRate = refreshRate;
      }

      if (requestedHz > 0) {
        if (Math.round(refreshRate) == requestedHz && refreshRate > exactMatch) {
          exactMatch = refreshRate;
        }

        float delta = Math.abs(refreshRate - requestedHz);
        if (delta < closestDelta || (delta == closestDelta && refreshRate > closestMatch)) {
          closestDelta = delta;
          closestMatch = refreshRate;
        }
      }
    }

    if (requestedHz <= 0) {
      return maxRefreshRate;
    }
    if (exactMatch > 0f) {
      return exactMatch;
    }
    if (closestMatch > 0f) {
      return closestMatch;
    }
    return maxRefreshRate;
  }

  public static int resolvePreferredDisplayModeId(Activity activity, int requestedHz) {
    Display display = getDisplay(activity);
    if (display == null) {
      return 0;
    }

    Display.Mode currentMode = display.getMode();
    Display.Mode[] modes = display.getSupportedModes();

    Display.Mode bestMode = null;
    float bestModeRate = 0f;
    boolean bestIsExact = false;
    float closestDelta = Float.MAX_VALUE;

    for (Display.Mode mode : modes) {
      if (!isSameModeGroup(currentMode, mode)) continue;

      float refreshRate = mode.getRefreshRate();
      if (refreshRate <= 0f) continue;

      if (requestedHz <= 0) {
        if (bestMode == null || refreshRate > bestModeRate) {
          bestMode = mode;
          bestModeRate = refreshRate;
        }
        continue;
      }

      if (Math.round(refreshRate) == requestedHz) {
        // Any exact match beats a non-exact one; among exact matches keep the highest rate.
        if (!bestIsExact || refreshRate > bestModeRate) {
          bestMode = mode;
          bestModeRate = refreshRate;
          bestIsExact = true;
          closestDelta = 0f;
        }
        continue;
      }

      // Never let a non-exact mode override an exact match found elsewhere in the list.
      if (bestIsExact) continue;

      float delta = Math.abs(refreshRate - requestedHz);
      if (bestMode == null
          || delta < closestDelta
          || (delta == closestDelta && refreshRate > bestModeRate)) {
        bestMode = mode;
        bestModeRate = refreshRate;
        closestDelta = delta;
      }
    }

    if (bestMode != null) {
      return bestMode.getModeId();
    }
    return requestedHz <= 0 ? currentMode.getModeId() : 0;
  }

  public static int resolveFramePacedRefreshRate(Activity activity, int requestedHz, int fpsLimit) {
    // An explicit rate is the user's choice; only auto is steered to fit the cap's cadence.
    if (fpsLimit <= 0 || requestedHz > 0) {
      return requestedHz;
    }

    float preferredRefreshRate = resolvePreferredRefreshRate(activity, requestedHz);
    if (isFrameCadenceCompatible(preferredRefreshRate, fpsLimit)) {
      return Math.round(preferredRefreshRate);
    }

    Display display = getDisplay(activity);
    if (display == null) {
      return fpsLimit;
    }

    Display.Mode currentMode = display.getMode();
    Display.Mode bestMode = null;
    float bestModeRate = 0f;

    for (Display.Mode mode : display.getSupportedModes()) {
      if (!isSameModeGroup(currentMode, mode)) continue;

      float refreshRate = mode.getRefreshRate();
      if (refreshRate <= 0f || refreshRate < fpsLimit) continue;
      if (!isFrameCadenceCompatible(refreshRate, fpsLimit)) continue;

      if (bestMode == null
          || Math.round(refreshRate) == fpsLimit
          || refreshRate < bestModeRate) {
        bestMode = mode;
        bestModeRate = refreshRate;
      }
    }

    if (bestMode != null) {
      return Math.round(bestModeRate);
    }
    return fpsLimit;
  }

  public static boolean isFrameCadenceCompatible(float refreshRate, int fpsLimit) {
    if (refreshRate <= 0f || fpsLimit <= 0 || refreshRate < fpsLimit) {
      return false;
    }

    float ratio = refreshRate / fpsLimit;
    int nearestMultiple = Math.round(ratio);
    return nearestMultiple >= 1 && Math.abs(ratio - nearestMultiple) <= FRAME_CADENCE_EPSILON;
  }

  private static boolean isSameModeGroup(Display.Mode currentMode, Display.Mode candidateMode) {
    return currentMode.getPhysicalWidth() == candidateMode.getPhysicalWidth()
        && currentMode.getPhysicalHeight() == candidateMode.getPhysicalHeight();
  }

  @Nullable private static Display getDisplay(Activity activity) {
    return activity.getWindow().getDecorView().getDisplay();
  }

  public static void onActivityCreated(Activity activity) {
    attachWindowFocusListener(activity);
    applyPreferredRefreshRate(activity);
  }

  public static void onActivityResumed(Activity activity) {
    applyPreferredRefreshRate(activity);
  }

  public static void onActivityDestroyed(Activity activity) {
    detachWindowFocusListener(activity);
  }

  private static void attachWindowFocusListener(Activity activity) {
    if (WINDOW_FOCUS_LISTENERS.containsKey(activity)) return;

    View decorView = activity.getWindow().getDecorView();
    if (decorView == null) return;

    ViewTreeObserver observer = decorView.getViewTreeObserver();
    if (!observer.isAlive()) return;

    // Some devices do not fully honor the preferred mode until the window regains focus.
    ViewTreeObserver.OnWindowFocusChangeListener listener =
        hasFocus -> {
          if (hasFocus) {
            applyPreferredRefreshRate(activity);
          }
        };
    observer.addOnWindowFocusChangeListener(listener);
    WINDOW_FOCUS_LISTENERS.put(activity, listener);
  }

  private static void detachWindowFocusListener(Activity activity) {
    ViewTreeObserver.OnWindowFocusChangeListener listener = WINDOW_FOCUS_LISTENERS.remove(activity);
    if (listener == null) return;

    View decorView = activity.getWindow().getDecorView();
    if (decorView == null) return;

    ViewTreeObserver observer = decorView.getViewTreeObserver();
    if (!observer.isAlive()) return;
    observer.removeOnWindowFocusChangeListener(listener);
  }

  public static void applyPreferredRefreshRate(Activity activity) {
    applyPreferredRefreshRate(activity, getSavedGlobalRefreshRateOverride(activity));
  }

  public static void applyPreferredRefreshRate(Activity activity, int requestedHz) {
    applyPreferredRefreshRate(activity, requestedHz, 0);
  }

  public static void applyPreferredRefreshRate(Activity activity, int requestedHz, int fpsLimit) {
    if (activity.isFinishing() || activity.isDestroyed()) return;

    if (com.winlator.cmod.shared.framegen.FrameGen.INSTANCE.getRequested()) {
      com.winlator.cmod.shared.framegen.FrameGen.applyDisplayMode(activity);
      return;
    }

    // The window has no display until it is attached; applying here resolves to a
    // bogus fallback (mode 0 / default rate) that briefly overrides the real choice.
    // Skip and let the next apply (resume / focus / display-change) set it once ready.
    if (getDisplay(activity) == null) return;

    int effectiveRequestedHz = resolveFramePacedRefreshRate(activity, requestedHz, fpsLimit);
    WindowManager.LayoutParams params = activity.getWindow().getAttributes();
    int modeId = resolvePreferredDisplayModeId(activity, effectiveRequestedHz);
    float refreshRate = resolvePreferredRefreshRate(activity, effectiveRequestedHz);
    params.preferredDisplayModeId = modeId;
    // Must be 0 when a modeId is set.
    params.preferredRefreshRate = modeId != 0 ? 0f : refreshRate;
    activity.getWindow().setAttributes(params);
    Log.d(
        TAG,
        activity.getClass().getSimpleName()
            + " applyPreferredRefreshRate requestedHz="
            + requestedHz
            + " fpsLimit="
            + fpsLimit
            + " effectiveRequestedHz="
            + effectiveRequestedHz
            + " modeId="
            + modeId
            + " refreshRate="
            + refreshRate);
  }

  /** Current active refresh rate of the activity's display in Hz (0 if unavailable). */
  public static float getActiveRefreshRate(Activity activity) {
    Display display = getDisplay(activity);
    if (display == null) return 0f;
    Display.Mode mode = display.getMode();
    return mode != null ? mode.getRefreshRate() : 0f;
  }
}
