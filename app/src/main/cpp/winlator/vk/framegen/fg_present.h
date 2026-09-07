#pragma once

#include <android/native_window.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FgPresenter FgPresenter;

FgPresenter* fg_create(JNIEnv* env, jobject context, const char* driver_name,
                       ANativeWindow* output, uint32_t width, uint32_t height,
                       const char* cache_path, uint32_t multiplier, uint32_t target_rate,
                       float flow_scale, float refresh_rate, float source_rate);

ANativeWindow* fg_producer_window(FgPresenter* fg);

void fg_configure(FgPresenter* fg, uint32_t multiplier, uint32_t target_rate, float flow_scale,
                  float refresh_rate, float source_rate);

void fg_stats(FgPresenter* fg, uint64_t* real_frames, uint64_t* generated_frames);

void fg_destroy(FgPresenter* fg);

#ifdef __cplusplus
}
#endif
