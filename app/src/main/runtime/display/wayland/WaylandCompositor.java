package com.winlator.cmod.runtime.display.wayland;

import android.view.Surface;

import java.nio.charset.StandardCharsets;

/**
 * JNI surface of the embedded Wayland compositor (libwnwayland.so); see CREDITS.md for
 * attribution. One compositor thread lives for the whole process; each game session begins with
 * {@link #nativeStartWithSurface} and ends with {@link #nativeEndSession}. Listener callbacks run
 * on the compositor thread and must marshal to the UI thread themselves.
 */
public final class WaylandCompositor {
    static {
        System.loadLibrary("wnwayland");
    }

    private WaylandCompositor() {}

    public static final int HDR_MODE_OFF = 0, HDR_MODE_ON = 1, HDR_MODE_FORCE = 2;

    /** Scale modes of the output mapping (mirrors the compositor's vkp_scale_mode). */
    public static final int SCALE_OFF = 0, SCALE_FIT = 1, SCALE_STRETCH = 2, SCALE_FILL = 3, SCALE_INTEGER = 4;
    public static final int ALIGN_CENTER = 0, ALIGN_TOP = 1, ALIGN_BOTTOM = 2;

    /** Fired once per session when the first client frame reaches the screen. */
    public interface FirstFrameListener {
        void onFirstFrame();
    }

    /** A window presenting GPU frames, its frames, and the program behind it. */
    public interface GameListener {
        void onGameSurface(String window, String gpuName);
        void onGameFrame();
        default void onGameProgram(int pid, String program) {}
    }

    /** A program locked or released the pointer (zwp_pointer_constraints_v1). */
    public interface PointerLockListener {
        void onPointerLock(boolean locked, int x, int y);
    }

    public interface ClipboardListener {
        void onGuestClipboardText(String text);
    }

    public interface TextInputListener {
        void onTextInput(boolean enabled, String program, int x, int y, int w, int h);
    }

    private static volatile FirstFrameListener firstFrameListener;
    private static volatile GameListener gameListener;
    private static volatile PointerLockListener pointerLockListener;
    private static volatile ClipboardListener clipboardListener;
    private static volatile TextInputListener textInputListener;

    public static void setFirstFrameListener(FirstFrameListener l) { firstFrameListener = l; }
    public static void setGameListener(GameListener l) { gameListener = l; }
    public static void setPointerLockListener(PointerLockListener l) { pointerLockListener = l; }
    public static void setClipboardListener(ClipboardListener l) { clipboardListener = l; }
    public static void setTextInputListener(TextInputListener l) { textInputListener = l; }

    /** Drops every listener; called when a session ends so a stale activity is never called back. */
    public static void clearListeners() {
        firstFrameListener = null;
        gameListener = null;
        pointerLockListener = null;
        clipboardListener = null;
        textInputListener = null;
    }

    @SuppressWarnings("unused")
    static void onFirstFramePresented() {
        FirstFrameListener l = firstFrameListener;
        if (l != null) l.onFirstFrame();
    }

    @SuppressWarnings("unused")
    static void onGameSurface(String window, String gpuName) {
        GameListener l = gameListener;
        if (l != null) l.onGameSurface(window, gpuName);
    }

    @SuppressWarnings("unused")
    static void onGameFrame() {
        GameListener l = gameListener;
        if (l != null) l.onGameFrame();
    }

    @SuppressWarnings("unused")
    static void onGameProgram(int pid, String program) {
        GameListener l = gameListener;
        if (l != null) l.onGameProgram(pid, program != null ? program : "");
    }

    @SuppressWarnings("unused")
    static void onPointerLock(boolean locked, int x, int y) {
        PointerLockListener l = pointerLockListener;
        if (l != null) l.onPointerLock(locked, x, y);
    }

    @SuppressWarnings("unused")
    static void onClipboardText(byte[] utf8) {
        ClipboardListener l = clipboardListener;
        if (l != null && utf8 != null) l.onGuestClipboardText(new String(utf8, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused")
    static void onTextInput(boolean enabled, String program, int x, int y, int w, int h) {
        TextInputListener l = textInputListener;
        if (l != null) l.onTextInput(enabled, program, x, y, w, h);
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    public static void setClipboardText(String text) { nativeSetClipboardText(utf8(text)); }
    public static void textInputCommit(String text) { if (text != null && !text.isEmpty()) nativeTextInputCommit(utf8(text)); }
    public static void textInputPreedit(String text, int cursor) { nativeTextInputPreedit(utf8(text), cursor, cursor); }
    public static void textInputDelete(int before, int after) { nativeTextInputDelete(before, after); }

    /** Relative pointer motion in scene pixels: the pointer-lock and relative-mouse path. */
    public static void sendPointerDelta(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        nativeSendSceneInput(6, dx * 256, dy * 256);
    }

    private static native void nativeSetClipboardText(byte[] utf8);
    private static native void nativeTextInputCommit(byte[] utf8);
    private static native void nativeTextInputPreedit(byte[] utf8, int cursorBegin, int cursorEnd);
    private static native void nativeTextInputDelete(int before, int after);

    // Lifecycle. Session settings (hide shell, output size and rate, FPS limit, zero-copy, UBWC)
    // are stored before the start call; the compositor thread reads them as it runs.
    public static native boolean nativeIsRunning();
    public static native void nativeSetLogDir(String dir);
    public static native void nativeStart(String xdgRuntimeDir);
    public static native void nativeStartWithSurface(Surface surface, String xdgRuntimeDir,
                                                     String driverPath, String libraryName,
                                                     String nativeLibDir);
    public static native void nativeSetSurface(Surface surface);
    public static native void nativeEndSession();

    // Input. Scene input types: 2 = move to a,b; 3 = evdev button a pressed (b=1) or released;
    // 4 = a wheel steps (negative = up); 6 = relative motion by a,b in 1/256 px.
    public static native void nativeSendPointer(int action, int x, int y);
    public static native void nativeSendKey(int evdev, int state);
    public static native void nativeSendSceneInput(int type, int a, int b);
    public static native void nativeVsync(long frameTimeNanos);

    // Output.
    public static native void nativeSetHideShell(boolean hide);
    public static native void nativeSetOutputRefreshRate(float hz);
    public static native void nativeSetOutputSize(int width, int height);
    public static native void nativeSetScaleMode(int scaleMode, int alignment);
    public static native void nativeSetFpsLimit(int fps);
    public static native void nativeSetLayerFrameRate(float hz);

    // Zero-copy display layer and buffer layout.
    public static native void nativeSetZeroCopy(boolean on);
    public static native int nativeZeroCopyFrames();
    public static native int nativeZeroCopyLastFrameAgeMs();
    public static native void nativeSetUbwc(boolean on);
    public static native void nativeSetNoRenderNode(boolean on);

    // Session log lines written from Java.
    public static native void nativeLogDisplay(String message);
    public static native void nativeLogPerf(String message);
    public static native void nativeLogColor(String message);
    public static native void nativeLog(String area, String message);

    // HDR10 output. WinNative leaves the request at HDR_MODE_OFF; the entry points stay bound to
    // the library's exports.
    public static native void nativeSetHdrRequest(int mode, String source, boolean dxvkHdr, boolean zeroCopyForced);
    public static native void nativeSetHdrDisplay(int displayId, String name, String formats, boolean hdr10,
                                                  float maxLuminance, float maxAverageLuminance, float minLuminance,
                                                  boolean hdrSdrRatioAvailable, float hdrSdrRatio, int apiLevel);
    public static native void nativeHdrSdrRatioSample(float ratio, boolean listener);
    public static native int nativeHdrLastFrameAgeMs();
    public static native int nativeHdrGateState();
    public static native void nativeHdrSessionEnd();
    public static native boolean nativeHdrOnScreen();
    public static native void nativeSetHdrSdrWhite(float nits);
    public static native void nativeSetHdrOutput(boolean on);
    public static native boolean nativeHdrOutput();
    public static native boolean nativeHdrToneMappedOnScreen();
    public static native int nativeHdrState();
    public static native void nativeHdrEnvSample(int thermalStatus, float thermalHeadroom, int brightness, int brightnessMode);
    public static native void nativeSetHdrHighestRatio(float ratio);
    public static native float nativeHdrScreenHeadroom();
    public static native String nativeHdrScreenHeadroomWhy();
    public static native void nativeHdrNoteHeadroomRequest(float ratio);

    // Screen effects run by the compositor between the composed scene and the output blit.
    public static native void nativeSetUpscaler(int mode);
    public static native void nativeSetUpscaleSharpness(int sharpness);
    public static native void nativeSetCas(boolean enabled, int sharpness);
    public static native void nativeSetHdr(boolean enabled);
    public static native void nativeSetDeband(boolean enabled, int strength);
    public static native void nativeSetScreenEffects(float brightness, float contrast, float gamma, float saturation,
                                                     boolean fxaa, boolean toon, boolean crt, boolean ntsc);
    public static native void nativeSetLookName(String name);

    // Frame generation. kind is ENGINE_LSFG or ENGINE_DIS; every setter is a store the compositor
    // thread picks up on its next frame, so they are safe to call before the compositor is up.
    public static final int ENGINE_LSFG = 0;
    public static final int ENGINE_DIS = 1;

    public static native void nativeSetFrameGenEngine(int kind);
    public static native void nativeSetFrameGenArmed(boolean armed, int multiplier);
    public static native void nativeSetLsfgCachePath(String path);
    public static native void nativeSetFrameGenTuning(float flowScale, float refreshHz);
    public static native void nativeSetEngineTuning(int flowMinSide, int targetFps);
    public static native int nativeFrameGenProblem();
    public static native String nativeFrameGenCapsReason();
    public static native float[] nativeFrameGenStats();
    public static native long nativeFrameGenPresentedFrames();
    public static native long nativeFrameGenGeneratedFrames();
}
