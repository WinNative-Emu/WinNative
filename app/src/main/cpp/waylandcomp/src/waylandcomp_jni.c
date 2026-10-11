/*
 * JNI entry for the embedded Wayland compositor (experimental parallel runtime).
 * Starts the compositor on a dedicated thread (it blocks in the wl event loop).
 * The render-to-Surface backend + input are added in the M4 phase; this brings up
 * the server so a Wayland client (eventually winewayland.drv) can connect.
 */
#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/resource.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "vk_present.h"
#include "desktop_ext.h"
#include "ahb_swapchain.h"
#include "sc_layer.h"
#include "effects_chain.h"
#include "color_mgmt.h"

extern int wnc_wayland_run(void);
extern void wnc_wayland_set_log_dir(const char *dir);
extern void wnc_wayland_end_session(void);
extern void wnc_wayland_begin_session(void);
extern void wnc_wayland_send_pointer(int action, int x, int y);
extern void wnc_wayland_send_key(int evdev, int state);
extern void wnc_wayland_send_scene_input(int type, int a, int b);
extern void wnc_wayland_vsync(int64_t frame_time_ns);
extern volatile int g_fps_limit;
extern volatile int g_hide_shell;
extern volatile int g_zero_copy;
extern volatile unsigned g_zero_copy_last;
extern volatile int g_ubwc;
extern volatile int g_no_render_node;
extern volatile int g_output_refresh_mhz;
extern volatile int g_output_w, g_output_h;

#define TAG "WNWayland"

static JavaVM *g_jvm;
static jclass g_compositor_cls;      /* global ref */
static jmethodID g_on_first_frame;   /* static void onFirstFramePresented() */
static jmethodID g_on_game_surface;  /* static void onGameSurface(String, String) */
static jmethodID g_on_game_frame;    /* static void onGameFrame() */
static jmethodID g_on_game_program;  /* static void onGameProgram(int, String) */
static jmethodID g_on_pointer_lock;  /* static void onPointerLock(boolean, int, int) */
static jmethodID g_on_clipboard;     /* static void onClipboardText(byte[]) */
static jmethodID g_on_text_input;    /* static void onTextInput(boolean, String, int, int, int, int) */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        jclass c = (*env)->FindClass(env, "com/winlator/cmod/runtime/display/wayland/WaylandCompositor");
        if (c) {
            g_compositor_cls = (*env)->NewGlobalRef(env, c);
            g_on_first_frame = (*env)->GetStaticMethodID(env, g_compositor_cls,
                                                         "onFirstFramePresented", "()V");
            g_on_game_surface = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameSurface",
                                                          "(Ljava/lang/String;Ljava/lang/String;)V");
            g_on_game_frame = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameFrame", "()V");
            g_on_game_program = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameProgram",
                                                          "(ILjava/lang/String;)V");
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); g_on_game_program = NULL; }
            g_on_pointer_lock = (*env)->GetStaticMethodID(env, g_compositor_cls, "onPointerLock", "(ZII)V");
            g_on_clipboard = (*env)->GetStaticMethodID(env, g_compositor_cls, "onClipboardText", "([B)V");
            g_on_text_input = (*env)->GetStaticMethodID(env, g_compositor_cls, "onTextInput",
                                                        "(ZLjava/lang/String;IIII)V");
        }
    }
    return JNI_VERSION_1_6;
}

/* Called from vk_present.c on the compositor thread when the first client frame is
 * presented. Attaches to the JVM (this thread is a bare pthread) and calls back into
 * Java so the launch overlay can dismiss. Fires exactly once. */
void wnc_on_first_frame(void) {
    if (!g_jvm || !g_compositor_cls || !g_on_first_frame) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_first_frame);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    __android_log_print(ANDROID_LOG_INFO, TAG, "first client frame presented -> notified app");
}

/* The compositor thread stays attached once it first calls into Java: the HUD gets an upcall
 * for every game frame. */
static __thread JNIEnv *t_env;
static __thread int t_attached;

static JNIEnv *thread_env(void) {
    if (t_env) return t_env;
    if (!g_jvm) return NULL;
    JNIEnv *env = NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return NULL;
        t_attached = 1;
    }
    return t_env = env;
}

/* A window started presenting GPU frames (window = its description), or NULL when it closed. */
void wnc_on_game_surface(const char *window, const char *gpu) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_surface || !(env = thread_env())) return;
    jstring jw = window ? (*env)->NewStringUTF(env, window) : NULL;
    jstring jg = gpu ? (*env)->NewStringUTF(env, gpu) : NULL;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_surface, jw, jg);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jw) (*env)->DeleteLocalRef(env, jw);
    if (jg) (*env)->DeleteLocalRef(env, jg);
}

/* The program behind the game window that just started presenting: its Linux pid and executable name
 * ("" = unknown). The app arms its launch-time CPU affinity on it. Compositor thread. */
void wnc_on_game_program(int pid, const char *program) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_program || !(env = thread_env())) return;
    jstring jp = (*env)->NewStringUTF(env, program ? program : "");
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_program, (jint)pid, jp);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jp) (*env)->DeleteLocalRef(env, jp);
}

/* One GPU frame from that window. */
void wnc_on_game_frame(void) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_frame || !(env = thread_env())) return;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_frame);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* A program locked the pointer (locked = 1; the app's input path switches to deltas) or the lock
 * ended (locked = 0; x,y = where the pointer is now, in scene coordinates, for the app to re-sync
 * its own pointer to). Compositor thread. */
void wnc_on_pointer_lock(int locked, int x, int y) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_pointer_lock || !(env = thread_env())) return;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_pointer_lock, (jboolean)(locked != 0), (jint)x, (jint)y);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* A program copied text (UTF-8, not NUL-terminated for the app's sake — a byte[] so emoji and
 * NULs survive JNI). Compositor thread. */
void wnc_on_clipboard_text(const char *utf8, int len) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_clipboard || !(env = thread_env())) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { (*env)->ExceptionClear(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)utf8);
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_clipboard, arr);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, arr);
}

/* A program started (enabled) or stopped accepting IME text; x,y,w,h = its caret rectangle in
 * scene pixels (all 0 = unknown). Compositor thread. */
void wnc_on_text_input(int enabled, const char *program, int x, int y, int w, int h) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_text_input || !(env = thread_env())) return;
    jstring jp = (*env)->NewStringUTF(env, program ? program : "");
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_text_input, (jboolean)(enabled != 0), jp,
                                 (jint)x, (jint)y, (jint)w, (jint)h);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jp) (*env)->DeleteLocalRef(env, jp);
}

/* android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY: the level the hwui RenderThread runs at. */
#define COMPOSITOR_NICE (-8)

static void *comp_thread(void *arg) {
    (void)arg;
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread starting");
    /* Every buffer release, frame callback and layer transaction of the session goes through this one
     * thread. It is named, so `ps -T`, the logs and the drawer's Thread Priority Boost (PerfPriority
     * matches thread names) can find it, and it runs at display priority instead of whatever the
     * thread that started it had. Never lowered: a thread that already runs hotter keeps its value. */
    pthread_setname_np(pthread_self(), "wl-compositor");
    const id_t tid = (id_t)gettid();
    errno = 0;
    const int before = getpriority(PRIO_PROCESS, tid);
    int refused = 0;
    if (before > COMPOSITOR_NICE && setpriority(PRIO_PROCESS, tid, COMPOSITOR_NICE) != 0) refused = errno ? errno : -1;
    const int after = getpriority(PRIO_PROCESS, tid);
    if (refused)
        wnc_log("perf", "compositor thread %d \"wl-compositor\": stays at nice %d, a higher priority was refused (%s)",
                   (int)tid, after, refused > 0 ? strerror(refused) : "?");
    else
        wnc_log("perf", "compositor thread %d \"wl-compositor\": nice %d -> %d", (int)tid, before, after);
    wnc_wayland_run();
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread exited");
    if (t_attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    t_env = NULL; t_attached = 0;
    return NULL;
}

static void set_runtime_dir(JNIEnv *env, jstring xdgRuntimeDir) {
    if (!xdgRuntimeDir) return;
    const char *dir = (*env)->GetStringUTFChars(env, xdgRuntimeDir, NULL);
    if (dir) {
        setenv("XDG_RUNTIME_DIR", dir, 1);
        (*env)->ReleaseStringUTFChars(env, xdgRuntimeDir, dir);
    }
}

static char *dup_jstr(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = c ? strdup(c) : NULL;
    if (c) (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

/* One compositor thread per process: WinNative keeps its process alive between sessions, so the
 * thread is started once and sessions begin and end on it (wnc_wayland_begin/end_session). */
static pthread_mutex_t g_start_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_started;

/* Returns 1 when this call started the thread, 0 when it was already running. */
static int start_thread(void) {
    pthread_t t;
    pthread_mutex_lock(&g_start_lock);
    if (g_started) { pthread_mutex_unlock(&g_start_lock); return 0; }
    if (pthread_create(&t, NULL, comp_thread, NULL) == 0) {
        pthread_detach(t);
        g_started = 1;
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pthread_create failed");
    }
    pthread_mutex_unlock(&g_start_lock);
    return g_started;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeIsRunning(JNIEnv *env, jclass clazz) {
    int r;
    pthread_mutex_lock(&g_start_lock);
    r = g_started;
    pthread_mutex_unlock(&g_start_lock);
    return r ? JNI_TRUE : JNI_FALSE;
}

/* Where session logs go (one file per session); empty/null = logcat only. Any thread, before the start. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetLogDir(JNIEnv *env, jclass clazz,
                                                                                 jstring dir) {
    char *d = dup_jstr(env, dir);
    wnc_wayland_set_log_dir(d);
    free(d);
}

/* The game session is over: drop the screen surface and have the compositor thread disconnect every
 * client and reset its per-session state. The thread and its Vulkan device stay up for the next
 * session. Any thread. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeEndSession(JNIEnv *env, jclass clazz) {
    vk_present_set_window(NULL);
    wnc_wayland_end_session();
}

/* Headless start (no output window) — used for bring-up tests. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeStart(JNIEnv *env, jclass clazz,
                                                             jstring xdgRuntimeDir) {
    set_runtime_dir(env, xdgRuntimeDir);
    if (!start_thread()) wnc_wayland_begin_session();
}

/* Start with a real output Surface + the container's Turnip driver (adrenotools).
 * Frames committed by clients are composited to this Surface via Turnip. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeStartWithSurface(
        JNIEnv *env, jclass clazz, jobject surface, jstring xdgRuntimeDir,
        jstring driverPath, jstring libraryName, jstring nativeLibDir) {
    set_runtime_dir(env, xdgRuntimeDir);
    char *dp = dup_jstr(env, driverPath);
    char *ln = dup_jstr(env, libraryName);
    char *nl = dup_jstr(env, nativeLibDir);
    vk_present_set_driver(dp, ln, nl);
    free(dp); free(ln); free(nl);
    if (surface) {
        ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
        vk_present_set_window(win); /* backend acquires; released on nativeSetSurface(null) */
        __android_log_print(ANDROID_LOG_INFO, TAG, "output window bound (%p)", (void *)win);
    }
    if (!start_thread()) {
        /* Already running: this is a new session on the same compositor. The driver above is only
         * taken by the first start (the Vulkan device is up already). */
        wnc_wayland_begin_session();
        __android_log_print(ANDROID_LOG_INFO, TAG, "new session on the running compositor");
    }
}

/* Inject a pointer event from the Android SurfaceView touch listener (UI thread).
 * action: 0=down 1=move 2=up; x/y in output space (0..1919, 0..1079). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSendPointer(
        JNIEnv *env, jclass clazz, jint action, jint x, jint y) {
    wnc_wayland_send_pointer(action, x, y);
}

/* Inject a key event. evdev = Linux input keycode (KEY_A=30…); state 1=down 0=up. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSendKey(
        JNIEnv *env, jclass clazz, jint evdev, jint state) {
    wnc_wayland_send_key(evdev, state);
}

/* App X-server input in scene (virtual desktop) coordinates; see wnc_wayland_send_scene_input. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSendSceneInput(
        JNIEnv *env, jclass clazz, jint type, jint a, jint b) {
    wnc_wayland_send_scene_input(type, a, b);
}

/* One screen refresh (Choreographer frame callback, UI thread): the compositor draws once. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeVsync(JNIEnv *env, jclass clazz, jlong frameTimeNanos) {
    wnc_wayland_vsync((int64_t)frameTimeNanos);
}

/* Shortcut launches: don't draw explorer's windows (desktop, taskbar), like X11's unviewable classes. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetHideShell(JNIEnv *env, jclass clazz, jboolean hide) {
    g_hide_shell = hide ? 1 : 0;
}

/* Zero-copy layer mode (BANNER_WAYLAND_ZERO_COPY=1 at launch, the drawer's switch live): one fullscreen
 * window on its own Android layer instead of the swapchain blit (sc_layer.c / ahb_swapchain.c). Any
 * thread, any time: before the compositor starts it is the launch default; afterwards the compositor
 * thread flips the state, tells the bound games to rebuild their swapchains for it and redraws. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetZeroCopy(JNIEnv *env, jclass clazz, jboolean on) {
    int live = wnc_get_display() != NULL;
    if (!live) g_zero_copy = on ? 1 : 0; /* read by ahb_swapchain_init before the queue drains */
    wnc_host_zero_copy(on ? 1 : 0, live);
    __android_log_print(ANDROID_LOG_INFO, TAG, "zero-copy layer mode %s%s", on ? "on" : "off", live ? " (live)" : "");
}

/* Milliseconds since the compositor last put a game frame on the layer without a copy; -1 = never
 * this session. The drawer's status line ("switching..." until the frames arrive / stop). */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeZeroCopyLastFrameAgeMs(JNIEnv *env, jclass clazz) {
    return (jint)ahb_swapchain_last_frame_age_ms();
}

/* Zero-copy frames in the last completed 10 s stats window (on_stats_timer), for the drawer's live line. */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeZeroCopyFrames(JNIEnv *env, jclass clazz) {
    return (jint)g_zero_copy_last;
}

/* Compressed (UBWC) game buffers: advertise DRM_FORMAT_MOD_QCOM_COMPRESSED on zwp_linux_dmabuf_v1 when
 * the renderer's driver imports it (default on; BANNER_WAYLAND_UBWC=0 = off). Set before the compositor
 * starts. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetUbwc(JNIEnv *env, jclass clazz, jboolean on) {
    g_ubwc = on ? 1 : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "compressed (UBWC) game buffers %s", on ? "on" : "off");
}

/* Debug: name no DRM device in the dma-buf feedback (BANNER_WAYLAND_NO_RENDER_NODE=1), the way a
 * phone that exposes no /dev/dri node to apps does. Set before the compositor starts. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetNoRenderNode(JNIEnv *env, jclass clazz, jboolean on) {
    g_no_render_node = on ? 1 : 0;
    if (on) __android_log_print(ANDROID_LOG_INFO, TAG, "debug: advertising no DRM device (main device 0:0)");
}

/* VRR / refresh-rate matching: the rate the app is voting for the panel, mirrored onto the game's
 * own SurfaceControl layer. Under zero-copy the game's frames bypass the app's surface entirely, so
 * without this SurfaceFlinger never sees the game's cadence. 0 = clear the vote. Any thread, any
 * time; the compositor applies it with its next layer transaction and logs the change. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetLayerFrameRate(JNIEnv *env, jclass clazz, jfloat hz) {
    (void)env; (void)clazz;
    sc_layer_set_frame_rate((float)hz);
}

/* The panel's refresh rate (Hz) for the advertised wl_output mode. Set before the compositor starts. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetOutputRefreshRate(JNIEnv *env, jclass clazz, jfloat hz) {
    g_output_refresh_mhz = hz > 1.0f ? (int)(hz * 1000.0f + 0.5f) : 0;
}

/* The container's screen size for the advertised wl_output mode. Set before the compositor starts. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetOutputSize(JNIEnv *env, jclass clazz, jint w, jint h) {
    g_output_w = w > 0 ? w : 0;
    g_output_h = h > 0 ? h : 0;
}

/* Fullscreen mode + screen alignment (Container.FULLSCREEN_* / ALIGN_* values): how the scene is
 * fitted onto the output. Any thread, any time; the next frame uses it. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetScaleMode(JNIEnv *env, jclass clazz, jint mode, jint alignment) {
    vk_present_set_scale_mode(mode, alignment);
    __android_log_print(ANDROID_LOG_INFO, TAG, "scale mode %d alignment %d", mode, alignment);
}

/* ---- screen effects (effects_chain.c): the X11 Vulkan renderer's post chain in the compositor.
 * Any thread, any time; the compositor thread applies them on its next frame and logs one
 * `effects` line per change. Values are 1:1 with VulkanRenderer's setters. */

/* Scaling mode: 0=None 1=Linear 2=Nearest 3=SGSR 4=FSR 5=FSR Fit 6=Sharpen 7=NIS 8=SGSR HQ. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetUpscaler(JNIEnv *env, jclass clazz, jint mode) {
    vkp_effects_set_scaling(mode);
}

/* The upscaler's sharpness slider 0..100 (RCAS lobe / SGSR edge / NIS sharpness). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetUpscaleSharpness(JNIEnv *env, jclass clazz, jint sharpness) {
    vkp_effects_set_upscale_sharpness(sharpness);
}

/* AMD CAS sharpen toggle + level 0..100 (0 = pass off). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetCas(JNIEnv *env, jclass clazz, jboolean enabled, jint sharpness) {
    vkp_effects_set_cas(enabled ? 1 : 0, sharpness);
}

/* Fake-HDR toggle. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetHdr(JNIEnv *env, jclass clazz, jboolean enabled) {
    vkp_effects_set_hdr(enabled ? 1 : 0);
}

/* Terminal debanding toggle + strength 0..200 (100 = 1 LSB). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetDeband(JNIEnv *env, jclass clazz, jboolean enabled, jint strength) {
    vkp_effects_set_deband(enabled ? 1 : 0, strength);
}

/* Colour grade (slider units: brightness/contrast -100..100, gamma 0.5..3, saturation 0..200 %) and the
 * FXAA / Toon / CRT / NTSC toggles — VulkanRenderer.setScreenEffects. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetScreenEffects(JNIEnv *env, jclass clazz, jfloat brightness,
        jfloat contrast, jfloat gamma, jfloat saturation, jboolean fxaa, jboolean toon, jboolean crt, jboolean ntsc) {
    vkp_effects_set_screen(brightness, contrast, gamma, saturation, fxaa ? 1 : 0, toon ? 1 : 0, crt ? 1 : 0, ntsc ? 1 : 0);
}

/* One "display" line in the session log, written from Java: facts that live in the Android
 * framework (the panel's HDR capability) rather than in the compositor. wnc_log() mirrors to
 * logcat and appends to the session file when one is open, so this is safe at any point. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeLogDisplay(JNIEnv *env, jclass clazz, jstring message) {
    char *s = dup_jstr(env, message);
    if (s) wnc_log("display", "%s", s);
    free(s);
}

/* The same under the "perf" area: facts the app knows about the session's performance setup (the CPU
 * cores the game is pinned to, say). Same safety as nativeLogDisplay. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeLogPerf(JNIEnv *env, jclass clazz, jstring message) {
    char *s = dup_jstr(env, message);
    if (s) wnc_log("perf", "%s", s);
    free(s);
}

/* ---- HDR10 output, round 1 (color_mgmt.h / wl_color_mgmt.c) ---- */

/* The opt-in: mode 0 off, 1 BANNER_WAYLAND_HDR=1, 2 =force (testing). Before the compositor starts. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetHdrRequest(JNIEnv *env, jclass clazz, jint mode,
        jstring source, jboolean dxvkHdr, jboolean zeroCopyForced) {
    char *s = dup_jstr(env, source);
    wnc_color_set_request((int)mode, s, dxvkHdr ? 1 : 0, zeroCopyForced ? 1 : 0);
    free(s);
}

/* The game's display as android.view.Display reports it (before the start; again on every change). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetHdrDisplay(JNIEnv *env, jclass clazz, jint id, jstring name,
        jstring formats, jboolean hdr10, jfloat maxLum, jfloat maxAvg, jfloat minLum, jboolean ratioAvailable,
        jfloat ratio, jint api) {
    char *n = dup_jstr(env, name), *f = dup_jstr(env, formats);
    wnc_color_set_display((int)id, n, f, hdr10 ? 1 : 0, (float)maxLum, (float)maxAvg, (float)minLum,
                             ratioAvailable ? 1 : 0, (float)ratio, (int)api);
    free(n); free(f);
}

/* One Display.getHdrSdrRatio() reading (listener = from the display's ratio listener). Any thread. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrSdrRatioSample(JNIEnv *env, jclass clazz, jfloat ratio,
        jboolean listener) {
    wnc_color_ratio_sample((float)ratio, listener ? 1 : 0);
}

/* ms since an HDR frame last reached a display layer, -1 = never this session. Any thread. */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrLastFrameAgeMs(JNIEnv *env, jclass clazz) {
    return (jint)wnc_color_last_frame_age_ms();
}

/* -1 = not decided yet, 0 = closed, 1 = open. Any thread. */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrGateState(JNIEnv *env, jclass clazz) {
    return (jint)wnc_color_gate_state();
}

/* The session is ending: the "HDR on screen: ..." summary line. Any thread, once. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrSessionEnd(JNIEnv *env, jclass clazz) {
    wnc_color_session_end();
}

/* HDR frames really on screen right now (the HUD badge). Any thread. */
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrOnScreen(JNIEnv *env, jclass clazz) {
    return wnc_color_hdr_on_screen() ? JNI_TRUE : JNI_FALSE;
}

/* One "color" line in the session log from Java. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeLogColor(JNIEnv *env, jclass clazz, jstring message) {
    char *s = dup_jstr(env, message);
    if (s) wnc_log("color", "%s", s);
    free(s);
}

/* One line in the session log under an area Java names ("gpu", "nvapi"): launch facts the app decides.
 * The area is the log's 9-character column; an empty one reads "app". */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeLog(JNIEnv *env, jclass clazz, jstring area, jstring message) {
    char *a = dup_jstr(env, area), *s = dup_jstr(env, message);
    if (s) wnc_log(a && a[0] ? a : "app", "%s", s);
    free(a);
    free(s);
}

/* SDR content's level inside an HDR picture, in nits (default 203). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetHdrSdrWhite(JNIEnv *env, jclass clazz, jfloat nits) {
    wnc_color_set_sdr_white((float)nits);
}

/* The drawer's live HDR output switch: on = HDR frames as HDR, off = the same frames tone-mapped to SDR.
 * Applied on the compositor thread (logged there, with a redraw). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetHdrOutput(JNIEnv *env, jclass clazz, jboolean on) {
    if (wnc_get_display()) wnc_host_hdr_output(on ? 1 : 0);
    else wnc_color_set_output(on ? 1 : 0); /* no compositor thread yet: nothing is drawing either */
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrOutput(JNIEnv *env, jclass clazz) {
    return wnc_color_output() ? JNI_TRUE : JNI_FALSE;
}

/* Device evidence for the HDR lines: thermal status + headroom, brightness + mode. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrEnvSample(JNIEnv *env, jclass clazz, jint thermal,
                                                                   jfloat headroom, jint brightness, jint mode) {
    wnc_color_env_sample((int)thermal, (float)headroom != (float)headroom ? -1.0f : (float)headroom,
                            (int)brightness, (int)mode);
}

/* Display.getHighestHdrSdrRatio() (Android 16+), <= 0 = not reported. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetHdrHighestRatio(JNIEnv *env, jclass clazz, jfloat ratio) {
    wnc_color_set_highest_ratio((float)ratio == (float)ratio ? (float)ratio : -1.0f);
}

/* The HDR headroom the screen surface should ask for (HDR10 swapchain frames in the last 1.5 s), 0 = none. */
JNIEXPORT jfloat JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrScreenHeadroom(JNIEnv *env, jclass clazz) {
    return (jfloat)wnc_color_screen_headroom(NULL, 0);
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrScreenHeadroomWhy(JNIEnv *env, jclass clazz) {
    char why[200];
    wnc_color_screen_headroom(why, sizeof(why));
    return (*env)->NewStringUTF(env, why);
}

/* The app's screen-surface request, for the no-headroom lines: > 0 asked, 0 cleared, -1 not possible. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrNoteHeadroomRequest(JNIEnv *env, jclass clazz, jfloat ratio) {
    wnc_color_note_headroom_request((float)ratio);
}

/* 0 none, 1 HDR frames on screen with headroom, 2 HDR frames on screen without headroom for 5 s+. */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrState(JNIEnv *env, jclass clazz) {
    return (jint)wnc_color_hdr_state();
}

/* An HDR game's frames were shown tone-mapped to SDR in the last 1.5 s (the drawer's status line). */
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeHdrToneMappedOnScreen(JNIEnv *env, jclass clazz) {
    return wnc_color_tonemapped_on_screen() ? JNI_TRUE : JNI_FALSE;
}

/* The Look the controls currently match (null = Custom) — only named in the session log. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetLookName(JNIEnv *env, jclass clazz, jstring name) {
    char *s = dup_jstr(env, name);
    vkp_effects_set_look(s);
    free(s);
}

/* The in-game FPS limiter: frames per second, 0 = unlimited. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetFpsLimit(JNIEnv *env, jclass clazz, jint fps) {
    g_fps_limit = fps > 0 ? fps : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "fps limit %d", fps);
}

/* Swap/clear the output window (e.g. SurfaceView recreated/destroyed). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetSurface(
        JNIEnv *env, jclass clazz, jobject surface) {
    if (surface) {
        vk_present_set_window(ANativeWindow_fromSurface(env, surface));
    } else {
        vk_present_set_window(NULL);
    }
}

/* ---- clipboard + text input (any thread; queued to the compositor thread) ----
 * Text crosses as UTF-8 byte arrays: JNI's modified UTF-8 would mangle emoji. */

static char *dup_bytes(JNIEnv *env, jbyteArray arr, int *len) {
    *len = 0;
    if (!arr) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char *buf = malloc((size_t)n + 1);
    if (!buf) return NULL;
    if (n) (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)buf);
    buf[n] = 0;
    *len = (int)n;
    return buf;
}

/* Android's clipboard text becomes the guest's selection (empty/null = clear). */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeSetClipboardText(JNIEnv *env, jclass clazz, jbyteArray utf8) {
    int len;
    char *buf = dup_bytes(env, utf8, &len);
    wnc_host_clipboard_text(buf ? buf : "", len);
    free(buf);
}

/* Soft-keyboard text committed to the program accepting text input. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeTextInputCommit(JNIEnv *env, jclass clazz, jbyteArray utf8) {
    int len;
    char *buf = dup_bytes(env, utf8, &len);
    if (buf && len) wnc_host_text_commit(buf, len);
    free(buf);
}

/* Composing (pre-edit) text; cursorBegin/cursorEnd are character indexes into it, -1 = end. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeTextInputPreedit(JNIEnv *env, jclass clazz, jbyteArray utf8,
                                                                        jint cursorBegin, jint cursorEnd) {
    int len;
    char *buf = dup_bytes(env, utf8, &len);
    wnc_host_text_preedit(buf ? buf : "", len, cursorBegin, cursorEnd);
    free(buf);
}

/* The IME deleted characters around the caret. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_wayland_WaylandCompositor_nativeTextInputDelete(JNIEnv *env, jclass clazz, jint before, jint after) {
    wnc_host_text_delete(before, after);
}
