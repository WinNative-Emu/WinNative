package com.winlator.cmod.runtime.display.xserver;

import android.util.Log;
import android.util.SparseArray;
import com.winlator.cmod.shared.math.Mathf;
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer;
import com.winlator.cmod.runtime.display.winhandler.MouseEventFlags;
import com.winlator.cmod.runtime.display.winhandler.WinHandler;
import com.winlator.cmod.runtime.display.xserver.extensions.BigReqExtension;
import com.winlator.cmod.runtime.display.xserver.extensions.DRI3Extension;
import com.winlator.cmod.runtime.display.xserver.extensions.Extension;
import com.winlator.cmod.runtime.display.xserver.extensions.MITSHMExtension;
import com.winlator.cmod.runtime.display.xserver.extensions.PresentExtension;
import com.winlator.cmod.runtime.display.xserver.extensions.SyncExtension;
import com.winlator.cmod.runtime.display.xserver.extensions.XInput2Extension;
import com.winlator.cmod.shared.android.CursorLocker;
import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
  private static final String SGSR_RESIZE_TAG = "SGSRResize";

  public enum Lockable {
    WINDOW_MANAGER,
    PIXMAP_MANAGER,
    DRAWABLE_MANAGER,
    GRAPHIC_CONTEXT_MANAGER,
    INPUT_DEVICE,
    CURSOR_MANAGER,
    SHMSEGMENT_MANAGER
  }

  public static final short VERSION = 11;
  public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
  public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
  public final SparseArray<Extension> extensions = new SparseArray<>();
  public final ScreenInfo screenInfo;
  public final PixmapManager pixmapManager;
  public final ResourceIDs resourceIDs = new ResourceIDs(128);
  public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
  public final SelectionManager selectionManager;
  public final DrawableManager drawableManager;
  public final WindowManager windowManager;
  public final CursorManager cursorManager;
  public final Keyboard keyboard = Keyboard.createKeyboard(this);
  public final Pointer pointer = new Pointer(this);
  public final InputDeviceManager inputDeviceManager;
  public final GrabManager grabManager;
  public final CursorLocker cursorLocker;
  private SHMSegmentManager shmSegmentManager;
  private VulkanRenderer renderer;
  private WinHandler winHandler;
  private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
  private boolean relativeMouseMovement = false;
  private boolean pointerCaptureActive = false;
  private boolean simulateTouchScreen = false;
  private boolean isGrabbed = false;
  private XClient grabbingClient = null;
  private final boolean dri3Enabled;

  public XServer(ScreenInfo screenInfo) {
    this(screenInfo, true);
  }

  public XServer(ScreenInfo screenInfo, boolean dri3Enabled) {
    this.screenInfo = screenInfo;
    this.dri3Enabled = dri3Enabled;
    cursorLocker = new CursorLocker(this);
    cursorLocker.setEnabled(!relativeMouseMovement && !pointerCaptureActive);
    for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

    pixmapManager = new PixmapManager();
    drawableManager = new DrawableManager(this);
    cursorManager = new CursorManager(drawableManager);
    windowManager = new WindowManager(screenInfo, drawableManager);
    selectionManager = new SelectionManager(windowManager);
    inputDeviceManager = new InputDeviceManager(this);
    grabManager = new GrabManager(this);

    DesktopHelper.attachTo(this);
    setupExtensions();
  }

  public boolean isDri3Enabled() {
    return dri3Enabled;
  }

  /** Delta mouse delivery: the user's relative-mouse toggle, or a Wayland pointer lock. */
  public boolean isRelativeMouseMovement() {
    return relativeMouseMovement || externalRelativeMode;
  }

  private volatile boolean externalRelativeMode;

  /** Wayland mode: a program holds a pointer lock, so input must arrive as deltas. */
  public void setExternalRelativeMode(boolean on) {
    externalRelativeMode = on;
  }

  /**
   * Wayland mode: no X client is connected, so the input the app injects (touchpad, on-screen
   * controls, keyboard) is handed to the compositor as well. Coordinates are screen pixels, keys
   * are evdev codes.
   */
  public interface InputSink {
    void onPointerMove(int x, int y);

    void onPointerButton(Pointer.Button button, boolean pressed);

    void onKey(int evdev, boolean pressed);
  }

  private volatile InputSink inputSink;

  public void setInputSink(InputSink sink) {
    inputSink = sink;
  }

  public boolean hasInputSink() {
    return inputSink != null;
  }

  private void sinkPointerMove() {
    InputSink sink = inputSink;
    if (sink != null) sink.onPointerMove(pointer.getX(), pointer.getY());
  }

  private void sinkPointerButton(Pointer.Button button, boolean pressed) {
    InputSink sink = inputSink;
    if (sink != null) sink.onPointerButton(button, pressed);
  }

  private void sinkKey(XKeycode xKeycode, boolean pressed) {
    InputSink sink = inputSink;
    if (sink != null) sink.onKey((xKeycode.id & 0xff) - 8, pressed);
  }

  public boolean isPointerCaptureActive() {
    return pointerCaptureActive;
  }

  public void setPointerCaptureActive(boolean pointerCaptureActive) {
    this.pointerCaptureActive = pointerCaptureActive;
    cursorLocker.setEnabled(!relativeMouseMovement && !pointerCaptureActive);
  }

  public void setRelativeMouseMovement(boolean relativeMouseMovement) {
    cursorLocker.setEnabled(!relativeMouseMovement && !pointerCaptureActive);
    this.relativeMouseMovement = relativeMouseMovement;
  }

  public boolean isSimulateTouchScreen() {
    return simulateTouchScreen;
  }

  public void setSimulateTouchScreen(boolean simulateTouchScreen) {
    this.simulateTouchScreen = simulateTouchScreen;
  }

  public VulkanRenderer getRenderer() {
    return renderer;
  }

  public GrabManager getGrabManager() {
    return grabManager;
  }

  public void setRenderer(VulkanRenderer renderer) {
    this.renderer = renderer;
  }

  public WinHandler getWinHandler() {
    return winHandler;
  }

  public void setWinHandler(WinHandler winHandler) {
    this.winHandler = winHandler;
  }

  public SHMSegmentManager getSHMSegmentManager() {
    return shmSegmentManager;
  }

  public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
    this.shmSegmentManager = shmSegmentManager;
  }

  public boolean resizeScreen(ScreenInfo newScreenInfo) {
    if (newScreenInfo == null) return false;
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.DRAWABLE_MANAGER, Lockable.INPUT_DEVICE)) {
      String oldScreenInfo = screenInfo.toString();
      Log.i(SGSR_RESIZE_TAG, "resizeScreen requested: current='" + oldScreenInfo +
          "' target='" + newScreenInfo + "'");
      if (screenInfo.width == newScreenInfo.width && screenInfo.height == newScreenInfo.height) {
        Log.i(SGSR_RESIZE_TAG, "resizeScreen no-op: screen already " + oldScreenInfo);
        return false;
      }
      screenInfo.setSize(newScreenInfo);
      windowManager.resizeRootWindow(screenInfo.width, screenInfo.height);
      pointer.setPosition(pointer.getClampedX(), pointer.getClampedY());
      Log.i(SGSR_RESIZE_TAG, "resizeScreen applied: '" + oldScreenInfo + "' -> '" +
          screenInfo + "' pointer=" + pointer.getX() + "," + pointer.getY());
      return true;
    }
  }

  public void stop() {
    cursorLocker.stop();
    renderer = null;
    shmSegmentManager = null;
  }

  private class SingleXLock implements XLock {
    private final ReentrantLock lock;

    private SingleXLock(Lockable lockable) {
      this.lock = locks.get(lockable);
      lock.lock();
    }

    @Override
    public void close() {
      lock.unlock();
    }
  }

  private class MultiXLock implements XLock {
    private final Lockable[] lockables;

    private MultiXLock(Lockable[] lockables) {
      this.lockables = lockables;
      for (Lockable lockable : lockables) locks.get(lockable).lock();
    }

    @Override
    public void close() {
      for (int i = lockables.length - 1; i >= 0; i--) {
        locks.get(lockables[i]).unlock();
      }
    }
  }

  public XLock lock(Lockable lockable) {
    return new SingleXLock(lockable);
  }

  public XLock lock(Lockable... lockables) {
    return new MultiXLock(lockables);
  }

  public XLock lockAll() {
    return new MultiXLock(Lockable.values());
  }

  public Extension getExtensionByName(String name) {
    for (int i = 0; i < extensions.size(); i++) {
      Extension extension = extensions.valueAt(i);
      if (extension.getName().equals(name)) return extension;
    }
    return null;
  }

  public void injectPointerMove(int x, int y) {
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      pointer.setPosition(x, y);
    }
    sinkPointerMove();
  }

  public void injectPointerMoveDelta(int dx, int dy) {
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      int beforeX = pointer.getX();
      int beforeY = pointer.getY();
      int x = beforeX + dx;
      int y = beforeY + dy;

      int maxX = screenInfo.width - 1;
      int maxY = screenInfo.height - 1;
      android.graphics.Rect confinement = grabManager.getConfinementBounds();
      if (confinement != null) {
        int minX = Math.max(0, confinement.left);
        int minY = Math.max(0, confinement.top);
        int maxX2 = Math.min(maxX, confinement.right - 1);
        int maxY2 = Math.min(maxY, confinement.bottom - 1);
        x = Mathf.clamp(x, minX, maxX2);
        y = Mathf.clamp(y, minY, maxY2);
        pointer.setPosition(x, y);
      } else {
        short softMarginX = (short) (screenInfo.width * 0.05f);
        short softMarginY = (short) (screenInfo.height * 0.05f);
        x = Mathf.clamp(x, -softMarginX, (screenInfo.width - 1) + softMarginX);
        y = Mathf.clamp(y, -softMarginY, (screenInfo.height - 1) + softMarginY);
        pointer.setPosition(x, y);

        int clampedX = x;
        int clampedY = y;
        if (x < 0) clampedX = 0;
        else if (x > screenInfo.width - 1) clampedX = screenInfo.width - 1;
        if (y < 0) clampedY = 0;
        else if (y > screenInfo.height - 1) clampedY = screenInfo.height - 1;
        pointer.setX(clampedX);
        pointer.setY(clampedY);
      }

      XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
      if (xi != null) xi.emitRawMotion(2, (double)dx, (double)dy);
    }
    sinkPointerMove();
    if (renderer != null) renderer.requestCursorRender();
  }

  public void updatePointerForDisplay(int x, int y) {
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      pointer.setX(x);
      pointer.setY(y);
    }
    if (renderer != null) renderer.requestCursorRender();
  }

  public void updatePointerForDisplayDelta(int dx, int dy) {
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      short softMarginX = (short) (screenInfo.width * 0.05f);
      short softMarginY = (short) (screenInfo.height * 0.05f);
      int x = Mathf.clamp(pointer.getX() + dx, -softMarginX, (screenInfo.width - 1) + softMarginX);
      int y = Mathf.clamp(pointer.getY() + dy, -softMarginY, (screenInfo.height - 1) + softMarginY);
      pointer.setPosition(x, y);

      int clampedX = x;
      int clampedY = y;
      if (x < 0) clampedX = 0;
      else if (x > screenInfo.width - 1) clampedX = screenInfo.width - 1;
      if (y < 0) clampedY = 0;
      else if (y > screenInfo.height - 1) clampedY = screenInfo.height - 1;
      pointer.setX(clampedX);
      pointer.setY(clampedY);
    }
    if (renderer != null) renderer.requestCursorRender();
  }

  public void injectPointerButtonPress(Pointer.Button buttonCode) {
    if (winHandler != null) winHandler.onPointerButtonInjected(buttonCode, true);
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      pointer.setButton(buttonCode, true);

      XInput2Extension xInput2Extension = getExtension(XInput2Extension.MAJOR_OPCODE);
      if (xInput2Extension != null) xInput2Extension.emitRawButton(2, buttonCode.ordinal() + 1, true);
    }
    sinkPointerButton(buttonCode, true);
  }

  public void injectPointerButtonRelease(Pointer.Button buttonCode) {
    if (winHandler != null) winHandler.onPointerButtonInjected(buttonCode, false);
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      pointer.setButton(buttonCode, false);

      XInput2Extension xInput2Extension = getExtension(XInput2Extension.MAJOR_OPCODE);
      if (xInput2Extension != null) xInput2Extension.emitRawButton(2, buttonCode.ordinal() + 1, false);
    }
    sinkPointerButton(buttonCode, false);
  }

  public void injectKeyPress(XKeycode xKeycode) {
    injectKeyPress(xKeycode, 0);
  }

  public void injectKeyPress(XKeycode xKeycode, int keysym) {
    if (winHandler != null) winHandler.onKeyInjected(xKeycode, true);
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      keyboard.setKeyPress(xKeycode.id, keysym);
    }
    sinkKey(xKeycode, true);
  }

  public void injectKeyRelease(XKeycode xKeycode) {
    if (winHandler != null) winHandler.onKeyInjected(xKeycode, false);
    try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
      keyboard.setKeyRelease(xKeycode.id);
    }
    sinkKey(xKeycode, false);
  }

  private void registerExtension(Extension ext, int[] nextEventId, int[] nextErrorId) {
    if (ext.getNumEvents() > 0) {
      ext.setFirstEventId((byte) nextEventId[0]);
      nextEventId[0] += ext.getNumEvents();
    }
    if (ext.getNumErrors() > 0) {
      ext.setFirstErrorId((byte) nextErrorId[0]);
      nextErrorId[0] += ext.getNumErrors();
    }
    extensions.put(ext.getMajorOpcode(), ext);
  }

  private void setupExtensions() {
    int[] nextEventId = {64};
    int[] nextErrorId = {128};
    registerExtension(new BigReqExtension(), nextEventId, nextErrorId);
    registerExtension(new MITSHMExtension(), nextEventId, nextErrorId);
    if (dri3Enabled) {
      registerExtension(new DRI3Extension(), nextEventId, nextErrorId);
    }
    registerExtension(new PresentExtension(), nextEventId, nextErrorId);
    registerExtension(new SyncExtension(), nextEventId, nextErrorId);
    registerExtension(new XInput2Extension(), nextEventId, nextErrorId);
  }

  public <T extends Extension> T getExtension(int opcode) {
    return (T) extensions.get(opcode);
  }

  public synchronized void setGrabbed(boolean grabbed, XClient client) {
    this.isGrabbed = grabbed;
    this.grabbingClient = client;
  }

  public synchronized boolean isGrabbedBy(XClient client) {
    return isGrabbed && grabbingClient == client;
  }
}
