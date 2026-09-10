package com.winlator.cmod.runtime.display.xserver.extensions;

import static com.winlator.cmod.runtime.display.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;
import com.winlator.cmod.runtime.display.connector.XInputStream;
import com.winlator.cmod.runtime.display.connector.XOutputStream;
import com.winlator.cmod.runtime.display.connector.XStreamLock;
import com.winlator.cmod.runtime.display.renderer.GPUImage;
import com.winlator.cmod.runtime.display.renderer.Texture;
import com.winlator.cmod.runtime.display.xserver.Bitmask;
import com.winlator.cmod.runtime.display.xserver.Drawable;
import com.winlator.cmod.runtime.display.xserver.Pixmap;
import com.winlator.cmod.runtime.display.xserver.Window;
import com.winlator.cmod.runtime.display.xserver.WindowManager;
import com.winlator.cmod.runtime.display.xserver.XClient;
import com.winlator.cmod.runtime.display.xserver.XLock;
import com.winlator.cmod.runtime.display.xserver.XResource;
import com.winlator.cmod.runtime.display.xserver.XResourceManager;
import com.winlator.cmod.runtime.display.xserver.XServer;
import com.winlator.cmod.runtime.display.xserver.errors.BadImplementation;
import com.winlator.cmod.runtime.display.xserver.errors.BadMatch;
import com.winlator.cmod.runtime.display.xserver.errors.BadPixmap;
import com.winlator.cmod.runtime.display.xserver.errors.BadWindow;
import com.winlator.cmod.runtime.display.xserver.errors.XRequestError;
import com.winlator.cmod.runtime.display.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.runtime.display.xserver.events.PresentIdleNotify;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.LockSupport;

public class PresentExtension
    implements Extension,
        XResourceManager.OnResourceLifecycleListener,
        WindowManager.OnWindowModificationListener {
  public static final byte MAJOR_OPCODE = -103;
  public static volatile long lastPresentNanos = 0;
  private static final int FAKE_INTERVAL = 1000000 / 60;
  private byte firstEventId = 0;
  private byte firstErrorId = 0;

  public enum Kind {
    PIXMAP,
    MSC_NOTIFY
  }

  public enum Mode {
    COPY,
    FLIP,
    SKIP
  }

  private final SparseArray<Event> events = new SparseArray<>();
  private final SparseArray<PendingScanout> pendingScanouts = new SparseArray<>();
  private SyncExtension syncExtension;
  private boolean lifecycleListenersRegistered = false;
  private XServer xServer;

  private static final long FIRE_EARLY_NS = 700_000L;
  private final ConcurrentHashMap<Integer, WindowTiming> windowTimings = new ConcurrentHashMap<>();
  private final PriorityBlockingQueue<PendingIdle> idleQueue =
      new PriorityBlockingQueue<>(16, (a, b) -> Long.compare(a.fireNs, b.fireNs));
  private volatile Thread pacerThread;

  private abstract static class ClientOpcodes {
    private static final byte QUERY_VERSION = 0;
    private static final byte PRESENT_PIXMAP = 1;
    private static final byte SELECT_INPUT = 3;
  }

  private static class Event {
    private Window window;
    private XClient client;
    private int id;
    private Bitmask mask;
  }

  private static class PendingScanout {
    private Window window;
    private Pixmap pixmap;
    private int serial;
    private int idleFence;
  }

  private static class PresentPixmapParams {
    int windowId;
    int pixmapId;
    int serial;
    short xOff;
    short yOff;
    int waitFence;
    int idleFence;
  }

  private static class WindowTiming {
    long nextIdleNs;
  }

  private static class PendingIdle {
    final Window window;
    final Pixmap pixmap;
    final int serial;
    final int idleFence;
    final long fireNs;

    PendingIdle(Window window, Pixmap pixmap, int serial, int idleFence, long fireNs) {
      this.window = window;
      this.pixmap = pixmap;
      this.serial = serial;
      this.idleFence = idleFence;
      this.fireNs = fireNs;
    }
  }

  @Override
  public String getName() {
    return "Present";
  }

  @Override
  public byte getMajorOpcode() {
    return MAJOR_OPCODE;
  }

  @Override
  public byte getFirstErrorId() {
    return firstErrorId;
  }

  @Override
  public byte getFirstEventId() {
    return firstEventId;
  }

  @Override
  public int getNumEvents() {
    return 0;
  }

  @Override
  public int getNumErrors() {
    return 0;
  }

  @Override
  public void setFirstEventId(byte id) {
    this.firstEventId = id;
  }

  @Override
  public void setFirstErrorId(byte id) {
    this.firstErrorId = id;
  }

  private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
    if (idleFence != 0) syncExtension.setTriggered(idleFence);

    synchronized (events) {
      for (int i = 0; i < events.size(); i++) {
        Event event = events.valueAt(i);
        if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
          event.client.sendEvent(
              new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
        }
      }
    }
  }

  private int targetFps(XServer xs) {
    com.winlator.cmod.runtime.display.renderer.VulkanRenderer renderer =
        xs != null ? xs.getRenderer() : null;
    return renderer != null ? renderer.getFpsLimit() : 0;
  }

  private void scheduleIdleNotify(
      Window window, Pixmap pixmap, int serial, int idleFence, int targetFps) {
    if (targetFps <= 0) {
      sendIdleNotify(window, pixmap, serial, idleFence);
      return;
    }
    long frameNs = 1_000_000_000L / targetFps;
    long now = System.nanoTime();
    WindowTiming wt = windowTimings.computeIfAbsent(window.id, k -> new WindowTiming());
    synchronized (wt) {
      if (wt.nextIdleNs <= now - frameNs) {
        wt.nextIdleNs = now + frameNs;
      } else {
        wt.nextIdleNs += frameNs;
      }
      idleQueue.offer(new PendingIdle(window, pixmap, serial, idleFence, wt.nextIdleNs));
    }
    startPacer();
  }

  private void startPacer() {
    if (pacerThread != null) return;
    synchronized (this) {
      if (pacerThread != null) return;
      Thread t = new Thread(this::runPacer, "PresentPacer");
      t.setDaemon(true);
      t.setPriority(Thread.MAX_PRIORITY);
      pacerThread = t;
      t.start();
    }
  }

  private void runPacer() {
    while (!Thread.interrupted()) {
      try {
        PendingIdle p = idleQueue.take();
        long remaining = p.fireNs - FIRE_EARLY_NS - System.nanoTime();
        while (remaining > 0) {
          LockSupport.parkNanos(remaining);
          if (Thread.interrupted()) return;
          remaining = p.fireNs - FIRE_EARLY_NS - System.nanoTime();
        }
        sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private void drainAndFire(Window window, Pixmap pixmap) {
    if (window != null) windowTimings.remove(window.id);
    ArrayList<PendingIdle> hit = new ArrayList<>();
    for (PendingIdle p : idleQueue) {
      if ((window != null && p.window == window) || (pixmap != null && p.pixmap == pixmap)) hit.add(p);
    }
    for (PendingIdle p : hit) {
      if (idleQueue.remove(p)) sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
    }
  }

  private void sendCompleteNotify(
      Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
    synchronized (events) {
      for (int i = 0; i < events.size(); i++) {
        Event event = events.valueAt(i);
        if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
          event.client.sendEvent(
              new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
        }
      }
    }
  }

  private static void queryVersion(
      XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException, XRequestError {
    inputStream.skip(8);

    try (XStreamLock lock = outputStream.lock()) {
      outputStream.writeByte(RESPONSE_CODE_SUCCESS);
      outputStream.writeByte((byte) 0);
      outputStream.writeShort(client.getSequenceNumber());
      outputStream.writeInt(0);
      outputStream.writeInt(1);
      outputStream.writeInt(0);
      outputStream.writePad(16);
    }
  }

  private static PresentPixmapParams parsePresentPixmap(XClient client, XInputStream inputStream) {
    PresentPixmapParams p = new PresentPixmapParams();
    p.windowId = inputStream.readInt();
    p.pixmapId = inputStream.readInt();
    p.serial = inputStream.readInt();
    inputStream.skip(8); // valid-area + update-area
    p.xOff = inputStream.readShort();
    p.yOff = inputStream.readShort();
    inputStream.skip(4); // target-crtc
    p.waitFence = inputStream.readInt();
    p.idleFence = inputStream.readInt();
    inputStream.skip(client.getRemainingRequestLength());
    return p;
  }

  private boolean presentPixmap(XClient client, PresentPixmapParams p, XOutputStream outputStream)
      throws IOException, XRequestError {
    final Window window = client.xServer.windowManager.getWindow(p.windowId);
    if (window == null) throw new BadWindow(p.windowId);

    final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(p.pixmapId);
    if (pixmap == null) throw new BadPixmap(p.pixmapId);

    Drawable content = window.getContent();
    if (content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

    long ust = System.nanoTime() / 1000;
    long msc = ust / FAKE_INTERVAL;
    boolean isLargeFrame = pixmap.drawable.width > client.xServer.screenInfo.width / 2;
    int targetFps = targetFps(client.xServer);

    synchronized (content.renderLock) {
      Mode mode;
      content.setPresentedSourceSize(
          pixmap.drawable.getPresentedSourceWidth(), pixmap.drawable.getPresentedSourceHeight());
      if (canDirectScanout(content, pixmap.drawable, p.xOff, p.yOff)) {
        releasePendingScanout(window, true);
        content.setScanoutSource(pixmap.drawable, p.xOff, p.yOff);
        PendingScanout pendingScanout = new PendingScanout();
        pendingScanout.window = window;
        pendingScanout.pixmap = pixmap;
        pendingScanout.serial = p.serial;
        pendingScanout.idleFence = p.idleFence;
        pendingScanouts.put(window.id, pendingScanout);
        mode = Mode.FLIP;
      } else {
        releasePendingScanout(window, true);
        copyPresentedRegion(content, pixmap.drawable, p.xOff, p.yOff);
        // TODO(perf): gate on a Vulkan release fence — we mark idle on CPU return, before
        // the GPU has actually sampled the pixmap.
        if (isLargeFrame) scheduleIdleNotify(window, pixmap, p.serial, p.idleFence, targetFps);
        else sendIdleNotify(window, pixmap, p.serial, p.idleFence);
        mode = Mode.COPY;
      }
      sendCompleteNotify(window, p.serial, Kind.PIXMAP, mode, ust, msc);
      client.xServer.windowManager.triggerOnFramePresented(
          window, com.winlator.cmod.runtime.display.xserver.WindowManager.FrameSource.PRESENT, p.serial);
    }

    return isLargeFrame;
  }

  private void releasePendingScanout(Window window, boolean paced) {
    PendingScanout pendingScanout = pendingScanouts.get(window.id);
    if (pendingScanout == null) return;

    pendingScanouts.remove(window.id);
    Drawable content = window.getContent();
    if (content != null) {
      synchronized (content.renderLock) {
        if (content.getScanoutSource() == pendingScanout.pixmap.drawable) {
          content.clearScanoutSource();
        }
      }
    }
    if (paced) {
      scheduleIdleNotify(
          pendingScanout.window,
          pendingScanout.pixmap,
          pendingScanout.serial,
          pendingScanout.idleFence,
          targetFps(xServer));
    } else {
      sendIdleNotify(
          pendingScanout.window,
          pendingScanout.pixmap,
          pendingScanout.serial,
          pendingScanout.idleFence);
    }
  }

  private void releasePendingScanoutsForPixmap(Pixmap pixmap) {
    for (int i = pendingScanouts.size() - 1; i >= 0; i--) {
      PendingScanout pendingScanout = pendingScanouts.valueAt(i);
      if (pendingScanout.pixmap == pixmap) {
        releasePendingScanout(pendingScanout.window, false);
      }
    }
  }

  private void removeEventsForWindow(Window window) {
    synchronized (events) {
      for (int i = events.size() - 1; i >= 0; i--) {
        if (events.valueAt(i).window == window) events.removeAt(i);
      }
    }
  }

  private void registerLifecycleListeners(XServer xServer) {
    if (lifecycleListenersRegistered) return;
    synchronized (this) {
      if (lifecycleListenersRegistered) return;
      this.xServer = xServer;
      xServer.pixmapManager.addOnResourceLifecycleListener(this);
      xServer.windowManager.addOnWindowModificationListener(this);
      lifecycleListenersRegistered = true;
    }
  }

  @Override
  public void onFreeResource(XResource resource) {
    if (resource instanceof Pixmap) {
      releasePendingScanoutsForPixmap((Pixmap) resource);
      drainAndFire(null, (Pixmap) resource);
    }
  }

  @Override
  public void onDestroyWindow(Window window) {
    releasePendingScanout(window, false);
    drainAndFire(window, null);
    removeEventsForWindow(window);
  }

  private boolean canDirectScanout(Drawable content, Drawable pixmap, short xOff, short yOff) {
    Texture texture = pixmap.getTexture();
    if (texture instanceof GPUImage) {
      GPUImage gpuImage = (GPUImage) texture;
      if (!gpuImage.isValid() || gpuImage.hasSamplingFailed()) return false;
    }

    return pixmap.isDirectScanout()
        && texture != null
        && xOff <= 0
        && yOff <= 0
        && pixmap.width + xOff >= content.width
        && pixmap.height + yOff >= content.height;
  }

  private static void copyPresentedRegion(
      Drawable content, Drawable pixmap, short xOff, short yOff) {
    int srcX = Math.max(0, -xOff);
    int srcY = Math.max(0, -yOff);
    int dstX = Math.max(0, xOff);
    int dstY = Math.max(0, yOff);
    int width = Math.min(pixmap.width - srcX, content.width - dstX);
    int height = Math.min(pixmap.height - srcY, content.height - dstY);
    if (width <= 0 || height <= 0) return;

    content.copyArea(
        (short) srcX,
        (short) srcY,
        (short) dstX,
        (short) dstY,
        (short) width,
        (short) height,
        pixmap);
  }

  private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException, XRequestError {
    int eventId = inputStream.readInt();
    int windowId = inputStream.readInt();
    Bitmask mask = new Bitmask(inputStream.readInt());

    Window window = client.xServer.windowManager.getWindow(windowId);
    if (window == null) throw new BadWindow(windowId);

    synchronized (events) {
      Event event = events.get(eventId);
      if (event != null) {
        if (event.window != window || event.client != client) throw new BadMatch();

        if (!mask.isEmpty()) {
          event.mask = mask;
        } else events.remove(eventId);
      } else {
        event = new Event();
        event.id = eventId;
        event.window = window;
        event.client = client;
        event.mask = mask;
        events.put(eventId, event);
      }
    }
  }

  @Override
  public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException, XRequestError {
    registerLifecycleListeners(client.xServer);
    int opcode = client.getRequestData();
    if (syncExtension == null)
      syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

    switch (opcode) {
      case ClientOpcodes.QUERY_VERSION:
        queryVersion(client, inputStream, outputStream);
        break;
      case ClientOpcodes.PRESENT_PIXMAP: {
        // Wait-fence is parsed for protocol conformance but NOT awaited on the dispatch
        // thread: blocking here stalls every subsequent X request from this client behind
        // the GPU and was a measured FPS regression vs. pre-DRI3-1.3 behaviour. The actual
        // sampling happens later on the renderer thread; if a GPU-side sync becomes
        // necessary, import the sync_file as a Vulkan semaphore and chain it into the
        // submit instead of blocking here.
        PresentPixmapParams p = parsePresentPixmap(client, inputStream);
        lastPresentNanos = System.nanoTime();

        try (XLock lock =
            client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
          presentPixmap(client, p, outputStream);
        }

        if (client.xServer.getRenderer() != null) {
          client.xServer.getRenderer().onGuestFramePresented();
          client.xServer.getRenderer().requestRenderImmediate();
        }
        break;
      }
      case ClientOpcodes.SELECT_INPUT:
        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
          selectInput(client, inputStream, outputStream);
        }
        break;
      default:
        throw new BadImplementation();
    }
  }
}
