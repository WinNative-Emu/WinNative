package com.winlator.cmod.runtime.display.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.winlator.cmod.runtime.display.renderer.RenderCallback;
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer;
import com.winlator.cmod.runtime.display.xserver.XServer;
import com.winlator.cmod.runtime.system.LogManager;

import java.util.ArrayDeque;
import java.util.Deque;

/** SurfaceView that drives a {@link VulkanRenderer} on a dedicated render thread, preserving the public API: {@link #queueEvent(Runnable)}, {@link #requestRender()}, {@link #setRenderMode(int)}, {@link #onResume()}, {@link #onPause()}, {@link #getRenderer()}. */
@SuppressLint("ViewConstructor")
public class XServerSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    public static final int RENDERMODE_WHEN_DIRTY  = 0;
    public static final int RENDERMODE_CONTINUOUSLY = 1;
    private static final long TRANSIENT_FRAME_INTERVAL_NS = 1_000_000_000L / 120L;

    private final VulkanRenderer renderer;

    private final Object renderLock = new Object();
    private final Deque<Runnable> eventQueue = new ArrayDeque<>();
    private Thread renderThread;
    // Outgoing render thread finishing teardown; the next surfaceCreated joins it first so a stale destroy() can't free the handle the new surface re-attaches to.
    private Thread retiringRenderThread;
    private volatile boolean running;
    private volatile boolean renderRequested;
    private volatile boolean transientRenderRequested;
    private volatile boolean paused;
    private volatile boolean surfaceReady;
    private volatile long transientRenderUntilNs;
    private long nextContinuousFrameNs;
    private int renderMode = RENDERMODE_WHEN_DIRTY;

    private static final int REASON_REQUESTED = 0;
    private static final int REASON_CONTINUOUS = 1;
    private static final int REASON_TRANSIENT_REQ = 2;
    private static final int REASON_TRANSIENT_ACTIVE = 3;
    private final int[] drawReasonCounts = new int[4];
    private int drawReasonTotal;
    private long drawReasonStartNs;

    private volatile int width;
    private volatile int height;

    private String TAG = "XServerSurfaceView";

    public XServerSurfaceView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderer = new VulkanRenderer(this, xServer);
        getHolder().addCallback(this);
    }

    public VulkanRenderer getRenderer() {
        return renderer;
    }

    public void queueEvent(Runnable r) {
        if (r == null) return;
        synchronized (renderLock) {
            eventQueue.add(r);
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void requestRender() {
        synchronized (renderLock) {
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void requestTransientRender(long durationMs) {
        long untilNs = System.nanoTime() + Math.max(1L, durationMs) * 1_000_000L;
        synchronized (renderLock) {
            if (untilNs > transientRenderUntilNs) transientRenderUntilNs = untilNs;
            transientRenderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void setRenderMode(int mode) {
        if (mode != RENDERMODE_WHEN_DIRTY && mode != RENDERMODE_CONTINUOUSLY) return;
        synchronized (renderLock) {
            renderMode = mode;
            if (mode == RENDERMODE_CONTINUOUSLY) {
                renderRequested = true;
                renderLock.notifyAll();
            }
        }
    }

    public int getRenderMode() {
        return renderMode;
    }

    public void onResume() {
        synchronized (renderLock) {
            paused = false;
            renderRequested = true;
            renderLock.notifyAll();
//            LogManager.log(TAG, "onResume: inside [synchronized (renderLock)]");
        }
        LogManager.log(TAG, "onResume called");
    }

    public void onPause() {
        synchronized (renderLock) {
            paused = true;
            renderLock.notifyAll();
//            LogManager.log(TAG, "onPause: inside [synchronized (renderLock)]");
        }
        LogManager.log(TAG, "onPause called");
    }

    // --- SurfaceHolder.Callback ----------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Let any retiring render thread finish freeing the renderer before attaching the new surface.
        joinRetiringRenderThread();
        synchronized (renderLock) {
            surfaceReady = false;
            width = 0;
            height = 0;
//            LogManager.log(TAG, "surfaceCreated: inside [synchronized (renderLock)]");
        }
        renderer.attachSurface(holder.getSurface());
        startRenderThreadIfNeeded();
        LogManager.log(TAG, "surfaceCreated called");
    }

    private void joinRetiringRenderThread() {
        Thread t;
        synchronized (renderLock) {
            t = retiringRenderThread;
            retiringRenderThread = null;
        }
        if (t != null && t != Thread.currentThread() && t.isAlive()) {
            try { t.join(3000); } catch (InterruptedException ignore) {}
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (w <= 0 || h <= 0) {
            synchronized (renderLock) {
                surfaceReady = false;
                width = 0;
                height = 0;
                renderLock.notifyAll();
//                LogManager.log(TAG, "surfaceChanged: inside [synchronized (renderLock)] return");
            }
            return;
        }

        renderer.notifySurfaceChanged(w, h);
        synchronized (renderLock) {
            width = w;
            height = h;
            eventQueue.add(() -> renderer.onSurfaceChanged(w, h));
            surfaceReady = true;
            renderRequested = true;
            renderLock.notifyAll();
//            LogManager.log(TAG, "surfaceChanged: inside [synchronized (renderLock)] second");
        }
        LogManager.log(TAG, "surfaceChanged called");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (renderLock) {
            surfaceReady = false;
            width = 0;
            height = 0;
            renderLock.notifyAll();
//            LogManager.log(TAG, "surfaceDestroyed: inside [synchronized (renderLock)]");
        }
        // Run the render thread one more iteration so it sees surfaceReady=false and exits.
        stopRenderThread();
        renderer.detachSurface();
        LogManager.log(TAG, "surfaceDestroyed called");
    }

    // --- Render thread -------------------------------------------------------

    private void startRenderThreadIfNeeded() {
        if (renderThread != null && renderThread.isAlive()) return;
        running = true;
        renderThread = new Thread(this::renderLoop, "VkRenderer");
        renderThread.start();
    }

    private void stopRenderThread() {
        synchronized (renderLock) {
            running = false;
            renderLock.notifyAll();
            if (renderThread != null) retiringRenderThread = renderThread;
            renderThread = null;
        }
    }

    private void renderLoop() {
        renderer.onSurfaceCreated();
        if (width > 0 && height > 0) renderer.onSurfaceChanged(width, height);

        while (true) {
            Runnable event = null;
            boolean draw = false;
            int reason = 0;
            synchronized (renderLock) {
                while (true) {
                    if (!running) break;
                    if (paused || !surfaceReady) {
                        nextContinuousFrameNs = 0;
                        try { renderLock.wait(50); } catch (InterruptedException ignore) {}
                        continue;
                    }

                    long now = System.nanoTime();
                    boolean transientActive = transientRenderUntilNs > now;

                    if (!eventQueue.isEmpty()) {
                        event = eventQueue.poll();
                        break;
                    }

                    if (renderRequested) {
                        draw = true;
                        reason = REASON_REQUESTED;
                        renderRequested = false;
                        transientRenderRequested = false;
                        if (!transientActive) nextContinuousFrameNs = 0;
                        break;
                    }

                    if (renderMode == RENDERMODE_CONTINUOUSLY) {
                        draw = true;
                        reason = REASON_CONTINUOUS;
                        transientRenderRequested = false;
                        nextContinuousFrameNs = 0;
                        break;
                    }

                    if (transientRenderRequested) {
                        draw = true;
                        reason = REASON_TRANSIENT_REQ;
                        transientRenderRequested = false;
                        nextContinuousFrameNs = now + TRANSIENT_FRAME_INTERVAL_NS;
                        break;
                    }

                    if (transientActive) {
                        if (nextContinuousFrameNs == 0 || now >= nextContinuousFrameNs) {
                            draw = true;
                            reason = REASON_TRANSIENT_ACTIVE;
                            nextContinuousFrameNs = now + TRANSIENT_FRAME_INTERVAL_NS;
                            break;
                        }
                        waitNanosLocked(nextContinuousFrameNs - now);
                        continue;
                    }

                    nextContinuousFrameNs = 0;
                    try { renderLock.wait(); } catch (InterruptedException ignore) {}
                }
            }
            if (draw) countDrawReason(reason);
            if (!running) break;
            if (event != null) {
                try { event.run(); } catch (Throwable ignore) {}
            } else if (draw) {
                try { renderer.onDrawFrame(); } catch (Throwable ignore) {}
            }
        }
        renderer.onSurfaceDestroyed();
    }

    private void countDrawReason(int reason) {
        drawReasonCounts[reason]++;
        if (++drawReasonTotal < 600) return;
        long nowNs = System.nanoTime();
        float seconds = drawReasonStartNs == 0 ? 0f : (nowNs - drawReasonStartNs) / 1e9f;
        drawReasonStartNs = nowNs;
        android.util.Log.i("VkRenderer", String.format(
                "draw reasons over %.1fs: requested=%d continuous=%d transientReq=%d transientActive=%d guestPresents=%d coalesced:%s",
                seconds, drawReasonCounts[REASON_REQUESTED], drawReasonCounts[REASON_CONTINUOUS],
                drawReasonCounts[REASON_TRANSIENT_REQ], drawReasonCounts[REASON_TRANSIENT_ACTIVE],
                renderer.takeGuestPresentDelta(), renderer.takeWakeBreakdown()));
        java.util.Arrays.fill(drawReasonCounts, 0);
        drawReasonTotal = 0;
    }

    private void waitNanosLocked(long nanos) {
        if (nanos <= 0) return;
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        try { renderLock.wait(millis, extraNanos); } catch (InterruptedException ignore) {}
    }

    // ---- Convenience accessors used by VulkanRenderer ----------------------

    public int getSurfaceWidth() { return width; }
    public int getSurfaceHeight() { return height; }
}
