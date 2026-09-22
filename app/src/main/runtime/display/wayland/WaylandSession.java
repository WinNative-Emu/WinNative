package com.winlator.cmod.runtime.display.wayland;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.winlator.cmod.runtime.display.renderer.ViewTransformation;
import com.winlator.cmod.runtime.display.winhandler.WinHandler;
import com.winlator.cmod.runtime.display.xserver.Pointer;
import com.winlator.cmod.runtime.display.xserver.XServer;

import java.io.File;

/**
 * One game session on the embedded Wayland compositor: the output SurfaceView, the overlay
 * pointer, clipboard and IME sync, the vsync feed and the input bridge from the X server's
 * injected input. The native compositor outlives the activity; {@link #detach()} releases the
 * activity's views and listeners while the guest keeps running (background sessions) and
 * {@link #end()} closes the session for good.
 */
public final class WaylandSession {
    private static final String TAG = "WaylandSession";

    public interface Host {
        /** UI thread: a program took or released the pointer lock. */
        void onPointerLockChanged(boolean locked);

        /** UI thread: a window started ({@code present}) or stopped presenting GPU frames. */
        void onGameSurface(boolean present, String gpuName);

        /** Compositor thread, must stay cheap: one GPU frame reached the screen. */
        void onGameFrame();

        /** UI thread, once per session: the first client frame is on screen. */
        void onFirstFrame();

        /** UI thread: the Linux process behind the window now presenting ({@code pid} 0 = unknown). */
        default void onGameProgram(int pid, String program) {}
    }

    /** Settings the compositor reads for the session; stored before the surface comes up. */
    public static final class Config {
        public String driverPath;
        public String libraryName;
        public boolean hideShell;
        public float refreshHz;
        public int outputWidth;
        public int outputHeight;
        public int fpsLimit;
        public boolean zeroCopy;
        public boolean ubwc = true;
        public boolean noRenderNode;
        public int scaleMode = WaylandCompositor.SCALE_FIT;
        public File logDir;
    }

    private static volatile boolean sessionActive;

    /** A compositor session is live in this process (a background session may be reattached). */
    public static boolean hasActiveSession() {
        return sessionActive;
    }

    private final Activity activity;
    private final Host host;
    private final XServer xServer;
    private final WinHandler winHandler;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WaylandTextInput.SurfaceInputView surfaceView;
    /** The space nativeSendPointer takes its coordinates in, over the whole output. */
    private static final int INPUT_SPACE_WIDTH = 1920;
    private static final int INPUT_SPACE_HEIGHT = 1080;
    private ImageView cursorView;
    private WaylandClipboardSync clipboard;
    private WaylandTextInput textInput;
    private volatile boolean attached;
    private boolean vsyncRunning;
    private volatile boolean pointerLocked;
    private volatile int scaleMode = WaylandCompositor.SCALE_FIT;
    private boolean firstFrameSeen;

    private final Choreographer.FrameCallback vsyncCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!vsyncRunning) return;
            WaylandCompositor.nativeVsync(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public WaylandSession(Activity activity, Host host, XServer xServer, WinHandler winHandler) {
        this.activity = activity;
        this.host = host;
        this.xServer = xServer;
        this.winHandler = winHandler;
    }

    public boolean isPointerLocked() {
        return pointerLocked;
    }

    /**
     * Puts the compositor's surface into {@code root} at {@code index} and starts (or, for a
     * reattached background session, resumes) the session. UI thread.
     */
    public void attach(FrameLayout root, int index, Config cfg, boolean reattach) {
        if (attached) return;
        attached = true;
        final boolean fresh = !reattach || !sessionActive;
        sessionActive = true;
        scaleMode = cfg.scaleMode;
        if (fresh) {
            WaylandCompositor.nativeSetLogDir(cfg.logDir != null ? cfg.logDir.getPath() : "");
            WaylandCompositor.nativeSetHideShell(cfg.hideShell);
            WaylandCompositor.nativeSetOutputRefreshRate(cfg.refreshHz);
            WaylandCompositor.nativeSetOutputSize(cfg.outputWidth, cfg.outputHeight);
            WaylandCompositor.nativeSetFpsLimit(cfg.fpsLimit);
            // The cadence the session is aiming for, on the layer the game presents on. Left
            // unsaid, the layer is posted with a vote of zero and the system is free to read the
            // rate it is already achieving as the rate it wants - which is the wrong way round on
            // a device whose scheduler decides clocks from it.
            WaylandCompositor.nativeSetLayerFrameRate(cfg.fpsLimit > 0 ? cfg.fpsLimit : cfg.refreshHz);
            WaylandCompositor.nativeSetZeroCopy(cfg.zeroCopy);
            WaylandCompositor.nativeSetUbwc(cfg.ubwc);
            WaylandCompositor.nativeSetNoRenderNode(cfg.noRenderNode);
            WaylandCompositor.nativeSetHdrRequest(WaylandCompositor.HDR_MODE_OFF, "", false, false);
        }
        WaylandCompositor.nativeSetScaleMode(scaleMode, WaylandCompositor.ALIGN_CENTER);
        registerListeners();

        WaylandTextInput.SurfaceInputView view = new WaylandTextInput.SurfaceInputView(activity);
        view.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        surfaceView = view;
        cursorView = new ImageView(activity);
        cursorView.setImageBitmap(makeArrowCursorBitmap());
        cursorView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        cursorView.setVisibility(View.GONE);

        final File runtimeDir = new File(activity.getFilesDir(), ".wayland-rt");
        if (!runtimeDir.isDirectory() && !runtimeDir.mkdirs()) {
            Log.e(TAG, "wayland: cannot create " + runtimeDir);
        }
        final String driverPath = cfg.driverPath;
        final String libraryName = cfg.libraryName;
        final String nativeLibDir = activity.getApplicationInfo().nativeLibraryDir;
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            private boolean started;

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (!attached) return;
                if (!started && fresh) {
                    started = true;
                    WaylandCompositor.nativeStartWithSurface(holder.getSurface(), runtimeDir.getPath(),
                            driverPath, libraryName, nativeLibDir);
                } else {
                    started = true;
                    WaylandCompositor.nativeSetSurface(holder.getSurface());
                }
                startVsync();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                /* The ticks drive the compositor's drawing, and there is nothing to draw on until
                 * surfaceCreated hands it another surface. Left running they woke the UI thread and
                 * the compositor thread at the panel's refresh rate for a screen the session no
                 * longer has - all of it while the app sat in the background. */
                stopVsync();
                WaylandCompositor.nativeSetSurface(null);
            }
        });
        root.addView(view, index);
        root.addView(cursorView, index + 1);

        if (winHandler != null) winHandler.setWaylandMouseRouting(true);
        clipboard = new WaylandClipboardSync(activity);
        clipboard.start();
        textInput = new WaylandTextInput(activity, view);
        textInput.start();
        Log.i(TAG, (fresh ? "session started" : "session reattached") + " (scale mode " + scaleMode + ")");
    }

    /**
     * UI thread. A touchscreen-mode touch at screen coordinates: 0 = down, 1 = move, 2 = up. The
     * compositor places it on the scene pixel drawn under the finger and clicks the left button.
     */
    public boolean sendTouch(int action, float rawX, float rawY) {
        View sv = surfaceView;
        if (!attached || sv == null || sv.getWidth() <= 0 || sv.getHeight() <= 0) return false;
        int[] origin = new int[2];
        sv.getLocationOnScreen(origin);
        int x = Math.round((rawX - origin[0]) * INPUT_SPACE_WIDTH / sv.getWidth());
        int y = Math.round((rawY - origin[1]) * INPUT_SPACE_HEIGHT / sv.getHeight());
        WaylandCompositor.nativeSendPointer(action,
                Math.max(0, Math.min(INPUT_SPACE_WIDTH - 1, x)), Math.max(0, Math.min(INPUT_SPACE_HEIGHT - 1, y)));
        return true;
    }

    /** Releases the activity's side of the session; the guest and the compositor keep running. */
    public void detach() {
        if (!attached) return;
        attached = false;
        stopVsync();
        WaylandCompositor.clearListeners();
        if (xServer != null) {
            xServer.setInputSink(null);
            xServer.setExternalRelativeMode(false);
        }
        if (winHandler != null) winHandler.setWaylandMouseRouting(false);
        if (clipboard != null) { clipboard.stop(); clipboard = null; }
        if (textInput != null) { textInput.stop(); textInput = null; }
        WaylandCompositor.nativeSetSurface(null);
        final View sv = surfaceView, cv = cursorView;
        surfaceView = null;
        cursorView = null;
        main.post(() -> {
            if (sv != null && sv.getParent() instanceof FrameLayout) ((FrameLayout) sv.getParent()).removeView(sv);
            if (cv != null && cv.getParent() instanceof FrameLayout) ((FrameLayout) cv.getParent()).removeView(cv);
        });
    }

    /** The session is over: the guest has been terminated and the compositor resets for the next one. */
    public void end() {
        detach();
        if (sessionActive) {
            sessionActive = false;
            WaylandCompositor.nativeEndSession();
            Log.i(TAG, "session ended");
        }
    }

    public void onResume() {
        if (clipboard != null) clipboard.refresh();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus && clipboard != null) clipboard.refresh();
    }

    public void onUserToggledKeyboard() {
        if (textInput != null) textInput.onUserToggledKeyboard();
    }

    public void setFpsLimit(int fps) {
        WaylandCompositor.nativeSetFpsLimit(fps);
    }

    /** Mirrors the X renderer's letterbox/stretch choice so the picture and the touch map agree. */
    public void setScaleMode(int mode) {
        scaleMode = mode;
        WaylandCompositor.nativeSetScaleMode(mode, WaylandCompositor.ALIGN_CENTER);
    }

    private void registerListeners() {
        WaylandCompositor.setFirstFrameListener(() -> main.post(() -> {
            if (!attached || firstFrameSeen) return;
            firstFrameSeen = true;
            host.onFirstFrame();
        }));
        WaylandCompositor.setGameListener(new WaylandCompositor.GameListener() {
            @Override
            public void onGameSurface(String window, String gpuName) {
                final boolean present = window != null;
                main.post(() -> { if (attached) host.onGameSurface(present, gpuName); });
            }

            @Override
            public void onGameFrame() {
                if (attached) host.onGameFrame();
            }

            @Override
            public void onGameProgram(int pid, String program) {
                main.post(() -> { if (attached) host.onGameProgram(pid, program); });
            }
        });
        WaylandCompositor.setPointerLockListener((locked, x, y) -> main.post(() -> {
            if (!attached || xServer == null) return;
            pointerLocked = locked;
            xServer.setExternalRelativeMode(locked);
            if (locked) {
                if (cursorView != null) cursorView.setVisibility(View.GONE);
            } else {
                xServer.injectPointerMove(x, y);
            }
            host.onPointerLockChanged(locked);
        }));
        if (xServer != null) xServer.setInputSink(new XServer.InputSink() {
            @Override
            public void onPointerMove(int x, int y) {
                WaylandCompositor.nativeSendSceneInput(2, x, y);
                main.post(() -> moveCursorView(x, y));
            }

            @Override
            public void onPointerButton(Pointer.Button button, boolean pressed) {
                switch (button) {
                    case BUTTON_LEFT: WaylandCompositor.nativeSendSceneInput(3, 0x110, pressed ? 1 : 0); break;
                    case BUTTON_RIGHT: WaylandCompositor.nativeSendSceneInput(3, 0x111, pressed ? 1 : 0); break;
                    case BUTTON_MIDDLE: WaylandCompositor.nativeSendSceneInput(3, 0x112, pressed ? 1 : 0); break;
                    case BUTTON_SCROLL_UP: if (pressed) WaylandCompositor.nativeSendSceneInput(4, -1, 0); break;
                    case BUTTON_SCROLL_DOWN: if (pressed) WaylandCompositor.nativeSendSceneInput(4, 1, 0); break;
                    default: break;
                }
            }

            @Override
            public void onKey(int evdev, boolean pressed) {
                if (evdev > 0) WaylandCompositor.nativeSendKey(evdev, pressed ? 1 : 0);
            }
        });
    }

    private void moveCursorView(int x, int y) {
        View sv = surfaceView;
        ImageView cv = cursorView;
        if (!attached || sv == null || cv == null || pointerLocked || xServer == null) return;
        int vw = sv.getWidth(), vh = sv.getHeight();
        if (vw <= 0 || vh <= 0) return;
        int sw = xServer.screenInfo.width, sh = xServer.screenInfo.height;
        ViewTransformation vt = new ViewTransformation();
        vt.mode = scaleMode == WaylandCompositor.SCALE_STRETCH
                ? ViewTransformation.FILL_MODE_STRETCH : ViewTransformation.FILL_MODE_FIT;
        vt.update(vw, vh, sw, sh);
        float vx, vy;
        if (vt.mode == ViewTransformation.FILL_MODE_STRETCH) {
            vx = (float) x * vw / sw;
            vy = (float) y * vh / sh;
        } else {
            vx = vt.viewOffsetX + x * vt.aspect;
            vy = vt.viewOffsetY + y * vt.aspect;
        }
        cv.setX(vx);
        cv.setY(vy);
        if (cv.getVisibility() != View.VISIBLE) cv.setVisibility(View.VISIBLE);
    }

    private void startVsync() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::startVsync);
            return;
        }
        if (vsyncRunning) return;
        vsyncRunning = true;
        Choreographer.getInstance().postFrameCallback(vsyncCallback);
    }

    private void stopVsync() {
        vsyncRunning = false;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> Choreographer.getInstance().removeFrameCallback(vsyncCallback));
            return;
        }
        Choreographer.getInstance().removeFrameCallback(vsyncCallback);
    }

    private static Bitmap makeArrowCursorBitmap() {
        int w = 22, h = 34;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path p = new Path();
        p.moveTo(1, 1);
        p.lineTo(1, 26);
        p.lineTo(7, 20);
        p.lineTo(11, 30);
        p.lineTo(15, 28);
        p.lineTo(11, 19);
        p.lineTo(19, 19);
        p.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(Color.BLACK);
        c.drawPath(p, fill);
        c.drawPath(p, stroke);
        return bmp;
    }
}
