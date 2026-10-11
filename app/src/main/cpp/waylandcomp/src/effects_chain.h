#ifndef EFFECTS_CHAIN_H
#define EFFECTS_CHAIN_H
/*
 * Screen-effect chain for the embedded Wayland compositor: the X11 Vulkan renderer's post
 * chain (the .frag files in app/src/main/cpp/winlator, VulkanRendererContext::recordUpscalePasses) run
 * between the composited scene and the output blit, with the same SPIR-V, push-constant
 * layouts, uniform ranges and pass order, so a saved preset looks the same on both backends.
 *
 * Present-pass hook order (vk_present.c, vkp_render):
 *     scene (all draws blitted 1:1 into a scene-sized image)
 *       -> vkp_effects_run()      this file: scaling -> effects, in the X11 order
 *       -> vkp_framegen_run()     frame generation (feat/wayland-framegen; inserted after this)
 *       -> update_map / blit      Fullscreen Mode + Screen Alignment, onto the swapchain image
 *       -> present
 * When nothing is on, vkp_effects_active() is 0 and vkp_render keeps its plain path (the draws
 * are blitted straight through the mapping; only the blit filter follows Linear/Nearest).
 *
 * Pass order (LOCKED to the X11 Vulkan renderer):
 *     scaling (SGSR / SGSR HQ / NIS / FSR EASU+RCAS / Sharpen=RCAS; None/Linear/Nearest = a
 *     filtered resize) -> FXAA -> Toon -> Colour -> CAS -> HDR -> NTSC -> CRT -> Debanding.
 * Scaling resizes the scene to its mapped output size (there is no render scale on Wayland);
 * every later pass runs at that resolution and ping-pongs between two targets.
 *
 * Settings come from any thread (JNI); the compositor thread snapshots them once per frame and
 * logs one `effects` line per change. Vulkan objects are created lazily on the first active frame.
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

/* Compositor thread, from dev_init: the device the chain builds its objects on. */
void vkp_effects_bind_device(VkDevice dev, VkPhysicalDevice pd,
                             const VkPhysicalDeviceMemoryProperties *memprops);

/* Compositor thread: pick up settings changed since the last call. Returns 1 when something
 * changed (the caller redraws even a static scene); logs the `effects` line. */
int vkp_effects_sync(void);

/* Compositor thread: 1 when the frame must go through the scene image + chain (any effect on,
 * or a spatial scaling mode). Modes None/Linear/Nearest alone are NOT active: the plain blit
 * handles them through vkp_effects_blit_filter(). */
int vkp_effects_active(void);

/* The filter for the scene -> output resize: NEAREST for scaling mode 2, else LINEAR. */
VkFilter vkp_effects_blit_filter(void);

/* THE HOOK. Records the chain into `cmd`. `scene` (scene_w x scene_h, R8G8B8A8_UNORM, in
 * TRANSFER_DST_OPTIMAL after the composite) -> the image to present, returned in
 * TRANSFER_SRC_OPTIMAL with its size in res_w / res_h. out_w/out_h = the scene's mapped size on
 * the output (what the scaling modes resize to; the caller maps the result over the whole scene
 * afterwards, so Fullscreen Mode / Alignment still apply after the chain). Returns `scene` itself
 * (transitioned to TRANSFER_SRC_OPTIMAL) when there is nothing to do or the chain is unavailable. */
VkImage vkp_effects_run(VkCommandBuffer cmd, VkImage scene, int scene_w, int scene_h,
                        int out_w, int out_h, int *res_w, int *res_h);

/* Compositor thread: drop every Vulkan object (device teardown / device lost). */
void vkp_effects_destroy(void);

/* Compositor thread, before vkp_effects_run(): the format of the scene image it will be handed and the
 * format its own targets use. R8G8B8A8 / R8G8B8A8 for every SDR frame (the default); A2B10G10R10 /
 * A2B10G10R10 while an HDR picture runs through it (hdr_compose.h). A target-format change rebuilds the
 * chain's objects on its next run (once per switch). */
void vkp_effects_set_formats(VkFormat scene_fmt, VkFormat target_fmt);

/* ---- settings (any thread; values 1:1 with the X11 Vulkan renderer's setters) ---- */
/* 0=None 1=Linear 2=Nearest 3=SGSR 4=FSR 5=FSR Fit 6=Sharpen 7=NIS 8=SGSR HQ */
void vkp_effects_set_scaling(int mode);
/* Slider 0..100: RCAS lobe scale (FSR/Sharpen), SGSR EdgeSharpness 0.5..4.5, NIS sharpness. */
void vkp_effects_set_upscale_sharpness(int pct);
/* CAS toggle + slider 0..100 (0 = the pass is off). */
void vkp_effects_set_cas(int enabled, int pct);
void vkp_effects_set_hdr(int enabled);
/* Terminal dither: slider 0..200 -> strength/100 LSBs. */
void vkp_effects_set_deband(int enabled, int pct);
/* Colour grade in slider units (brightness/contrast -100..100, gamma 0.5..3, saturation 0..200
 * percent; neutral = 0,0,1,100 turns the pass off) + the four shader toggles. */
void vkp_effects_set_screen(float brightness, float contrast, float gamma, float saturation,
                            int fxaa, int toon, int crt, int ntsc);
/* The Look the current controls match ("Custom" when none), for the log line only. */
void vkp_effects_set_look(const char *name);

#endif
