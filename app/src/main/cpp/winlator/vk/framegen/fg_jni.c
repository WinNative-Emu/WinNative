#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "fg_present.h"

#define FG_FN(name) Java_com_winlator_cmod_shared_framegen_FrameGenNative_##name

static char* fg_copy_utf(JNIEnv* env, jstring value) {
    if (!value) return NULL;
    const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!chars) return NULL;
    char* copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

JNIEXPORT jlong JNICALL FG_FN(nativeCreate)(JNIEnv* env, jclass clazz, jobject context,
                                            jobject output, jint width, jint height,
                                            jstring cachePath, jstring driverName, jint multiplier,
                                            jint targetRate, jint flowScale, jfloat refreshRate,
                                            jfloat sourceRate) {
    (void)clazz;
    if (!output || width <= 0 || height <= 0) return 0;

    ANativeWindow* window = ANativeWindow_fromSurface(env, output);
    if (!window) return 0;

    char* cache = fg_copy_utf(env, cachePath);
    char* driver = fg_copy_utf(env, driverName);

    float flow = flowScale > 0 ? (float)flowScale / 100.0f : 0.7f;
    FgPresenter* fg = fg_create(env, context, driver, window, (uint32_t)width, (uint32_t)height,
                                cache, (uint32_t)multiplier, (uint32_t)targetRate, flow,
                                refreshRate, sourceRate);

    ANativeWindow_release(window);
    free(cache);
    free(driver);
    return (jlong)(intptr_t)fg;
}

JNIEXPORT jobject JNICALL FG_FN(nativeProducerSurface)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)clazz;
    FgPresenter* fg = (FgPresenter*)(intptr_t)handle;
    ANativeWindow* window = fg_producer_window(fg);
    if (!window) return NULL;
    return ANativeWindow_toSurface(env, window);
}

JNIEXPORT void JNICALL FG_FN(nativeConfigure)(JNIEnv* env, jclass clazz, jlong handle,
                                              jint multiplier, jint targetRate, jint flowScale,
                                              jfloat refreshRate, jfloat sourceRate) {
    (void)env;
    (void)clazz;
    FgPresenter* fg = (FgPresenter*)(intptr_t)handle;
    float flow = flowScale > 0 ? (float)flowScale / 100.0f : 0.7f;
    fg_configure(fg, (uint32_t)multiplier, (uint32_t)targetRate, flow, refreshRate, sourceRate);
}

JNIEXPORT jlong JNICALL FG_FN(nativeRealFrames)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    uint64_t real = 0;
    fg_stats((FgPresenter*)(intptr_t)handle, &real, NULL);
    return (jlong)real;
}

JNIEXPORT jlong JNICALL FG_FN(nativeGeneratedFrames)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    uint64_t generated = 0;
    fg_stats((FgPresenter*)(intptr_t)handle, NULL, &generated);
    return (jlong)generated;
}

JNIEXPORT void JNICALL FG_FN(nativeDestroy)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    fg_destroy((FgPresenter*)(intptr_t)handle);
}
