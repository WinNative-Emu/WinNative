/* Compositor-side dmabuf import into Turnip (see vk_import.h). */
#define _POSIX_C_SOURCE 200809L
#include "vk_import.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#define MOD_INVALID 0x00ffffffffffffffULL

static int g_ready = -1; /* -1 = untried, 0 = ok, 1 = init failed */
static VkInstance g_inst;
static VkPhysicalDevice g_pd;
static VkDevice g_dev;
static PFN_vkGetMemoryFdPropertiesKHR p_getMemFdProps;

static int has_ext(VkExtensionProperties *e, uint32_t n, const char *name) {
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

static int vk_init(void) {
    if (g_ready >= 0) return g_ready == 0 ? 0 : -1;

    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "spike-compositor-import",
                             .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                .pApplicationInfo = &app};
    if (vkCreateInstance(&ici, NULL, &g_inst) != VK_SUCCESS) {
        fprintf(stderr, "[imp] vkCreateInstance failed\n");
        g_ready = 1; return -1;
    }
    uint32_t npd = 0;
    vkEnumeratePhysicalDevices(g_inst, &npd, NULL);
    if (!npd) { g_ready = 1; return -1; }
    VkPhysicalDevice pds[8];
    if (npd > 8) npd = 8;
    vkEnumeratePhysicalDevices(g_inst, &npd, pds);
    g_pd = pds[0]; /* Turnip is forced via VK_ICD_FILENAMES */

    const char *want[] = {"VK_KHR_external_memory_fd",
                          "VK_EXT_external_memory_dma_buf",
                          "VK_EXT_image_drm_format_modifier",
                          "VK_KHR_image_format_list"};
    uint32_t ne = 0;
    vkEnumerateDeviceExtensionProperties(g_pd, NULL, &ne, NULL);
    VkExtensionProperties *exts = calloc(ne, sizeof(*exts));
    vkEnumerateDeviceExtensionProperties(g_pd, NULL, &ne, exts);
    for (unsigned i = 0; i < 4; i++)
        if (!has_ext(exts, ne, want[i]))
            fprintf(stderr, "[imp] WARNING device missing %s\n", want[i]);
    free(exts);

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                   .queueFamilyIndex = 0, .queueCount = 1,
                                   .pQueuePriorities = &prio};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                              .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci,
                              .enabledExtensionCount = 4, .ppEnabledExtensionNames = want};
    if (vkCreateDevice(g_pd, &dci, NULL, &g_dev) != VK_SUCCESS) {
        fprintf(stderr, "[imp] vkCreateDevice failed\n");
        g_ready = 1; return -1;
    }
    p_getMemFdProps = (PFN_vkGetMemoryFdPropertiesKHR)vkGetDeviceProcAddr(
        g_dev, "vkGetMemoryFdPropertiesKHR");
    g_ready = 0;
    fprintf(stderr, "[imp] Vulkan import device ready (Turnip)\n");
    return 0;
}

static VkFormat drm_to_vk(uint32_t drm) {
    /* XR24/AR24 (XRGB/ARGB8888, little-endian bytes B,G,R,X) -> BGRA8. */
    return VK_FORMAT_B8G8R8A8_UNORM;
}

int vk_import_dmabuf(int fd, uint32_t drm_format, uint64_t modifier, int w, int h,
                     uint32_t stride, uint32_t offset) {
    if (modifier == MOD_INVALID) {
        fprintf(stderr, "[imp] skip: INVALID modifier (needs implicit path)\n");
        return -1;
    }
    if (vk_init() != 0) return -1;

    VkSubresourceLayout plane = {.offset = offset, .rowPitch = stride};
    VkImageDrmFormatModifierExplicitCreateInfoEXT modInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT,
        .drmFormatModifier = modifier,
        .drmFormatModifierPlaneCount = 1,
        .pPlaneLayouts = &plane};
    VkExternalMemoryImageCreateInfo extImg = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = &modInfo,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &extImg,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = drm_to_vk(drm_format),
        .extent = {w, h, 1},
        .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
        .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    VkImage image;
    VkResult r = vkCreateImage(g_dev, &ici, NULL, &image);
    if (r != VK_SUCCESS) {
        fprintf(stderr, "[imp] vkCreateImage(drm_modifier) -> %d\n", r);
        return -1;
    }

    int dupfd = dup(fd);
    uint32_t allowed_types = 0xffffffff;
    if (p_getMemFdProps) {
        VkMemoryFdPropertiesKHR fp = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        if (p_getMemFdProps(g_dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                            dupfd, &fp) == VK_SUCCESS)
            allowed_types = fp.memoryTypeBits;
    }
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(g_dev, image, &req);
    uint32_t bits = req.memoryTypeBits & allowed_types;
    int type_idx = -1;
    for (int i = 0; i < 32; i++)
        if (bits & (1u << i)) { type_idx = i; break; }
    if (type_idx < 0) {
        fprintf(stderr, "[imp] no compatible memory type\n");
        vkDestroyImage(g_dev, image, NULL); close(dupfd); return -1;
    }

    VkImportMemoryFdInfoKHR imp = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
        .fd = dupfd};
    VkMemoryDedicatedAllocateInfo ded = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = &imp, .image = image};
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                .pNext = &ded,
                                .allocationSize = req.size,
                                .memoryTypeIndex = (uint32_t)type_idx};
    VkDeviceMemory mem;
    r = vkAllocateMemory(g_dev, &mai, NULL, &mem);
    if (r != VK_SUCCESS) {
        fprintf(stderr, "[imp] vkAllocateMemory(import fd) -> %d\n", r);
        vkDestroyImage(g_dev, image, NULL); close(dupfd); return -1;
    }
    r = vkBindImageMemory(g_dev, image, mem, 0);
    if (r != VK_SUCCESS) {
        fprintf(stderr, "[imp] vkBindImageMemory -> %d\n", r);
        vkFreeMemory(g_dev, mem, NULL); vkDestroyImage(g_dev, image, NULL);
        return -1;
    }

    fprintf(stderr,
            "[imp] *** IMPORTED dmabuf -> VkImage OK (memType %d, size %llu) "
            "==> compositor can turn game frames into VkImages\n",
            type_idx, (unsigned long long)req.size);
    /* import took ownership of dupfd; free our objects */
    vkFreeMemory(g_dev, mem, NULL);
    vkDestroyImage(g_dev, image, NULL);
    return 0;
}
