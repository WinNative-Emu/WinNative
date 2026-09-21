package com.winlator.cmod.runtime.input.controls;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class SteamControllerNavigation implements SteamControllerBackend.Listener {
  private final Consumer<KeyEvent> dispatch;
  private final boolean trackpadNavigation;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<Integer, Set<Integer>> pads = new HashMap<>();
  private final Map<Integer, Long> held = new HashMap<>();
  private final Runnable repeat = this::repeatDirections;
  private boolean repeatScheduled;
  private int generation;

  public SteamControllerNavigation(Consumer<KeyEvent> dispatch) {
    this(dispatch, true);
  }

  public SteamControllerNavigation(Consumer<KeyEvent> dispatch, boolean trackpadNavigation) {
    this.dispatch = dispatch;
    this.trackpadNavigation = trackpadNavigation;
  }

  public boolean hasControllers() { return !pads.isEmpty(); }

  public void clear() {
    generation++;
    pads.clear();
    updateKeys();
  }

  @Override public void onSteamPadConnected(ExternalController pad) {
    pads.put(pad.getDeviceId(), new HashSet<>());
  }

  @Override public void onSteamPadDisconnected(ExternalController pad) {
    pads.remove(pad.getDeviceId());
    updateKeys();
  }

  @Override public void onSteamPadState(ExternalController pad, boolean guide, boolean quickAccess, int[] pressed) {
    Set<Integer> keys = new HashSet<>();
    for (int key : pressed) {
      if (!trackpadNavigation && (key == KeyEvent.KEYCODE_BUTTON_6 || key == KeyEvent.KEYCODE_BUTTON_7)) continue;
      switch (key) {
        case KeyEvent.KEYCODE_BUTTON_1:
        case KeyEvent.KEYCODE_BUTTON_2: key = KeyEvent.KEYCODE_BUTTON_L1; break;
        case KeyEvent.KEYCODE_BUTTON_3:
        case KeyEvent.KEYCODE_BUTTON_4: key = KeyEvent.KEYCODE_BUTTON_R1; break;
        case KeyEvent.KEYCODE_BUTTON_5: key = KeyEvent.KEYCODE_BUTTON_START; break;
        case KeyEvent.KEYCODE_BUTTON_6: key = KeyEvent.KEYCODE_BUTTON_B; break;
        case KeyEvent.KEYCODE_BUTTON_7: key = KeyEvent.KEYCODE_BUTTON_A; break;
      }
      keys.add(key);
    }
    addDirection(keys, pad.state.thumbLX, pad.state.thumbLY);
    if (trackpadNavigation && pad.steamLeftTouch) addDirection(keys, pad.steamLeftX * 2 - 1, pad.steamLeftY * 2 - 1);
    if (trackpadNavigation && pad.steamRightTouch) addDirection(keys, pad.steamRightX * 2 - 1, pad.steamRightY * 2 - 1);
    if (pad.state.triggerL > 0.5f) keys.add(KeyEvent.KEYCODE_BUTTON_L2);
    if (pad.state.triggerR > 0.5f) keys.add(KeyEvent.KEYCODE_BUTTON_R2);
    pads.put(pad.getDeviceId(), keys);
    updateKeys();
  }

  private static void addDirection(Set<Integer> keys, float x, float y) {
    if (Math.max(Math.abs(x), Math.abs(y)) <= 0.55f) return;
    if (Math.abs(x) > Math.abs(y)) {
      keys.add(x < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
    } else {
      keys.add(y < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
    }
  }

  private void updateKeys() {
    int delivery = generation;
    Set<Integer> next = new HashSet<>();
    for (Set<Integer> keys : pads.values()) next.addAll(keys);
    for (int key : new HashSet<>(held.keySet())) {
      if (!next.contains(key)) {
        long downTime = held.remove(key);
        emit(key, KeyEvent.ACTION_UP, downTime, 0);
        if (delivery != generation) return;
      }
    }
    for (int key : next) {
      if (!held.containsKey(key)) {
        long now = SystemClock.uptimeMillis();
        held.put(key, now);
        emit(key, KeyEvent.ACTION_DOWN, now, 0);
        if (delivery != generation) return;
      }
    }
    scheduleRepeat();
  }

  private void scheduleRepeat() {
    if (held.keySet().stream().anyMatch(SteamControllerNavigation::isDirection)) {
      if (!repeatScheduled) {
        repeatScheduled = true;
        handler.postDelayed(repeat, 250);
      }
    } else {
      handler.removeCallbacks(repeat);
      repeatScheduled = false;
    }
  }

  private static boolean isDirection(int key) {
    return key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
  }

  private void repeatDirections() {
    repeatScheduled = false;
    for (int key : new HashSet<>(held.keySet())) {
      Long downTime = held.get(key);
      if (downTime != null && isDirection(key)) emit(key, KeyEvent.ACTION_DOWN, downTime, 1);
    }
    scheduleRepeat();
  }

  private void emit(int key, int action, long downTime, int repeats) {
    dispatch.accept(new KeyEvent(downTime, SystemClock.uptimeMillis(), action, key, repeats, 0,
        SteamControllerBackend.DEVICE_ID_BASE, 0, 0, InputDevice.SOURCE_GAMEPAD));
  }

  @Override public void onSteamPadBinding(Binding binding, boolean down) {}
  @Override public void onSteamPadMouseMove(int dx, int dy) {}
  @Override public void onSteamPadMouseButton(boolean secondary, boolean down) {}
}
