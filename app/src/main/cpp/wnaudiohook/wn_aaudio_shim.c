#include <dlfcn.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <sys/mman.h>
#include <unistd.h>
#include <pthread.h>
#include <android/dlext.h>
#include <android/api-level.h>
#include <android/log.h>

#define TAG "wn-aaudio-shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef void *(*dlopen_t)(const char *, int);
typedef void *(*loader_dlopen_t)(const char *, int, const void *);
typedef struct android_namespace_t *(*create_ns_t)(
    const char *, const char *, const char *, uint64_t, const char *,
    struct android_namespace_t *, const void *);
typedef bool (*link_all_t)(struct android_namespace_t *, struct android_namespace_t *);
enum { NS_TYPE_SHARED = 2 };

static void *g_real_aaudio = NULL;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static void *align_page(void *p) {
    return (void *)((uintptr_t)p & ~(uintptr_t)(getpagesize() - 1));
}

static void bridge_init(void) {
    if (android_get_device_api_level() < 28) { LOGE("api < 28"); return; }
    dlopen_t real_dlopen = (dlopen_t) dlsym(RTLD_DEFAULT, "dlopen");
    if (!real_dlopen) { LOGE("no dlopen"); return; }
    mprotect(align_page((void *)real_dlopen), getpagesize(), PROT_READ | PROT_WRITE | PROT_EXEC);
    uint32_t *p = (uint32_t *)real_dlopen;
    loader_dlopen_t loader_dlopen = NULL;
    for (int i = 0; i < 64; i++) {
        uint32_t raw = p[i];
        if ((raw >> 26) == 0x25u) {
            int32_t off = ((int32_t)(raw << 6)) >> 6;
            loader_dlopen = (loader_dlopen_t)(p + i + off);
            break;
        }
    }
    if (!loader_dlopen) { LOGE("loader_dlopen walk failed"); return; }
    mprotect(align_page((void *)loader_dlopen), getpagesize(), PROT_READ | PROT_WRITE | PROT_EXEC);
    void *ld = loader_dlopen("ld-android.so", RTLD_LAZY, (void *)real_dlopen);
    void *libdl = loader_dlopen("libdl_android.so", RTLD_LAZY, (void *)real_dlopen);
    if (!ld || !libdl) { LOGE("loader ld/libdl failed"); return; }
    link_all_t link_all = (link_all_t) dlsym(ld, "__loader_android_link_namespaces_all_libs");
    create_ns_t create_ns = (create_ns_t) dlsym(libdl, "__loader_android_create_namespace");
    if (!link_all || !create_ns) { LOGE("loader ns symbols missing"); return; }
    struct android_namespace_t *def = create_ns("wn-aaudio-defcopy", NULL, NULL, NS_TYPE_SHARED, NULL, NULL, (void *)real_dlopen);
    struct android_namespace_t *ns = create_ns("wn-aaudio", "/system/lib64", NULL, NS_TYPE_SHARED, NULL, NULL, (void *)real_dlopen);
    if (!def || !ns || !link_all(ns, def)) { LOGE("ns setup failed"); return; }
    android_dlextinfo info;
    memset(&info, 0, sizeof(info));
    info.flags = ANDROID_DLEXT_USE_NAMESPACE;
    info.library_namespace = ns;
    g_real_aaudio = android_dlopen_ext("/system/lib64/libaaudio.so", RTLD_NOW, &info);
    if (!g_real_aaudio) { LOGE("dlopen real libaaudio failed: %s", dlerror()); return; }
    LOGI("real /system/lib64/libaaudio.so bridged via default-all-libs namespace");
}

typedef long (*genfn_t)(long, long, long, long, long, long, long, long);
static void *resolve_sym(const char *name) {
    pthread_once(&g_once, bridge_init);
    return g_real_aaudio ? dlsym(g_real_aaudio, name) : NULL;
}

#define FWD(name) \
__attribute__((visibility("default"))) \
long name(long a, long b, long c, long d, long e, long f, long g, long h) { \
    static genfn_t fn; static int resolved; \
    if (!resolved) { fn = (genfn_t) resolve_sym(#name); resolved = 1; } \
    if (!fn) { LOGE("unresolved %s", #name); return -1; } \
    return fn(a, b, c, d, e, f, g, h); \
}
FWD(AAudioStreamBuilder_delete)
FWD(AAudioStreamBuilder_openStream)
FWD(AAudioStreamBuilder_setBufferCapacityInFrames)
FWD(AAudioStreamBuilder_setChannelCount)
FWD(AAudioStreamBuilder_setDataCallback)
FWD(AAudioStreamBuilder_setDirection)
FWD(AAudioStreamBuilder_setErrorCallback)
FWD(AAudioStreamBuilder_setFormat)
FWD(AAudioStreamBuilder_setInputPreset)
FWD(AAudioStreamBuilder_setPerformanceMode)
FWD(AAudioStreamBuilder_setSampleRate)
FWD(AAudioStreamBuilder_setSharingMode)
FWD(AAudioStreamBuilder_setUsage)
FWD(AAudioStream_close)
FWD(AAudioStream_getBufferCapacityInFrames)
FWD(AAudioStream_getBufferSizeInFrames)
FWD(AAudioStream_getChannelCount)
FWD(AAudioStream_getFormat)
FWD(AAudioStream_getFramesPerBurst)
FWD(AAudioStream_getPerformanceMode)
FWD(AAudioStream_getSampleRate)
FWD(AAudioStream_getSharingMode)
FWD(AAudioStream_getXRunCount)
FWD(AAudioStream_requestStart)
FWD(AAudioStream_requestStop)
FWD(AAudioStream_setBufferSizeInFrames)
FWD(AAudio_createStreamBuilder)
