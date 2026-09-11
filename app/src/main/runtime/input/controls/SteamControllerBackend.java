package com.winlator.cmod.runtime.input.controls;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import java.util.Arrays;
import org.libsdl.app.HIDDeviceManager;
import org.libsdl.app.SDL;

public final class SteamControllerBackend {
  private static final String TAG = "SteamControllerBackend";

  public static final int VALVE_VENDOR_ID = 0x28DE;
  public static final int DEVICE_ID_BASE = -1000;

  private static final long POLL_INTERVAL_MS = 4;
  private static final float TRACKPAD_PIXELS_PER_PAD = 900f;
  private static final float TRIGGER_FULL = 0.98f;

  private static final int MAX_PADS = 4;
  private static final int I_ID = 0, I_BUTTONS = 1, I_STRIDE = 2;
  private static final int F_LX = 0, F_LY = 1, F_RX = 2, F_RY = 3, F_LT = 4, F_RT = 5;
  private static final int F_RPAD_DOWN = 6, F_LPAD_DOWN = 9, F_STRIDE = 12;
  private static final int B_A = 0,
      B_B = 1,
      B_X = 2,
      B_Y = 3,
      B_LB = 4,
      B_RB = 5,
      B_BACK = 6,
      B_START = 7,
      B_LSTICK = 8,
      B_RSTICK = 9,
      B_GUIDE = 10,
      B_DPAD_UP = 11,
      B_DPAD_DOWN = 12,
      B_DPAD_LEFT = 13,
      B_DPAD_RIGHT = 14,
      B_QAM = 15,
      B_R4 = 16,
      B_L4 = 17,
      B_R5 = 18,
      B_L5 = 19,
      B_RPAD_CLICK = 20,
      B_LPAD_CLICK = 21;

  public static final int TRACKPAD_MOUSE_OFF = 0,
      TRACKPAD_MOUSE_RIGHT = 1,
      TRACKPAD_MOUSE_LEFT = 2,
      TRACKPAD_MOUSE_BOTH = 3;

  public static final int PADDLE_COUNT = 5;
  private static final int[] PADDLE_BITS = {B_L4, B_L5, B_R4, B_R5, B_QAM};

  private static final int[][] BUTTON_KEYCODES = {
    {B_A, KeyEvent.KEYCODE_BUTTON_A},
    {B_B, KeyEvent.KEYCODE_BUTTON_B},
    {B_X, KeyEvent.KEYCODE_BUTTON_X},
    {B_Y, KeyEvent.KEYCODE_BUTTON_Y},
    {B_LB, KeyEvent.KEYCODE_BUTTON_L1},
    {B_RB, KeyEvent.KEYCODE_BUTTON_R1},
    {B_BACK, KeyEvent.KEYCODE_BUTTON_SELECT},
    {B_START, KeyEvent.KEYCODE_BUTTON_START},
    {B_LSTICK, KeyEvent.KEYCODE_BUTTON_THUMBL},
    {B_RSTICK, KeyEvent.KEYCODE_BUTTON_THUMBR},
    {B_GUIDE, KeyEvent.KEYCODE_BUTTON_MODE},
  };

  public interface Listener {
    void onSteamPadConnected(ExternalController pad);

    void onSteamPadDisconnected(ExternalController pad);

    void onSteamPadState(
        ExternalController pad, boolean guideDown, boolean quickAccessDown, int[] pressedKeyCodes);

    void onSteamPadBinding(Binding binding, boolean down);

    void onSteamPadMouseMove(int dx, int dy);

    void onSteamPadMouseButton(boolean secondary, boolean down);
  }

  private static boolean librariesLoaded;
  private static boolean jniReady;
  private static SteamControllerBackend running_;

  private final Activity activity;
  private final Listener listener;
  private final int trackpadMode;
  private final Binding[] paddleBindings = new Binding[PADDLE_COUNT];
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private HIDDeviceManager hidManager;
  private Thread pollThread;
  private volatile boolean running;

  private final Object frameLock = new Object();
  private int[] frameInts;
  private float[] frameFloats;
  private String[] frameNames;
  private String[] framePaths;
  private int frameCount;
  private boolean applyQueued;
  private final Runnable applyFrame = this::applyFrame;

  private final SparseArray<Pad> pads = new SparseArray<>();

  private static final class Pad {
    final ExternalController controller;
    int buttons = -1;
    final float[] axes = new float[6];
    final Trackpad right = new Trackpad();
    final Trackpad left = new Trackpad();
    final boolean[] paddleDown = new boolean[PADDLE_COUNT];

    Pad(ExternalController controller) {
      this.controller = controller;
    }
  }

  private static final class Trackpad {
    boolean down, clickDown;
    float x, y, accX, accY;
  }

  public SteamControllerBackend(
      Activity activity, int trackpadMode, Binding[] paddles, Listener listener) {
    this.activity = activity;
    this.trackpadMode =
        (trackpadMode < TRACKPAD_MOUSE_OFF || trackpadMode > TRACKPAD_MOUSE_BOTH)
            ? TRACKPAD_MOUSE_RIGHT
            : trackpadMode;
    this.listener = listener;
    for (int i = 0; i < PADDLE_COUNT; i++) {
      Binding b = paddles != null && i < paddles.length ? paddles[i] : null;
      paddleBindings[i] = b == null ? Binding.NONE : b;
    }
  }

  public static boolean hasBluetoothPermission(Context context) {
    String permission =
        Build.VERSION.SDK_INT >= 31
            ? Manifest.permission.BLUETOOTH_CONNECT
            : Manifest.permission.BLUETOOTH;
    return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  public boolean start() {
    if (running) return true;
    if (running_ != null && running_ != this) running_.stop();
    if (!loadLibraries()) return false;
    try {
      if (!jniReady) {
        SDL.setupJNI();
        jniReady = true;
      }
      SDL.initialize();
      SDL.setContext(activity);
      hidManager = HIDDeviceManager.acquire(activity);
    } catch (Throwable t) {
      Log.e(TAG, "SDL Java setup failed; Steam Controller support stays off", t);
      releaseHidManager();
      clearSdlContextIfUnowned();
      return false;
    }
    final boolean bluetooth = hasBluetoothPermission(activity);
    running = true;
    running_ = this;
    pollThread = new Thread(() -> pollLoop(bluetooth), "SteamCtrlPoll");
    pollThread.start();
    Log.i(TAG, "Started (bluetooth " + bluetooth + ", trackpad mouse mode " + trackpadMode + ")");
    return true;
  }

  public void stop() {
    running = false;
    boolean pollThreadFinished = true;
    if (pollThread != null) {
      try {
        pollThread.join(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      pollThreadFinished = !pollThread.isAlive();
      pollThread = null;
    }
    if (running_ == this) running_ = null;
    mainHandler.removeCallbacks(applyFrame);
    synchronized (frameLock) {
      applyQueued = false;
      frameInts = null;
      frameFloats = null;
      frameCount = 0;
    }
    pads.clear();
    if (!pollThreadFinished) {
      Log.w(TAG, "Poll thread did not finish in time; leaving SDL teardown to it");
      return;
    }
    releaseHidManager();
    clearSdlContextIfUnowned();
    Log.i(TAG, "Stopped");
  }

  private void releaseHidManager() {
    if (hidManager == null) return;
    try {
      HIDDeviceManager.release(hidManager);
    } catch (Throwable t) {
      Log.e(TAG, "HIDDeviceManager.release failed", t);
    }
    hidManager = null;
  }

  private static void clearSdlContextIfUnowned() {
    if (running_ != null) return;
    try {
      SDL.setContext(null);
    } catch (Throwable t) {
      Log.e(TAG, "Clearing the SDL context failed", t);
    }
  }

  public void rumble(int deviceId, int low, int high, int durationMs) {
    if (running) nativeRumble(DEVICE_ID_BASE - deviceId, low, high, durationMs);
  }

  private static synchronized boolean loadLibraries() {
    if (librariesLoaded) return true;
    try {
      System.loadLibrary("SDL3");
      System.loadLibrary("steamctrl");
      librariesLoaded = true;
    } catch (Throwable t) {
      Log.e(TAG, "Could not load SDL3 / steamctrl; Steam Controller support stays off", t);
    }
    return librariesLoaded;
  }

  private void pollLoop(boolean bluetooth) {
    try {
      pollLoopInner(bluetooth);
    } catch (Throwable t) {
      Log.e(TAG, "Steam Controller poll thread failed; support stops this session", t);
      running = false;
      try {
        nativeShutdown();
      } catch (Throwable shutdownFailure) {
        Log.e(TAG, "nativeShutdown after poll failure also failed", shutdownFailure);
      }
    }
  }

  private void pollLoopInner(boolean bluetooth) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
    if (!nativeInit(bluetooth)) {
      Log.w(TAG, "SDL init failed; Steam Controller support stays off this session");
      running = false;
      return;
    }
    int[] ints = new int[MAX_PADS * I_STRIDE];
    float[] floats = new float[MAX_PADS * F_STRIDE];
    String[] names = new String[MAX_PADS];
    String[] paths = new String[MAX_PADS];
    int[] lastInts = new int[ints.length];
    float[] lastFloats = new float[floats.length];
    int lastCount = 0;
    SparseArray<String[]> identities = new SparseArray<>();
    while (running) {
      int count = nativePoll(ints, floats);
      if (count < 0) break;
      if (count != lastCount
          || !Arrays.equals(ints, lastInts)
          || !Arrays.equals(floats, lastFloats)) {
        for (int p = 0; p < count; p++) {
          int id = ints[p * I_STRIDE + I_ID];
          String[] identity = identities.get(id);
          if (identity == null) {
            identity = new String[] {nativeGetName(id), nativeGetPath(id)};
            identities.put(id, identity);
          }
          names[p] = identity[0];
          paths[p] = identity[1];
        }
        publish(ints, floats, names, paths, count);
        System.arraycopy(ints, 0, lastInts, 0, ints.length);
        System.arraycopy(floats, 0, lastFloats, 0, floats.length);
        lastCount = count;
      }
      SystemClock.sleep(POLL_INTERVAL_MS);
    }
    nativeShutdown();
  }

  private void publish(int[] ints, float[] floats, String[] names, String[] paths, int count) {
    synchronized (frameLock) {
      frameInts = ints.clone();
      frameFloats = floats.clone();
      frameNames = names.clone();
      framePaths = paths.clone();
      frameCount = count;
      if (applyQueued) return;
      applyQueued = true;
    }
    mainHandler.post(applyFrame);
  }

  private void applyFrame() {
    try {
      applyFrameInner();
    } catch (Throwable t) {
      Log.e(TAG, "Steam Controller frame delivery failed", t);
    }
  }

  private void applyFrameInner() {
    int[] ints;
    float[] floats;
    String[] names;
    String[] paths;
    int count;
    synchronized (frameLock) {
      ints = frameInts;
      floats = frameFloats;
      names = frameNames;
      paths = framePaths;
      count = frameCount;
      applyQueued = false;
    }
    if (!running || ints == null) return;

    for (int i = pads.size() - 1; i >= 0; i--) {
      int id = pads.keyAt(i);
      boolean present = false;
      for (int p = 0; p < count; p++) {
        if (ints[p * I_STRIDE + I_ID] == id) {
          present = true;
          break;
        }
      }
      if (!present) {
        Pad pad = pads.valueAt(i);
        pads.removeAt(i);
        releaseHeld(pad);
        listener.onSteamPadDisconnected(pad.controller);
      }
    }

    for (int p = 0; p < count; p++) {
      int id = ints[p * I_STRIDE + I_ID];
      Pad pad = pads.get(id);
      if (pad == null) {
        pad = new Pad(createController(id, names[p], paths[p]));
        pads.put(id, pad);
        listener.onSteamPadConnected(pad.controller);
      }
      applyPad(pad, ints[p * I_STRIDE + I_BUTTONS], floats, p * F_STRIDE);
    }
  }

  private static ExternalController createController(int sdlId, String name, String path) {
    ExternalController controller = new ExternalController();
    if (name == null || name.isEmpty()) name = "Steam Controller";
    controller.setName(name);
    controller.setId("sdl:" + (path != null && !path.isEmpty() ? path : name + "#" + sdlId));
    controller.setDeviceId(DEVICE_ID_BASE - sdlId);
    return controller;
  }

  private void applyPad(Pad pad, int buttons, float[] floats, int base) {
    boolean changed = buttons != pad.buttons;
    for (int a = 0; a < pad.axes.length; a++) {
      if (pad.axes[a] != floats[base + a]) {
        pad.axes[a] = floats[base + a];
        changed = true;
      }
    }
    if (changed) {
      pad.buttons = buttons;
      int effective = buttons;
      float lt = floats[base + F_LT];
      float rt = floats[base + F_RT];
      for (int i = 0; i < PADDLE_COUNT; i++) {
        boolean down = bit(buttons, PADDLE_BITS[i]);
        Binding target = paddleBindings[i];
        if (target == Binding.NONE) {
          pad.paddleDown[i] = down;
          continue;
        }
        if (target.isGamepad()) {
          if (down) {
            if (target == Binding.GAMEPAD_BUTTON_L2) lt = 1f;
            else if (target == Binding.GAMEPAD_BUTTON_R2) rt = 1f;
            else effective |= gamepadTargetBits(target);
          }
        } else if (down != pad.paddleDown[i]) {
          listener.onSteamPadBinding(target, down);
        }
        pad.paddleDown[i] = down;
      }

      GamepadState s = pad.controller.state;
      s.thumbLX = deadZone(floats[base + F_LX]);
      s.thumbLY = deadZone(floats[base + F_LY]);
      s.thumbRX = deadZone(floats[base + F_RX]);
      s.thumbRY = deadZone(floats[base + F_RY]);
      s.triggerL = lt;
      s.triggerR = rt;
      s.setPressed(ExternalController.IDX_BUTTON_A, bit(effective, B_A));
      s.setPressed(ExternalController.IDX_BUTTON_B, bit(effective, B_B));
      s.setPressed(ExternalController.IDX_BUTTON_X, bit(effective, B_X));
      s.setPressed(ExternalController.IDX_BUTTON_Y, bit(effective, B_Y));
      s.setPressed(ExternalController.IDX_BUTTON_L1, bit(effective, B_LB));
      s.setPressed(ExternalController.IDX_BUTTON_R1, bit(effective, B_RB));
      s.setPressed(ExternalController.IDX_BUTTON_SELECT, bit(effective, B_BACK));
      s.setPressed(ExternalController.IDX_BUTTON_START, bit(effective, B_START));
      s.setPressed(ExternalController.IDX_BUTTON_L3, bit(effective, B_LSTICK));
      s.setPressed(ExternalController.IDX_BUTTON_R3, bit(effective, B_RSTICK));
      s.setPressed(ExternalController.IDX_BUTTON_L2, s.triggerL >= TRIGGER_FULL);
      s.setPressed(ExternalController.IDX_BUTTON_R2, s.triggerR >= TRIGGER_FULL);
      s.dpad[0] = bit(effective, B_DPAD_UP);
      s.dpad[1] = bit(effective, B_DPAD_RIGHT);
      s.dpad[2] = bit(effective, B_DPAD_DOWN);
      s.dpad[3] = bit(effective, B_DPAD_LEFT);
      listener.onSteamPadState(
          pad.controller, bit(effective, B_GUIDE), bit(buttons, B_QAM), pressedKeyCodes(effective));
    }
    if (trackpadMode == TRACKPAD_MOUSE_RIGHT || trackpadMode == TRACKPAD_MOUSE_BOTH)
      applyTrackpad(pad.right, floats, base + F_RPAD_DOWN, bit(buttons, B_RPAD_CLICK), false);
    if (trackpadMode == TRACKPAD_MOUSE_LEFT || trackpadMode == TRACKPAD_MOUSE_BOTH)
      applyTrackpad(
          pad.left,
          floats,
          base + F_LPAD_DOWN,
          bit(buttons, B_LPAD_CLICK),
          trackpadMode == TRACKPAD_MOUSE_BOTH);
  }

  private static int gamepadTargetBits(Binding target) {
    switch (target) {
      case GAMEPAD_BUTTON_A:
        return 1 << B_A;
      case GAMEPAD_BUTTON_B:
        return 1 << B_B;
      case GAMEPAD_BUTTON_X:
        return 1 << B_X;
      case GAMEPAD_BUTTON_Y:
        return 1 << B_Y;
      case GAMEPAD_BUTTON_L1:
        return 1 << B_LB;
      case GAMEPAD_BUTTON_R1:
        return 1 << B_RB;
      case GAMEPAD_BUTTON_SELECT:
        return 1 << B_BACK;
      case GAMEPAD_BUTTON_START:
        return 1 << B_START;
      case GAMEPAD_BUTTON_L3:
        return 1 << B_LSTICK;
      case GAMEPAD_BUTTON_R3:
        return 1 << B_RSTICK;
      case GAMEPAD_DPAD_UP:
        return 1 << B_DPAD_UP;
      case GAMEPAD_DPAD_DOWN:
        return 1 << B_DPAD_DOWN;
      case GAMEPAD_DPAD_LEFT:
        return 1 << B_DPAD_LEFT;
      case GAMEPAD_DPAD_RIGHT:
        return 1 << B_DPAD_RIGHT;
      default:
        return 0;
    }
  }

  private static int[] pressedKeyCodes(int buttons) {
    int n = 0;
    for (int[] m : BUTTON_KEYCODES) if (bit(buttons, m[0])) n++;
    int[] out = new int[n];
    n = 0;
    for (int[] m : BUTTON_KEYCODES) if (bit(buttons, m[0])) out[n++] = m[1];
    return out;
  }

  private void applyTrackpad(
      Trackpad t, float[] floats, int off, boolean click, boolean secondary) {
    boolean down = floats[off] > 0.5f;
    float x = floats[off + 1];
    float y = floats[off + 2];
    if (down && t.down) {
      t.accX += (x - t.x) * TRACKPAD_PIXELS_PER_PAD;
      t.accY += (y - t.y) * TRACKPAD_PIXELS_PER_PAD;
      int dx = (int) t.accX;
      int dy = (int) t.accY;
      if (dx != 0 || dy != 0) {
        t.accX -= dx;
        t.accY -= dy;
        listener.onSteamPadMouseMove(dx, dy);
      }
    } else {
      t.accX = 0;
      t.accY = 0;
    }
    t.down = down;
    t.x = x;
    t.y = y;

    if (click != t.clickDown) {
      t.clickDown = click;
      listener.onSteamPadMouseButton(secondary, click);
    }
  }

  private void releaseHeld(Pad pad) {
    if (pad.right.clickDown) {
      pad.right.clickDown = false;
      listener.onSteamPadMouseButton(false, false);
    }
    if (pad.left.clickDown) {
      pad.left.clickDown = false;
      listener.onSteamPadMouseButton(trackpadMode == TRACKPAD_MOUSE_BOTH, false);
    }
    for (int i = 0; i < PADDLE_COUNT; i++) {
      Binding target = paddleBindings[i];
      if (pad.paddleDown[i] && target != Binding.NONE && !target.isGamepad())
        listener.onSteamPadBinding(target, false);
      pad.paddleDown[i] = false;
    }
  }

  private static boolean bit(int buttons, int b) {
    return (buttons & (1 << b)) != 0;
  }

  private static float deadZone(float v) {
    return Math.abs(v) >= ControlElement.STICK_DEAD_ZONE ? v : 0.0f;
  }

  private static native boolean nativeInit(boolean bluetooth);

  private static native int nativePoll(int[] ints, float[] floats);

  private static native String nativeGetName(int id);

  private static native String nativeGetPath(int id);

  private static native void nativeRumble(int id, int low, int high, int durationMs);

  private static native void nativeShutdown();
}
