// SPDX-License-Identifier: GPL-3.0-or-later

#include "vkr_dis.h"

#include "dis_chain.hpp"

#include <algorithm>
#include <cmath>
#include <memory>
#include <time.h>

#include <android/log.h>

#define DIS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VkrDis", __VA_ARGS__)
#define DIS_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VkrDis", __VA_ARGS__)

struct VkrDis {
    VkDevice device{VK_NULL_HANDLE};
    VkPhysicalDevice physical_device{VK_NULL_HANDLE};

    std::unique_ptr<dis::DisChain> chain;

    VkExtent2D built_flow_extent{};
    VkExtent2D built_target_extent{};
    VkFormat built_format{VK_FORMAT_UNDEFINED};

    VkExtent2D peak_guest_extent{};
    float flow_scale{1.0f};

    uint32_t target_rate{};
    uint32_t multiplier{};
    float refresh_rate{};
    float source_rate{};

    uint64_t last_source_frames{};
    uint64_t last_source_time{};

    uint64_t frame_count{};
    size_t last_generations{};
    uint64_t plan_calls{};
    uint32_t debug_mode{};
    bool unavailable{};
};

namespace {

constexpr uint64_t DIS_TELEMETRY_INTERVAL = 60;

constexpr float DIS_FLOW_SCALE_MIN = 0.25f;
constexpr float DIS_FLOW_SCALE_MAX = 1.0f;

constexpr float DIS_SOURCE_RATE_SMOOTHING = 0.5f;
constexpr float DIS_FALLBACK_SOURCE_RATE = 60.0f;
constexpr float DIS_FALLBACK_TARGET_RATE = 120.0f;

uint64_t DisNowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

VkExtent2D ComputeFlowExtent(const VkrDis* dis, uint32_t width, uint32_t height) {
    uint32_t fw = dis->peak_guest_extent.width != 0 ? dis->peak_guest_extent.width : width;
    uint32_t fh = dis->peak_guest_extent.height != 0 ? dis->peak_guest_extent.height : height;

    const float scale = std::clamp(dis->flow_scale, DIS_FLOW_SCALE_MIN, DIS_FLOW_SCALE_MAX);
    fw = std::max(16u, static_cast<uint32_t>(std::lroundf(static_cast<float>(fw) * scale)));
    fh = std::max(16u, static_cast<uint32_t>(std::lroundf(static_cast<float>(fh) * scale)));
    return VkExtent2D{fw, fh};
}

} // namespace

VkrDis* vkr_dis_create(VkDevice device, VkPhysicalDevice physical_device) {
    if (device == VK_NULL_HANDLE || physical_device == VK_NULL_HANDLE) return nullptr;

    auto* dis = new VkrDis();
    dis->device = device;
    dis->physical_device = physical_device;
    DIS_LOGI("frame generation (DIS) ready");
    return dis;
}

void vkr_dis_destroy(VkrDis* dis) {
    delete dis;
}

void vkr_dis_configure(VkrDis* dis, uint32_t target_rate, float flow_scale,
                       float refresh_rate, float source_rate) {
    if (!dis) return;

    dis->target_rate = target_rate;
    dis->refresh_rate = refresh_rate;
    if (source_rate > 0.0f) dis->source_rate = source_rate;
    dis->flow_scale = std::clamp(flow_scale, DIS_FLOW_SCALE_MIN, DIS_FLOW_SCALE_MAX);
}

void vkr_dis_set_guest_extent(VkrDis* dis, uint32_t width, uint32_t height) {
    if (!dis || width == 0 || height == 0) return;
    dis->peak_guest_extent.width = std::max(dis->peak_guest_extent.width, width);
    dis->peak_guest_extent.height = std::max(dis->peak_guest_extent.height, height);
}

void vkr_dis_set_refresh_rate(VkrDis* dis, float refresh_rate) {
    if (!dis) return;
    dis->refresh_rate = refresh_rate;
}

void vkr_dis_set_multiplier(VkrDis* dis, uint32_t multiplier) {
    if (!dis) return;
    dis->multiplier = multiplier >= 2 ? multiplier : 0u;
}

bool vkr_dis_needs_rebuild(const VkrDis* dis, uint32_t width, uint32_t height, VkFormat format) {
    if (!dis || dis->unavailable) return false;
    const VkExtent2D flow_extent = ComputeFlowExtent(dis, width, height);
    return !dis->chain || dis->built_target_extent.width != width ||
           dis->built_target_extent.height != height || dis->built_format != format ||
           dis->built_flow_extent.width != flow_extent.width ||
           dis->built_flow_extent.height != flow_extent.height;
}

bool vkr_dis_prepare(VkrDis* dis, uint32_t width, uint32_t height, VkFormat format) {
    if (!dis || dis->unavailable) return false;
    if (width == 0 || height == 0 || format == VK_FORMAT_UNDEFINED) return false;

    if (!vkr_dis_needs_rebuild(dis, width, height, format)) {
        return dis->chain && dis->chain->Valid();
    }

    const VkExtent2D flow_extent = ComputeFlowExtent(dis, width, height);

    dis->chain.reset();
    dis->chain = std::make_unique<dis::DisChain>(dis->device, dis->physical_device);
    if (!dis->chain->Build(flow_extent, VkExtent2D{width, height})) {
        DIS_LOGW("DIS chain build failed at flow %ux%u target %ux%u; frame generation unavailable",
                 flow_extent.width, flow_extent.height, width, height);
        dis->chain.reset();
        dis->unavailable = true;
        return false;
    }

    dis->built_flow_extent = flow_extent;
    dis->built_target_extent = VkExtent2D{width, height};
    dis->built_format = format;
    dis->chain->SetDebugMode(static_cast<int32_t>(dis->debug_mode));
    dis->frame_count = 0;
    dis->plan_calls = 0;
    dis->source_rate = 0.0f;
    DIS_LOGI("DIS chain built at flow %ux%u, target %ux%u, scale %.2f, guest %ux%u",
             flow_extent.width, flow_extent.height, width, height, (double)dis->flow_scale,
             dis->peak_guest_extent.width, dis->peak_guest_extent.height);
    return true;
}

uint32_t vkr_dis_plan(VkrDis* dis, uint32_t capacity, uint64_t source_frames) {
    if (!dis || dis->unavailable) return 0;

    // Track the source rate immediately (no warm-up, no back-off).
    const uint64_t now = DisNowNs();
    if (dis->last_source_time != 0) {
        const uint64_t frames_delta =
            source_frames > dis->last_source_frames ? source_frames - dis->last_source_frames : 0;
        const uint64_t time_delta = now - dis->last_source_time;
        if (frames_delta > 0 && time_delta > 0) {
            const float instant =
                static_cast<float>(frames_delta) * 1.0e9f / static_cast<float>(time_delta);
            dis->source_rate = dis->source_rate > 0.0f
                                   ? dis->source_rate + (instant - dis->source_rate) *
                                                            DIS_SOURCE_RATE_SMOOTHING
                                   : instant;
        }
    }
    dis->last_source_frames = source_frames;
    dis->last_source_time = now;

    // The DIS chain needs a previous and current frame before it can interpolate.
    if (dis->frame_count < 1) return 0;

    const uint32_t ceiling = std::min(capacity, VKR_DIS_MAX_GENERATIONS);
    const bool explicit_multiplier = dis->multiplier >= 2;
    int32_t generations;
    if (explicit_multiplier) {
        generations = static_cast<int32_t>(dis->multiplier) - 1;
    } else {
        const float src =
            dis->source_rate > 1.0f ? dis->source_rate : DIS_FALLBACK_SOURCE_RATE;
        const float target = static_cast<float>(dis->target_rate > 0 ? dis->target_rate
                                                                      : DIS_FALLBACK_TARGET_RATE);
        generations = static_cast<int32_t>(std::lroundf(target / src)) - 1;
    }
    generations = std::clamp(generations, 0, static_cast<int32_t>(ceiling));

    if ((dis->plan_calls++ % DIS_TELEMETRY_INTERVAL) == 0) {
        if (explicit_multiplier && static_cast<uint32_t>(generations) + 1 < dis->multiplier) {
            DIS_LOGW("DIS multiplier x%u capped to x%d by generation capacity %u",
                     dis->multiplier, generations + 1, capacity);
        }
        DIS_LOGI("dis plan gen=%d mult=%u cap=%u src=%.1f target=%.0f refresh=%.1f",
                 generations, dis->multiplier, capacity, (double)dis->source_rate,
                 (double)dis->target_rate, (double)dis->refresh_rate);
    }

    return static_cast<uint32_t>(generations);
}

void vkr_dis_process(VkrDis* dis, VkCommandBuffer cmd, VkImage source,
                     VkImageView fullres_view_cur, VkImageView fullres_view_prev,
                     uint32_t width, uint32_t height, VkRect2D content_rect,
                     uint32_t generations) {
    if (!dis || !dis->chain || !dis->chain->Valid()) return;

    dis->frame_count++;
    dis->last_generations = generations;

    dis->chain->Process(cmd, source, fullres_view_cur, fullres_view_prev, content_rect,
                        VkExtent2D{width, height});
}

void vkr_dis_generate_into(VkrDis* dis, VkCommandBuffer cmd, uint32_t generation,
                           uint32_t target_index, VkImage target_image, VkImageView target_view,
                           uint32_t width, uint32_t height) {
    if (!dis || !dis->chain || !dis->chain->Valid()) return;

    dis->chain->GenerateInto(cmd, generation, static_cast<uint32_t>(dis->last_generations),
                             target_image, target_view, VkExtent2D{width, height});
}

void vkr_dis_forget_targets(VkrDis* dis) {
    if (!dis || !dis->chain) return;
    dis->chain->ForgetTargets();
}

void vkr_dis_set_debug_mode(VkrDis* dis, uint32_t mode) {
    if (!dis) return;
    dis->debug_mode = mode;
    if (dis->chain) dis->chain->SetDebugMode(static_cast<int32_t>(mode));
}

bool vkr_dis_debug_ready(const VkrDis* dis) {
    return dis && dis->chain && dis->chain->Valid() && dis->chain->FrameCount() >= 2;
}

void vkr_dis_reset(VkrDis* dis) {
    if (!dis) return;
    dis->peak_guest_extent = VkExtent2D{};
    dis->source_rate = 0.0f;
    dis->last_source_frames = 0;
    dis->last_source_time = 0;
}
