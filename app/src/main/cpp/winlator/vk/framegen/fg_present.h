#pragma once

#include <android/native_window.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FgPresenter FgPresenter;

// Which interpolator the presenter drives. The presenter itself owns the
// swapchain, the pacing and the compositing; the engine only turns two real
// frames into the ones in between, so the two are interchangeable behind the
// same six calls (configure / needs_rebuild / prepare / plan / process /
// generate_into) and only one is ever instantiated at a time.
typedef enum FgEngine {
    FG_ENGINE_LSFG = 0,  // Lossless Scaling, driven by flow_scale (0..1 of the frame)
    FG_ENGINE_DIS = 1,   // Dense Inverse Search, driven by dis_min_side (pixels)
} FgEngine;

// The two engines take their quality setting in different units on purpose:
// LSFG scales the flow buffer by a fraction of the frame, DIS fixes the SHORTER
// side of the flow buffer in pixels so a preset costs the same on 720p and on
// 1440p. Keeping them as separate fields is what stops one engine's setting from
// being reinterpreted by the other when the user switches between them.
#define FG_DIS_MIN_SIDE_DEFAULT 180u

FgPresenter* fg_create(JNIEnv* env, jobject context, const char* driver_name,
                       ANativeWindow* output, uint32_t width, uint32_t height,
                       const char* cache_path, uint32_t multiplier, uint32_t target_rate,
                       float flow_scale, float refresh_rate, float source_rate,
                       uint32_t engine, uint32_t dis_min_side);

ANativeWindow* fg_producer_window(FgPresenter* fg);

void fg_configure(FgPresenter* fg, uint32_t multiplier, uint32_t target_rate, float flow_scale,
                  float refresh_rate, float source_rate, uint32_t engine, uint32_t dis_min_side);

void fg_stats(FgPresenter* fg, uint64_t* real_frames, uint64_t* generated_frames);

void fg_destroy(FgPresenter* fg);

#ifdef __cplusplus
}
#endif
