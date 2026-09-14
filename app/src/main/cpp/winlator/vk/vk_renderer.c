// Vulkan compositor for the X-server display path.
//
// Owns the entire native-side rendering state. Java JNI shims push scene snapshots and call
// frame submit; this file handles instance/device/swapchain/pipelines/sync.
//
// All vk* calls below resolve through vk_dispatch.h, which redirects them to the dlopen
// handle (system libvulkan or adrenotools-loaded Turnip) chosen at nativeCreate.
//
// Synchronization model:
//   - One graphics queue, serialized externally via VkRenderer::queue_mutex (any thread submits).
//   - VK_FRAMES_IN_FLIGHT in-flight frames, each with its own semaphores + fence + cmd buffer.
//   - Scene state guarded by VkRenderer::scene_mutex.
//   - Texture lifetime: created/uploaded synchronously (blocks ~ms); destroyed via per-frame
//     graveyard processed on the render thread, and tracked so renderer teardown can drain
//     native texture objects that Java handles have not explicitly destroyed yet.

#include "vk_state.h"
#include "vk_driver.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// SPIR-V shader byte arrays generated at build time by glslc + bin2c.cmake.
#include "shaders/window_vert.spv.h"
#include "shaders/window_frag.spv.h"
#include "shaders/cursor_frag.spv.h"
#include "shaders/quad_vert.spv.h"
#include "shaders/blit_frag.spv.h"
#include "shaders/effect_crt_frag.spv.h"
#include "shaders/effect_vivid_frag.spv.h"
#include "shaders/effect_hdr_frag.spv.h"
#include "shaders/effect_natural_frag.spv.h"
#include "shaders/effect_toon_frag.spv.h"
#include "shaders/effect_ntsc_frag.spv.h"
#include "shaders/effect_ntsc2_frag.spv.h"
#include "shaders/effect_coloradj_frag.spv.h"
#include "shaders/effect_colorgrade_frag.spv.h"
#include "shaders/effect_sharpen_frag.spv.h"
#include "shaders/effect_scanlines_frag.spv.h"
#include "shaders/effect_colorblind_frag.spv.h"
#include "shaders/effect_pixelate_frag.spv.h"
#include "shaders/sgsr1_frag.spv.h"

// ============================================================
// Forward decls
// ============================================================

static bool create_instance(VkRenderer* r);
static void destroy_debug_messenger(VkRenderer* r);
static bool pick_physical_device(VkRenderer* r);
static bool create_device(VkRenderer* r);
static void query_device_caps(VkRenderer* r);
static bool create_command_pool(VkRenderer* r);
static bool create_descriptor_pool(VkRenderer* r, uint32_t capacity);
static bool create_pipelines(VkRenderer* r);
static void destroy_pipelines(VkRenderer* r);
static bool create_swapchain(VkRenderer* r, uint32_t fallback_width, uint32_t fallback_height);
static void destroy_swapchain_resources(VkRenderer* r);
static void destroy_swapchain(VkRenderer* r);
static bool create_offscreen(VkRenderer* r, uint32_t w, uint32_t h, bool need_second);
static void destroy_offscreen(VkRenderer* r);
static bool create_sgsr1_resources(VkRenderer* r, uint32_t w, uint32_t h);
static void destroy_sgsr1_resources(VkRenderer* r);
static void destroy_composite_targets(VkRenderer* r);
static bool create_composite_targets(VkRenderer* r, uint32_t w, uint32_t h, uint32_t count);
static bool composite_format_supported(VkRenderer* r);
static void blit_composite_to_swapchain(VkRenderer* r, VkCommandBuffer cmd,
                                        VkCompositeTarget* src, VkImage dst);
static void create_lsfg(VkRenderer* r);
static void destroy_lsfg(VkRenderer* r);
static void create_dis(VkRenderer* r);
static void destroy_dis(VkRenderer* r);
static uint32_t framegen_extra_images(const VkRenderer* r);
static void framegen_rebuild_swapchain(VkRenderer* r);
static bool create_quad_vbo(VkRenderer* r);
static void destroy_quad_vbo(VkRenderer* r);
static bool is_plain_rotation_transform(VkSurfaceTransformFlagBitsKHR transform);
static bool is_quarter_turn_transform(VkSurfaceTransformFlagBitsKHR transform);
static void detach_graveyard_slot(VkRenderer* r, uint32_t slot_idx,
                                  VkTexture*** out_textures, uint32_t* out_count);
static void destroy_graveyard_textures(VkRenderer* r, VkTexture** textures, uint32_t count);
static bool record_and_submit_frame(VkRenderer* r);

// ============================================================
// Descriptor set allocation (called from vk_image.c)
// ============================================================

VkDescriptorSet vkr_alloc_descriptor_set(VkRenderer* r) {
    if (r->pipelines.sampler_set_layout == VK_NULL_HANDLE || r->descriptor_pool == VK_NULL_HANDLE) {
        VK_LOGE("vkr_alloc_descriptor_set called before pipelines/pool ready");
        return VK_NULL_HANDLE;
    }

    pthread_mutex_lock(&r->descriptor_mutex);
    if (r->descriptor_free_count > 0) {
        VkDescriptorSet set = r->descriptor_free_list[--r->descriptor_free_count];
        pthread_mutex_unlock(&r->descriptor_mutex);
        return set;
    }

    VkDescriptorSetAllocateInfo ai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = r->descriptor_pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &r->pipelines.sampler_set_layout;

    VkDescriptorSet set = VK_NULL_HANDLE;
    VkResult res = vkAllocateDescriptorSets(r->device, &ai, &set);
    if (res != VK_SUCCESS) {
        VK_LOGE("vkAllocateDescriptorSets failed: %d (pool used %u/%u)",
                res, r->descriptor_pool_used, r->descriptor_pool_capacity);
        pthread_mutex_unlock(&r->descriptor_mutex);
        return VK_NULL_HANDLE;
    }
    r->descriptor_pool_used++;
    pthread_mutex_unlock(&r->descriptor_mutex);
    return set;
}

void vkr_free_descriptor_set(VkRenderer* r, VkDescriptorSet set) {
    if (set == VK_NULL_HANDLE) return;
    pthread_mutex_lock(&r->descriptor_mutex);
    if (r->descriptor_free_count < r->descriptor_free_capacity) {
        r->descriptor_free_list[r->descriptor_free_count++] = set;
    } else {
        vkFreeDescriptorSets(r->device, r->descriptor_pool, 1, &set);
        if (r->descriptor_pool_used > 0) r->descriptor_pool_used--;
    }
    pthread_mutex_unlock(&r->descriptor_mutex);
}

// ============================================================
// Instance
// ============================================================

static bool has_extension(const VkExtensionProperties* exts, uint32_t count, const char* name) {
    for (uint32_t i = 0; i < count; i++) {
        if (strcmp(exts[i].extensionName, name) == 0) return true;
    }
    return false;
}

static bool has_layer(const VkLayerProperties* layers, uint32_t count, const char* name) {
    for (uint32_t i = 0; i < count; i++) {
        if (strcmp(layers[i].layerName, name) == 0) return true;
    }
    return false;
}

static const char* debug_severity_name(VkDebugUtilsMessageSeverityFlagBitsEXT severity) {
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) return "error";
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) return "warning";
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) return "info";
    return "verbose";
}

static VKAPI_ATTR VkBool32 VKAPI_CALL vvl_debug_callback(
        VkDebugUtilsMessageSeverityFlagBitsEXT severity,
        VkDebugUtilsMessageTypeFlagsEXT type,
        const VkDebugUtilsMessengerCallbackDataEXT* data,
        void* user) {
    (void)type;
    (void)user;
    const char* message = (data && data->pMessage) ? data->pMessage : "(no message)";
    const char* severity_name = debug_severity_name(severity);
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) {
        VK_LOGE("VVL %s: %s", severity_name, message);
    } else if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {
        VK_LOGW("VVL %s: %s", severity_name, message);
    } else {
        VK_LOGI("VVL %s: %s", severity_name, message);
    }
    return VK_FALSE;
}

static bool create_instance(VkRenderer* r) {
    uint32_t ext_count = 0;
    vkEnumerateInstanceExtensionProperties(NULL, &ext_count, NULL);
    VkExtensionProperties* exts = calloc(ext_count, sizeof(VkExtensionProperties));
    vkEnumerateInstanceExtensionProperties(NULL, &ext_count, exts);

    const char* required_exts[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    for (uint32_t i = 0; i < sizeof(required_exts) / sizeof(required_exts[0]); i++) {
        if (!has_extension(exts, ext_count, required_exts[i])) {
            VK_LOGE("Missing required instance extension: %s", required_exts[i]);
            free(exts);
            return false;
        }
    }

    const char* enabled_layers[1] = {0};
    uint32_t enabled_layer_count = 0;
    if (r->validation_enabled) {
        uint32_t layer_count = 0;
        vkEnumerateInstanceLayerProperties(&layer_count, NULL);
        VkLayerProperties* layers = calloc(layer_count, sizeof(VkLayerProperties));
        vkEnumerateInstanceLayerProperties(&layer_count, layers);
        if (has_layer(layers, layer_count, "VK_LAYER_KHRONOS_validation")) {
            enabled_layers[enabled_layer_count++] = "VK_LAYER_KHRONOS_validation";
            VK_LOGI("Vulkan validation layer enabled");
        } else {
            VK_LOGW("Vulkan validation layer requested but VK_LAYER_KHRONOS_validation is unavailable");
            r->validation_enabled = false;
        }
        free(layers);
    }

    const char* enabled_exts[4] = {0};
    uint32_t enabled_ext_count = 0;
    for (uint32_t i = 0; i < sizeof(required_exts) / sizeof(required_exts[0]); i++) {
        enabled_exts[enabled_ext_count++] = required_exts[i];
    }
    if (r->validation_enabled) {
        if (has_extension(exts, ext_count, VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            enabled_exts[enabled_ext_count++] = VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
            r->debug_utils_enabled = true;
        } else {
            VK_LOGW("VK_EXT_debug_utils unavailable; validation remains enabled without callback logging");
        }
    }
    free(exts);

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "WinNative";
    app.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app.pEngineName = "WinNativeVk";
    app.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo ic = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ic.pApplicationInfo = &app;
    ic.enabledExtensionCount = enabled_ext_count;
    ic.ppEnabledExtensionNames = enabled_exts;
    ic.enabledLayerCount = enabled_layer_count;
    ic.ppEnabledLayerNames = enabled_layers;

    VkResult res = vkCreateInstance(&ic, NULL, &r->instance);
    if (res != VK_SUCCESS) {
        VK_LOGE("vkCreateInstance failed: %d", res);
        return false;
    }

    if (!vkd_load_instance(r->instance)) {
        VK_LOGE("vkd_load_instance failed");
        return false;
    }

    if (r->debug_utils_enabled) {
        r->fnCreateDebugUtilsMessenger =
                (PFN_vkCreateDebugUtilsMessengerEXT)vkGetInstanceProcAddr(
                        r->instance, "vkCreateDebugUtilsMessengerEXT");
        r->fnDestroyDebugUtilsMessenger =
                (PFN_vkDestroyDebugUtilsMessengerEXT)vkGetInstanceProcAddr(
                        r->instance, "vkDestroyDebugUtilsMessengerEXT");
        if (r->fnCreateDebugUtilsMessenger && r->fnDestroyDebugUtilsMessenger) {
            VkDebugUtilsMessengerCreateInfoEXT dc = {
                VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT
            };
            dc.messageSeverity =
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
            dc.messageType =
                    VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
            dc.pfnUserCallback = vvl_debug_callback;
            res = r->fnCreateDebugUtilsMessenger(r->instance, &dc, NULL, &r->debug_messenger);
            if (res != VK_SUCCESS) {
                VK_LOGW("vkCreateDebugUtilsMessengerEXT failed: %d", res);
                r->debug_utils_enabled = false;
            }
        } else {
            VK_LOGW("VK_EXT_debug_utils entry points unavailable");
            r->debug_utils_enabled = false;
        }
    }
    return true;
}

static void destroy_debug_messenger(VkRenderer* r) {
    if (r->debug_messenger != VK_NULL_HANDLE && r->fnDestroyDebugUtilsMessenger) {
        r->fnDestroyDebugUtilsMessenger(r->instance, r->debug_messenger, NULL);
        r->debug_messenger = VK_NULL_HANDLE;
    }
}

// ============================================================
// Physical device
// ============================================================

static bool pick_physical_device(VkRenderer* r) {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(r->instance, &count, NULL);
    if (count == 0) return false;

    VkPhysicalDevice* devices = calloc(count, sizeof(VkPhysicalDevice));
    vkEnumeratePhysicalDevices(r->instance, &count, devices);

    // Score: prefer DISCRETE > INTEGRATED > anything; require graphics queue + surface support is checked later.
    int best_score = -1;
    int best_idx = -1;
    for (uint32_t i = 0; i < count; i++) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(devices[i], &p);
        int s = (p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) ? 1000
              : (p.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) ? 100 : 1;
        if (s > best_score) { best_score = s; best_idx = (int)i; }
    }
    if (best_idx < 0) { free(devices); return false; }

    r->physical_device = devices[best_idx];
    free(devices);

    vkGetPhysicalDeviceMemoryProperties(r->physical_device, &r->mem_props);

    uint32_t qf_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(r->physical_device, &qf_count, NULL);
    VkQueueFamilyProperties* qf = calloc(qf_count, sizeof(VkQueueFamilyProperties));
    vkGetPhysicalDeviceQueueFamilyProperties(r->physical_device, &qf_count, qf);

    r->graphics_queue_family = UINT32_MAX;
    for (uint32_t i = 0; i < qf_count; i++) {
        VK_LOGI("queue family %u: count=%u%s%s%s%s", i, qf[i].queueCount,
                (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) ? " graphics" : "",
                (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) ? " compute" : "",
                (qf[i].queueFlags & VK_QUEUE_TRANSFER_BIT) ? " transfer" : "",
                (qf[i].queueFlags & VK_QUEUE_SPARSE_BINDING_BIT) ? " sparse" : "");
        if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)
            && r->graphics_queue_family == UINT32_MAX) {
            r->graphics_queue_family = i;
        }
    }
    {
        VkPhysicalDeviceProperties dp;
        vkGetPhysicalDeviceProperties(r->physical_device, &dp);
        VK_LOGI("device \"%s\" api=%u.%u.%u driver=0x%x families=%u chosen=%u",
                dp.deviceName, VK_VERSION_MAJOR(dp.apiVersion),
                VK_VERSION_MINOR(dp.apiVersion), VK_VERSION_PATCH(dp.apiVersion),
                dp.driverVersion, qf_count, r->graphics_queue_family);
    }
    free(qf);
    if (r->graphics_queue_family == UINT32_MAX) return false;
    return true;
}

// ============================================================
// Device
// ============================================================

static bool create_device(VkRenderer* r) {
    uint32_t ext_count = 0;
    vkEnumerateDeviceExtensionProperties(r->physical_device, NULL, &ext_count, NULL);
    VkExtensionProperties* exts = calloc(ext_count, sizeof(VkExtensionProperties));
    vkEnumerateDeviceExtensionProperties(r->physical_device, NULL, &ext_count, exts);

    bool has_swap = has_extension(exts, ext_count, VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    if (!has_swap) {
        VK_LOGE("VK_KHR_swapchain not supported");
        free(exts);
        return false;
    }

    bool has_ahb = has_extension(exts, ext_count, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
    bool has_extmem = has_extension(exts, ext_count, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
    bool has_dedicated = has_extension(exts, ext_count, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME);
    bool has_get_mem_req2 = has_extension(exts, ext_count, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);
    bool has_ycbcr = has_extension(exts, ext_count, VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME);
    bool has_extmem_caps = has_extension(exts, ext_count, VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME);
    bool has_queue_fam = has_extension(exts, ext_count, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    bool has_cubic = has_extension(exts, ext_count, VK_EXT_FILTER_CUBIC_EXTENSION_NAME);
    bool has_shader_f16 = has_extension(exts, ext_count,
                                        VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);

    free(exts);

    const char* enable[16];
    uint32_t enable_n = 0;
    enable[enable_n++] = VK_KHR_SWAPCHAIN_EXTENSION_NAME;

    bool ahb_ok = has_ahb && has_extmem && has_dedicated && has_get_mem_req2;
    if (ahb_ok) {
        enable[enable_n++] = VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
        enable[enable_n++] = VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME;
        enable[enable_n++] = VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME;
        enable[enable_n++] = VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;
        if (has_queue_fam) enable[enable_n++] = VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME;
    }
    if (has_ycbcr) enable[enable_n++] = VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME;
    if (has_cubic) enable[enable_n++] = VK_EXT_FILTER_CUBIC_EXTENSION_NAME;
    (void)has_extmem_caps;

    VkPhysicalDeviceShaderFloat16Int8FeaturesKHR f16_feat = {
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR
    };
    bool enable_f16 = false;
    if (has_shader_f16) {
        VkPhysicalDeviceShaderFloat16Int8FeaturesKHR probe = {
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR
        };
        VkPhysicalDeviceFeatures2 probe2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        probe2.pNext = &probe;
        vkGetPhysicalDeviceFeatures2(r->physical_device, &probe2);
        enable_f16 = probe.shaderFloat16 == VK_TRUE;
        if (enable_f16) {
            f16_feat.shaderFloat16 = VK_TRUE;
            enable[enable_n++] = VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME;
        }
    }
    r->ext_shader_float16 = enable_f16;
    VK_LOGI("shaderFloat16: extension=%d enabled=%d", has_shader_f16, enable_f16);

    r->ext_ahb = ahb_ok;
    r->ext_ycbcr = has_ycbcr;
    r->ext_filter_cubic = has_cubic;
    VK_LOGI("AHB Vulkan device support: android_hardware_buffer=%d external_memory=%d dedicated=%d get_memory_requirements2=%d queue_family_foreign=%d enabled=%d",
            has_ahb, has_extmem, has_dedicated, has_get_mem_req2, has_queue_fam, r->ext_ahb);
    if (!r->ext_ahb) {
        VK_LOGW("AHB Vulkan import disabled; one or more required device extensions are missing");
    }

    float qprio = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = r->graphics_queue_family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &qprio;

    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr_feat = {
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES
    };
    ycbcr_feat.samplerYcbcrConversion = has_ycbcr ? VK_TRUE : VK_FALSE;

    void* feature_chain = NULL;
    if (has_ycbcr) {
        ycbcr_feat.pNext = feature_chain;
        feature_chain = &ycbcr_feat;
    }
    if (enable_f16) {
        f16_feat.pNext = feature_chain;
        feature_chain = &f16_feat;
    }

    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = feature_chain;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = enable_n;
    dci.ppEnabledExtensionNames = enable;

    if (vkCreateDevice(r->physical_device, &dci, NULL, &r->device) != VK_SUCCESS) {
        VK_LOGE("vkCreateDevice failed");
        return false;
    }
    vkGetDeviceQueue(r->device, r->graphics_queue_family, 0, &r->graphics_queue);

    if (r->ext_ahb) {
        r->fnGetAhbProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
            vkGetDeviceProcAddr(r->device, "vkGetAndroidHardwareBufferPropertiesANDROID");
        if (!r->fnGetAhbProps) {
            VK_LOGW("AHB Vulkan import disabled; vkGetAndroidHardwareBufferPropertiesANDROID is unavailable");
            r->ext_ahb = false;
        }
    }
    if (r->ext_ycbcr) {
        r->fnCreateYcbcr = (PFN_vkCreateSamplerYcbcrConversion)
            vkGetDeviceProcAddr(r->device, "vkCreateSamplerYcbcrConversion");
        r->fnDestroyYcbcr = (PFN_vkDestroySamplerYcbcrConversion)
            vkGetDeviceProcAddr(r->device, "vkDestroySamplerYcbcrConversion");
        // Fallback to KHR variants if 1.1 core entry points aren't exposed by the loader.
        if (!r->fnCreateYcbcr) {
            r->fnCreateYcbcr = (PFN_vkCreateSamplerYcbcrConversion)
                vkGetDeviceProcAddr(r->device, "vkCreateSamplerYcbcrConversionKHR");
        }
        if (!r->fnDestroyYcbcr) {
            r->fnDestroyYcbcr = (PFN_vkDestroySamplerYcbcrConversion)
                vkGetDeviceProcAddr(r->device, "vkDestroySamplerYcbcrConversionKHR");
        }
        if (!r->fnCreateYcbcr || !r->fnDestroyYcbcr) {
            VK_LOGW("Ycbcr conversion entry points unavailable; AHB import limited to RGB formats");
            r->ext_ycbcr = false;
        }
    }

    VK_LOGI("Vulkan device created (AHB=%d, Ycbcr=%d)", r->ext_ahb, r->ext_ycbcr);
    return true;
}

// ============================================================
// Device capability probe
// ============================================================

static void query_device_caps(VkRenderer* r) {
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(r->physical_device, &props);
    r->caps.vendor_id      = props.vendorID;
    r->caps.device_id      = props.deviceID;
    r->caps.driver_version = props.driverVersion;
    r->caps.is_adreno      = (props.vendorID == 0x5143);  // Qualcomm
    r->caps.limits         = props.limits;

    // Descriptor pool capacity. Vulkan doesn't spec-bound pool size — the only ceiling
    // is driver memory, and each combined-image-sampler set is ~100-200 bytes on Adreno,
    // so 4096 sets is ~1 MB upfront. Pick a number high enough that an X server with
    // hundreds of short-lived pixmaps can't realistically exhaust it. Grow-on-exhaust
    // is the proper unbounded answer and remains a separate TODO.
    r->caps.descriptor_pool_capacity = 4096;

    // Offscreen color format. Prefer BGRA8 to match the upload format (no shader swizzle),
    // fall back to RGBA8 if the driver doesn't expose BGRA as a sampled color attachment
    // in OPTIMAL tiling. RGBA8 is spec-guaranteed for both features.
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    const VkFormat offscreen_candidates[2] = {
        VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM
    };
    r->caps.offscreen_format = VK_FORMAT_R8G8B8A8_UNORM;
    for (int i = 0; i < 2; i++) {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(r->physical_device, offscreen_candidates[i], &fp);
        if ((fp.optimalTilingFeatures & need) == need) {
            r->caps.offscreen_format = offscreen_candidates[i];
            break;
        }
    }

    // CPU-uploaded texture format. RGBA8 is spec-guaranteed; BGRA8 is optional.
    const VkFormatFeatureFlags upload_need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                                           | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    r->caps.upload_format = VK_FORMAT_R8G8B8A8_UNORM;
    r->caps.upload_needs_bgra_swizzle = true;
    {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(r->physical_device, VK_FORMAT_B8G8R8A8_UNORM, &fp);
        if ((fp.optimalTilingFeatures & upload_need) == upload_need) {
            r->caps.upload_format = VK_FORMAT_B8G8R8A8_UNORM;
            r->caps.upload_needs_bgra_swizzle = false;
        }
    }

    // AHB BGRA8 importability — diagnostic only; per-import paths still probe themselves.
    r->caps.ahb_bgra_supported = false;
    if (r->ext_ahb) {
        VkPhysicalDeviceExternalImageFormatInfo ext = {
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO
        };
        ext.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

        VkPhysicalDeviceImageFormatInfo2 ifi = {
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2
        };
        ifi.pNext  = &ext;
        ifi.format = VK_FORMAT_B8G8R8A8_UNORM;
        ifi.type   = VK_IMAGE_TYPE_2D;
        ifi.tiling = VK_IMAGE_TILING_OPTIMAL;
        ifi.usage  = VK_IMAGE_USAGE_SAMPLED_BIT;

        VkExternalImageFormatProperties ext_out = {
            VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES
        };
        VkImageFormatProperties2 out = { VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2 };
        out.pNext = &ext_out;

        // The 1.1 core entry point isn't statically exported by the Android Vulkan loader on all
        // NDK targets; resolve dynamically and fall back to the KHR alias.
        PFN_vkGetPhysicalDeviceImageFormatProperties2 fnGetIfp2 =
            (PFN_vkGetPhysicalDeviceImageFormatProperties2)
                vkGetInstanceProcAddr(r->instance, "vkGetPhysicalDeviceImageFormatProperties2");
        if (!fnGetIfp2) {
            fnGetIfp2 = (PFN_vkGetPhysicalDeviceImageFormatProperties2)
                vkGetInstanceProcAddr(r->instance, "vkGetPhysicalDeviceImageFormatProperties2KHR");
        }
        if (fnGetIfp2 && fnGetIfp2(r->physical_device, &ifi, &out) == VK_SUCCESS) {
            r->caps.ahb_bgra_supported =
                (ext_out.externalMemoryProperties.externalMemoryFeatures
                 & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0;
        }
    }

    VK_LOGI("Device caps: vendor=0x%x device=0x%x driver=0x%x adreno=%d offscreen=%s upload=%s ahb_bgra=%d desc_pool=%u",
            r->caps.vendor_id, r->caps.device_id, r->caps.driver_version,
            r->caps.is_adreno,
            r->caps.offscreen_format == VK_FORMAT_B8G8R8A8_UNORM ? "BGRA8" : "RGBA8",
            r->caps.upload_format == VK_FORMAT_B8G8R8A8_UNORM ? "BGRA8" : "RGBA8(swizzle)",
            r->caps.ahb_bgra_supported,
            r->caps.descriptor_pool_capacity);
}

// ============================================================
// Command pool + per-frame
// ============================================================

static bool create_command_pool(VkRenderer* r) {
    VkCommandPoolCreateInfo ci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    ci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    ci.queueFamilyIndex = r->graphics_queue_family;
    if (vkCreateCommandPool(r->device, &ci, NULL, &r->cmd_pool) != VK_SUCCESS) return false;

    VkCommandBufferAllocateInfo ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = r->cmd_pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;

    VkSemaphoreCreateInfo si = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;

    for (uint32_t i = 0; i < VK_FRAMES_IN_FLIGHT; i++) {
        VkFrame* f = &r->frames[i];
        if (vkAllocateCommandBuffers(r->device, &ai, &f->cmd) != VK_SUCCESS) return false;
        if (vkCreateSemaphore(r->device, &si, NULL, &f->image_available) != VK_SUCCESS) return false;
        for (uint32_t g = 0; g < VKR_LSFG_MAX_GENERATIONS; g++) {
            if (vkCreateSemaphore(r->device, &si, NULL, &f->image_available_gen[g]) != VK_SUCCESS) {
                return false;
            }
        }
        if (vkCreateFence(r->device, &fi, NULL, &f->in_flight) != VK_SUCCESS) return false;
    }
    return true;
}

// ============================================================
// Descriptor pool
// ============================================================

static bool create_descriptor_pool(VkRenderer* r, uint32_t capacity) {
    VkDescriptorPoolSize ps = {0};
    ps.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    ps.descriptorCount = capacity;

    VkDescriptorPoolCreateInfo ci = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    ci.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.maxSets = capacity;
    ci.poolSizeCount = 1;
    ci.pPoolSizes = &ps;
    if (vkCreateDescriptorPool(r->device, &ci, NULL, &r->descriptor_pool) != VK_SUCCESS) {
        VK_LOGE("vkCreateDescriptorPool failed");
        return false;
    }
    r->descriptor_pool_capacity = capacity;
    r->descriptor_pool_used = 0;
    uint32_t free_cap = capacity < 512 ? capacity : 512;
    r->descriptor_free_list = calloc(free_cap, sizeof(VkDescriptorSet));
    r->descriptor_free_count = 0;
    r->descriptor_free_capacity = r->descriptor_free_list ? free_cap : 0;
    return true;
}

// ============================================================
// Pipelines
// ============================================================

static VkShaderModule load_shader_module(VkRenderer* r, const uint32_t* code, size_t code_size) {
    VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = code_size;
    ci.pCode = code;
    VkShaderModule m;
    if (vkCreateShaderModule(r->device, &ci, NULL, &m) != VK_SUCCESS) {
        VK_LOGE("vkCreateShaderModule failed (size=%zu)", code_size);
        return VK_NULL_HANDLE;
    }
    return m;
}

static bool create_render_passes(VkRenderer* r) {
    // Swapchain pass: color attachment, presentation final layout.
    {
        VkAttachmentDescription att = {0};
        att.format = r->swapchain_format;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};

        VkSubpassDescription sp = {0};
        sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sp.colorAttachmentCount = 1;
        sp.pColorAttachments = &ref;

        VkSubpassDependency dep = {0};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.srcAccessMask = 0;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo rci = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rci.attachmentCount = 1;
        rci.pAttachments = &att;
        rci.subpassCount = 1;
        rci.pSubpasses = &sp;
        rci.dependencyCount = 1;
        rci.pDependencies = &dep;
        if (vkCreateRenderPass(r->device, &rci, NULL, &r->pipelines.swapchain_pass) != VK_SUCCESS) {
            return false;
        }
    }

    // Offscreen pass: color attachment, final shader-read layout.
    {
        VkAttachmentDescription att = {0};
        att.format = r->caps.offscreen_format;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};

        VkSubpassDescription sp = {0};
        sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sp.colorAttachmentCount = 1;
        sp.pColorAttachments = &ref;

        VkSubpassDependency deps[2] = {0};
        deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass = 0;
        deps[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].srcSubpass = 0;
        deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

        VkRenderPassCreateInfo rci = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rci.attachmentCount = 1;
        rci.pAttachments = &att;
        rci.subpassCount = 1;
        rci.pSubpasses = &sp;
        rci.dependencyCount = 2;
        rci.pDependencies = deps;
        if (vkCreateRenderPass(r->device, &rci, NULL, &r->pipelines.offscreen_pass) != VK_SUCCESS) {
            return false;
        }
    }

    {
        VkAttachmentDescription att = {0};
        att.format = r->swapchain_format;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_GENERAL;

        VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};

        VkSubpassDescription sp = {0};
        sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sp.colorAttachmentCount = 1;
        sp.pColorAttachments = &ref;

        VkSubpassDependency deps[2] = {0};
        deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass = 0;
        deps[0].srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT
                             | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
        deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].srcSubpass = 0;
        deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT
                             | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;

        VkRenderPassCreateInfo rci = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rci.attachmentCount = 1;
        rci.pAttachments = &att;
        rci.subpassCount = 1;
        rci.pSubpasses = &sp;
        rci.dependencyCount = 2;
        rci.pDependencies = deps;
        if (vkCreateRenderPass(r->device, &rci, NULL, &r->pipelines.composite_pass) != VK_SUCCESS) {
            return false;
        }
    }

    return true;
}

static bool create_pipeline_layouts(VkRenderer* r) {
    VkDescriptorSetLayoutBinding bind = {0};
    bind.binding = 0;
    bind.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bind.descriptorCount = 1;
    bind.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo dlci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dlci.bindingCount = 1;
    dlci.pBindings = &bind;
    if (vkCreateDescriptorSetLayout(r->device, &dlci, NULL, &r->pipelines.sampler_set_layout) != VK_SUCCESS) {
        return false;
    }

    // Window/cursor: push constants = float xform[6] + vec2 viewSize + vec4 uvRect
    // + int swapRB = 52 bytes
    VkPushConstantRange pcr_window = {0};
    pcr_window.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    pcr_window.offset = 0;
    pcr_window.size = 52;

    VkPipelineLayoutCreateInfo plci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &r->pipelines.sampler_set_layout;
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr_window;
    if (vkCreatePipelineLayout(r->device, &plci, NULL, &r->pipelines.window_layout) != VK_SUCCESS) {
        return false;
    }

    // Effect: push constants = vec2 resolution + 4 floats (sat, contrast, sharp, mode) = 24 bytes.
    // Other effect shaders only declare the first 16 bytes and ignore the rest.
    VkPushConstantRange pcr_effect = {0};
    pcr_effect.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    pcr_effect.offset = 0;
    pcr_effect.size = 24;

    VkPipelineLayoutCreateInfo plci2 = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci2.setLayoutCount = 1;
    plci2.pSetLayouts = &r->pipelines.sampler_set_layout;
    plci2.pushConstantRangeCount = 1;
    plci2.pPushConstantRanges = &pcr_effect;
    if (vkCreatePipelineLayout(r->device, &plci2, NULL, &r->pipelines.effect_layout) != VK_SUCCESS) {
        return false;
    }

    return true;
}

static VkPipeline create_graphics_pipeline(
    VkRenderer* r,
    VkShaderModule vs, VkShaderModule fs,
    VkPipelineLayout layout,
    VkRenderPass pass,
    bool has_vertex_input,
    bool blend_alpha,
    const VkSpecializationInfo* fs_spec)
{
    VkPipelineShaderStageCreateInfo stages[2] = {0};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vs;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fs;
    stages[1].pName = "main";
    stages[1].pSpecializationInfo = fs_spec;

    VkVertexInputBindingDescription vbind = {0};
    vbind.binding = 0;
    vbind.stride = sizeof(float) * 2;
    vbind.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    VkVertexInputAttributeDescription vattr = {0};
    vattr.location = 0;
    vattr.binding = 0;
    vattr.format = VK_FORMAT_R32G32_SFLOAT;
    vattr.offset = 0;

    VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    if (has_vertex_input) {
        vi.vertexBindingDescriptionCount = 1;
        vi.pVertexBindingDescriptions = &vbind;
        vi.vertexAttributeDescriptionCount = 1;
        vi.pVertexAttributeDescriptions = &vattr;
    }

    VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = has_vertex_input ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1;
    vp.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blend = {0};
    blend.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                         | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    blend.blendEnable = blend_alpha ? VK_TRUE : VK_FALSE;
    blend.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    blend.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blend.colorBlendOp = VK_BLEND_OP_ADD;
    blend.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    blend.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
    blend.alphaBlendOp = VK_BLEND_OP_ADD;

    VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &blend;

    VkDynamicState dyn[3] = {
        VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR
    };
    VkPipelineDynamicStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2;
    ds.pDynamicStates = dyn;

    VkGraphicsPipelineCreateInfo gpi = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gpi.stageCount = 2;
    gpi.pStages = stages;
    gpi.pVertexInputState = &vi;
    gpi.pInputAssemblyState = &ia;
    gpi.pViewportState = &vp;
    gpi.pRasterizationState = &rs;
    gpi.pMultisampleState = &ms;
    gpi.pColorBlendState = &cb;
    gpi.pDynamicState = &ds;
    gpi.layout = layout;
    gpi.renderPass = pass;
    gpi.subpass = 0;

    VkPipeline pipe = VK_NULL_HANDLE;
    if (vkCreateGraphicsPipelines(r->device, VK_NULL_HANDLE, 1, &gpi, NULL, &pipe) != VK_SUCCESS) {
        VK_LOGE("vkCreateGraphicsPipelines failed");
        return VK_NULL_HANDLE;
    }
    return pipe;
}

static bool create_pipelines(VkRenderer* r) {
    if (!create_render_passes(r)) return false;
    if (!create_pipeline_layouts(r)) return false;

    VkShaderModule vs_window = load_shader_module(r, window_vert, window_vert_size);
    VkShaderModule fs_window = load_shader_module(r, window_frag, window_frag_size);
    VkShaderModule fs_cursor = load_shader_module(r, cursor_frag, cursor_frag_size);
    VkShaderModule vs_quad   = load_shader_module(r, quad_vert,   quad_vert_size);
    VkShaderModule fs_blit   = load_shader_module(r, blit_frag,   blit_frag_size);
    VkShaderModule fs_crt    = load_shader_module(r, effect_crt_frag,    effect_crt_frag_size);
    VkShaderModule fs_vivid  = load_shader_module(r, effect_vivid_frag,  effect_vivid_frag_size);
    VkShaderModule fs_hdr    = load_shader_module(r, effect_hdr_frag,    effect_hdr_frag_size);
    VkShaderModule fs_natural= load_shader_module(r, effect_natural_frag,effect_natural_frag_size);
    VkShaderModule fs_toon   = load_shader_module(r, effect_toon_frag,   effect_toon_frag_size);
    VkShaderModule fs_ntsc   = load_shader_module(r, effect_ntsc_frag,   effect_ntsc_frag_size);
    VkShaderModule fs_ntsc2  = load_shader_module(r, effect_ntsc2_frag,  effect_ntsc2_frag_size);
    VkShaderModule fs_coloradj = load_shader_module(r, effect_coloradj_frag, effect_coloradj_frag_size);
    VkShaderModule fs_colorgrade = load_shader_module(r, effect_colorgrade_frag, effect_colorgrade_frag_size);
    VkShaderModule fs_sharpen = load_shader_module(r, effect_sharpen_frag, effect_sharpen_frag_size);
    VkShaderModule fs_scanlines = load_shader_module(r, effect_scanlines_frag, effect_scanlines_frag_size);
    VkShaderModule fs_colorblind = load_shader_module(r, effect_colorblind_frag, effect_colorblind_frag_size);
    VkShaderModule fs_pixelate = load_shader_module(r, effect_pixelate_frag, effect_pixelate_frag_size);
    VkShaderModule fs_sgsr1 = load_shader_module(r, sgsr1_frag, sgsr1_frag_size);
    if (!vs_window || !fs_window || !fs_cursor || !vs_quad || !fs_blit
        || !fs_crt || !fs_vivid || !fs_hdr || !fs_natural
        || !fs_toon || !fs_ntsc || !fs_ntsc2 || !fs_coloradj
        || !fs_colorgrade || !fs_sharpen || !fs_scanlines
        || !fs_colorblind || !fs_pixelate
        || !fs_sgsr1) {
        return false;
    }

    r->pipelines.window_pipeline = create_graphics_pipeline(
        r, vs_window, fs_window, r->pipelines.window_layout, r->pipelines.swapchain_pass,
        true, false, NULL);
    r->pipelines.cursor_pipeline = create_graphics_pipeline(
        r, vs_window, fs_cursor, r->pipelines.window_layout, r->pipelines.swapchain_pass,
        true, true, NULL);
    r->pipelines.blit_pipeline = create_graphics_pipeline(
        r, vs_quad, fs_blit, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_CRT] = create_graphics_pipeline(
        r, vs_quad, fs_crt, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_VIVID] = create_graphics_pipeline(
        r, vs_quad, fs_vivid, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_HDR] = create_graphics_pipeline(
        r, vs_quad, fs_hdr, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_NATURAL] = create_graphics_pipeline(
        r, vs_quad, fs_natural, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_SGSR1] = create_graphics_pipeline(
        r, vs_quad, fs_sgsr1, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_TOON] = create_graphics_pipeline(
        r, vs_quad, fs_toon, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_NTSC] = create_graphics_pipeline(
        r, vs_quad, fs_ntsc, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_COLORADJ] = create_graphics_pipeline(
        r, vs_quad, fs_coloradj, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_COLORGRADE] = create_graphics_pipeline(
        r, vs_quad, fs_colorgrade, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_SHARPEN] = create_graphics_pipeline(
        r, vs_quad, fs_sharpen, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_SCANLINES] = create_graphics_pipeline(
        r, vs_quad, fs_scanlines, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_NTSC2] = create_graphics_pipeline(
        r, vs_quad, fs_ntsc2, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_COLORBLIND] = create_graphics_pipeline(
        r, vs_quad, fs_colorblind, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.effect_pipelines[VK_EFFECT_PIXELATE] = create_graphics_pipeline(
        r, vs_quad, fs_pixelate, r->pipelines.effect_layout, r->pipelines.swapchain_pass,
        false, false, NULL);
    r->pipelines.offscreen_window_pipeline = create_graphics_pipeline(
        r, vs_window, fs_window, r->pipelines.window_layout, r->pipelines.offscreen_pass,
        true, false, NULL);
    r->pipelines.offscreen_cursor_pipeline = create_graphics_pipeline(
        r, vs_window, fs_cursor, r->pipelines.window_layout, r->pipelines.offscreen_pass,
        true, true, NULL);
    r->pipelines.offscreen_blit_pipeline = create_graphics_pipeline(
        r, vs_quad, fs_blit, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_CRT] = create_graphics_pipeline(
        r, vs_quad, fs_crt, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_VIVID] = create_graphics_pipeline(
        r, vs_quad, fs_vivid, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_HDR] = create_graphics_pipeline(
        r, vs_quad, fs_hdr, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_NATURAL] = create_graphics_pipeline(
        r, vs_quad, fs_natural, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_SGSR1] = create_graphics_pipeline(
        r, vs_quad, fs_sgsr1, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_TOON] = create_graphics_pipeline(
        r, vs_quad, fs_toon, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_NTSC] = create_graphics_pipeline(
        r, vs_quad, fs_ntsc, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_COLORADJ] = create_graphics_pipeline(
        r, vs_quad, fs_coloradj, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_COLORGRADE] = create_graphics_pipeline(
        r, vs_quad, fs_colorgrade, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_SHARPEN] = create_graphics_pipeline(
        r, vs_quad, fs_sharpen, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_SCANLINES] = create_graphics_pipeline(
        r, vs_quad, fs_scanlines, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_NTSC2] = create_graphics_pipeline(
        r, vs_quad, fs_ntsc2, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_COLORBLIND] = create_graphics_pipeline(
        r, vs_quad, fs_colorblind, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);
    r->pipelines.offscreen_effect_pipelines[VK_EFFECT_PIXELATE] = create_graphics_pipeline(
        r, vs_quad, fs_pixelate, r->pipelines.effect_layout, r->pipelines.offscreen_pass,
        false, false, NULL);

    vkDestroyShaderModule(r->device, vs_window, NULL);
    vkDestroyShaderModule(r->device, fs_window, NULL);
    vkDestroyShaderModule(r->device, fs_cursor, NULL);
    vkDestroyShaderModule(r->device, vs_quad, NULL);
    vkDestroyShaderModule(r->device, fs_blit, NULL);
    vkDestroyShaderModule(r->device, fs_crt, NULL);
    vkDestroyShaderModule(r->device, fs_vivid, NULL);
    vkDestroyShaderModule(r->device, fs_hdr, NULL);
    vkDestroyShaderModule(r->device, fs_natural, NULL);
    vkDestroyShaderModule(r->device, fs_toon, NULL);
    vkDestroyShaderModule(r->device, fs_ntsc, NULL);
    vkDestroyShaderModule(r->device, fs_ntsc2, NULL);
    vkDestroyShaderModule(r->device, fs_coloradj, NULL);
    vkDestroyShaderModule(r->device, fs_colorgrade, NULL);
    vkDestroyShaderModule(r->device, fs_sharpen, NULL);
    vkDestroyShaderModule(r->device, fs_scanlines, NULL);
    vkDestroyShaderModule(r->device, fs_colorblind, NULL);
    vkDestroyShaderModule(r->device, fs_pixelate, NULL);
    vkDestroyShaderModule(r->device, fs_sgsr1, NULL);

    if (!r->pipelines.window_pipeline || !r->pipelines.cursor_pipeline
        || !r->pipelines.blit_pipeline
        || !r->pipelines.offscreen_window_pipeline
        || !r->pipelines.offscreen_cursor_pipeline
        || !r->pipelines.offscreen_blit_pipeline) {
        destroy_pipelines(r);
        return false;
    }
    for (uint32_t i = 0; i < VK_EFFECT_COUNT; i++) {
        if (!r->pipelines.effect_pipelines[i]
            || !r->pipelines.offscreen_effect_pipelines[i]) {
            VK_LOGE("Failed to create effect pipeline %u", i);
            destroy_pipelines(r);
            return false;
        }
    }
    r->pipelines_built = true;
    return true;
}

static void destroy_pipelines(VkRenderer* r) {
    for (uint32_t i = 0; i < VK_EFFECT_COUNT; i++) {
        if (r->pipelines.effect_pipelines[i] != VK_NULL_HANDLE) {
            vkDestroyPipeline(r->device, r->pipelines.effect_pipelines[i], NULL);
            r->pipelines.effect_pipelines[i] = VK_NULL_HANDLE;
        }
        if (r->pipelines.offscreen_effect_pipelines[i] != VK_NULL_HANDLE) {
            vkDestroyPipeline(r->device, r->pipelines.offscreen_effect_pipelines[i], NULL);
            r->pipelines.offscreen_effect_pipelines[i] = VK_NULL_HANDLE;
        }
    }
    if (r->pipelines.window_pipeline) vkDestroyPipeline(r->device, r->pipelines.window_pipeline, NULL);
    if (r->pipelines.cursor_pipeline) vkDestroyPipeline(r->device, r->pipelines.cursor_pipeline, NULL);
    if (r->pipelines.blit_pipeline)   vkDestroyPipeline(r->device, r->pipelines.blit_pipeline, NULL);
    if (r->pipelines.offscreen_window_pipeline) vkDestroyPipeline(r->device, r->pipelines.offscreen_window_pipeline, NULL);
    if (r->pipelines.offscreen_cursor_pipeline) vkDestroyPipeline(r->device, r->pipelines.offscreen_cursor_pipeline, NULL);
    if (r->pipelines.offscreen_blit_pipeline)   vkDestroyPipeline(r->device, r->pipelines.offscreen_blit_pipeline, NULL);
    if (r->pipelines.window_layout)   vkDestroyPipelineLayout(r->device, r->pipelines.window_layout, NULL);
    if (r->pipelines.effect_layout)   vkDestroyPipelineLayout(r->device, r->pipelines.effect_layout, NULL);
    if (r->pipelines.sampler_set_layout) vkDestroyDescriptorSetLayout(r->device, r->pipelines.sampler_set_layout, NULL);
    if (r->pipelines.swapchain_pass)  vkDestroyRenderPass(r->device, r->pipelines.swapchain_pass, NULL);
    if (r->pipelines.offscreen_pass)  vkDestroyRenderPass(r->device, r->pipelines.offscreen_pass, NULL);
    if (r->pipelines.composite_pass)  vkDestroyRenderPass(r->device, r->pipelines.composite_pass, NULL);
    memset(&r->pipelines, 0, sizeof(r->pipelines));
    r->pipelines_built = false;
}

// ============================================================
// Quad VBO (for window/cursor pipelines that use vertex input)
// ============================================================

static bool create_quad_vbo(VkRenderer* r) {
    static const float QUAD[] = {
        0.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 0.0f,
        1.0f, 1.0f,
    };

    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = sizeof(QUAD);
    bci.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(r->device, &bci, NULL, &r->quad_vbo) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(r->device, r->quad_vbo, &mr);

    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = vkr_find_memory_type(r, mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) return false;
    if (vkAllocateMemory(r->device, &ai, NULL, &r->quad_vbo_memory) != VK_SUCCESS) return false;
    vkBindBufferMemory(r->device, r->quad_vbo, r->quad_vbo_memory, 0);

    void* mapped = NULL;
    vkMapMemory(r->device, r->quad_vbo_memory, 0, sizeof(QUAD), 0, &mapped);
    memcpy(mapped, QUAD, sizeof(QUAD));
    vkUnmapMemory(r->device, r->quad_vbo_memory);
    return true;
}

static void destroy_quad_vbo(VkRenderer* r) {
    if (r->quad_vbo) { vkDestroyBuffer(r->device, r->quad_vbo, NULL); r->quad_vbo = VK_NULL_HANDLE; }
    if (r->quad_vbo_memory) { vkFreeMemory(r->device, r->quad_vbo_memory, NULL); r->quad_vbo_memory = VK_NULL_HANDLE; }
}

// ============================================================
// Swapchain
// ============================================================

static bool create_swapchain(VkRenderer* r, uint32_t fallback_width, uint32_t fallback_height) {
    if (!r->surface) return false;

    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(r->physical_device, r->surface, &caps) != VK_SUCCESS) {
        VK_LOGE("vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed");
        return false;
    }

    uint32_t fmt_count = 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(r->physical_device, r->surface, &fmt_count, NULL) != VK_SUCCESS
        || fmt_count == 0) {
        VK_LOGE("No surface formats available");
        return false;
    }
    VkSurfaceFormatKHR* fmts = calloc(fmt_count, sizeof(VkSurfaceFormatKHR));
    if (!fmts) return false;
    vkGetPhysicalDeviceSurfaceFormatsKHR(r->physical_device, r->surface, &fmt_count, fmts);

    VkSurfaceFormatKHR chosen = fmts[0];
    for (uint32_t i = 0; i < fmt_count; i++) {
        if (fmts[i].format == VK_FORMAT_R8G8B8A8_UNORM
            && fmts[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = fmts[i];
            break;
        }
        if (fmts[i].format == VK_FORMAT_B8G8R8A8_UNORM
            && fmts[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = fmts[i];
        }
    }
    free(fmts);
    r->swapchain_format = chosen.format;

    // Honor the Java-requested present mode if the device supports it; otherwise fall back
    // to FIFO (always supported per spec). target_present_mode is initialized to FIFO in
    // nativeCreate, so a value-equality check is safe (no zero-sentinel ambiguity with
    // VK_PRESENT_MODE_IMMEDIATE_KHR which is enum value 0).
    VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR;
    VkPresentModeKHR want = r->target_present_mode;
    if (want != VK_PRESENT_MODE_FIFO_KHR) {
        uint32_t pm_count = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(r->physical_device, r->surface, &pm_count, NULL);
        if (pm_count > 0) {
            VkPresentModeKHR* pms = calloc(pm_count, sizeof(VkPresentModeKHR));
            if (pms) {
                vkGetPhysicalDeviceSurfacePresentModesKHR(r->physical_device, r->surface, &pm_count, pms);
                for (uint32_t i = 0; i < pm_count; i++) {
                    if (pms[i] == want) { present_mode = want; break; }
                }
                free(pms);
            }
        }
        if (present_mode != want) {
            VK_LOGW("Requested present mode %d unavailable; using FIFO", want);
        }
    }

    VkSurfaceTransformFlagBitsKHR pre_transform = caps.currentTransform;
    if (!is_plain_rotation_transform(pre_transform)
        || !(caps.supportedTransforms & pre_transform)) {
        pre_transform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            : caps.currentTransform;
    }

    VkExtent2D surface_extent = caps.currentExtent;
    if (surface_extent.width == 0xFFFFFFFFu) {
        surface_extent.width = fallback_width;
        surface_extent.height = fallback_height;
    }
    if ((surface_extent.width == 0 || surface_extent.height == 0) && r->anw) {
        int anw_w = ANativeWindow_getWidth(r->anw);
        int anw_h = ANativeWindow_getHeight(r->anw);
        if (anw_w > 0 && anw_h > 0) {
            surface_extent.width = (uint32_t)anw_w;
            surface_extent.height = (uint32_t)anw_h;
        }
    }
    if (surface_extent.width == 0 || surface_extent.height == 0) {
        VK_LOGW("Skipping swapchain creation for empty surface (%ux%u)",
                surface_extent.width, surface_extent.height);
        return false;
    }

    VkExtent2D extent = surface_extent;
    if (is_quarter_turn_transform(pre_transform)) {
        uint32_t tmp = extent.width;
        extent.width = extent.height;
        extent.height = tmp;
    }
    r->surface_extent = surface_extent;
    r->swapchain_extent = extent;
    r->swapchain_transform = pre_transform;
    // Only possible for unsupported mirrored transforms; avoid an Adreno present loop
    // while still letting normal rotation changes recreate the swapchain.
    r->ignore_suboptimal = r->caps.is_adreno && (pre_transform != caps.currentTransform);
    VK_LOGI("Swapchain surface=%ux%u extent=%ux%u currentTransform=0x%x preTransform=0x%x",
            surface_extent.width, surface_extent.height, extent.width, extent.height,
            caps.currentTransform, pre_transform);

    uint32_t image_count = caps.minImageCount + 1 + framegen_extra_images(r);
    if (caps.maxImageCount > 0 && image_count > caps.maxImageCount) image_count = caps.maxImageCount;
    if (image_count > VK_MAX_SWAPCHAIN_IMAGES) image_count = VK_MAX_SWAPCHAIN_IMAGES;

    VkSwapchainCreateInfoKHR sci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = r->surface;
    sci.minImageCount = image_count;
    sci.imageFormat = chosen.format;
    sci.imageColorSpace = chosen.colorSpace;
    sci.imageExtent = extent;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    if (r->record_blit_src
        && (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        sci.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT; // blit source for the encoder mirror
    }
    bool transfer_dst_capable = (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0;
    r->swapchain_transfer_dst = (r->framegen_requested || r->dis_requested) && transfer_dst_capable;
    if (r->swapchain_transfer_dst) sci.imageUsage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    r->swapchain_storage = r->framegen_requested
        && (caps.supportedUsageFlags & VK_IMAGE_USAGE_STORAGE_BIT) != 0
        && composite_format_supported(r);
    if (r->swapchain_storage) sci.imageUsage |= VK_IMAGE_USAGE_STORAGE_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sci.preTransform = pre_transform;
    sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
        ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
        : (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR)
            ? VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR
            : (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR)
                ? VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR
                : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sci.presentMode = present_mode;
    sci.clipped = VK_TRUE;
    VkSwapchainKHR old_sc = r->swapchain;
    sci.oldSwapchain = old_sc;
    VkSwapchainKHR new_sc = VK_NULL_HANDLE;
    if (vkCreateSwapchainKHR(r->device, &sci, NULL, &new_sc) != VK_SUCCESS) {
        VK_LOGE("vkCreateSwapchainKHR failed");
        if (old_sc) { vkDestroySwapchainKHR(r->device, old_sc, NULL); r->swapchain = VK_NULL_HANDLE; }
        return false;
    }
    r->swapchain = new_sc;
    if (old_sc) vkDestroySwapchainKHR(r->device, old_sc, NULL);

    uint32_t actual_count = 0;
    if (vkGetSwapchainImagesKHR(r->device, r->swapchain, &actual_count, NULL) != VK_SUCCESS
        || actual_count == 0) {
        VK_LOGE("vkGetSwapchainImagesKHR count failed");
        goto fail;
    }
    if (actual_count > VK_MAX_SWAPCHAIN_IMAGES) {
        VK_LOGE("Swapchain image count %u exceeds storage capacity %u",
                actual_count, VK_MAX_SWAPCHAIN_IMAGES);
        goto fail;
    }
    uint32_t got = actual_count;
    if (vkGetSwapchainImagesKHR(r->device, r->swapchain, &got, r->swapchain_images) != VK_SUCCESS
        || got != actual_count) {
        VK_LOGE("vkGetSwapchainImagesKHR images failed");
        goto fail;
    }
    r->swapchain_image_count = got;
    if (r->swapchain_storage && got > VKR_LSFG_MAX_TARGETS) {
        r->swapchain_storage = false;
        VK_LOGI("Swapchain has %u images, more than the %u frame generation targets; "
                "keeping the composite path", got, VKR_LSFG_MAX_TARGETS);
    }
    r->framegen_supported = transfer_dst_capable && composite_format_supported(r);
    VK_LOGI("Swapchain images requested=%u actual=%u caps.min=%u caps.max=%u framegen_extra=%u",
            image_count, got, caps.minImageCount, caps.maxImageCount, framegen_extra_images(r));

    if (!r->pipelines_built) {
        if (!create_pipelines(r)) goto fail;
    }

    VkSemaphoreCreateInfo sem_ci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < got; i++) {
        if (vkCreateSemaphore(r->device, &sem_ci, NULL,
                              &r->swapchain_render_finished[i]) != VK_SUCCESS) {
            goto fail;
        }

        VkImageViewCreateInfo ivci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        ivci.image = r->swapchain_images[i];
        ivci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        ivci.format = chosen.format;
        ivci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        ivci.subresourceRange.levelCount = 1;
        ivci.subresourceRange.layerCount = 1;
        if (vkCreateImageView(r->device, &ivci, NULL, &r->swapchain_views[i]) != VK_SUCCESS) {
            goto fail;
        }

        VkFramebufferCreateInfo fbci = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fbci.renderPass = r->pipelines.swapchain_pass;
        fbci.attachmentCount = 1;
        fbci.pAttachments = &r->swapchain_views[i];
        fbci.width = extent.width;
        fbci.height = extent.height;
        fbci.layers = 1;
        if (vkCreateFramebuffer(r->device, &fbci, NULL, &r->swapchain_framebuffers[i]) != VK_SUCCESS) {
            goto fail;
        }
    }
    return true;

fail:
    destroy_swapchain(r);
    return false;
}

static void destroy_swapchain_resources(VkRenderer* r) {
    for (uint32_t i = 0; i < r->swapchain_image_count; i++) {
        if (r->swapchain_render_finished[i]) {
            vkDestroySemaphore(r->device, r->swapchain_render_finished[i], NULL);
            r->swapchain_render_finished[i] = VK_NULL_HANDLE;
        }
        if (r->swapchain_framebuffers[i]) {
            vkDestroyFramebuffer(r->device, r->swapchain_framebuffers[i], NULL);
            r->swapchain_framebuffers[i] = VK_NULL_HANDLE;
        }
        if (r->swapchain_views[i]) {
            vkDestroyImageView(r->device, r->swapchain_views[i], NULL);
            r->swapchain_views[i] = VK_NULL_HANDLE;
        }
    }
    r->swapchain_image_count = 0;
}

static void destroy_swapchain(VkRenderer* r) {
    destroy_composite_targets(r);
    destroy_swapchain_resources(r);
    if (r->swapchain) { vkDestroySwapchainKHR(r->device, r->swapchain, NULL); r->swapchain = VK_NULL_HANDLE; }
}

// ============================================================
// Recording mirror swapchain (screen-capture target)
// ============================================================

static void destroy_record_ui_resources(VkRenderer* r) {
    VkRecordSwap* rec = &r->rec;
    for (uint32_t i = 0; i < VK_MAX_RECORD_IMAGES; i++) {
        if (rec->framebuffers[i]) { vkDestroyFramebuffer(r->device, rec->framebuffers[i], NULL); rec->framebuffers[i] = VK_NULL_HANDLE; }
        if (rec->views[i])        { vkDestroyImageView(r->device, rec->views[i], NULL); rec->views[i] = VK_NULL_HANDLE; }
    }
    if (rec->ui_pipeline) { vkDestroyPipeline(r->device, rec->ui_pipeline, NULL); rec->ui_pipeline = VK_NULL_HANDLE; }
    if (rec->ui_pass)     { vkDestroyRenderPass(r->device, rec->ui_pass, NULL); rec->ui_pass = VK_NULL_HANDLE; }
    if (rec->ui_texture)  { vkr_texture_destroy(r, rec->ui_texture); rec->ui_texture = NULL; }
    rec->fb_built = false;
}

// Build the LOAD render pass, framebuffers, and blended blit pipeline for the Record UI composite.
static bool build_record_ui_resources(VkRenderer* r) {
    VkRecordSwap* rec = &r->rec;
    if (rec->image_count == 0) return false;
    if (!r->pipelines_built) return false;

    VkAttachmentDescription att = {0};
    att.format = rec->format;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sp = {0};
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sp.colorAttachmentCount = 1;
    sp.pColorAttachments = &ref;
    VkSubpassDependency dep = {0};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rci = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rci.attachmentCount = 1;
    rci.pAttachments = &att;
    rci.subpassCount = 1;
    rci.pSubpasses = &sp;
    rci.dependencyCount = 1;
    rci.pDependencies = &dep;
    if (vkCreateRenderPass(r->device, &rci, NULL, &rec->ui_pass) != VK_SUCCESS) {
        VK_LOGE("record: ui_pass create failed");
        return false;
    }

    for (uint32_t i = 0; i < rec->image_count; i++) {
        VkImageViewCreateInfo ivci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        ivci.image = rec->images[i];
        ivci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        ivci.format = rec->format;
        ivci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        ivci.subresourceRange.levelCount = 1;
        ivci.subresourceRange.layerCount = 1;
        if (vkCreateImageView(r->device, &ivci, NULL, &rec->views[i]) != VK_SUCCESS) goto fail;

        VkFramebufferCreateInfo fbci = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fbci.renderPass = rec->ui_pass;
        fbci.attachmentCount = 1;
        fbci.pAttachments = &rec->views[i];
        fbci.width = rec->extent.width;
        fbci.height = rec->extent.height;
        fbci.layers = 1;
        if (vkCreateFramebuffer(r->device, &fbci, NULL, &rec->framebuffers[i]) != VK_SUCCESS) goto fail;
    }

    VkShaderModule vs = load_shader_module(r, quad_vert, quad_vert_size);
    VkShaderModule fs = load_shader_module(r, blit_frag, blit_frag_size);
    if (vs && fs) {
        rec->ui_pipeline = create_graphics_pipeline(
            r, vs, fs, r->pipelines.effect_layout, rec->ui_pass, false, true, NULL);
    }
    if (vs) vkDestroyShaderModule(r->device, vs, NULL);
    if (fs) vkDestroyShaderModule(r->device, fs, NULL);
    if (!rec->ui_pipeline) { VK_LOGE("record: ui_pipeline create failed"); goto fail; }

    rec->fb_built = true;
    VK_LOGI("record: UI composite resources ready");
    return true;

fail:
    destroy_record_ui_resources(r);
    return false;
}

static void destroy_record_swapchain(VkRenderer* r) {
    destroy_record_ui_resources(r);
    VkRecordSwap* rec = &r->rec;
    for (uint32_t i = 0; i < rec->image_count; i++) {
        if (rec->present_ready[i]) {
            vkDestroySemaphore(r->device, rec->present_ready[i], NULL);
            rec->present_ready[i] = VK_NULL_HANDLE;
        }
    }
    for (uint32_t i = 0; i < VK_FRAMES_IN_FLIGHT; i++) {
        if (rec->acquire[i]) {
            vkDestroySemaphore(r->device, rec->acquire[i], NULL);
            rec->acquire[i] = VK_NULL_HANDLE;
        }
    }
    rec->image_count = 0;
    if (rec->swapchain) { vkDestroySwapchainKHR(r->device, rec->swapchain, NULL); rec->swapchain = VK_NULL_HANDLE; }
    if (rec->surface)   { vkDestroySurfaceKHR(r->instance, rec->surface, NULL); rec->surface = VK_NULL_HANDLE; }
    if (rec->anw)       { ANativeWindow_release(rec->anw); rec->anw = NULL; }
    rec->active = false;
    rec->disabled = false;
    rec->ui_enabled = false;
}

static bool create_record_swapchain(VkRenderer* r) {
    VkRecordSwap* rec = &r->rec;
    if (!rec->anw) return false;

    VkAndroidSurfaceCreateInfoKHR aci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    aci.window = rec->anw;
    if (vkCreateAndroidSurfaceKHR(r->instance, &aci, NULL, &rec->surface) != VK_SUCCESS) {
        VK_LOGE("record: vkCreateAndroidSurfaceKHR failed");
        return false;
    }

    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(r->physical_device, r->graphics_queue_family,
                                         rec->surface, &supported);
    if (!supported) { VK_LOGE("record: surface not presentable"); goto fail; }

    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(r->physical_device, rec->surface, &caps) != VK_SUCCESS) {
        VK_LOGE("record: surface caps query failed");
        goto fail;
    }
    if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
        VK_LOGE("record: encoder surface lacks TRANSFER_DST usage (0x%x); cannot capture",
                caps.supportedUsageFlags);
        goto fail;
    }

    uint32_t fmt_count = 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(r->physical_device, rec->surface, &fmt_count, NULL) != VK_SUCCESS
        || fmt_count == 0) {
        VK_LOGE("record: surface formats query failed");
        goto fail;
    }
    VkSurfaceFormatKHR* fmts = calloc(fmt_count, sizeof(VkSurfaceFormatKHR));
    if (!fmts) goto fail;
    vkGetPhysicalDeviceSurfaceFormatsKHR(r->physical_device, rec->surface, &fmt_count, fmts);
    VkSurfaceFormatKHR chosen = fmts[0];
    for (uint32_t i = 0; i < fmt_count; i++) {
        if (fmts[i].format == VK_FORMAT_R8G8B8A8_UNORM) { chosen = fmts[i]; break; }
        if (fmts[i].format == VK_FORMAT_B8G8R8A8_UNORM) chosen = fmts[i];
    }
    free(fmts);
    rec->format = chosen.format;

    VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR; // prefer MAILBOX below
    uint32_t pm_count = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(r->physical_device, rec->surface, &pm_count, NULL);
    if (pm_count > 0) {
        VkPresentModeKHR* pms = calloc(pm_count, sizeof(VkPresentModeKHR));
        if (pms) {
            vkGetPhysicalDeviceSurfacePresentModesKHR(r->physical_device, rec->surface, &pm_count, pms);
            for (uint32_t i = 0; i < pm_count; i++) {
                if (pms[i] == VK_PRESENT_MODE_MAILBOX_KHR) { present_mode = VK_PRESENT_MODE_MAILBOX_KHR; break; }
            }
            free(pms);
        }
    }

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == 0xFFFFFFFFu) {
        int w = ANativeWindow_getWidth(rec->anw);
        int h = ANativeWindow_getHeight(rec->anw);
        extent.width = w > 0 ? (uint32_t)w : r->swapchain_extent.width;
        extent.height = h > 0 ? (uint32_t)h : r->swapchain_extent.height;
    }
    if (extent.width == 0 || extent.height == 0) goto fail;
    rec->extent = extent;

    VkSurfaceTransformFlagBitsKHR pre =
        (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;

    uint32_t image_count = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && image_count > caps.maxImageCount) image_count = caps.maxImageCount;
    if (image_count > VK_MAX_RECORD_IMAGES) image_count = VK_MAX_RECORD_IMAGES;

    VkSwapchainCreateInfoKHR sci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = rec->surface;
    sci.minImageCount = image_count;
    sci.imageFormat = chosen.format;
    sci.imageColorSpace = chosen.colorSpace;
    sci.imageExtent = extent;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (rec->ui_enabled && (caps.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) {
        sci.imageUsage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT; // Record UI renders onto the rec image
    } else if (rec->ui_enabled) {
        VK_LOGW("record: encoder surface can't be a color attachment; UI overlay disabled");
        rec->ui_enabled = false;
    }
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sci.preTransform = pre;
    sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
        ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sci.presentMode = present_mode;
    sci.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(r->device, &sci, NULL, &rec->swapchain) != VK_SUCCESS) {
        VK_LOGE("record: vkCreateSwapchainKHR failed");
        goto fail;
    }

    uint32_t got = 0;
    if (vkGetSwapchainImagesKHR(r->device, rec->swapchain, &got, NULL) != VK_SUCCESS
        || got == 0 || got > VK_MAX_RECORD_IMAGES) {
        VK_LOGE("record: swapchain image count query failed (got=%u)", got);
        goto fail;
    }
    if (vkGetSwapchainImagesKHR(r->device, rec->swapchain, &got, rec->images) != VK_SUCCESS) {
        VK_LOGE("record: swapchain images query failed");
        goto fail;
    }
    rec->image_count = got;

    VkSemaphoreCreateInfo sem_ci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < got; i++) {
        if (vkCreateSemaphore(r->device, &sem_ci, NULL, &rec->present_ready[i]) != VK_SUCCESS) goto fail;
    }
    for (uint32_t i = 0; i < VK_FRAMES_IN_FLIGHT; i++) {
        if (vkCreateSemaphore(r->device, &sem_ci, NULL, &rec->acquire[i]) != VK_SUCCESS) goto fail;
    }
    VK_LOGI("record: mirror swapchain %ux%u images=%u", extent.width, extent.height, got);

    if (rec->ui_enabled && !build_record_ui_resources(r)) {
        VK_LOGW("record: UI composite unavailable; capturing game only");
        rec->ui_enabled = false;
    }
    return true;

fail:
    destroy_record_swapchain(r);
    return false;
}

// ============================================================
// Offscreen ping-pong (for effect chain)
// ============================================================

static bool create_one_offscreen(VkRenderer* r, VkOffscreen* o, uint32_t w, uint32_t h,
                                 VkFilter filter) {
    o->width = w;
    o->height = h;

    VkImageCreateInfo ic = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = r->caps.offscreen_format;
    ic.extent.width = w;
    ic.extent.height = h;
    ic.extent.depth = 1;
    ic.mipLevels = 1;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(r->device, &ic, NULL, &o->image) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(r->device, o->image, &mr);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = vkr_find_memory_type(r, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) return false;
    if (vkAllocateMemory(r->device, &ai, NULL, &o->memory) != VK_SUCCESS) return false;
    vkBindImageMemory(r->device, o->image, o->memory, 0);

    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = o->image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ic.format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    if (vkCreateImageView(r->device, &vi, NULL, &o->view) != VK_SUCCESS) return false;

    VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = filter;
    si.minFilter = filter;
    si.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(r->device, &si, NULL, &o->sampler) != VK_SUCCESS) return false;

    o->descriptor_set = vkr_alloc_descriptor_set(r);
    if (o->descriptor_set == VK_NULL_HANDLE) return false;

    VkDescriptorImageInfo dii = {0};
    dii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    dii.imageView = o->view;
    dii.sampler = o->sampler;
    VkWriteDescriptorSet wri = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    wri.dstSet = o->descriptor_set;
    wri.dstBinding = 0;
    wri.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wri.descriptorCount = 1;
    wri.pImageInfo = &dii;
    vkUpdateDescriptorSets(r->device, 1, &wri, 0, NULL);

    VkFramebufferCreateInfo fbci = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbci.renderPass = r->pipelines.offscreen_pass;
    fbci.attachmentCount = 1;
    fbci.pAttachments = &o->view;
    fbci.width = w;
    fbci.height = h;
    fbci.layers = 1;
    if (vkCreateFramebuffer(r->device, &fbci, NULL, &o->framebuffer) != VK_SUCCESS) return false;

    return true;
}

static void destroy_one_offscreen(VkRenderer* r, VkOffscreen* o) {
    if (o->framebuffer)    vkDestroyFramebuffer(r->device, o->framebuffer, NULL);
    if (o->descriptor_set) vkr_free_descriptor_set(r, o->descriptor_set);
    if (o->sampler)        vkDestroySampler(r->device, o->sampler, NULL);
    if (o->view)           vkDestroyImageView(r->device, o->view, NULL);
    if (o->image)          vkDestroyImage(r->device, o->image, NULL);
    if (o->memory)         vkFreeMemory(r->device, o->memory, NULL);
    memset(o, 0, sizeof(*o));
}

// Builds offscreen[0], plus the second ping-pong target (~8 MB RGBA8 + view/sampler/descriptor/
// framebuffer) only when need_second. At matching dims, a missing second target is added in
// place without disturbing offscreen[0].
static bool create_offscreen(VkRenderer* r, uint32_t w, uint32_t h, bool need_second) {
    bool dims_ok = r->offscreen_built
                && r->offscreen[0].width == w && r->offscreen[0].height == h;
    bool second_ok = !need_second || r->offscreen[1].image != VK_NULL_HANDLE;
    if (dims_ok && second_ok) return true;

    if (dims_ok && !second_ok) {  // add second target only (callers drained the queue)
        if (!create_one_offscreen(r, &r->offscreen[1], w, h, VK_FILTER_LINEAR)) {
            destroy_one_offscreen(r, &r->offscreen[1]);
            return false;
        }
        return true;
    }

    destroy_offscreen(r);
    if (!create_one_offscreen(r, &r->offscreen[0], w, h, VK_FILTER_LINEAR)) goto fail;
    if (need_second && !create_one_offscreen(r, &r->offscreen[1], w, h, VK_FILTER_LINEAR)) goto fail;
    r->offscreen_built = true;
    return true;

fail:
    destroy_offscreen(r);
    return false;
}

static void destroy_offscreen(VkRenderer* r) {
    destroy_one_offscreen(r, &r->offscreen[0]);
    destroy_one_offscreen(r, &r->offscreen[1]);
    r->offscreen_built = false;
}

static void destroy_one_composite(VkRenderer* r, VkCompositeTarget* c) {
    if (c->framebuffer) vkDestroyFramebuffer(r->device, c->framebuffer, NULL);
    if (c->view)        vkDestroyImageView(r->device, c->view, NULL);
    if (c->image)       vkDestroyImage(r->device, c->image, NULL);
    if (c->memory)      vkFreeMemory(r->device, c->memory, NULL);
    memset(c, 0, sizeof(*c));
}

static void destroy_composite_targets(VkRenderer* r) {
    for (uint32_t i = 0; i < VK_MAX_COMPOSITE_TARGETS; i++) {
        destroy_one_composite(r, &r->composite[i]);
    }
    r->composite_count = 0;
    r->composite_built = false;
}

static bool create_one_composite(VkRenderer* r, VkCompositeTarget* c, uint32_t w, uint32_t h) {
    c->width = w;
    c->height = h;

    VkImageCreateInfo ic = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = r->swapchain_format;
    ic.extent.width = w;
    ic.extent.height = h;
    ic.extent.depth = 1;
    ic.mipLevels = 1;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
             | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
             | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(r->device, &ic, NULL, &c->image) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(r->device, c->image, &mr);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = vkr_find_memory_type(r, mr.memoryTypeBits,
                                              VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) return false;
    if (vkAllocateMemory(r->device, &ai, NULL, &c->memory) != VK_SUCCESS) return false;
    vkBindImageMemory(r->device, c->image, c->memory, 0);

    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = c->image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ic.format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    if (vkCreateImageView(r->device, &vi, NULL, &c->view) != VK_SUCCESS) return false;

    VkFramebufferCreateInfo fbci = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbci.renderPass = r->pipelines.composite_pass;
    fbci.attachmentCount = 1;
    fbci.pAttachments = &c->view;
    fbci.width = w;
    fbci.height = h;
    fbci.layers = 1;
    if (vkCreateFramebuffer(r->device, &fbci, NULL, &c->framebuffer) != VK_SUCCESS) return false;

    return true;
}

static bool create_composite_targets(VkRenderer* r, uint32_t w, uint32_t h, uint32_t count) {
    if (count == 0 || count > VK_MAX_COMPOSITE_TARGETS) return false;
    if (r->composite_built && r->composite_count == count
        && r->composite[0].width == w && r->composite[0].height == h) {
        return true;
    }

    destroy_composite_targets(r);
    for (uint32_t i = 0; i < count; i++) {
        if (!create_one_composite(r, &r->composite[i], w, h)) {
            destroy_composite_targets(r);
            return false;
        }
    }
    r->composite_count = count;
    r->composite_built = true;
    return true;
}

static void destroy_lsfg(VkRenderer* r) {
    if (!r->lsfg) return;
    vkr_lsfg_destroy(r->lsfg);
    r->lsfg = NULL;
    r->framegen_real_frames = 0;
    r->framegen_made_frames = 0;
    r->framegen_draw_ns = 0;
    r->framegen_gap_ns = 0;
    r->framegen_last_end_ns = 0;
    r->framegen_timed_frames = 0;
}

static void create_lsfg(VkRenderer* r) {
    if (r->lsfg || !r->lsfg_cache_path || !r->device || !r->physical_device) return;

    r->lsfg = vkr_lsfg_create(r->device, r->physical_device, r->lsfg_cache_path);
    if (!r->lsfg) {
        VK_LOGW("LSFG shaders unavailable at %s; frame generation stays off", r->lsfg_cache_path);
        return;
    }
    vkr_lsfg_configure(r->lsfg, r->framegen_multiplier ? r->framegen_multiplier : 2u,
                       r->framegen_target_rate,
                       r->framegen_flow_scale > 0.0f ? r->framegen_flow_scale : 0.7f,
                       r->framegen_refresh_rate, 0.0f);
}

static void destroy_dis(VkRenderer* r) {
    if (!r->dis) return;
    vkr_dis_destroy(r->dis);
    r->dis = NULL;
    r->framegen_real_frames = 0;
    r->framegen_made_frames = 0;
    r->framegen_draw_ns = 0;
    r->framegen_gap_ns = 0;
    r->framegen_last_end_ns = 0;
    r->framegen_timed_frames = 0;
}

static void create_dis(VkRenderer* r) {
    if (r->dis || !r->device || !r->physical_device) return;

    r->dis = vkr_dis_create(r->device, r->physical_device);
    if (!r->dis) {
        VK_LOGW("DIS shaders unavailable; frame generation stays off");
        return;
    }
    vkr_dis_configure(r->dis, r->dis_scale ? r->dis_scale : 180u, r->dis_target_fps,
                      r->framegen_refresh_rate);
    vkr_dis_set_debug_flow(r->dis, r->dis_debug_flow);
}

static uint32_t framegen_extra_images(const VkRenderer* r) {
    if (!r->framegen_requested && !r->dis_requested) return 0;
    if (r->dis_requested) return VKR_DIS_MAX_GENERATIONS;
    if (r->framegen_target_rate != 0) return VKR_LSFG_MAX_GENERATIONS;

    uint32_t generations = r->framegen_multiplier > 1 ? r->framegen_multiplier - 1 : 1;
    return generations > VKR_LSFG_MAX_GENERATIONS ? VKR_LSFG_MAX_GENERATIONS : generations;
}

static void framegen_rebuild_swapchain(VkRenderer* r) {
    if (!r->surface || !r->swapchain) return;

    pthread_mutex_lock(&r->scene_mutex);
    r->surface_ready = false;
    pthread_mutex_unlock(&r->scene_mutex);

    if (r->device) vkDeviceWaitIdle(r->device);
    uint32_t fw = r->surface_extent.width;
    uint32_t fh = r->surface_extent.height;
    destroy_sgsr1_resources(r);
    destroy_offscreen(r);
    destroy_swapchain(r);
    if (!create_swapchain(r, fw, fh)) {
        VK_LOGE("Swapchain re-create failed for frame generation");
        return;
    }
    pthread_mutex_lock(&r->scene_mutex);
    r->surface_ready = true;
    pthread_mutex_unlock(&r->scene_mutex);
}

static void blit_composite_to_swapchain(VkRenderer* r, VkCommandBuffer cmd,
                                        VkCompositeTarget* src, VkImage dst) {
    vkr_image_barrier(cmd, dst,
                      VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                      VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                      0, VK_ACCESS_TRANSFER_WRITE_BIT);

    VkImageBlit blit = {0};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = (int32_t)src->width;
    blit.srcOffsets[1].y = (int32_t)src->height;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[1].x = (int32_t)r->swapchain_extent.width;
    blit.dstOffsets[1].y = (int32_t)r->swapchain_extent.height;
    blit.dstOffsets[1].z = 1;
    const bool rescaling = src->width != r->swapchain_extent.width
                        || src->height != r->swapchain_extent.height;
    vkCmdBlitImage(cmd, src->image, VK_IMAGE_LAYOUT_GENERAL,
                   dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                   rescaling ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);

    vkr_image_barrier(cmd, dst,
                      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                      VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                      VK_ACCESS_TRANSFER_WRITE_BIT, 0);
}

static bool composite_format_supported(VkRenderer* r) {
    if (r->swapchain_format == VK_FORMAT_UNDEFINED) return false;

    VkFormatProperties props;
    memset(&props, 0, sizeof(props));
    vkGetPhysicalDeviceFormatProperties(r->physical_device, r->swapchain_format, &props);

    const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
                                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                                        | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                                        | VK_FORMAT_FEATURE_BLIT_SRC_BIT
                                        | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    return (props.optimalTilingFeatures & required) == required;
}

static bool create_sgsr1_resources(VkRenderer* r, uint32_t w, uint32_t h) {
    if (r->sgsr1.built && r->sgsr1.width == w && r->sgsr1.height == h) return true;

    destroy_sgsr1_resources(r);
    if (!create_one_offscreen(r, &r->sgsr1.source, w, h, VK_FILTER_LINEAR)) goto fail;

    r->sgsr1.width = w;
    r->sgsr1.height = h;
    r->sgsr1.built = true;
    VK_LOGI("SGSR1 source created %ux%u -> swapchain %ux%u",
            w, h, r->swapchain_extent.width, r->swapchain_extent.height);
    return true;

fail:
    destroy_sgsr1_resources(r);
    return false;
}

static void destroy_sgsr1_resources(VkRenderer* r) {
    if (r->sgsr1.built) {
        VK_LOGI("SGSR1 source destroyed");
    }
    destroy_one_offscreen(r, &r->sgsr1.source);
    r->sgsr1.built = false;
    r->sgsr1.width = 0;
    r->sgsr1.height = 0;
}

// ============================================================
// Graveyard processing
// ============================================================

// Detach the slot's pending-destroy list under scene_mutex. The Vulkan destroy calls
// (vkFreeDescriptorSets, vkDestroyImage, vkFreeMemory, AHardwareBuffer_release) can each
// take tens to hundreds of microseconds on Adreno, so doing them under scene_mutex stalls
// every scene producer (X server, input thread) for the full duration. Caller passes the
// detached array to destroy_graveyard_textures() after releasing the lock.
static void detach_graveyard_slot(VkRenderer* r, uint32_t slot_idx,
                                  VkTexture*** out_textures, uint32_t* out_count) {
    VkGraveSlot* slot = &r->graveyard[slot_idx];
    *out_textures = slot->textures;
    *out_count = slot->count;
    slot->textures = NULL;
    slot->count = 0;
    slot->capacity = 0;
}

static void destroy_graveyard_textures(VkRenderer* r, VkTexture** textures, uint32_t count) {
    if (!textures) return;
    for (uint32_t i = 0; i < count; i++) {
        vkr_texture_destroy(r, textures[i]);
    }
    free(textures);
}

// ============================================================
// Frame recording + submission
// ============================================================

static bool is_plain_rotation_transform(VkSurfaceTransformFlagBitsKHR transform) {
    return transform == VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
        || transform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR
        || transform == VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR
        || transform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;
}

static bool is_quarter_turn_transform(VkSurfaceTransformFlagBitsKHR transform) {
    return transform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR
        || transform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;
}

static void transform_xform_for_pretransform(float out[6], const float in[6],
                                             uint32_t view_w, uint32_t view_h,
                                             VkSurfaceTransformFlagBitsKHR transform) {
    switch (transform) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
            out[0] = -in[1];
            out[1] =  in[0];
            out[2] = -in[3];
            out[3] =  in[2];
            out[4] = (float)view_h - in[5];
            out[5] =  in[4];
            break;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
            out[0] = -in[0];
            out[1] = -in[1];
            out[2] = -in[2];
            out[3] = -in[3];
            out[4] = (float)view_w - in[4];
            out[5] = (float)view_h - in[5];
            break;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
            out[0] =  in[1];
            out[1] = -in[0];
            out[2] =  in[3];
            out[3] = -in[2];
            out[4] =  in[5];
            out[5] = (float)view_w - in[4];
            break;
        default:
            memcpy(out, in, sizeof(float) * 6);
            break;
    }
}

static void transformed_view_size(uint32_t* w, uint32_t* h,
                                  VkSurfaceTransformFlagBitsKHR transform) {
    if (is_quarter_turn_transform(transform)) {
        uint32_t tmp = *w;
        *w = *h;
        *h = tmp;
    }
}

typedef struct VkPreRotatedRect {
    int x;
    int y;
    int w;
    int h;
} VkPreRotatedRect;

static VkPreRotatedRect transform_rect_for_pretransform(int x, int y, int w, int h,
                                                        uint32_t buffer_w,
                                                        uint32_t buffer_h,
                                                        VkSurfaceTransformFlagBitsKHR transform) {
    VkPreRotatedRect r = {x, y, w, h};
    switch (transform) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
            r.x = (int)buffer_w - h - y;
            r.y = x;
            r.w = h;
            r.h = w;
            break;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
            r.x = (int)buffer_w - w - x;
            r.y = (int)buffer_h - h - y;
            break;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
            r.x = y;
            r.y = (int)buffer_h - w - x;
            r.w = h;
            r.h = w;
            break;
        default:
            break;
    }
    return r;
}

static VkPreRotatedRect clamp_rect_to_extent(VkPreRotatedRect r, uint32_t extent_w,
                                             uint32_t extent_h) {
    int max_w = (int)extent_w;
    int max_h = (int)extent_h;
    if (r.x < 0) {
        r.w += r.x;
        r.x = 0;
    }
    if (r.y < 0) {
        r.h += r.y;
        r.y = 0;
    }
    if (r.x + r.w > max_w) r.w = max_w - r.x;
    if (r.y + r.h > max_h) r.h = max_h - r.y;
    if (r.w < 0) r.w = 0;
    if (r.h < 0) r.h = 0;
    return r;
}

static VkPreRotatedRect scale_rect_from_swapchain(const VkRenderer* r, VkPreRotatedRect rect,
                                                  uint32_t target_w, uint32_t target_h) {
    if (target_w == r->swapchain_extent.width && target_h == r->swapchain_extent.height) {
        return rect;
    }

    float sx = r->swapchain_extent.width > 0
        ? (float)target_w / (float)r->swapchain_extent.width
        : 1.0f;
    float sy = r->swapchain_extent.height > 0
        ? (float)target_h / (float)r->swapchain_extent.height
        : 1.0f;

    rect.x = (int)((float)rect.x * sx + 0.5f);
    rect.y = (int)((float)rect.y * sy + 0.5f);
    rect.w = (int)((float)rect.w * sx + 0.5f);
    rect.h = (int)((float)rect.h * sy + 0.5f);
    return rect;
}

static void push_window_constants(VkCommandBuffer cmd, VkPipelineLayout layout,
                                  const float xform[6], float view_w, float view_h,
                                  float u0, float v0, float u1, float v1,
                                  bool swap_rb) {
    struct {
        float xform[6];
        float view_size[2];
        float uv_rect[4];
        int32_t swap_rb;
    } pc;
    memcpy(pc.xform, xform, sizeof(pc.xform));
    pc.view_size[0] = view_w;
    pc.view_size[1] = view_h;
    pc.uv_rect[0] = u0;
    pc.uv_rect[1] = v0;
    pc.uv_rect[2] = u1;
    pc.uv_rect[3] = v1;
    pc.swap_rb = swap_rb ? 1 : 0;
    vkCmdPushConstants(cmd, layout,
                       VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                       0, sizeof(pc), &pc);
}

static void compose_xform_for_window(float out[6], const float scene_xform[6],
                                     int wx, int wy, int ww, int wh) {
    // Equivalent to GLRenderer.renderDrawable: tmpXForm1 = make(x, y, w, h); tmpXForm1 *= tmpXForm2
    // XForm.set(out, x, y, w, h):  [w, 0, 0, h, x, y]
    float a[6] = { (float)ww, 0.0f, 0.0f, (float)wh, (float)wx, (float)wy };
    // 2x2 + translation multiply: result = a * scene_xform
    out[0] = a[0]*scene_xform[0] + a[1]*scene_xform[2];
    out[1] = a[0]*scene_xform[1] + a[1]*scene_xform[3];
    out[2] = a[2]*scene_xform[0] + a[3]*scene_xform[2];
    out[3] = a[2]*scene_xform[1] + a[3]*scene_xform[3];
    out[4] = a[4]*scene_xform[0] + a[5]*scene_xform[2] + scene_xform[4];
    out[5] = a[4]*scene_xform[1] + a[5]*scene_xform[3] + scene_xform[5];
}

static VkrDisContentRect compute_dis_content_rect(VkRenderer* r, const VkScene* s,
                                                  uint32_t composite_w, uint32_t composite_h) {
    VkrDisContentRect full = {0, 0, composite_w, composite_h};
    if (!s->viewport_set || s->viewport_w <= 0 || s->viewport_h <= 0) return full;

    VkPreRotatedRect vr = transform_rect_for_pretransform(
        s->viewport_x, s->viewport_y, s->viewport_w, s->viewport_h,
        r->swapchain_extent.width, r->swapchain_extent.height, r->swapchain_transform);
    vr = scale_rect_from_swapchain(r, vr, composite_w, composite_h);
    vr = clamp_rect_to_extent(vr, composite_w, composite_h);
    if (vr.w <= 0 || vr.h <= 0) return full;

    VkrDisContentRect out = {vr.x, vr.y, (uint32_t)vr.w, (uint32_t)vr.h};
    return out;
}

static void set_viewport_scissor(VkCommandBuffer cmd, VkRenderer* r, const VkScene* s,
                                 uint32_t target_w, uint32_t target_h) {
    if (target_w == 0) target_w = r->swapchain_extent.width;
    if (target_h == 0) target_h = r->swapchain_extent.height;

    int vx, vy, vw, vh;
    if (s->viewport_set) {
        vx = s->viewport_x;
        vy = s->viewport_y;
        vw = s->viewport_w;
        vh = s->viewport_h;
    } else {
        vx = 0;
        vy = 0;
        vw = (int)r->surface_extent.width;
        vh = (int)r->surface_extent.height;
    }

    VkPreRotatedRect vr = transform_rect_for_pretransform(
        vx, vy, vw, vh, r->swapchain_extent.width, r->swapchain_extent.height,
        r->swapchain_transform);
    vr = scale_rect_from_swapchain(r, vr, target_w, target_h);

    VkViewport vp = {0};
    vp.x = (float)vr.x;
    vp.y = (float)vr.y;
    vp.width = (float)vr.w;
    vp.height = (float)vr.h;
    vp.minDepth = 0.0f;
    vp.maxDepth = 1.0f;
    vkCmdSetViewport(cmd, 0, 1, &vp);

    int sx, sy, sw, sh;
    if (s->scissor_enabled) {
        sx = s->scissor_x;
        sy = s->scissor_y;
        sw = s->scissor_w;
        sh = s->scissor_h;
    } else {
        sx = 0;
        sy = 0;
        sw = (int)r->surface_extent.width;
        sh = (int)r->surface_extent.height;
    }
    VkPreRotatedRect sr = transform_rect_for_pretransform(
        sx, sy, sw, sh, r->swapchain_extent.width, r->swapchain_extent.height,
        r->swapchain_transform);
    sr = scale_rect_from_swapchain(r, sr, target_w, target_h);
    sr = clamp_rect_to_extent(sr, target_w, target_h);

    VkRect2D sc = {0};
    sc.offset.x = sr.x;
    sc.offset.y = sr.y;
    sc.extent.width = (uint32_t)sr.w;
    sc.extent.height = (uint32_t)sr.h;
    vkCmdSetScissor(cmd, 0, 1, &sc);
}

static void draw_scene_pass(VkRenderer* r, VkCommandBuffer cmd, const VkScene* s, bool offscreen,
                            uint32_t target_w, uint32_t target_h) {
    if (s->screen_width == 0 || s->screen_height == 0) return;

    set_viewport_scissor(cmd, r, s, target_w, target_h);

    // Windows
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                      offscreen ? r->pipelines.offscreen_window_pipeline
                                : r->pipelines.window_pipeline);
    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &r->quad_vbo, &offset);

    for (uint32_t i = 0; i < s->window_count; i++) {
        const VkRenderableWindow* w = &s->windows[i];
        if (!w->texture || !w->texture->ready) continue;

        float xf[6];
        compose_xform_for_window(xf, s->xform, w->x, w->y, w->width, w->height);
        float pre_xf[6];
        uint32_t view_w = s->screen_width;
        uint32_t view_h = s->screen_height;
        transform_xform_for_pretransform(pre_xf, xf, view_w, view_h, r->swapchain_transform);
        transformed_view_size(&view_w, &view_h, r->swapchain_transform);
        push_window_constants(cmd, r->pipelines.window_layout, pre_xf,
                              (float)view_w, (float)view_h,
                              w->u0, w->v0, w->u1, w->v1, s->swap_rb);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                r->pipelines.window_layout, 0, 1, &w->texture->descriptor_set,
                                0, NULL);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    // Cursor
    if (s->cursor_visible && s->cursor_texture && s->cursor_texture->ready
        && s->cursor_width > 0 && s->cursor_height > 0) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          offscreen ? r->pipelines.offscreen_cursor_pipeline
                                    : r->pipelines.cursor_pipeline);
        float xf[6];
        compose_xform_for_window(xf, s->xform, s->cursor_x, s->cursor_y,
                                 s->cursor_width, s->cursor_height);
        float pre_xf[6];
        uint32_t view_w = s->screen_width;
        uint32_t view_h = s->screen_height;
        transform_xform_for_pretransform(pre_xf, xf, view_w, view_h, r->swapchain_transform);
        transformed_view_size(&view_w, &view_h, r->swapchain_transform);
        push_window_constants(cmd, r->pipelines.window_layout, pre_xf,
                              (float)view_w, (float)view_h,
                              0.0f, 0.0f, 1.0f, 1.0f, false);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                r->pipelines.window_layout, 0, 1,
                                &s->cursor_texture->descriptor_set, 0, NULL);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }
}

static void run_effect(VkRenderer* r, VkCommandBuffer cmd, VkEffectSlot* eff,
                       VkDescriptorSet src_set, uint32_t target_w, uint32_t target_h,
                       bool offscreen) {
    VkPipeline pipe = VK_NULL_HANDLE;
    if ((uint32_t)eff->type < VK_EFFECT_COUNT) {
        pipe = offscreen ? r->pipelines.offscreen_effect_pipelines[eff->type]
                         : r->pipelines.effect_pipelines[eff->type];
    }
    if (!pipe) {
        pipe = offscreen ? r->pipelines.offscreen_blit_pipeline : r->pipelines.blit_pipeline;
    }

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);

    VkViewport vp = {0, 0, (float)target_w, (float)target_h, 0.0f, 1.0f};
    VkRect2D sc = {{0, 0}, {target_w, target_h}};
    vkCmdSetViewport(cmd, 0, 1, &vp);
    vkCmdSetScissor(cmd, 0, 1, &sc);

    float pc[6];
    pc[0] = (float)target_w;
    pc[1] = (float)target_h;
    pc[2] = eff->param0;
    pc[3] = eff->param1;
    pc[4] = eff->param2;
    pc[5] = (float)eff->mode;
    vkCmdPushConstants(cmd, r->pipelines.effect_layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                       sizeof(pc), pc);

    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            r->pipelines.effect_layout, 0, 1, &src_set, 0, NULL);
    vkCmdDraw(cmd, 3, 1, 0, 0);
}

static bool scene_starts_with_sgsr1(const VkScene* s) {
    return s->effect_count > 0 && s->effects[0].type == VK_EFFECT_SGSR1;
}

static void wait_inflight_frames(VkRenderer* r) {
    VkFence fences[VK_FRAMES_IN_FLIGHT];
    uint32_t count = 0;
    for (uint32_t i = 0; i < VK_FRAMES_IN_FLIGHT; i++) {
        if (r->frames[i].in_flight) fences[count++] = r->frames[i].in_flight;
    }
    if (count == 0) return;
    vkWaitForFences(r->device, count, fences, VK_TRUE, UINT64_MAX);
}

// SGSR1 upscales the actual X/DRI3 source; ratio changes take effect after restart.
static VkExtent2D compute_sgsr1_source_extent(VkRenderer* r, const VkScene* s) {
    VkExtent2D out = r->swapchain_extent;
    if (out.width == 0 || out.height == 0 || s->screen_width == 0 || s->screen_height == 0) {
        return out;
    }

    // Cap SGSR1's source at the X screen so oversized DRI3 buffers do not undo scaling.
    uint32_t source_w = s->source_width > 0 && s->source_width < s->screen_width
        ? s->source_width : s->screen_width;
    uint32_t source_h = s->source_height > 0 && s->source_height < s->screen_height
        ? s->source_height : s->screen_height;
    transformed_view_size(&source_w, &source_h, r->swapchain_transform);
    if (source_w == 0 || source_h == 0) return out;

    VkExtent2D source = {
        source_w < out.width ? source_w : out.width,
        source_h < out.height ? source_h : out.height,
    };
    if (source.width < 1) source.width = 1;
    if (source.height < 1) source.height = 1;
    return source;
}

static VkExtent2D compute_container_extent(VkRenderer* r, const VkScene* s) {
    VkExtent2D out = r->swapchain_extent;
    if (out.width == 0 || out.height == 0 || s->screen_width == 0 || s->screen_height == 0) {
        return out;
    }

    uint32_t w = s->screen_width;
    uint32_t h = s->screen_height;
    transformed_view_size(&w, &h, r->swapchain_transform);
    if (w == 0 || h == 0) return out;

    if (s->viewport_set && s->viewport_w > 0 && s->viewport_h > 0
        && r->swapchain_extent.width > 0 && r->swapchain_extent.height > 0) {
        VkPreRotatedRect vr = transform_rect_for_pretransform(
            s->viewport_x, s->viewport_y, s->viewport_w, s->viewport_h,
            r->swapchain_extent.width, r->swapchain_extent.height, r->swapchain_transform);
        if (vr.w > 0 && vr.h > 0) {
            const double fx = (double)vr.w / (double)r->swapchain_extent.width;
            const double fy = (double)vr.h / (double)r->swapchain_extent.height;
            if (fx > 0.0 && fx < 1.0) w = (uint32_t)((double)w / fx + 0.5);
            if (fy > 0.0 && fy < 1.0) h = (uint32_t)((double)h / fy + 0.5);
        }
    }

    VkExtent2D e = {
        w < out.width ? w : out.width,
        h < out.height ? h : out.height,
    };
    if (e.width < 1) e.width = 1;
    if (e.height < 1) e.height = 1;
    return e;
}

static uint64_t vkr_monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static bool record_and_submit_frame(VkRenderer* r) {
    if (!r->surface_ready || !r->swapchain) return false;
    if (!vkd_bind(r->vulkan_handle, r->instance)) return false;

    const uint64_t draw_begin_ns = vkr_monotonic_ns();

    pthread_mutex_lock(&r->render_mutex);

    VkFrame* f = &r->frames[r->frame_index];
    uint32_t grave_slot = r->graveyard_index;

    if (f->in_flight == VK_NULL_HANDLE) {
        VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateFence(r->device, &fci, NULL, &f->in_flight) != VK_SUCCESS) {
            f->in_flight = VK_NULL_HANDLE;
            VK_LOGE("frame fence unavailable; skipping frame");
            pthread_mutex_unlock(&r->render_mutex);
            return false;
        }
    }

    vkWaitForFences(r->device, 1, &f->in_flight, VK_TRUE, UINT64_MAX);

    // Snapshot the scene under scene_mutex (cheap memcpy of a few KB), then release it so
    // scene producers (texture destroys, X server window updates) don't stall behind the
    // long acquire/record/submit/present below. render_mutex still serializes us against
    // surface lifecycle changes, which keeps the swapchain handles stable for our use.
    VkScene snap;
    VkTexture** dead = NULL;
    uint32_t dead_count = 0;
    pthread_mutex_lock(&r->scene_mutex);
    if (!r->surface_ready || !r->swapchain || r->swapchain_image_count == 0
        || r->swapchain_extent.width == 0 || r->swapchain_extent.height == 0) {
        pthread_mutex_unlock(&r->scene_mutex);
        pthread_mutex_unlock(&r->render_mutex);
        return false;
    }
    snap = r->scene;
    detach_graveyard_slot(r, grave_slot, &dead, &dead_count);
    pthread_mutex_unlock(&r->scene_mutex);
    destroy_graveyard_textures(r, dead, dead_count);

    bool wants_sgsr1 = scene_starts_with_sgsr1(&snap);
    bool needs_fullres_offscreen = snap.effect_count > 0
        && (!wants_sgsr1 || snap.effect_count > 1);
    // offscreen[1] is reached only once the chain writes two distinct offscreen buffers: the
    // effect loop's dst_idx starts at 1 for a non-SGSR chain but at 0 for an SGSR1-led one
    // (scene goes to the separate SGSR source), so the threshold is >1 normally, >2 for SGSR.
    bool needs_second_offscreen = needs_fullres_offscreen
        && snap.effect_count > (wants_sgsr1 ? 2u : 1u);
    VkExtent2D sgsr1_source_extent = wants_sgsr1
        ? compute_sgsr1_source_extent(r, &snap)
        : r->swapchain_extent;

    if (wants_sgsr1) {
        VkPreRotatedRect dbg_sw = transform_rect_for_pretransform(
            snap.viewport_set ? snap.viewport_x : 0,
            snap.viewport_set ? snap.viewport_y : 0,
            snap.viewport_set ? snap.viewport_w : (int)r->surface_extent.width,
            snap.viewport_set ? snap.viewport_h : (int)r->surface_extent.height,
            r->swapchain_extent.width, r->swapchain_extent.height, r->swapchain_transform);
        VkPreRotatedRect dbg_src = scale_rect_from_swapchain(
            r, dbg_sw, sgsr1_source_extent.width, sgsr1_source_extent.height);
        const double sx = r->swapchain_extent.width
            ? (double)sgsr1_source_extent.width / (double)r->swapchain_extent.width : 1.0;
        const double exact_x = (double)dbg_sw.x * sx;
        const uint64_t sig = ((uint64_t)sgsr1_source_extent.width << 48)
                           ^ ((uint64_t)sgsr1_source_extent.height << 32)
                           ^ ((uint64_t)(uint32_t)dbg_sw.x << 16)
                           ^ (uint64_t)(uint32_t)dbg_sw.w;
        if (sig != r->sgsr1_dbg_sig) {
            r->sgsr1_dbg_sig = sig;
            VK_LOGI("SGSR1 geometry: swapchain %ux%u, screen %ux%u, dri3 %ux%u, source %ux%u | "
                    "viewport swapchain %d,%d %dx%d -> source %d,%d %dx%d (exact x %.3f, "
                    "err %.3f src px = %.3f target px)",
                    r->swapchain_extent.width, r->swapchain_extent.height,
                    snap.screen_width, snap.screen_height,
                    snap.source_width, snap.source_height,
                    sgsr1_source_extent.width, sgsr1_source_extent.height,
                    dbg_sw.x, dbg_sw.y, dbg_sw.w, dbg_sw.h,
                    dbg_src.x, dbg_src.y, dbg_src.w, dbg_src.h,
                    exact_x, (double)dbg_src.x - exact_x,
                    sx > 0.0 ? ((double)dbg_src.x - exact_x) / sx : 0.0);
        }
    }

    // Full-res ping-pong targets exist only when the chain needs them (SGSR-only writes its
    // low-res source straight to the swapchain). offscreen[1] is grown/freed lazily as the
    // chain crosses the threshold above; effect counts change on user action, not per frame,
    // so this doesn't thrash. Safe under render_mutex (no concurrent swapchain teardown).
    bool offscreen_dims_stale = !r->offscreen_built
        || r->offscreen[0].width != r->swapchain_extent.width
        || r->offscreen[0].height != r->swapchain_extent.height;
    bool second_present = r->offscreen[1].image != VK_NULL_HANDLE;
    if (needs_fullres_offscreen) {
        if (offscreen_dims_stale || (needs_second_offscreen && !second_present)) {
            wait_inflight_frames(r);
            create_offscreen(r, r->swapchain_extent.width, r->swapchain_extent.height,
                             needs_second_offscreen);
        } else if (!needs_second_offscreen && second_present) {
            wait_inflight_frames(r);  // chain no longer reaches offscreen[1]; reclaim it
            destroy_one_offscreen(r, &r->offscreen[1]);
        }
    } else if (r->offscreen_built) {
        wait_inflight_frames(r);
        destroy_offscreen(r);
    }
    // Only rebuild SGSR1 source on meaningful dim change. Tiny pixmap-size flicker (off-by-
    // one DRI3 jitter, transient resizes) used to thrash this allocation every frame and
    // stall the render thread on the full-device wait that preceded it.
    int sgsr1_dw = (int)r->sgsr1.width  - (int)sgsr1_source_extent.width;
    int sgsr1_dh = (int)r->sgsr1.height - (int)sgsr1_source_extent.height;
    if (sgsr1_dw < 0) sgsr1_dw = -sgsr1_dw;
    if (sgsr1_dh < 0) sgsr1_dh = -sgsr1_dh;
    bool sgsr1_dim_changed = r->sgsr1.built && (sgsr1_dw > 4 || sgsr1_dh > 4);
    if (wants_sgsr1 && (!r->sgsr1.built || sgsr1_dim_changed)) {
        wait_inflight_frames(r);
        create_sgsr1_resources(r, sgsr1_source_extent.width, sgsr1_source_extent.height);
    } else if (!wants_sgsr1 && r->sgsr1.built) {
        wait_inflight_frames(r);
        destroy_sgsr1_resources(r);
    }

    const bool framegen_on = r->framegen_requested || r->dis_requested;
    const bool use_dis = r->dis != NULL;
    bool via_composite = framegen_on && r->framegen_supported
                      && r->swapchain_transfer_dst;

    VkExtent2D composite_extent = r->swapchain_extent;
    if (via_composite) {
        if (r->lsfg) {
            VkExtent2D guest = compute_sgsr1_source_extent(r, &snap);
            vkr_lsfg_set_guest_extent(r->lsfg, guest.width, guest.height);
        } else if (use_dis) {
            if (snap.screen_width == 0 || snap.screen_height == 0) {
                via_composite = false;
            } else if (wants_sgsr1) {
                composite_extent = r->swapchain_extent;
            } else {
                composite_extent = compute_container_extent(r, &snap);
            }
        }
    }

    uint32_t framegen_capacity = 0;
    if (via_composite && (r->lsfg || r->dis) && r->swapchain_image_count > 2) {
        framegen_capacity = r->swapchain_image_count - 2;
        if (framegen_capacity > VKR_LSFG_MAX_GENERATIONS) {
            framegen_capacity = VKR_LSFG_MAX_GENERATIONS;
        }
        if (VK_FRAMES_IN_FLIGHT + framegen_capacity > VK_MAX_COMPOSITE_TARGETS) {
            framegen_capacity = VK_MAX_COMPOSITE_TARGETS - VK_FRAMES_IN_FLIGHT;
        }
    }

    if (via_composite) {
        uint32_t composite_needed = VK_FRAMES_IN_FLIGHT + framegen_capacity;
        bool composite_stale = !r->composite_built
            || r->composite_count != composite_needed
            || r->composite[0].width != composite_extent.width
            || r->composite[0].height != composite_extent.height;
        VkrDisContentRect dis_content = compute_dis_content_rect(
            r, &snap, composite_extent.width, composite_extent.height);
        bool chain_stale =
            (r->lsfg && vkr_lsfg_needs_rebuild(r->lsfg, r->swapchain_extent.width,
                                               r->swapchain_extent.height, r->swapchain_format))
            || (r->dis && vkr_dis_needs_rebuild(r->dis, composite_extent.width,
                                                composite_extent.height, r->swapchain_format,
                                                dis_content));
        if (composite_stale || chain_stale) {
            if (r->dis) {
                vkDeviceWaitIdle(r->device);
            } else {
                wait_inflight_frames(r);
            }
            if (!create_composite_targets(r, composite_extent.width,
                                          composite_extent.height, composite_needed)) {
                VK_LOGW("Composite targets unavailable; frame generation path disabled");
                r->framegen_supported = false;
                via_composite = false;
                framegen_capacity = 0;
            } else if (r->lsfg) {
                vkr_lsfg_forget_targets(r->lsfg);
                if (!vkr_lsfg_prepare(r->lsfg, r->swapchain_extent.width,
                                      r->swapchain_extent.height, r->swapchain_format)) {
                    framegen_capacity = 0;
                }
            } else if (r->dis) {
                vkr_dis_forget_targets(r->dis);
                if (!vkr_dis_prepare(r->dis, composite_extent.width,
                                     composite_extent.height, r->swapchain_format,
                                     dis_content)) {
                    framegen_capacity = 0;
                }
            }
        }
    } else if (r->composite_built) {
        wait_inflight_frames(r);
        destroy_composite_targets(r);
    }

    uint32_t framegen_planned = 0;
    if (via_composite) {
        const int32_t pending_mhz = __atomic_load_n(&r->framegen_refresh_mhz, __ATOMIC_RELAXED);
        r->framegen_refresh_rate = pending_mhz > 0 ? (float)pending_mhz / 1000.0f : 0.0f;
        if (r->lsfg) {
            vkr_lsfg_set_refresh_rate(r->lsfg, r->framegen_refresh_rate);
            framegen_planned = vkr_lsfg_plan(r->lsfg, framegen_capacity,
                                             __atomic_load_n(&r->framegen_source_frames,
                                                             __ATOMIC_RELAXED));
        } else if (r->dis) {
            vkr_dis_configure(r->dis, r->dis_scale, r->dis_target_fps, r->framegen_refresh_rate);
            framegen_planned = vkr_dis_plan(r->dis, framegen_capacity,
                                            __atomic_load_n(&r->framegen_source_frames,
                                                            __ATOMIC_RELAXED));
        }
    }

    VkCompositeTarget* composite = via_composite ? &r->composite[r->frame_index] : NULL;

    uint32_t image_index = 0;
    VkResult acq = vkAcquireNextImageKHR(r->device, r->swapchain, UINT64_MAX,
                                         f->image_available, VK_NULL_HANDLE, &image_index);
    bool recreate_after_present = false;
    if (acq == VK_ERROR_OUT_OF_DATE_KHR) {
        r->surface_ready = false;
        pthread_mutex_lock(&r->queue_mutex);
        vkQueueWaitIdle(r->graphics_queue);
        pthread_mutex_unlock(&r->queue_mutex);
        destroy_swapchain_resources(r);
        r->surface_ready = create_swapchain(r, r->surface_extent.width, r->surface_extent.height);
        pthread_mutex_unlock(&r->render_mutex);
        return false;
    } else if (acq == VK_SUBOPTIMAL_KHR) {
        if (!r->ignore_suboptimal) recreate_after_present = true;
    } else if (acq != VK_SUCCESS) {
        VK_LOGE("vkAcquireNextImageKHR -> %d", acq);
        pthread_mutex_unlock(&r->render_mutex);
        return false;
    }
    VkSemaphore render_finished = r->swapchain_render_finished[image_index];

    uint64_t gen_acquire_timeout = VK_FRAMEGEN_ACQUIRE_TIMEOUT_NS;
    if (r->framegen_refresh_rate > 1.0f) {
        gen_acquire_timeout = (uint64_t)(1000000000.0f / r->framegen_refresh_rate);
        if (gen_acquire_timeout < VK_FRAMEGEN_ACQUIRE_TIMEOUT_NS) {
            gen_acquire_timeout = VK_FRAMEGEN_ACQUIRE_TIMEOUT_NS;
        }
        if (gen_acquire_timeout > VK_FRAMEGEN_ACQUIRE_TIMEOUT_MAX_NS) {
            gen_acquire_timeout = VK_FRAMEGEN_ACQUIRE_TIMEOUT_MAX_NS;
        }
    }

    uint32_t gen_count = 0;
    uint32_t gen_image_index[VKR_LSFG_MAX_GENERATIONS] = {0};
    for (uint32_t g = 0; g < framegen_planned; g++) {
        uint32_t idx = 0;
        VkResult ga = vkAcquireNextImageKHR(r->device, r->swapchain, gen_acquire_timeout,
                                            f->image_available_gen[g], VK_NULL_HANDLE, &idx);
        if (ga != VK_SUCCESS && ga != VK_SUBOPTIMAL_KHR) {
            if (r->framegen_acquire_misses++ % 120 == 0) {
                VK_LOGW("Generated frame %u/%u dropped: acquire returned %d "
                        "(swapchain images=%u capacity=%u)",
                        g + 1, framegen_planned, (int)ga, r->swapchain_image_count,
                        framegen_capacity);
            }
            break;
        }
        gen_image_index[gen_count++] = idx;
    }

    // Sample the render rate down to the requested fps on a fixed grid, then acquire an encoder
    // image to blit this frame into (bounded timeout so a busy encoder skips rather than stalls).
    bool rec_this_frame = false;
    uint32_t rec_index = 0;
    bool rec_due = true;
    if (r->rec.active && r->rec.min_interval_ns > 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        uint64_t now = (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
        if (r->rec.last_capture_ns == 0) r->rec.last_capture_ns = now;
        if (now < r->rec.last_capture_ns) {
            rec_due = false;
        } else {
            r->rec.last_capture_ns += r->rec.min_interval_ns;
            if (now > r->rec.last_capture_ns + 4ULL * r->rec.min_interval_ns) {
                r->rec.last_capture_ns = now; // resync after a long stall
            }
        }
    }
    if (rec_due && r->rec.active && !r->rec.disabled && r->rec.swapchain) {
        VkResult racq = vkAcquireNextImageKHR(r->device, r->rec.swapchain, 16000000ULL,
                                              r->rec.acquire[r->frame_index], VK_NULL_HANDLE, &rec_index);
        if (racq == VK_SUCCESS || racq == VK_SUBOPTIMAL_KHR) {
            rec_this_frame = true;
            if (r->rec.captured++ == 0) VK_LOGI("record: first frame captured (rec_index=%u)", rec_index);
        } else if (racq == VK_ERROR_OUT_OF_DATE_KHR) {
            r->rec.disabled = true;
            VK_LOGW("record: mirror swapchain out of date; capture disabled");
        } else if ((r->rec.skipped++ % 120) == 0) {
            VK_LOGW("record: no encoder image ready (code=%d, skipped=%llu)",
                    racq, (unsigned long long)r->rec.skipped);
        }
    }

    vkResetFences(r->device, 1, &f->in_flight);

    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(f->cmd, &bi);

    bool has_effects = snap.effect_count > 0 && r->offscreen_built;
    if (snap.effect_count > 0) {
        // Don't enter the effect path if a required target's lazy creation failed (else we'd
        // record into a null framebuffer).
        bool full_ok = !needs_fullres_offscreen
            || (r->offscreen_built
                && (!needs_second_offscreen || r->offscreen[1].image != VK_NULL_HANDLE));
        has_effects = full_ok && (!wants_sgsr1 || r->sgsr1.built);
    }

    VkRenderPass final_pass = composite ? r->pipelines.composite_pass
                                        : r->pipelines.swapchain_pass;
    VkFramebuffer final_fb = composite ? composite->framebuffer
                                       : r->swapchain_framebuffers[image_index];
    VkExtent2D final_extent = composite
        ? (VkExtent2D){composite->width, composite->height}
        : r->swapchain_extent;

    VkClearValue clear = {0};
    clear.color.float32[0] = 0.0f;
    clear.color.float32[1] = 0.0f;
    clear.color.float32[2] = 0.0f;
    clear.color.float32[3] = 1.0f;

    if (has_effects) {
        VkOffscreen* scene_target = (wants_sgsr1 && r->sgsr1.built)
            ? &r->sgsr1.source
            : &r->offscreen[0];

        // Pass 1: render scene to either full-res effect input or SGSR1's low-res source.
        VkRenderPassBeginInfo rpbi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rpbi.renderPass = r->pipelines.offscreen_pass;
        rpbi.framebuffer = scene_target->framebuffer;
        rpbi.renderArea.extent.width = scene_target->width;
        rpbi.renderArea.extent.height = scene_target->height;
        rpbi.clearValueCount = 1;
        rpbi.pClearValues = &clear;
        vkCmdBeginRenderPass(f->cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
        draw_scene_pass(r, f->cmd, &snap, true,
                        scene_target->width, scene_target->height);
        vkCmdEndRenderPass(f->cmd);

        // Effect chain: source descriptor moves through ping-pong buffers. When SGSR1 is
        // first, the first source is low-res and SGSR1 writes full-res output.
        VkOffscreen* src_offscreen = scene_target;
        uint32_t dst_idx = (scene_target == &r->offscreen[0]) ? 1u : 0u;
        for (uint32_t i = 0; i < snap.effect_count; i++) {
            bool last = (i == snap.effect_count - 1);
            VkEffectSlot* eff = &snap.effects[i];

            if (last) {
                rpbi.renderPass = final_pass;
                rpbi.framebuffer = final_fb;
                rpbi.renderArea.extent = final_extent;
            } else {
                rpbi.renderPass = r->pipelines.offscreen_pass;
                rpbi.framebuffer = r->offscreen[dst_idx].framebuffer;
                rpbi.renderArea.extent.width = r->offscreen[dst_idx].width;
                rpbi.renderArea.extent.height = r->offscreen[dst_idx].height;
            }
            vkCmdBeginRenderPass(f->cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
            uint32_t target_w = last ? final_extent.width : rpbi.renderArea.extent.width;
            uint32_t target_h = last ? final_extent.height : rpbi.renderArea.extent.height;
            run_effect(r, f->cmd, eff, src_offscreen->descriptor_set, target_w, target_h, !last);
            vkCmdEndRenderPass(f->cmd);
            if (!last) {
                src_offscreen = &r->offscreen[dst_idx];
                dst_idx ^= 1u;
            }
        }
    } else {
        VkRenderPassBeginInfo rpbi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rpbi.renderPass = final_pass;
        rpbi.framebuffer = final_fb;
        rpbi.renderArea.extent = final_extent;
        rpbi.clearValueCount = 1;
        rpbi.pClearValues = &clear;
        vkCmdBeginRenderPass(f->cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
        draw_scene_pass(r, f->cmd, &snap, false,
                        final_extent.width, final_extent.height);
        vkCmdEndRenderPass(f->cmd);
    }

    if (composite) {
        if ((use_dis || r->lsfg) && framegen_capacity > 0) {
            if (use_dis) {
                vkr_dis_process(r->dis, f->cmd, composite->image,
                                composite->width, composite->height, gen_count);
            } else {
                vkr_lsfg_process(r->lsfg, f->cmd, composite->image,
                                 r->swapchain_extent.width, r->swapchain_extent.height, gen_count);
            }

            for (uint32_t g = 0; g < gen_count; g++) {
                const uint32_t idx = gen_image_index[g];
                if (r->swapchain_storage) {
                    if (use_dis) {
                        vkr_dis_generate_into(r->dis, f->cmd, g, idx,
                                              r->swapchain_images[idx], r->swapchain_views[idx],
                                              r->swapchain_extent.width,
                                              r->swapchain_extent.height,
                                              composite->image);
                    } else {
                        vkr_lsfg_generate_into(r->lsfg, f->cmd, g, idx,
                                               r->swapchain_images[idx], r->swapchain_views[idx],
                                               r->swapchain_extent.width,
                                               r->swapchain_extent.height);
                    }
                    vkr_image_barrier(f->cmd, r->swapchain_images[idx],
                                      VK_IMAGE_LAYOUT_GENERAL,
                                      VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                                      VK_ACCESS_SHADER_WRITE_BIT, 0);
                } else {
                    VkCompositeTarget* gt = &r->composite[VK_FRAMES_IN_FLIGHT + g];
                    if (use_dis) {
                        vkr_dis_generate_into(r->dis, f->cmd, g, VK_FRAMES_IN_FLIGHT + g,
                                              gt->image, gt->view,
                                              gt->width, gt->height,
                                              composite->image);
                    } else {
                        vkr_lsfg_generate_into(r->lsfg, f->cmd, g, VK_FRAMES_IN_FLIGHT + g,
                                               gt->image, gt->view,
                                               r->swapchain_extent.width,
                                               r->swapchain_extent.height);
                    }
                    blit_composite_to_swapchain(r, f->cmd, gt, r->swapchain_images[idx]);
                }
            }
            r->framegen_real_frames++;
            r->framegen_made_frames += gen_count;
            if ((r->framegen_real_frames % 120) == 0) {
                const uint64_t d_real = r->framegen_real_frames - r->framegen_log_real;
                const uint64_t d_made = r->framegen_made_frames - r->framegen_log_made;
                r->framegen_log_real = r->framegen_real_frames;
                r->framegen_log_made = r->framegen_made_frames;
                const uint64_t timed = r->framegen_timed_frames;
                const double draw_ms = timed ? (double)r->framegen_draw_ns / (double)timed / 1.0e6 : 0.0;
                const double gap_ms = timed ? (double)r->framegen_gap_ns / (double)timed / 1.0e6 : 0.0;
                r->framegen_draw_ns = 0;
                r->framegen_gap_ns = 0;
                r->framegen_timed_frames = 0;
                VK_LOGI("framegen delivered real=%llu made=%llu ratio=%.2f planned=%u got=%u "
                        "misses=%llu timeout=%.1fms images=%u capacity=%u draw=%.2fms gap=%.2fms",
                        (unsigned long long)r->framegen_real_frames,
                        (unsigned long long)r->framegen_made_frames,
                        d_real ? (double)d_made / (double)d_real : 0.0,
                        framegen_planned, gen_count,
                        (unsigned long long)r->framegen_acquire_misses,
                        (double)gen_acquire_timeout / 1000000.0,
                        r->swapchain_image_count, framegen_capacity,
                        draw_ms, gap_ms);
            }
        }

        if (use_dis && r->dis_debug_flow) {
            vkr_dis_debug_into(r->dis, f->cmd, r->swapchain_images[image_index],
                               r->swapchain_extent.width, r->swapchain_extent.height);
            vkr_image_barrier(f->cmd, r->swapchain_images[image_index],
                              VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                              VK_PIPELINE_STAGE_TRANSFER_BIT,
                              VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                              VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        } else {
            blit_composite_to_swapchain(r, f->cmd, composite, r->swapchain_images[image_index]);
        }
    }

    // Blit the final composited image (in PRESENT_SRC after the render pass) into the encoder image.
    if (rec_this_frame) {
        VkImage disp_img = r->swapchain_images[image_index];
        VkImage rec_img  = r->rec.images[rec_index];
        vkr_image_barrier(f->cmd, disp_img,
                          VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                          VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        vkr_image_barrier(f->cmd, rec_img,
                          VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                          VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          0, VK_ACCESS_TRANSFER_WRITE_BIT);

        VkImageBlit blit = {0};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1].x = (int32_t)r->swapchain_extent.width;
        blit.srcOffsets[1].y = (int32_t)r->swapchain_extent.height;
        blit.srcOffsets[1].z = 1;
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[1].x = (int32_t)r->rec.extent.width;
        blit.dstOffsets[1].y = (int32_t)r->rec.extent.height;
        blit.dstOffsets[1].z = 1;
        vkCmdBlitImage(f->cmd, disp_img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       rec_img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

        vkr_image_barrier(f->cmd, disp_img,
                          VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                          VK_ACCESS_TRANSFER_READ_BIT, 0);

        // Record UI: blend the overlay over the game, else just transition to presentable.
        bool do_ui = r->rec.ui_enabled && r->rec.fb_built && r->rec.ui_pipeline
                     && r->rec.ui_texture && r->rec.ui_texture->ready
                     && rec_index < r->rec.image_count && r->rec.framebuffers[rec_index];
        if (do_ui) {
            vkr_image_barrier(f->cmd, rec_img,
                              VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                              VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                              VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            VkRenderPassBeginInfo rp = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            rp.renderPass = r->rec.ui_pass;
            rp.framebuffer = r->rec.framebuffers[rec_index];
            rp.renderArea.extent = r->rec.extent;
            vkCmdBeginRenderPass(f->cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(f->cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, r->rec.ui_pipeline);
            VkViewport vp = {0, 0, (float)r->rec.extent.width, (float)r->rec.extent.height, 0.0f, 1.0f};
            VkRect2D scr = {{0, 0}, {r->rec.extent.width, r->rec.extent.height}};
            vkCmdSetViewport(f->cmd, 0, 1, &vp);
            vkCmdSetScissor(f->cmd, 0, 1, &scr);
            float pc[6] = {(float)r->rec.extent.width, (float)r->rec.extent.height, 0.0f, 0.0f, 0.0f, 0.0f};
            vkCmdPushConstants(f->cmd, r->pipelines.effect_layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), pc);
            vkCmdBindDescriptorSets(f->cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, r->pipelines.effect_layout,
                                    0, 1, &r->rec.ui_texture->descriptor_set, 0, NULL);
            vkCmdDraw(f->cmd, 3, 1, 0, 0);
            vkCmdEndRenderPass(f->cmd); // leaves rec image in PRESENT_SRC
        } else {
            vkr_image_barrier(f->cmd, rec_img,
                              VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                              VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                              VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        }
    }

    vkEndCommandBuffer(f->cmd);

    #define VK_MAX_FRAME_SEMAPHORES (2 + VKR_LSFG_MAX_GENERATIONS)
    VkSemaphore wait_sems[VK_MAX_FRAME_SEMAPHORES];
    VkPipelineStageFlags wait_stages[VK_MAX_FRAME_SEMAPHORES];
    VkSemaphore signal_sems[VK_MAX_FRAME_SEMAPHORES];
    uint32_t wait_count = 0;
    uint32_t signal_count = 0;

    wait_sems[wait_count] = f->image_available;
    wait_stages[wait_count] =
        composite ? (VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT)
                  : VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    wait_count++;
    signal_sems[signal_count++] = render_finished;

    for (uint32_t g = 0; g < gen_count; g++) {
        wait_sems[wait_count] = f->image_available_gen[g];
        wait_stages[wait_count] = r->swapchain_storage
            ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
            : VK_PIPELINE_STAGE_TRANSFER_BIT;
        wait_count++;
        signal_sems[signal_count++] = r->swapchain_render_finished[gen_image_index[g]];
    }

    if (rec_this_frame) {
        wait_sems[wait_count] = r->rec.acquire[r->frame_index];
        wait_stages[wait_count] = VK_PIPELINE_STAGE_TRANSFER_BIT;
        wait_count++;
        signal_sems[signal_count++] = r->rec.present_ready[rec_index];
    }

    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = wait_count;
    si.pWaitSemaphores = wait_sems;
    si.pWaitDstStageMask = wait_stages;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &f->cmd;
    si.signalSemaphoreCount = signal_count;
    si.pSignalSemaphores = signal_sems;

    pthread_mutex_lock(&r->queue_mutex);
    VkResult sr = vkQueueSubmit(r->graphics_queue, 1, &si, f->in_flight);
    pthread_mutex_unlock(&r->queue_mutex);
    if (sr != VK_SUCCESS) {
        VK_LOGE("vkQueueSubmit -> %d", sr);
        // The frame fence was reset before submit. If submit fails, nothing will ever signal
        // it, so restore a signaled fence before returning or the next frame can block forever.
        vkDestroyFence(r->device, f->in_flight, NULL);
        VkFenceCreateInfo rfi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        rfi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateFence(r->device, &rfi, NULL, &f->in_flight) != VK_SUCCESS) {
            f->in_flight = VK_NULL_HANDLE;
            VK_LOGE("Failed to recreate frame fence after submit failure");
        }
        vkDeviceWaitIdle(r->device);
        VkSemaphoreCreateInfo asi = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if (f->image_available) {
            vkDestroySemaphore(r->device, f->image_available, NULL);
            f->image_available = VK_NULL_HANDLE;
            vkCreateSemaphore(r->device, &asi, NULL, &f->image_available);
        }
        for (uint32_t g = 0; g < VKR_LSFG_MAX_GENERATIONS; g++) {
            if (!f->image_available_gen[g]) continue;
            vkDestroySemaphore(r->device, f->image_available_gen[g], NULL);
            f->image_available_gen[g] = VK_NULL_HANDLE;
            vkCreateSemaphore(r->device, &asi, NULL, &f->image_available_gen[g]);
        }
        r->surface_ready = false;
        destroy_swapchain_resources(r);
        r->surface_ready = create_swapchain(r, r->surface_extent.width,
                                            r->surface_extent.height);
        pthread_mutex_unlock(&r->render_mutex);
        return false;
    }

    VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &render_finished;
    pi.swapchainCount = 1;
    pi.pSwapchains = &r->swapchain;
    pi.pImageIndices = &image_index;

    bool gen_present_out_of_date = false;
    pthread_mutex_lock(&r->queue_mutex);
    for (uint32_t g = 0; g < gen_count; g++) {
        VkPresentInfoKHR gpi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        gpi.waitSemaphoreCount = 1;
        gpi.pWaitSemaphores = &r->swapchain_render_finished[gen_image_index[g]];
        gpi.swapchainCount = 1;
        gpi.pSwapchains = &r->swapchain;
        gpi.pImageIndices = &gen_image_index[g];
        VkResult gpr = vkQueuePresentKHR(r->graphics_queue, &gpi);
        if (gpr != VK_SUCCESS && gpr != VK_SUBOPTIMAL_KHR) {
            if (gpr == VK_ERROR_OUT_OF_DATE_KHR) gen_present_out_of_date = true;
            if (r->framegen_present_failures++ % 120 == 0) {
                VK_LOGW("generated frame present failed (%d, failures=%llu)", gpr,
                        (unsigned long long)r->framegen_present_failures);
            }
        } else {
            __atomic_fetch_add(&r->presented_frames, 1, __ATOMIC_RELAXED);
        }
    }
    VkResult pr = vkQueuePresentKHR(r->graphics_queue, &pi);
    if (pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR) {
        __atomic_fetch_add(&r->presented_frames, 1, __ATOMIC_RELAXED);
    }
    // Present the mirror separately so its result doesn't disturb the display recreate logic below.
    if (rec_this_frame) {
        VkPresentInfoKHR rpi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        rpi.waitSemaphoreCount = 1;
        rpi.pWaitSemaphores = &r->rec.present_ready[rec_index];
        rpi.swapchainCount = 1;
        rpi.pSwapchains = &r->rec.swapchain;
        rpi.pImageIndices = &rec_index;
        VkResult rpr = vkQueuePresentKHR(r->graphics_queue, &rpi);
        if (rpr != VK_SUCCESS && rpr != VK_SUBOPTIMAL_KHR) {
            r->rec.disabled = true;
            VK_LOGW("record: mirror present failed (%d); capture disabled", rpr);
        }
    }
    pthread_mutex_unlock(&r->queue_mutex);

    bool present_suboptimal = (pr == VK_SUBOPTIMAL_KHR) && !r->ignore_suboptimal;
    if (recreate_after_present || pr == VK_ERROR_OUT_OF_DATE_KHR || present_suboptimal
        || gen_present_out_of_date) {
        r->surface_ready = false;
        pthread_mutex_lock(&r->queue_mutex);
        vkQueueWaitIdle(r->graphics_queue);
        pthread_mutex_unlock(&r->queue_mutex);
        destroy_swapchain_resources(r);
        r->surface_ready = create_swapchain(r, r->surface_extent.width, r->surface_extent.height);
    }

    pthread_mutex_unlock(&r->render_mutex);

    r->frame_index = (r->frame_index + 1) % VK_FRAMES_IN_FLIGHT;
    r->graveyard_index = (r->graveyard_index + 1) % (VK_FRAMES_IN_FLIGHT + 1);

    const uint64_t draw_end_ns = vkr_monotonic_ns();
    if (r->framegen_last_end_ns != 0 && draw_begin_ns > r->framegen_last_end_ns) {
        r->framegen_gap_ns += draw_begin_ns - r->framegen_last_end_ns;
    }
    if (draw_end_ns > draw_begin_ns) r->framegen_draw_ns += draw_end_ns - draw_begin_ns;
    r->framegen_last_end_ns = draw_end_ns;
    r->framegen_timed_frames++;
    if (r->lsfg) {
        vkr_lsfg_note_frame(r->lsfg, draw_end_ns > draw_begin_ns ? draw_end_ns - draw_begin_ns : 0,
                            gen_count);
    }

    return true;
}

// ============================================================
// JNI entry points
// ============================================================

#define JNI_FN(name) Java_com_winlator_cmod_runtime_display_renderer_VulkanRenderer_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv* env, jclass clazz,
                                              jboolean enableValidationLayers,
                                              jstring driverName,
                                              jobject context) {
    (void)clazz;
    VkRenderer* r = calloc(1, sizeof(VkRenderer));
    if (!r) return 0;
    r->target_present_mode = VK_PRESENT_MODE_FIFO_KHR;
    r->validation_enabled = (enableValidationLayers == JNI_TRUE);
    pthread_mutex_init(&r->scene_mutex, NULL);
    pthread_mutex_init(&r->queue_mutex, NULL);
    pthread_mutex_init(&r->texture_mutex, NULL);
    pthread_mutex_init(&r->render_mutex, NULL);
    pthread_mutex_init(&r->descriptor_mutex, NULL);

    const char* driver_name_c = NULL;
    if (driverName != NULL) driver_name_c = (*env)->GetStringUTFChars(env, driverName, NULL);
    r->vulkan_handle = winlator_open_vulkan(env, context, driver_name_c);
    if (driver_name_c != NULL) (*env)->ReleaseStringUTFChars(env, driverName, driver_name_c);

    if (!r->vulkan_handle) {
        VK_LOGE("winlator_open_vulkan returned NULL");
        goto fail;
    }
    if (!vkd_init(r->vulkan_handle)) {
        VK_LOGE("vkd_init failed");
        goto fail;
    }

    if (!create_instance(r)) goto fail;
    if (!pick_physical_device(r)) goto fail;
    if (!create_device(r)) goto fail;
    query_device_caps(r);
    if (!create_command_pool(r)) goto fail;
    if (!create_descriptor_pool(r, r->caps.descriptor_pool_capacity)) goto fail;
    if (!create_quad_vbo(r)) goto fail;
    if (!vkr_create_sampler(r, VK_NULL_HANDLE, &r->shared_sampler)) goto fail;
    {
        VkSamplerCreateInfo nsi = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        nsi.magFilter = VK_FILTER_NEAREST;
        nsi.minFilter = VK_FILTER_NEAREST;
        nsi.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        nsi.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        nsi.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        nsi.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
        nsi.unnormalizedCoordinates = VK_FALSE;
        nsi.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        if (vkCreateSampler(r->device, &nsi, NULL, &r->shared_sampler_nearest) != VK_SUCCESS) goto fail;
    }
    if (r->ext_filter_cubic) {
        VkSamplerCreateInfo csi = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        csi.magFilter = VK_FILTER_CUBIC_EXT;
        csi.minFilter = VK_FILTER_LINEAR;
        csi.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        csi.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        csi.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        csi.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
        csi.unnormalizedCoordinates = VK_FALSE;
        csi.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        if (vkCreateSampler(r->device, &csi, NULL, &r->shared_sampler_cubic) != VK_SUCCESS) {
            r->shared_sampler_cubic = VK_NULL_HANDLE;
            r->ext_filter_cubic = false;
        }
    }
    if (!vkr_staging_pool_init(r)) goto fail;
    vkr_suballoc_init(r);

    r->initialized = true;
    return (jlong)(intptr_t)r;

fail:
    VK_LOGE("VulkanRenderer init failed");
    vkr_staging_pool_destroy(r);
    vkr_suballoc_destroy(r);  // safe if never initialized
    if (r->shared_sampler) vkDestroySampler(r->device, r->shared_sampler, NULL);
    if (r->shared_sampler_nearest) vkDestroySampler(r->device, r->shared_sampler_nearest, NULL);
    if (r->shared_sampler_cubic) vkDestroySampler(r->device, r->shared_sampler_cubic, NULL);
    if (r->cmd_pool) vkDestroyCommandPool(r->device, r->cmd_pool, NULL);
    free(r->descriptor_free_list); r->descriptor_free_list = NULL; r->descriptor_free_count = 0;
    if (r->descriptor_pool) vkDestroyDescriptorPool(r->device, r->descriptor_pool, NULL);
    if (r->device) vkDestroyDevice(r->device, NULL);
    destroy_debug_messenger(r);
    if (r->instance) vkDestroyInstance(r->instance, NULL);
    vkd_unload();
    if (r->vulkan_handle) { dlclose(r->vulkan_handle); r->vulkan_handle = NULL; }
    pthread_mutex_destroy(&r->scene_mutex);
    pthread_mutex_destroy(&r->queue_mutex);
    pthread_mutex_destroy(&r->texture_mutex);
    pthread_mutex_destroy(&r->render_mutex);
    pthread_mutex_destroy(&r->descriptor_mutex);
    free(r);
    return 0;
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    if (r->device) vkDeviceWaitIdle(r->device);

    // Drain any in-flight uploads and tear down the staging pool before destroying images.
    vkr_staging_pool_destroy(r);

    for (uint32_t i = 0; i < VK_FRAMES_IN_FLIGHT + 1; i++) {
        VkTexture** dead = NULL;
        uint32_t dead_count = 0;
        detach_graveyard_slot(r, i, &dead, &dead_count);
        destroy_graveyard_textures(r, dead, dead_count);
    }
    vkr_texture_destroy_all_live(r);
    free(r->live_textures);

    // All textures gone -> all spans returned. Reclaim pooled blocks + batch scratch.
    vkr_suballoc_destroy(r);
    free(r->batch_entry_scratch);
    free(r->batch_prepared_scratch);

    destroy_record_swapchain(r);
    destroy_sgsr1_resources(r);
    destroy_offscreen(r);
    destroy_lsfg(r);
    destroy_dis(r);
    free(r->lsfg_cache_path);
    r->lsfg_cache_path = NULL;

    destroy_swapchain(r);
    destroy_pipelines(r);
    destroy_quad_vbo(r);

    for (uint32_t i = 0; i < VK_FRAMES_IN_FLIGHT; i++) {
        VkFrame* f = &r->frames[i];
        if (f->image_available) vkDestroySemaphore(r->device, f->image_available, NULL);
        for (uint32_t g = 0; g < VKR_LSFG_MAX_GENERATIONS; g++) {
            if (f->image_available_gen[g]) {
                vkDestroySemaphore(r->device, f->image_available_gen[g], NULL);
            }
        }
        if (f->in_flight)       vkDestroyFence(r->device, f->in_flight, NULL);
    }

    if (r->shared_sampler) vkDestroySampler(r->device, r->shared_sampler, NULL);
    if (r->shared_sampler_nearest) vkDestroySampler(r->device, r->shared_sampler_nearest, NULL);
    if (r->shared_sampler_cubic) vkDestroySampler(r->device, r->shared_sampler_cubic, NULL);
    if (r->cmd_pool) vkDestroyCommandPool(r->device, r->cmd_pool, NULL);
    free(r->descriptor_free_list); r->descriptor_free_list = NULL; r->descriptor_free_count = 0;
    if (r->descriptor_pool) vkDestroyDescriptorPool(r->device, r->descriptor_pool, NULL);
    if (r->surface) vkDestroySurfaceKHR(r->instance, r->surface, NULL);
    if (r->anw)     ANativeWindow_release(r->anw);
    if (r->device)  vkDestroyDevice(r->device, NULL);
    destroy_debug_messenger(r);
    if (r->instance)vkDestroyInstance(r->instance, NULL);

    // Clear dispatch BEFORE dlclose so a stray call from another thread faults on NULL
    // rather than jumping into freed library memory.
    vkd_unload();
    if (r->vulkan_handle) { dlclose(r->vulkan_handle); r->vulkan_handle = NULL; }

    pthread_mutex_destroy(&r->scene_mutex);
    pthread_mutex_destroy(&r->queue_mutex);
    pthread_mutex_destroy(&r->texture_mutex);
    pthread_mutex_destroy(&r->render_mutex);
    pthread_mutex_destroy(&r->descriptor_mutex);
    free(r);
}

// Lifecycle helper: take render_mutex (waits for any in-flight render to finish), then
// briefly take scene_mutex to clear surface_ready so producers see a consistent state.
// Returns with only render_mutex held; caller must release it.
static void lifecycle_begin(VkRenderer* r) {
    pthread_mutex_lock(&r->render_mutex);
    pthread_mutex_lock(&r->scene_mutex);
    r->surface_ready = false;
    pthread_mutex_unlock(&r->scene_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSurfaceCreated)(JNIEnv* env, jclass clazz, jlong handle, jobject surface) {
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    lifecycle_begin(r);

    if (r->surface) {
        vkDeviceWaitIdle(r->device);
        destroy_sgsr1_resources(r);
        destroy_offscreen(r);
        destroy_swapchain(r);
        vkDestroySurfaceKHR(r->instance, r->surface, NULL);
        r->surface = VK_NULL_HANDLE;
    }
    if (r->anw) {
        ANativeWindow_release(r->anw);
        r->anw = NULL;
    }

    r->anw = ANativeWindow_fromSurface(env, surface);
    if (!r->anw) {
        VK_LOGE("ANativeWindow_fromSurface failed");
        pthread_mutex_unlock(&r->render_mutex);
        return;
    }

    VkAndroidSurfaceCreateInfoKHR aci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    aci.window = r->anw;
    if (vkCreateAndroidSurfaceKHR(r->instance, &aci, NULL, &r->surface) != VK_SUCCESS) {
        VK_LOGE("vkCreateAndroidSurfaceKHR failed");
        ANativeWindow_release(r->anw);
        r->anw = NULL;
        pthread_mutex_unlock(&r->render_mutex);
        return;
    }

    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(r->physical_device, r->graphics_queue_family,
                                         r->surface, &supported);
    if (!supported) {
        VK_LOGE("Selected queue family does not support presentation");
    }

    // Wait for SurfaceHolder.surfaceChanged() before creating the swapchain. On Android
    // the surface can be created while the activity is still completing a rotation, so
    // creating it here can lock in stale portrait dimensions for a landscape launch.
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSurfaceChanged)(JNIEnv* env, jclass clazz, jlong handle, jint w, jint h) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r || !r->surface) return;

    lifecycle_begin(r);
    vkDeviceWaitIdle(r->device);
    destroy_sgsr1_resources(r);
    destroy_offscreen(r);
    destroy_swapchain(r);
    if (!create_swapchain(r, (uint32_t)w, (uint32_t)h)) {
        VK_LOGE("Swapchain re-create failed in nativeSurfaceChanged");
    } else {
        pthread_mutex_lock(&r->scene_mutex);
        r->surface_ready = true;
        pthread_mutex_unlock(&r->scene_mutex);
    }
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeStartRecording)(JNIEnv* env, jclass clazz, jlong handle, jobject surface, jint fps, jboolean recordUI) {
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r || !surface) return JNI_FALSE;

    lifecycle_begin(r);

    jboolean ok = JNI_FALSE;
    do {
        if (r->rec.active) { ok = JNI_TRUE; break; }

        ANativeWindow* anw = ANativeWindow_fromSurface(env, surface);
        if (!anw) { VK_LOGE("record: ANativeWindow_fromSurface failed"); break; }
        r->rec.anw = anw;
        r->rec.ui_enabled = (recordUI == JNI_TRUE);

        if (r->device) vkDeviceWaitIdle(r->device);

        // Recreate the display swapchain so its images carry TRANSFER_SRC (the blit source).
        r->record_blit_src = true;
        if (r->surface) {
            destroy_sgsr1_resources(r);
            destroy_offscreen(r);
            destroy_swapchain(r);
            if (!create_swapchain(r, r->surface_extent.width, r->surface_extent.height)) {
                VK_LOGE("record: display swapchain recreate failed");
                r->record_blit_src = false;
                ANativeWindow_release(r->rec.anw); r->rec.anw = NULL;
                break;
            }
        }

        if (!create_record_swapchain(r)) {
            VK_LOGE("record: create_record_swapchain failed; recording will not capture");
            r->record_blit_src = false; // revert the display swapchain to its plain usage
            if (r->surface) {
                destroy_sgsr1_resources(r);
                destroy_offscreen(r);
                destroy_swapchain(r);
                create_swapchain(r, r->surface_extent.width, r->surface_extent.height);
            }
            break;
        }

        r->rec.active = true;
        r->rec.disabled = false;
        r->rec.captured = 0;
        r->rec.skipped = 0;
        r->rec.min_interval_ns = (fps > 0) ? (uint64_t)(1000000000.0 / (double)fps) : 0;
        r->rec.last_capture_ns = 0;
        ok = JNI_TRUE;
        VK_LOGI("record: started (display %ux%u -> mirror %ux%u @%dfps)",
                r->swapchain_extent.width, r->swapchain_extent.height,
                r->rec.extent.width, r->rec.extent.height, (int)fps);
    } while (0);

    pthread_mutex_lock(&r->scene_mutex);
    r->surface_ready = (r->swapchain != VK_NULL_HANDLE);
    pthread_mutex_unlock(&r->scene_mutex);
    pthread_mutex_unlock(&r->render_mutex);
    return ok;
}

JNIEXPORT void JNICALL JNI_FN(nativeStopRecording)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    lifecycle_begin(r);
    if (r->device) vkDeviceWaitIdle(r->device);

    destroy_record_swapchain(r);

    if (r->record_blit_src) {
        r->record_blit_src = false;
        if (r->surface) {
            destroy_sgsr1_resources(r);
            destroy_offscreen(r);
            destroy_swapchain(r);
            if (!create_swapchain(r, r->surface_extent.width, r->surface_extent.height)) {
                VK_LOGE("record: display swapchain restore failed after stop");
            }
        }
    }

    pthread_mutex_lock(&r->scene_mutex);
    r->surface_ready = (r->swapchain != VK_NULL_HANDLE);
    pthread_mutex_unlock(&r->scene_mutex);
    pthread_mutex_unlock(&r->render_mutex);
}

// Upload the latest overlay snapshot (direct ByteBuffer of BGRA pixels) for the Record UI composite.
JNIEXPORT void JNICALL JNI_FN(nativeUpdateRecordUITexture)(JNIEnv* env, jclass clazz, jlong handle,
                                                          jobject buffer, jint w, jint h) {
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r || !buffer || w <= 0 || h <= 0) return;
    void* data = (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!data || cap < (jlong)w * (jlong)h * 4) return;

    pthread_mutex_lock(&r->render_mutex);
    if (r->rec.active && r->rec.ui_enabled) {
        size_t bytes = (size_t)w * (size_t)h * 4u;
        if (r->rec.ui_texture == NULL
            || r->rec.ui_texture->width != (uint32_t)w
            || r->rec.ui_texture->height != (uint32_t)h) {
            if (r->rec.ui_texture) { vkr_texture_destroy(r, r->rec.ui_texture); r->rec.ui_texture = NULL; }
            r->rec.ui_texture = vkr_texture_create_uploaded(r, (uint32_t)w, (uint32_t)h, data, bytes, (uint32_t)w);
        } else {
            vkr_texture_update(r, r->rec.ui_texture, (uint32_t)w, (uint32_t)h, data, bytes,
                               (uint32_t)w, 0, 0, (uint32_t)w, (uint32_t)h);
        }
    }
    pthread_mutex_unlock(&r->render_mutex);
}

// Dimensions of the composited image (swapchain extent), which differ from the SurfaceView size
// under display rotation; the encoder is sized to these so the capture isn't squished.
JNIEXPORT jint JNICALL JNI_FN(nativeGetRecordWidth)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    return (r != NULL) ? (jint)r->swapchain_extent.width : 0;
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetRecordHeight)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    return (r != NULL) ? (jint)r->swapchain_extent.height : 0;
}

// Clockwise degrees to rotate the recording for upright playback (undoes the display preTransform).
JNIEXPORT jint JNICALL JNI_FN(nativeGetRecordOrientationHint)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (r == NULL) return 0;
    switch (r->swapchain_transform) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:  return 270;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: return 180;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: return 90;
        default: return 0;
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeSurfaceDestroyed)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    lifecycle_begin(r);

    if (r->device) vkDeviceWaitIdle(r->device);
    destroy_sgsr1_resources(r);
    destroy_offscreen(r);
    destroy_swapchain(r);
    if (r->surface) {
        vkDestroySurfaceKHR(r->instance, r->surface, NULL);
        r->surface = VK_NULL_HANDLE;
    }
    if (r->anw) {
        ANativeWindow_release(r->anw);
        r->anw = NULL;
    }
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeRenderFrame)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r || !r->surface_ready) return JNI_FALSE;
    return record_and_submit_frame(r) ? JNI_TRUE : JNI_FALSE;
}

// Scene byte buffer layout (must mirror VulkanRenderer.java offsets). Native-endian, packed.
// Using a single direct ByteBuffer instead of 6 separate jarray params avoids per-frame JNI
// critical regions (each ~3-8µs on ART) and the temporary array shadow allocations they
// trigger.
#define SCENE_OFF_CURSOR_HANDLE      0
#define SCENE_OFF_WINDOW_HANDLES     8        /* int64 × VK_MAX_RENDERABLE_WINDOWS */
#define SCENE_OFF_WINDOW_COUNT       520
#define SCENE_OFF_CURSOR_VISIBLE     524
#define SCENE_OFF_CURSOR_GEOM        528      /* int32 × 4 */
#define SCENE_OFF_XFORM              544      /* float32 × 6 */
#define SCENE_OFF_VIEWPORT           568      /* int32 × 4 */
#define SCENE_OFF_SCISSOR_ENABLED    584
#define SCENE_OFF_SCISSOR            588      /* int32 × 4 */
#define SCENE_OFF_SCREEN_W           604
#define SCENE_OFF_SCREEN_H           608
#define SCENE_OFF_EFFECT_COUNT       612
#define SCENE_OFF_EFFECT_TYPES       616      /* int32 × VK_MAX_EFFECTS */
#define SCENE_OFF_EFFECT_PARAMS      648      /* float32 × VK_MAX_EFFECTS × 4 */
#define SCENE_OFF_WINDOW_GEOM        776      /* int32 × VK_MAX_RENDERABLE_WINDOWS × 4 */
#define SCENE_OFF_WINDOW_UV          1800     /* float32 × VK_MAX_RENDERABLE_WINDOWS × 4 */
#define SCENE_OFF_SWAP_RB            2824
#define SCENE_OFF_SOURCE_W           2828
#define SCENE_OFF_SOURCE_H           2832
#define SCENE_BUF_SIZE               2836

JNIEXPORT void JNICALL JNI_FN(nativeSetScene)(JNIEnv* env, jclass clazz, jlong handle,
                                              jobject sceneBuf)
{
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r || !sceneBuf) return;

    const uint8_t* base = (const uint8_t*)(*env)->GetDirectBufferAddress(env, sceneBuf);
    if (!base) return;
    // Defensive: a future Java-side layout change with a stale SCENE_BUF_SIZE would silently
    // read past the buffer here. GetDirectBufferCapacity is one JNI call; cheap insurance.
    jlong cap = (*env)->GetDirectBufferCapacity(env, sceneBuf);
    if (cap < SCENE_BUF_SIZE) {
        VK_LOGE("nativeSetScene: scene buffer too small (%lld < %d)",
                (long long)cap, SCENE_BUF_SIZE);
        return;
    }

    pthread_mutex_lock(&r->scene_mutex);
    VkScene* s = &r->scene;

    // Windows
    int32_t window_count;
    memcpy(&window_count, base + SCENE_OFF_WINDOW_COUNT, sizeof(int32_t));
    if (window_count < 0) window_count = 0;
    if (window_count > VK_MAX_RENDERABLE_WINDOWS) window_count = VK_MAX_RENDERABLE_WINDOWS;
    s->window_count = (uint32_t)window_count;
    for (int32_t i = 0; i < window_count; i++) {
        VkRenderableWindow* w = &s->windows[i];
        int64_t h64;
        memcpy(&h64, base + SCENE_OFF_WINDOW_HANDLES + (size_t)i * 8, sizeof(int64_t));
        w->texture = (VkTexture*)(intptr_t)h64;
        int32_t g[4];
        memcpy(g, base + SCENE_OFF_WINDOW_GEOM + (size_t)i * 16, sizeof(g));
        w->x = g[0];
        w->y = g[1];
        w->width  = (uint32_t)g[2];
        w->height = (uint32_t)g[3];
        float uv[4];
        memcpy(uv, base + SCENE_OFF_WINDOW_UV + (size_t)i * 16, sizeof(uv));
        w->u0 = uv[0];
        w->v0 = uv[1];
        w->u1 = uv[2];
        w->v1 = uv[3];
        w->direct_scanout = false;
    }

    // Cursor
    int32_t cursor_visible;
    memcpy(&cursor_visible, base + SCENE_OFF_CURSOR_VISIBLE, sizeof(int32_t));
    s->cursor_visible = cursor_visible != 0;
    int64_t cursor_h64;
    memcpy(&cursor_h64, base + SCENE_OFF_CURSOR_HANDLE, sizeof(int64_t));
    s->cursor_texture = (VkTexture*)(intptr_t)cursor_h64;
    int32_t cg[4];
    memcpy(cg, base + SCENE_OFF_CURSOR_GEOM, sizeof(cg));
    s->cursor_x = cg[0];
    s->cursor_y = cg[1];
    s->cursor_width  = (uint32_t)cg[2];
    s->cursor_height = (uint32_t)cg[3];

    // XForm
    memcpy(s->xform, base + SCENE_OFF_XFORM, sizeof(float) * 6);

    // Viewport (set bit derived from positive dims, matching the previous semantics)
    int32_t vp[4];
    memcpy(vp, base + SCENE_OFF_VIEWPORT, sizeof(vp));
    s->viewport_x = vp[0];
    s->viewport_y = vp[1];
    s->viewport_w = vp[2];
    s->viewport_h = vp[3];
    s->viewport_set = (vp[2] > 0 && vp[3] > 0);

    // Scissor — Java sends an explicit enabled flag (replaces the old "scissor==null" check)
    int32_t scissor_enabled;
    memcpy(&scissor_enabled, base + SCENE_OFF_SCISSOR_ENABLED, sizeof(int32_t));
    int32_t sc[4];
    memcpy(sc, base + SCENE_OFF_SCISSOR, sizeof(sc));
    s->scissor_x = sc[0];
    s->scissor_y = sc[1];
    s->scissor_w = sc[2];
    s->scissor_h = sc[3];
    s->scissor_enabled = (scissor_enabled != 0) && (sc[2] > 0) && (sc[3] > 0);

    int32_t screen_w, screen_h;
    memcpy(&screen_w, base + SCENE_OFF_SCREEN_W, sizeof(int32_t));
    memcpy(&screen_h, base + SCENE_OFF_SCREEN_H, sizeof(int32_t));
    s->screen_width  = (uint32_t)screen_w;
    s->screen_height = (uint32_t)screen_h;
    int32_t swap_rb;
    memcpy(&swap_rb, base + SCENE_OFF_SWAP_RB, sizeof(int32_t));
    s->swap_rb = swap_rb != 0;
    int32_t source_w, source_h;
    memcpy(&source_w, base + SCENE_OFF_SOURCE_W, sizeof(int32_t));
    memcpy(&source_h, base + SCENE_OFF_SOURCE_H, sizeof(int32_t));
    s->source_width = source_w > 0 ? (uint32_t)source_w : 0;
    s->source_height = source_h > 0 ? (uint32_t)source_h : 0;

    // Effects
    int32_t effect_count;
    memcpy(&effect_count, base + SCENE_OFF_EFFECT_COUNT, sizeof(int32_t));
    if (effect_count < 0) effect_count = 0;
    if (effect_count > VK_MAX_EFFECTS) effect_count = VK_MAX_EFFECTS;
    s->effect_count = (uint32_t)effect_count;
    for (int32_t i = 0; i < effect_count; i++) {
        int32_t etype;
        memcpy(&etype, base + SCENE_OFF_EFFECT_TYPES + (size_t)i * 4, sizeof(int32_t));
        s->effects[i].type = (VkEffectType)etype;
        float ep[4];
        memcpy(ep, base + SCENE_OFF_EFFECT_PARAMS + (size_t)i * 16, sizeof(ep));
        s->effects[i].mode   = (int)ep[0];
        s->effects[i].param0 = ep[1];
        s->effects[i].param1 = ep[2];
        s->effects[i].param2 = ep[3];
    }

    pthread_mutex_unlock(&r->scene_mutex);
    // Offscreen rebuild is handled in record_and_submit_frame under render_mutex; nothing
    // here needs to touch swapchain-tied resources.
}

// FPS pacing is enforced by delaying PresentIdleNotify (buffer-release back-pressure) on the
// Present extension's pacer thread, plus the swapchain present mode + Choreographer-coalesced
// render requests. The compositor used to run its own sleep+busy-spin here too, which
// duplicated the pacing and burned CPU; this entry point is kept as a no-op for Java-side
// ABI compatibility.
JNIEXPORT void JNICALL JNI_FN(nativeSetFpsLimit)(JNIEnv* env, jclass clazz, jlong handle, jint fps) {
    (void)env; (void)clazz; (void)handle; (void)fps;
}

// 0=off/linear, 1=nearest, 2=linear, 3=bicubic (linear fallback when cubic unsupported).
JNIEXPORT void JNICALL JNI_FN(nativeSetScaleFilter)(JNIEnv* env, jclass clazz, jlong handle, jint mode) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    if (r->scale_filter == mode) return;

    pthread_mutex_lock(&r->render_mutex);
    wait_inflight_frames(r);
    r->scale_filter = mode;
    vkr_retarget_shared_sampler(r);
    pthread_mutex_unlock(&r->render_mutex);
}

// Set the compositor present mode. Java passes 0=FIFO, 1=MAILBOX, 2=IMMEDIATE; anything else
// is treated as FIFO. Triggers a swapchain rebuild if a surface is currently active so the
// change takes effect on the next frame.
JNIEXPORT void JNICALL JNI_FN(nativeSetPresentMode)(JNIEnv* env, jclass clazz, jlong handle, jint mode) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    VkPresentModeKHR vk_mode;
    switch (mode) {
        case 1:  vk_mode = VK_PRESENT_MODE_MAILBOX_KHR; break;
        case 2:  vk_mode = VK_PRESENT_MODE_IMMEDIATE_KHR; break;
        default: vk_mode = VK_PRESENT_MODE_FIFO_KHR; break;
    }
    if (r->target_present_mode == vk_mode) return;

    if (!r->surface) {
        pthread_mutex_lock(&r->render_mutex);
        r->target_present_mode = vk_mode;
        pthread_mutex_unlock(&r->render_mutex);
        return;
    }
    lifecycle_begin(r);
    r->target_present_mode = vk_mode;
    if (r->device) vkDeviceWaitIdle(r->device);
    uint32_t fw = r->surface_extent.width;
    uint32_t fh = r->surface_extent.height;
    destroy_sgsr1_resources(r);
    destroy_offscreen(r);
    destroy_swapchain(r);
    if (!create_swapchain(r, fw, fh)) {
        VK_LOGE("Swapchain re-create failed in nativeSetPresentMode");
    } else {
        pthread_mutex_lock(&r->scene_mutex);
        r->surface_ready = true;
        pthread_mutex_unlock(&r->scene_mutex);
    }
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetFrameGenerationEnabled)(JNIEnv* env, jclass clazz,
                                                               jlong handle, jboolean enabled) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    const bool want = (enabled == JNI_TRUE);
    if (r->framegen_requested == want) return;

    pthread_mutex_lock(&r->render_mutex);
    r->framegen_requested = want;
    if (!want) {
        wait_inflight_frames(r);
        destroy_lsfg(r);
    } else if (r->device && r->lsfg_cache_path) {
        destroy_dis(r);
        r->dis_requested = false;
        create_lsfg(r);
    }
    framegen_rebuild_swapchain(r);
    pthread_mutex_unlock(&r->render_mutex);
    VK_LOGI("Frame generation composite path %s (supported=%d)",
            want ? "enabled" : "disabled", (int)r->framegen_supported);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsFrameGenerationSupported)(JNIEnv* env, jclass clazz,
                                                                    jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return JNI_FALSE;
    return r->framegen_supported ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetFrameGenerationShaders)(JNIEnv* env, jclass clazz,
                                                               jlong handle, jstring cachePath) {
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    pthread_mutex_lock(&r->render_mutex);
    wait_inflight_frames(r);
    destroy_lsfg(r);
    free(r->lsfg_cache_path);
    r->lsfg_cache_path = NULL;

    if (cachePath != NULL) {
        const char* path = (*env)->GetStringUTFChars(env, cachePath, NULL);
        if (path != NULL) {
            r->lsfg_cache_path = strdup(path);
            (*env)->ReleaseStringUTFChars(env, cachePath, path);
        }
    }
    if (r->framegen_requested && r->device && r->lsfg_cache_path) create_lsfg(r);
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetSourceFrameCount)(JNIEnv* env, jclass clazz, jlong handle,
                                                         jlong count) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;
    __atomic_store_n(&r->framegen_source_frames, (uint64_t)count, __ATOMIC_RELAXED);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetFrameGenerationRefreshRate)(JNIEnv* env, jclass clazz,
                                                                   jlong handle, jfloat hz) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;
    const int32_t mhz = hz > 0.0f ? (int32_t)((float)hz * 1000.0f + 0.5f) : 0;
    __atomic_store_n(&r->framegen_refresh_mhz, mhz, __ATOMIC_RELAXED);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetFrameGenerationMode)(JNIEnv* env, jclass clazz,
                                                            jlong handle, jint multiplier,
                                                            jint targetRate,
                                                            jint flowScalePct) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    pthread_mutex_lock(&r->render_mutex);
    const uint32_t previous_images = framegen_extra_images(r);
    r->framegen_multiplier = multiplier < 2 ? 2u : (uint32_t)multiplier;
    r->framegen_target_rate = targetRate < 0 ? 0u : (uint32_t)targetRate;
    r->framegen_flow_scale = flowScalePct <= 0 ? 0.7f : (float)flowScalePct / 100.0f;
    if (r->lsfg) {
        vkr_lsfg_configure(r->lsfg, r->framegen_multiplier, r->framegen_target_rate,
                           r->framegen_flow_scale, r->framegen_refresh_rate, 0.0f);
    }
    if (framegen_extra_images(r) != previous_images) {
        wait_inflight_frames(r);
        framegen_rebuild_swapchain(r);
    }
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDisFrameGenerationEnabled)(JNIEnv* env, jclass clazz,
                                                                  jlong handle, jboolean enabled) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    const bool want = (enabled == JNI_TRUE);
    if (r->dis_requested == want) return;

    pthread_mutex_lock(&r->render_mutex);
    r->dis_requested = want;
    if (!want) {
        wait_inflight_frames(r);
        destroy_dis(r);
    } else if (r->device) {
        destroy_lsfg(r);
        r->framegen_requested = false;
        create_dis(r);
    }
    framegen_rebuild_swapchain(r);
    pthread_mutex_unlock(&r->render_mutex);
    VK_LOGI("DIS frame generation composite path %s (supported=%d)",
            want ? "enabled" : "disabled", (int)r->framegen_supported);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDisFrameGenerationScale)(JNIEnv* env, jclass clazz,
                                                                jlong handle, jint scalePercent) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    pthread_mutex_lock(&r->render_mutex);
    int side = scalePercent > 0 && scalePercent <= 100 ? scalePercent * 720 / 100 : scalePercent;
    r->dis_scale = side < 64 ? 64u : (uint32_t)(side > 1080 ? 1080 : side);
    if (r->dis) {
        vkr_dis_configure(r->dis, r->dis_scale, r->dis_target_fps, r->framegen_refresh_rate);
    }
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDisFrameGenerationTargetFps)(JNIEnv* env, jclass clazz,
                                                                    jlong handle, jint targetFps) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    pthread_mutex_lock(&r->render_mutex);
    r->dis_target_fps = targetFps < 0 ? 0u : (uint32_t)targetFps;
    if (r->dis) {
        vkr_dis_configure(r->dis, r->dis_scale, r->dis_target_fps, r->framegen_refresh_rate);
    }
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDisDebugFlow)(JNIEnv* env, jclass clazz,
                                                     jlong handle, jboolean debugFlow) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return;

    pthread_mutex_lock(&r->render_mutex);
    r->dis_debug_flow = (debugFlow == JNI_TRUE);
    if (r->dis) vkr_dis_set_debug_flow(r->dis, r->dis_debug_flow);
    pthread_mutex_unlock(&r->render_mutex);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetGeneratedFrameCount)(JNIEnv* env, jclass clazz,
                                                             jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return 0;
    return (jlong)r->framegen_made_frames;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetPresentedFrameCount)(JNIEnv* env, jclass clazz,
                                                             jlong handle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r) return 0;
    return (jlong)__atomic_load_n(&r->presented_frames, __ATOMIC_RELAXED);
}

// ============================================================
// JNI entry points for Java Texture / GPUImage
// ============================================================

#define TEX_FN(name) Java_com_winlator_cmod_runtime_display_renderer_Texture_##name
#define GPU_FN(name) Java_com_winlator_cmod_runtime_display_renderer_GPUImage_##name

JNIEXPORT jlong JNICALL TEX_FN(nativeAllocate)(JNIEnv* env, jclass clazz, jlong rendererHandle,
                                               jint width, jint height,
                                               jobject dataBuffer, jint stridePixels)
{
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)rendererHandle;
    if (!r) return 0;

    void* data = NULL;
    size_t size = 0;
    if (dataBuffer) {
        data = (*env)->GetDirectBufferAddress(env, dataBuffer);
        size = (size_t)(*env)->GetDirectBufferCapacity(env, dataBuffer);
    }
    VkTexture* t = vkr_texture_create_uploaded(r, (uint32_t)width, (uint32_t)height,
                                               data, size, (uint32_t)stridePixels);
    return (jlong)(intptr_t)t;
}

JNIEXPORT jboolean JNICALL TEX_FN(nativeUpdate)(JNIEnv* env, jclass clazz, jlong rendererHandle,
                                                jlong texHandle, jint width, jint height,
                                                jobject dataBuffer, jint stridePixels,
                                                jint dirtyX, jint dirtyY,
                                                jint dirtyWidth, jint dirtyHeight)
{
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)rendererHandle;
    VkTexture* t = (VkTexture*)(intptr_t)texHandle;
    if (!r || !t || !dataBuffer) return JNI_FALSE;
    void* data = (*env)->GetDirectBufferAddress(env, dataBuffer);
    size_t size = (size_t)(*env)->GetDirectBufferCapacity(env, dataBuffer);
    return vkr_texture_update(r, t, (uint32_t)width, (uint32_t)height, data, size,
                              (uint32_t)stridePixels,
                              dirtyX < 0 ? 0 : (uint32_t)dirtyX,
                              dirtyY < 0 ? 0 : (uint32_t)dirtyY,
                              dirtyWidth < 0 ? 0 : (uint32_t)dirtyWidth,
                               dirtyHeight < 0 ? 0 : (uint32_t)dirtyHeight)
        ? JNI_TRUE : JNI_FALSE;
}

// Grow-only scratch for parsed batch entries. Render-thread-only, so unlocked; every element
// is fully populated before use, so no zeroing. (Mirrors get_prepared_scratch in vk_image.c.)
static VkTextureBatchUpload* get_entry_scratch(VkRenderer* r, uint32_t count) {
    if (r->batch_entry_cap < count) {
        uint32_t new_cap = r->batch_entry_cap ? r->batch_entry_cap : 64;
        while (new_cap < count) new_cap *= 2;
        VkTextureBatchUpload* p = realloc(r->batch_entry_scratch,
                                          (size_t)new_cap * sizeof(VkTextureBatchUpload));
        if (!p) return NULL;
        r->batch_entry_scratch = p;
        r->batch_entry_cap = new_cap;
    }
    return r->batch_entry_scratch;
}

JNIEXPORT jboolean JNICALL TEX_FN(nativeBatchUpdate)(JNIEnv* env, jclass clazz, jlong rendererHandle,
                                                     jobject entriesBuffer,
                                                     jobjectArray dataBuffers,
                                                     jint count)
{
    (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)rendererHandle;
    if (!r || !entriesBuffer || !dataBuffers || count <= 0) return JNI_FALSE;

    const uint8_t* base = (const uint8_t*)(*env)->GetDirectBufferAddress(env, entriesBuffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, entriesBuffer);
    if (!base || cap < (jlong)count * 48) return JNI_FALSE;
    jsize buffer_count = (*env)->GetArrayLength(env, dataBuffers);
    if (buffer_count < count) return JNI_FALSE;

    VkTextureBatchUpload* uploads = get_entry_scratch(r, (uint32_t)count);
    if (!uploads) return JNI_FALSE;

    for (jint i = 0; i < count; i++) {
        const uint8_t* e = base + (size_t)i * 48;
        int64_t tex_h;
        int32_t width, height, stride, dirty_x, dirty_y, dirty_w, dirty_h, data_index;
        memcpy(&tex_h, e, sizeof(tex_h));
        memcpy(&width, e + 8, sizeof(width));
        memcpy(&height, e + 12, sizeof(height));
        memcpy(&stride, e + 16, sizeof(stride));
        memcpy(&dirty_x, e + 20, sizeof(dirty_x));
        memcpy(&dirty_y, e + 24, sizeof(dirty_y));
        memcpy(&dirty_w, e + 28, sizeof(dirty_w));
        memcpy(&dirty_h, e + 32, sizeof(dirty_h));
        memcpy(&data_index, e + 36, sizeof(data_index));
        if (data_index < 0 || data_index >= buffer_count) {
            return JNI_FALSE;
        }

        jobject data_buffer = (*env)->GetObjectArrayElement(env, dataBuffers, data_index);
        if (!data_buffer) {
            return JNI_FALSE;
        }
        void* data = (*env)->GetDirectBufferAddress(env, data_buffer);
        jlong data_size = (*env)->GetDirectBufferCapacity(env, data_buffer);
        (*env)->DeleteLocalRef(env, data_buffer);
        if (!data || data_size <= 0) {
            return JNI_FALSE;
        }

        uploads[i].texture = (VkTexture*)(intptr_t)tex_h;
        uploads[i].data = data;
        uploads[i].data_size = (size_t)data_size;
        uploads[i].width = width < 0 ? 0u : (uint32_t)width;
        uploads[i].height = height < 0 ? 0u : (uint32_t)height;
        uploads[i].stride_pixels = stride < 0 ? 0u : (uint32_t)stride;
        uploads[i].dirty_x = dirty_x < 0 ? 0u : (uint32_t)dirty_x;
        uploads[i].dirty_y = dirty_y < 0 ? 0u : (uint32_t)dirty_y;
        uploads[i].dirty_w = dirty_w < 0 ? 0u : (uint32_t)dirty_w;
        uploads[i].dirty_h = dirty_h < 0 ? 0u : (uint32_t)dirty_h;
    }

    bool ok = vkr_texture_batch_update(r, uploads, (uint32_t)count);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL TEX_FN(nativeDestroy)(JNIEnv* env, jclass clazz, jlong rendererHandle, jlong texHandle) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)rendererHandle;
    VkTexture* t = (VkTexture*)(intptr_t)texHandle;
    if (!r || !t) return;
    vkr_texture_schedule_destroy(r, t);
}

JNIEXPORT jlong JNICALL GPU_FN(nativeImportAhbToVulkan)(JNIEnv* env, jclass clazz,
                                                       jlong rendererHandle, jlong ahbPtr,
                                                       jboolean transferOwnership)
{
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)rendererHandle;
    AHardwareBuffer* ahb = (AHardwareBuffer*)(intptr_t)ahbPtr;
    if (!r || !ahb) {
        VK_LOGW("nativeImportAhbToVulkan skipped: renderer=%p ahb=%p", (void*)r, (void*)ahb);
        return 0;
    }
    VkTexture* t = vkr_texture_import_ahb(r, ahb, transferOwnership);
    if (!t) {
        VK_LOGW("nativeImportAhbToVulkan failed: ahb=%p transfer=%d", (void*)ahb, transferOwnership);
    }
    return (jlong)(intptr_t)t;
}
