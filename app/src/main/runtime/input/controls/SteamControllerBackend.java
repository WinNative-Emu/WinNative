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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.winnative.steam.HIDDeviceManager;
import org.winnative.steam.SDL;

public final class SteamControllerBackend {
  private static final String TAG = "SteamControllerBackend";

  public static final int VALVE_VENDOR_ID = 0x28DE;
  public static final int DEVICE_ID_BASE = -1000;

  private static final long POLL_INTERVAL_MS = 4;
  private static final float TRACKPAD_PIXELS_PER_PAD = 900f;
  private static final float TRIGGER_FULL = 0.98f;

  private static final int MAX_PADS = 4;
  private static final int I_ID = 0, I_BUTTONS = 1, I_CAPS = 2, I_PRODUCT = 3, I_STRIDE = 4;
  private static final int F_LX = 0, F_LY = 1, F_RX = 2, F_RY = 3, F_LT = 4, F_RT = 5;
  private static final int F_RPAD_DOWN = 6, F_LPAD_DOWN = 9, F_GYRO_X = 12, F_GYRO_VALID = 15, F_STRIDE = 16;
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
  private static final int[] PADDLE_KEYS = {KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_BUTTON_2,
      KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_BUTTON_4, KeyEvent.KEYCODE_BUTTON_5};

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
    {B_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP},
    {B_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN},
    {B_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT},
    {B_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT},
    {B_L4, KeyEvent.KEYCODE_BUTTON_1},
    {B_L5, KeyEvent.KEYCODE_BUTTON_2},
    {B_R4, KeyEvent.KEYCODE_BUTTON_3},
    {B_R5, KeyEvent.KEYCODE_BUTTON_4},
    {B_QAM, KeyEvent.KEYCODE_BUTTON_5},
    {B_LPAD_CLICK, KeyEvent.KEYCODE_BUTTON_6},
    {B_RPAD_CLICK, KeyEvent.KEYCODE_BUTTON_7},
  };

  public interface Listener {
    default boolean isSteamPadInputEnabled() { return true; }

    default boolean hasSteamPadBinding(ExternalController pad, int keyCode) { return false; }

    default void onSteamPadGyro(ExternalController pad, float x, float y, float z, long timestampNanos) {}

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
  private static SteamControllerBackend pending;

  private final Activity activity;
  private final Listener listener;
  private int trackpadMode;
  private final Binding[] paddleBindings = new Binding[PADDLE_COUNT];
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private final Transport transport;
  private Thread pollThread;
  private volatile boolean running;

  private final Object frameLock = new Object();
  private final ArrayDeque<Frame> frames = new ArrayDeque<>();
  private final Map<Integer, int[]> rumbles = new HashMap<>();
  private boolean applyQueued;
  private final Runnable applyFrame = this::applyFrame;

  private static final class Frame {
    final int[] ints;
    final float[] floats;
    final String[] names, paths;
    final int count;
    final long timestampNanos = System.nanoTime();

    Frame(int[] ints, float[] floats, String[] names, String[] paths, int count) {
      this.ints = ints.clone();
      this.floats = floats.clone();
      this.names = names.clone();
      this.paths = paths.clone();
      this.count = count;
    }
  }

  private Frame latestFrame;
  private boolean inputEnabled = true;
  private final Map<Binding, Integer> heldBindings = new HashMap<>();
  private final int[] heldMouseButtons = new int[2];
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

  interface Transport {
    boolean load();
    void setup(Activity activity);
    boolean init(boolean bluetooth);
    int poll(int[] ints, float[] floats);
    String name(int id);
    String path(int id);
    void rumble(int id, int low, int high, int durationMs);
    void shutdown();
    void release();
  }

  private static final class SdlTransport implements Transport {
    private HIDDeviceManager hidManager;

    public boolean load() { return loadLibraries(); }
    public void setup(Activity activity) {
      if (!jniReady) {
        SDL.setupJNI();
        jniReady = true;
      }
      SDL.initialize();
      SDL.setContext(activity);
      hidManager = HIDDeviceManager.acquire(activity);
    }
    public boolean init(boolean bluetooth) { return nativeInit(bluetooth); }
    public int poll(int[] ints, float[] floats) { return nativePoll(ints, floats); }
    public String name(int id) { return nativeGetName(id); }
    public String path(int id) { return nativeGetPath(id); }
    public void rumble(int id, int low, int high, int durationMs) {
      nativeRumble(id, low, high, durationMs);
    }
    public void shutdown() { nativeShutdown(); }
    public void release() {
      if (hidManager != null) {
        HIDDeviceManager.release(hidManager);
        hidManager = null;
      }
      SDL.setContext(null);
    }
  }

  public SteamControllerBackend(
      Activity activity, int trackpadMode, Binding[] paddles, Listener listener) {
    this(activity, trackpadMode, paddles, listener, new SdlTransport());
  }

  SteamControllerBackend(
      Activity activity, int trackpadMode, Binding[] paddles, Listener listener, Transport transport) {
    this.transport = transport;
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
    requireMainThread();
    if (running) return true;
    if (running_ == this || !transport.load()) return false;
    running = true;
    if (running_ != null) {
      if (pending != null) pending.stop();
      pending = this;
      running_.stop();
      return true;
    }
    return startOwned();
  }

  private boolean startOwned() {
    running_ = this;
    try {
      transport.setup(activity);
      final boolean bluetooth = hasBluetoothPermission(activity);
      pollThread = new Thread(() -> pollLoop(bluetooth), "SteamCtrlPoll");
      pollThread.start();
      return true;
    } catch (RuntimeException | LinkageError failure) {
      Log.e(TAG, "SDL setup failed", failure);
      running = false;
      finishStop();
      return false;
    }
  }

  public void stop() {
    requireMainThread();
    running = false;
    if (pending == this) pending = null;
    mainHandler.removeCallbacks(applyFrame);
    synchronized (frameLock) {
      frames.clear();
      rumbles.clear();
      applyQueued = false;
    }
    disconnectPads();
    latestFrame = null;
  }

  private void disconnectPads() {
    for (int i = pads.size() - 1; i >= 0; i--) {
      Pad pad = pads.valueAt(i);
      pads.removeAt(i);
      com.winlator.cmod.runtime.input.ControllerHelper.setSteamControllerConnected(pads.size() > 0);
      releaseHeld(pad);
      listener.onSteamPadDisconnected(pad.controller);
    }
  }

  private void finishStop() {
    requireMainThread();
    running = false;
    mainHandler.removeCallbacks(applyFrame);
    synchronized (frameLock) {
      frames.clear();
      rumbles.clear();
      applyQueued = false;
    }
    disconnectPads();
    transport.release();
    pollThread = null;
    if (running_ == this) running_ = null;
    latestFrame = null;
    SteamControllerBackend next = pending;
    pending = null;
    if (next != null && next.running) next.startOwned();
  }

  private static void requireMainThread() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      throw new IllegalStateException("Steam Controller lifecycle must run on the main thread");
    }
  }

  public void setTrackpadMouseMode(int mode) {
    requireMainThread();
    int next = mode >= TRACKPAD_MOUSE_OFF && mode <= TRACKPAD_MOUSE_BOTH ? mode : TRACKPAD_MOUSE_RIGHT;
    if (trackpadMode == next) return;
    for (int i = 0; i < pads.size(); i++) releaseTrackpads(pads.valueAt(i));
    trackpadMode = next;
  }

  public void publishCurrentState() {
    requireMainThread();
    if (!running || latestFrame == null) return;
    for (int i = 0; i < pads.size(); i++) {
      releaseHeld(pads.valueAt(i));
      pads.valueAt(i).buttons = -1;
    }
    applyFrameInner(latestFrame);
  }

  private void updateInputEnabled() {
    boolean enabled = listener.isSteamPadInputEnabled();
    if (inputEnabled == enabled) return;
    inputEnabled = enabled;
    for (int i = 0; i < pads.size(); i++) {
      Pad pad = pads.valueAt(i);
      releaseHeld(pad);
      pad.buttons = -1;
    }
  }

  public void rumble(int deviceId, int low, int high, int durationMs) {
    synchronized (frameLock) {
      if (running) rumbles.put(deviceId, new int[] {low, high, durationMs});
    }
  }

  private static synchronized boolean loadLibraries() {
    if (librariesLoaded) return true;
    try {
      System.loadLibrary("SDL3steam");
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
    } catch (RuntimeException | LinkageError failure) {
      Log.e(TAG, "Steam Controller polling failed", failure);
    } finally {
      running = false;
      try {
        transport.shutdown();
      } finally {
        mainHandler.post(this::finishStop);
      }
    }
  }

  private void pollLoopInner(boolean bluetooth) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
    if (!transport.init(bluetooth)) {
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
      Map<Integer, int[]> requests;
      synchronized (frameLock) {
        requests = rumbles.isEmpty() ? java.util.Collections.emptyMap() : new HashMap<>(rumbles);
        rumbles.clear();
      }
      for (Map.Entry<Integer, int[]> request : requests.entrySet()) {
        int[] values = request.getValue();
        transport.rumble(DEVICE_ID_BASE - request.getKey(), values[0], values[1], values[2]);
      }
      Arrays.fill(ints, 0);
      Arrays.fill(floats, 0);
      int count = transport.poll(ints, floats);
      if (count < 0) break;
      if ((count > 0 && floats[F_GYRO_VALID] > 0) || count != lastCount
          || !Arrays.equals(ints, lastInts)
          || !Arrays.equals(floats, lastFloats)) {
        for (int p = 0; p < count; p++) {
          int id = ints[p * I_STRIDE + I_ID];
          String[] identity = identities.get(id);
          if (identity == null) {
            identity = new String[] {transport.name(id), transport.path(id)};
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
  }

  private void publish(int[] ints, float[] floats, String[] names, String[] paths, int count) {
    synchronized (frameLock) {
      if (!running) return;
      if (frames.size() >= 256) {
        throw new IllegalStateException("Steam Controller input delivery stalled");
      }
      frames.addLast(new Frame(ints, floats, names, paths, count));
      if (applyQueued) return;
      applyQueued = true;
      mainHandler.post(applyFrame);
    }
  }

  private void applyFrame() {
    try {
      for (int delivered = 0; delivered < 32; delivered++) {
        Frame frame;
        synchronized (frameLock) {
          frame = frames.pollFirst();
          if (frame == null || !running) {
            applyQueued = false;
            return;
          }
        }
        applyFrameInner(frame);
      }
      mainHandler.post(applyFrame);
    } catch (RuntimeException failure) {
      Log.e(TAG, "Steam Controller frame delivery failed", failure);
      stop();
    }
  }

  private void applyFrameInner(Frame frame) {
    latestFrame = frame;
    updateInputEnabled();
    int[] ints = frame.ints;
    float[] floats = frame.floats;
    String[] names = frame.names;
    String[] paths = frame.paths;
    int count = frame.count;

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
        com.winlator.cmod.runtime.input.ControllerHelper.setSteamControllerConnected(pads.size() > 0);
        releaseHeld(pad);
        listener.onSteamPadDisconnected(pad.controller);
      }
    }

    for (int p = 0; p < count; p++) {
      int id = ints[p * I_STRIDE + I_ID];
      Pad pad = pads.get(id);
      boolean connected = pad == null;
      if (connected) {
        pad = new Pad(createController(id, names[p], paths[p]));
        pads.put(id, pad);
        com.winlator.cmod.runtime.input.ControllerHelper.setSteamControllerConnected(true);
      }
      int caps = ints[p * I_STRIDE + I_CAPS];
      pad.controller.steamTouchpadCount = caps >> 8;
      pad.controller.steamHasRumble = (caps & 1) != 0;
      pad.controller.steamHasGyro = (caps & 2) != 0;
      pad.controller.steamProductId = ints[p * I_STRIDE + I_PRODUCT];
      if (connected) listener.onSteamPadConnected(pad.controller);
      applyPad(pad, ints[p * I_STRIDE + I_BUTTONS], floats, p * F_STRIDE);
      int f = p * F_STRIDE;
      if (floats[f + F_GYRO_VALID] > 0) {
        listener.onSteamPadGyro(pad.controller, floats[f + F_GYRO_X],
            floats[f + F_GYRO_X + 1], floats[f + F_GYRO_X + 2], frame.timestampNanos);
      }
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
    ExternalController controller = pad.controller;
    boolean changed = buttons != pad.buttons
        || controller.steamLeftTouch != (floats[base + F_LPAD_DOWN] > 0.5f)
        || controller.steamRightTouch != (floats[base + F_RPAD_DOWN] > 0.5f)
        || controller.steamLeftX != floats[base + F_LPAD_DOWN + 1]
        || controller.steamLeftY != floats[base + F_LPAD_DOWN + 2]
        || controller.steamRightX != floats[base + F_RPAD_DOWN + 1]
        || controller.steamRightY != floats[base + F_RPAD_DOWN + 2];
    controller.steamButtons = buttons;
    controller.steamLeftTouch = floats[base + F_LPAD_DOWN] > 0.5f;
    controller.steamRightTouch = floats[base + F_RPAD_DOWN] > 0.5f;
    controller.steamLeftX = floats[base + F_LPAD_DOWN + 1];
    controller.steamLeftY = floats[base + F_LPAD_DOWN + 2];
    controller.steamRightX = floats[base + F_RPAD_DOWN + 1];
    controller.steamRightY = floats[base + F_RPAD_DOWN + 2];
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
      for (int i = 0; inputEnabled && i < PADDLE_COUNT; i++) {
        boolean down = bit(buttons, PADDLE_BITS[i])
            && !listener.hasSteamPadBinding(pad.controller, PADDLE_KEYS[i]);
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
          setBindingDown(target, down);
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
    if (inputEnabled && (trackpadMode == TRACKPAD_MOUSE_RIGHT || trackpadMode == TRACKPAD_MOUSE_BOTH))
      applyTrackpad(pad.right, floats, base + F_RPAD_DOWN,
          bit(buttons, B_RPAD_CLICK) && !listener.hasSteamPadBinding(pad.controller, KeyEvent.KEYCODE_BUTTON_7), false);
    if (inputEnabled && (trackpadMode == TRACKPAD_MOUSE_LEFT || trackpadMode == TRACKPAD_MOUSE_BOTH))
      applyTrackpad(
          pad.left,
          floats,
          base + F_LPAD_DOWN,
          bit(buttons, B_LPAD_CLICK) && !listener.hasSteamPadBinding(pad.controller, KeyEvent.KEYCODE_BUTTON_6),
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
      setMouseButtonDown(secondary, click);
    }
  }

  private void setBindingDown(Binding binding, boolean down) {
    int previous = heldBindings.getOrDefault(binding, 0);
    int next = Math.max(0, previous + (down ? 1 : -1));
    if (next == 0) heldBindings.remove(binding);
    else heldBindings.put(binding, next);
    if ((previous == 0) != (next == 0)) listener.onSteamPadBinding(binding, next > 0);
  }

  private void setMouseButtonDown(boolean secondary, boolean down) {
    int index = secondary ? 1 : 0;
    int previous = heldMouseButtons[index];
    int next = Math.max(0, previous + (down ? 1 : -1));
    heldMouseButtons[index] = next;
    if ((previous == 0) != (next == 0)) listener.onSteamPadMouseButton(secondary, next > 0);
  }

  private void releaseTrackpads(Pad pad) {
    pad.right.down = false;
    pad.left.down = false;
    if (pad.right.clickDown) {
      pad.right.clickDown = false;
      setMouseButtonDown(false, false);
    }
    if (pad.left.clickDown) {
      pad.left.clickDown = false;
      setMouseButtonDown(trackpadMode == TRACKPAD_MOUSE_BOTH, false);
    }
  }

  private void releaseHeld(Pad pad) {
    releaseTrackpads(pad);
    for (int i = 0; i < PADDLE_COUNT; i++) {
      Binding target = paddleBindings[i];
      if (pad.paddleDown[i] && target != Binding.NONE && !target.isGamepad())
        setBindingDown(target, false);
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
