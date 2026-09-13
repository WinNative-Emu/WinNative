// VkTexture allocation, upload, AHB import.
//
// Two creation paths:
//   1. CPU-uploaded: caller hands us BGRA pixel data; we allocate VkImage in DEVICE_LOCAL memory,
//      stage the upload through a host-visible buffer, and transition to SHADER_READ_OPTIMAL.
//   2. AHardwareBuffer import: caller hands us an AHB; we allocate dedicated memory backed by the
//      AHB (no copy) and bind it to a VkImage. For non-RGB formats (DRI3 vendor formats), we use
//      a Ycbcr conversion so the sampler can read them.
//
// Texture lifetimes:
//   - Created/updated synchronously on caller's thread (Java/render).
//   - Submits go through vkQueueSubmit which is serialized via VkRenderer::queue_mutex.
//   - Destruction is deferred via the graveyard so in-flight frames don't see freed handles.

#include "vk_state.h"
#include <stdlib.h>
#include <string.h>

#define HAL_PIXEL_FORMAT_BGRA_8888 5

uint32_t vkr_find_memory_type(VkRenderer* r, uint32_t type_bits, VkMemoryPropertyFlags props) {
    for (uint32_t i = 0; i < r->mem_props.memoryTypeCount; i++) {
        if ((type_bits & (1u << i))
            && (r->mem_props.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return UINT32_MAX;
}

void vkr_image_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to,
                       VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
                       VkAccessFlags src_access, VkAccessFlags dst_access) {
    VkImageMemoryBarrier b = {0};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcAccessMask = src_access;
    b.dstAccessMask = dst_access;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.baseMipLevel = 0;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.baseArrayLayer = 0;
    b.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &b);
}

// ============================================================
// Staging pool — async upload infrastructure
// ============================================================
//
// Each slot owns a VkBuffer, persistently-mapped HOST_VISIBLE memory, a VkCommandPool with
// one VkCommandBuffer, and a VkFence. Round-robin acquisition under a tiny mutex; per-slot
// mutex provides exclusive ownership for the lifetime of an upload (acquire→submit→release).
//
// On a single graphics queue, the upload's terminal pipeline barrier (TRANSFER_WRITE →
// SHADER_READ, dstStage=FRAGMENT_SHADER) extends into all subsequent submits per Vulkan
// spec — so the renderer needs no extra synchronization to safely sample a freshly-updated
// texture as long as the upload was submitted before the render.

bool vkr_staging_pool_init(VkRenderer* r) {
    if (r->staging_pool.initialized) return true;
    pthread_mutex_init(&r->staging_pool.mutex, NULL);
    r->staging_pool.mutex_init = true;
    r->staging_pool.next = 0;
    r->staging_pool.valid_slots = 0;

    VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;  // first acquire of each slot finds the fence ready

    for (uint32_t i = 0; i < VK_STAGING_POOL_SIZE; i++) {
        VkStagingSlot* s = &r->staging_pool.slots[i];
        pthread_mutex_init(&s->mutex, NULL);
        r->staging_pool.valid_slots = i + 1;  // mutex is now valid; destroy must clean it up

        VkCommandPoolCreateInfo cpci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpci.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        cpci.queueFamilyIndex = r->graphics_queue_family;
        if (vkCreateCommandPool(r->device, &cpci, NULL, &s->cmd_pool) != VK_SUCCESS) {
            VK_LOGE("staging pool: vkCreateCommandPool slot %u failed", i);
            return false;
        }

        VkCommandBufferAllocateInfo cbai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cbai.commandPool = s->cmd_pool;
        cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbai.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(r->device, &cbai, &s->cmd) != VK_SUCCESS) {
            VK_LOGE("staging pool: vkAllocateCommandBuffers slot %u failed", i);
            return false;
        }

        if (vkCreateFence(r->device, &fi, NULL, &s->fence) != VK_SUCCESS) {
            VK_LOGE("staging pool: vkCreateFence slot %u failed", i);
            return false;
        }
        // buffer/memory allocated lazily on first use, sized to the actual upload.
    }
    r->staging_pool.initialized = true;
    return true;
}

void vkr_staging_pool_destroy(VkRenderer* r) {
    // Tolerates partially-initialized pools — only iterate the slots whose mutexes were
    // successfully initialized.
    for (uint32_t i = 0; i < r->staging_pool.valid_slots; i++) {
        VkStagingSlot* s = &r->staging_pool.slots[i];
        if (s->fence) {
            // Drain any pending submission so the buffer/memory are safe to free.
            vkWaitForFences(r->device, 1, &s->fence, VK_TRUE, UINT64_MAX);
            vkDestroyFence(r->device, s->fence, NULL);
        }
        if (s->mapped && s->memory) vkUnmapMemory(r->device, s->memory);
        if (s->buffer)   vkDestroyBuffer(r->device, s->buffer, NULL);
        if (s->memory)   vkFreeMemory(r->device, s->memory, NULL);
        if (s->cmd_pool) vkDestroyCommandPool(r->device, s->cmd_pool, NULL);
        pthread_mutex_destroy(&s->mutex);
        memset(s, 0, sizeof(*s));
    }
    if (r->staging_pool.mutex_init) {
        pthread_mutex_destroy(&r->staging_pool.mutex);
    }
    memset(&r->staging_pool, 0, sizeof(r->staging_pool));
}

// Re-allocate a slot's staging buffer to at least `needed` bytes. Caller must own the slot.
static bool grow_staging_slot(VkRenderer* r, VkStagingSlot* s, VkDeviceSize needed) {
    // Round up to 64 KiB so consecutive size bumps don't trigger reallocs.
    VkDeviceSize new_size = (needed + 65535ull) & ~(VkDeviceSize)65535ull;

    if (s->mapped && s->memory) { vkUnmapMemory(r->device, s->memory); s->mapped = NULL; }
    if (s->buffer) { vkDestroyBuffer(r->device, s->buffer, NULL); s->buffer = VK_NULL_HANDLE; }
    if (s->memory) { vkFreeMemory(r->device, s->memory, NULL); s->memory = VK_NULL_HANDLE; }
    // Reset size now so a later allocation failure leaves the slot in a state where the next
    // acquire will retry grow_staging_slot rather than skip it and hand back a NULL buffer.
    s->size = 0;

    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = new_size;
    bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(r->device, &bi, NULL, &s->buffer) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(r->device, s->buffer, &mr);

    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    // Require HOST_VISIBLE | HOST_COHERENT (typically write-combined on Adreno). Skipping
    // HOST_CACHED avoids polluting CPU caches with write-once-then-GPU-read staging, which
    // hurts throughput by 5-20% on Adreno. We do not fall back to non-coherent memory:
    // vkr_texture_update submits without vkFlushMappedMemoryRanges, so non-coherent staging
    // would render undefined data. Vulkan spec §11.6 mandates that every device expose at
    // least one HOST_VISIBLE | HOST_COHERENT memory type, so this lookup cannot legally fail.
    ai.memoryTypeIndex = vkr_find_memory_type(r, mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) {
        vkDestroyBuffer(r->device, s->buffer, NULL); s->buffer = VK_NULL_HANDLE;
        return false;
    }
    if (vkAllocateMemory(r->device, &ai, NULL, &s->memory) != VK_SUCCESS) {
        vkDestroyBuffer(r->device, s->buffer, NULL); s->buffer = VK_NULL_HANDLE;
        return false;
    }
    vkBindBufferMemory(r->device, s->buffer, s->memory, 0);
    if (vkMapMemory(r->device, s->memory, 0, VK_WHOLE_SIZE, 0, &s->mapped) != VK_SUCCESS) {
        vkFreeMemory(r->device, s->memory, NULL); s->memory = VK_NULL_HANDLE;
        vkDestroyBuffer(r->device, s->buffer, NULL); s->buffer = VK_NULL_HANDLE;
        return false;
    }
    s->size = new_size;
    return true;
}

VkStagingSlot* vkr_staging_pool_acquire(VkRenderer* r, VkDeviceSize needed) {
    if (!r->staging_pool.initialized) return NULL;

    pthread_mutex_lock(&r->staging_pool.mutex);
    uint32_t idx = (uint32_t)(r->staging_pool.next++ % VK_STAGING_POOL_SIZE);
    pthread_mutex_unlock(&r->staging_pool.mutex);

    VkStagingSlot* s = &r->staging_pool.slots[idx];

    // Per-slot lock guards the slot's resources (buffer/cmd/fence) until release. Round-robin
    // means contention only happens once VK_STAGING_POOL_SIZE acquires have wrapped — i.e.
    // when the producer is consistently faster than the GPU can drain uploads.
    pthread_mutex_lock(&s->mutex);

    // Wait for the slot's previous submission to retire. With pool_size=8 this almost never
    // blocks because the fence signaled long ago. The fence is left signaled here on purpose
    // — it gets reset right before vkQueueSubmit, so any no-submit failure path between here
    // and submit leaves the fence safely signaled and the slot reusable.
    vkWaitForFences(r->device, 1, &s->fence, VK_TRUE, UINT64_MAX);
    vkResetCommandPool(r->device, s->cmd_pool, 0);

    if (s->size < needed && !grow_staging_slot(r, s, needed)) {
        pthread_mutex_unlock(&s->mutex);
        return NULL;
    }
    return s;
}

void vkr_staging_pool_release(VkStagingSlot* slot) {
    if (!slot) return;
    pthread_mutex_unlock(&slot->mutex);
}

void vkr_run_one_shot_cmd(VkRenderer* r, void (*fn)(VkCommandBuffer, void*), void* user) {
    VkCommandBufferAllocateInfo ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = r->cmd_pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;

    VkCommandBuffer cmd;
    VK_CHECK(vkAllocateCommandBuffers(r->device, &ai, &cmd));

    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_CHECK(vkBeginCommandBuffer(cmd, &bi));

    fn(cmd, user);

    VK_CHECK(vkEndCommandBuffer(cmd));

    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;

    VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    VK_CHECK(vkCreateFence(r->device, &fi, NULL, &fence));

    pthread_mutex_lock(&r->queue_mutex);
    VK_CHECK(vkQueueSubmit(r->graphics_queue, 1, &si, fence));
    pthread_mutex_unlock(&r->queue_mutex);

    VK_CHECK(vkWaitForFences(r->device, 1, &fence, VK_TRUE, UINT64_MAX));

    vkDestroyFence(r->device, fence, NULL);
    vkFreeCommandBuffers(r->device, r->cmd_pool, 1, &cmd);
}

// Forward declarations — defined in vk_renderer.c.
VkDescriptorSet vkr_alloc_descriptor_set(VkRenderer* r);
void            vkr_free_descriptor_set(VkRenderer* r, VkDescriptorSet set);

// Image sub-allocator — implemented lower in this file.
static bool vkr_suballoc_image(VkRenderer* r, VkImage image, VkSuballoc* out);
static void vkr_suballoc_free(VkRenderer* r, VkSuballoc* a);

// Copy `bytes` (multiple of 4) from src to dst, optionally swapping B and R per pixel.
static void copy_pixels_maybe_swizzle(uint8_t* dst, const uint8_t* src, size_t bytes,
                                      bool swizzle_bgra_rgba) {
    if (!swizzle_bgra_rgba) {
        memcpy(dst, src, bytes);
        return;
    }
    size_t pixels = bytes >> 2;
    for (size_t i = 0; i < pixels; i++) {
        uint8_t b = src[i*4 + 0];
        uint8_t g = src[i*4 + 1];
        uint8_t rr = src[i*4 + 2];
        uint8_t a = src[i*4 + 3];
        dst[i*4 + 0] = rr;
        dst[i*4 + 1] = g;
        dst[i*4 + 2] = b;
        dst[i*4 + 3] = a;
    }
}

bool vkr_create_sampler(VkRenderer* r, VkSamplerYcbcrConversion ycbcr, VkSampler* out) {
    VkSamplerYcbcrConversionInfo yi = {VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
    yi.conversion = ycbcr;

    VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    if (ycbcr != VK_NULL_HANDLE) si.pNext = &yi;
    si.magFilter = VK_FILTER_LINEAR;
    si.minFilter = VK_FILTER_LINEAR;
    si.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    si.unnormalizedCoordinates = VK_FALSE;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;

    return vkCreateSampler(r->device, &si, NULL, out) == VK_SUCCESS;
}

bool vkr_submit_async_transition(VkRenderer* r, VkImage image,
                                 VkImageLayout from, VkImageLayout to,
                                 VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
                                 VkAccessFlags src_access, VkAccessFlags dst_access) {
    // Reuse the staging pool's per-slot command pool/buffer/fence for this transition. We
    // pass needed=0 so the slot's staging buffer isn't grown (we only use the cmd buffer).
    VkStagingSlot* slot = vkr_staging_pool_acquire(r, 0);
    if (!slot) {
        VK_LOGE("vkr_submit_async_transition: staging slot acquire failed");
        return false;
    }

    VkCommandBufferBeginInfo cbi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(slot->cmd, &cbi) != VK_SUCCESS) {
        vkr_staging_pool_release(slot);
        return false;
    }
    vkr_image_barrier(slot->cmd, image, from, to, src_stage, dst_stage, src_access, dst_access);
    if (vkEndCommandBuffer(slot->cmd) != VK_SUCCESS) {
        vkr_staging_pool_release(slot);
        return false;
    }

    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &slot->cmd;

    vkResetFences(r->device, 1, &slot->fence);

    pthread_mutex_lock(&r->queue_mutex);
    VkResult sr = vkQueueSubmit(r->graphics_queue, 1, &si, slot->fence);
    pthread_mutex_unlock(&r->queue_mutex);
    if (sr != VK_SUCCESS) {
        VK_LOGE("vkr_submit_async_transition: vkQueueSubmit -> %d", sr);
        // Restore a signaled fence so the slot is reusable. (Same recovery path as
        // vkr_texture_update.)
        vkDestroyFence(r->device, slot->fence, NULL);
        VkFenceCreateInfo rfi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        rfi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(r->device, &rfi, NULL, &slot->fence);
        vkr_staging_pool_release(slot);
        return false;
    }
    vkr_staging_pool_release(slot);
    return true;
}

static void write_descriptor_set(VkRenderer* r, VkDescriptorSet set, VkImageView view, VkSampler sampler) {
    VkDescriptorImageInfo ii = {0};
    ii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    ii.imageView = view;
    ii.sampler = sampler;

    VkWriteDescriptorSet w = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = set;
    w.dstBinding = 0;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.descriptorCount = 1;
    w.pImageInfo = &ii;
    vkUpdateDescriptorSets(r->device, 1, &w, 0, NULL);
}

static VkSampler active_shared_sampler(VkRenderer* r) {
    switch (r->scale_filter) {
        case 1:  return r->shared_sampler_nearest;
        case 3:  return r->ext_filter_cubic ? r->shared_sampler_cubic : r->shared_sampler;
        default: return r->shared_sampler;
    }
}

// Caller must hold render_mutex with in-flight frames drained.
void vkr_retarget_shared_sampler(VkRenderer* r) {
    VkSampler s = active_shared_sampler(r);
    if (s == VK_NULL_HANDLE) return;
    pthread_mutex_lock(&r->texture_mutex);
    for (uint32_t i = 0; i < r->live_texture_count; i++) {
        VkTexture* t = r->live_textures[i];
        if (!t || t->descriptor_set == VK_NULL_HANDLE || t->view == VK_NULL_HANDLE) continue;
        if (t->ycbcr != VK_NULL_HANDLE) continue;
        write_descriptor_set(r, t->descriptor_set, t->view, s);
    }
    pthread_mutex_unlock(&r->texture_mutex);
}

static void destroy_texture_resources(VkRenderer* r, VkTexture* tex) {
    if (!tex) return;
    if (tex->descriptor_set != VK_NULL_HANDLE) {
        vkr_free_descriptor_set(r, tex->descriptor_set);
        tex->descriptor_set = VK_NULL_HANDLE;
    }
    if (tex->sampler != VK_NULL_HANDLE) vkDestroySampler(r->device, tex->sampler, NULL);
    if (tex->view != VK_NULL_HANDLE)    vkDestroyImageView(r->device, tex->view, NULL);
    if (tex->ycbcr != VK_NULL_HANDLE && r->fnDestroyYcbcr) r->fnDestroyYcbcr(r->device, tex->ycbcr, NULL);
    if (tex->image != VK_NULL_HANDLE)   vkDestroyImage(r->device, tex->image, NULL);
    // Free backing after the image. Sub-allocated -> return span to pool; else free own memory.
    if (tex->suballocated)              vkr_suballoc_free(r, &tex->suballoc);
    else if (tex->memory != VK_NULL_HANDLE) vkFreeMemory(r->device, tex->memory, NULL);
    if (tex->ahb != NULL)               AHardwareBuffer_release(tex->ahb);
    free(tex);
}

static bool track_live_texture(VkRenderer* r, VkTexture* tex) {
    if (!tex) return false;
    pthread_mutex_lock(&r->texture_mutex);
    if (r->live_texture_count >= r->live_texture_capacity) {
        uint32_t new_cap = r->live_texture_capacity ? r->live_texture_capacity * 2 : 64;
        VkTexture** next = realloc(r->live_textures, new_cap * sizeof(VkTexture*));
        if (!next) {
            pthread_mutex_unlock(&r->texture_mutex);
            return false;
        }
        r->live_textures = next;
        r->live_texture_capacity = new_cap;
    }
    r->live_textures[r->live_texture_count++] = tex;
    pthread_mutex_unlock(&r->texture_mutex);
    return true;
}

static void untrack_live_texture(VkRenderer* r, VkTexture* tex) {
    if (!tex) return;
    pthread_mutex_lock(&r->texture_mutex);
    for (uint32_t i = 0; i < r->live_texture_count; i++) {
        if (r->live_textures[i] == tex) {
            r->live_textures[i] = r->live_textures[--r->live_texture_count];
            r->live_textures[r->live_texture_count] = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&r->texture_mutex);
}

static VkTexture* pop_live_texture(VkRenderer* r) {
    pthread_mutex_lock(&r->texture_mutex);
    VkTexture* tex = NULL;
    if (r->live_texture_count > 0) {
        tex = r->live_textures[--r->live_texture_count];
        r->live_textures[r->live_texture_count] = NULL;
    }
    pthread_mutex_unlock(&r->texture_mutex);
    return tex;
}

// ----------------------------------------------------------------------
// Image sub-allocator
// ----------------------------------------------------------------------
//
// First-fit over a list of large DEVICE_LOCAL blocks, all under image_suballoc.mutex (alloc on
// producer threads, free on the render thread). Region nodes are malloc'd only on new-block
// creation or a non-coalescing free, so steady-state pixmap churn doesn't touch the C heap.

void vkr_suballoc_init(VkRenderer* r) {
    VkImageSuballocator* sa = &r->image_suballoc;
    sa->blocks = NULL;
    sa->block_size = VK_SUBALLOC_BLOCK_SIZE;
    pthread_mutex_init(&sa->mutex, NULL);
    sa->mutex_init = true;
}

static VkDeviceSize suballoc_align_up(VkDeviceSize v, VkDeviceSize a) {
    return (v + a - 1) & ~(a - 1);
}

// Carve [reg->offset .. bind+size) out of free region `reg` (`prev` = its free-list
// predecessor, or NULL). The leading alignment pad folds into the span so free recovers it
// verbatim. Caller verified the region fits.
static void suballoc_carve(VkMemBlock* block, VkMemRegion* prev, VkMemRegion* reg,
                           VkDeviceSize bind, VkDeviceSize size, VkSuballoc* out) {
    VkDeviceSize span_offset = reg->offset;
    VkDeviceSize span_end    = bind + size;
    VkDeviceSize reg_end     = reg->offset + reg->size;

    if (span_end < reg_end) {
        reg->offset = span_end;          // shrink region to the trailing remainder
        reg->size   = reg_end - span_end;
    } else {
        if (prev) prev->next = reg->next; // region fully consumed — unlink + free
        else      block->free_list = reg->next;
        free(reg);
    }

    out->block       = block;
    out->memory      = block->memory;
    out->bind_offset = bind;
    out->span_offset = span_offset;
    out->span_size   = span_end - span_offset;
}

// Reserve a span sized/aligned for `image` into `out`. Does NOT bind — caller issues the one
// vkBindImageMemory so a bind failure never forces an illegal rebind. False (nothing reserved)
// if no DEVICE_LOCAL type fits or a new block can't be allocated.
static bool vkr_suballoc_image(VkRenderer* r, VkImage image, VkSuballoc* out) {
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(r->device, image, &mr);

    uint32_t type_index = vkr_find_memory_type(r, mr.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type_index == UINT32_MAX) return false;

    VkDeviceSize align = mr.alignment ? mr.alignment : 1;

    VkImageSuballocator* sa = &r->image_suballoc;
    pthread_mutex_lock(&sa->mutex);

    // First fit across existing blocks of the matching memory type.
    for (VkMemBlock* b = sa->blocks; b; b = b->next) {
        if (b->memory_type_index != type_index) continue;
        VkMemRegion* prev = NULL;
        for (VkMemRegion* reg = b->free_list; reg; prev = reg, reg = reg->next) {
            VkDeviceSize bind = suballoc_align_up(reg->offset, align);
            if (bind + mr.size <= reg->offset + reg->size) {
                suballoc_carve(b, prev, reg, bind, mr.size, out);
                pthread_mutex_unlock(&sa->mutex);
                return true;
            }
        }
    }

    // No room anywhere — allocate a fresh block big enough for at least this image.
    VkDeviceSize block_size = sa->block_size;
    if (block_size < mr.size) block_size = mr.size;
    block_size = suballoc_align_up(block_size, align);

    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize  = block_size;
    ai.memoryTypeIndex = type_index;

    VkDeviceMemory mem = VK_NULL_HANDLE;
    if (vkAllocateMemory(r->device, &ai, NULL, &mem) != VK_SUCCESS) {
        pthread_mutex_unlock(&sa->mutex);
        return false;
    }

    VkMemBlock*  block  = malloc(sizeof(VkMemBlock));
    VkMemRegion* region = malloc(sizeof(VkMemRegion));
    if (!block || !region) {
        free(block);
        free(region);
        vkFreeMemory(r->device, mem, NULL);
        pthread_mutex_unlock(&sa->mutex);
        return false;
    }
    region->offset = 0;
    region->size   = block_size;
    region->next   = NULL;
    block->memory  = mem;
    block->size    = block_size;
    block->memory_type_index = type_index;
    block->free_list = region;
    block->next    = sa->blocks;
    sa->blocks     = block;

    VkDeviceSize bind = suballoc_align_up(region->offset, align);  // == 0 at block start
    suballoc_carve(block, NULL, region, bind, mr.size, out);
    pthread_mutex_unlock(&sa->mutex);
    return true;
}

static void vkr_suballoc_free(VkRenderer* r, VkSuballoc* a) {
    if (!a || !a->block) return;
    VkImageSuballocator* sa = &r->image_suballoc;
    pthread_mutex_lock(&sa->mutex);

    VkMemBlock*  b   = a->block;
    VkDeviceSize off = a->span_offset;
    VkDeviceSize sz  = a->span_size;

    // Sorted insertion point in the free list.
    VkMemRegion* prev = NULL;
    VkMemRegion* cur  = b->free_list;
    while (cur && cur->offset < off) { prev = cur; cur = cur->next; }

    bool merged_prev = prev && prev->offset + prev->size == off;
    bool merged_next = cur && off + sz == cur->offset;

    if (merged_prev && merged_next) {
        prev->size += sz + cur->size;
        prev->next  = cur->next;
        free(cur);
    } else if (merged_prev) {
        prev->size += sz;
    } else if (merged_next) {
        cur->offset = off;
        cur->size  += sz;
    } else {
        VkMemRegion* node = malloc(sizeof(VkMemRegion));
        if (node) {
            node->offset = off;
            node->size   = sz;
            node->next   = cur;
            if (prev) prev->next = node;
            else      b->free_list = node;
        } else {
            // Node alloc failed (near-impossible): leak the span; the block is reclaimed at
            // teardown anyway.
            VK_LOGE("suballoc free: region node alloc failed; leaking %llu bytes",
                    (unsigned long long)sz);
        }
    }

    // Return a fully-drained block so churn doesn't pin memory forever.
    if (b->free_list && b->free_list->next == NULL
        && b->free_list->offset == 0 && b->free_list->size == b->size) {
        VkMemBlock* pb = NULL;
        for (VkMemBlock* it = sa->blocks; it; pb = it, it = it->next) {
            if (it == b) {
                if (pb) pb->next = it->next;
                else    sa->blocks = it->next;
                break;
            }
        }
        free(b->free_list);
        vkFreeMemory(r->device, b->memory, NULL);
        free(b);
    }

    pthread_mutex_unlock(&sa->mutex);
    memset(a, 0, sizeof(*a));
}

void vkr_suballoc_destroy(VkRenderer* r) {
    VkImageSuballocator* sa = &r->image_suballoc;
    VkMemBlock* b = sa->blocks;
    while (b) {
        VkMemBlock* next = b->next;
        VkMemRegion* reg = b->free_list;
        while (reg) { VkMemRegion* rn = reg->next; free(reg); reg = rn; }
        if (b->memory) vkFreeMemory(r->device, b->memory, NULL);
        free(b);
        b = next;
    }
    sa->blocks = NULL;
    if (sa->mutex_init) {
        pthread_mutex_destroy(&sa->mutex);
        sa->mutex_init = false;
    }
}

// ----------------------------------------------------------------------
// CPU-uploaded path
// ----------------------------------------------------------------------

typedef struct UploadCtx {
    VkBuffer staging;
    VkImage  dst;
    VkDeviceSize offset;
    uint32_t x, y, w, h;
    VkImageLayout old_layout;
    bool     to_shader_read;
} UploadCtx;

static void upload_cmds(VkCommandBuffer cmd, void* user) {
    UploadCtx* u = (UploadCtx*)user;

    VkPipelineStageFlags src_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkAccessFlags src_access = 0;
    if (u->old_layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        src_stage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        src_access = VK_ACCESS_SHADER_READ_BIT;
    } else if (u->old_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        src_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        src_access = VK_ACCESS_TRANSFER_WRITE_BIT;
    }

    vkr_image_barrier(cmd, u->dst,
        u->old_layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        src_stage, VK_PIPELINE_STAGE_TRANSFER_BIT,
        src_access, VK_ACCESS_TRANSFER_WRITE_BIT);

    VkBufferImageCopy bic = {0};
    bic.bufferOffset = u->offset;
    bic.bufferRowLength = 0;
    bic.bufferImageHeight = 0;
    bic.imageOffset.x = (int32_t)u->x;
    bic.imageOffset.y = (int32_t)u->y;
    bic.imageOffset.z = 0;
    bic.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    bic.imageSubresource.layerCount = 1;
    bic.imageExtent.width = u->w;
    bic.imageExtent.height = u->h;
    bic.imageExtent.depth = 1;
    vkCmdCopyBufferToImage(cmd, u->staging, u->dst,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bic);

    if (u->to_shader_read) {
        vkr_image_barrier(cmd, u->dst,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    }
}

static bool create_image_basic(VkRenderer* r, uint32_t w, uint32_t h, VkFormat fmt,
                               VkImageUsageFlags usage, VkTexture* t) {
    VkImageCreateInfo ic = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = fmt;
    ic.extent.width = w;
    ic.extent.height = h;
    ic.extent.depth = 1;
    ic.mipLevels = 1;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = usage;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(r->device, &ic, NULL, &t->image) != VK_SUCCESS) return false;

    // Preferred path: pooled span (no per-texture vkAllocateMemory). Bind once here.
    VkSuballoc sub = {0};
    if (vkr_suballoc_image(r, t->image, &sub)) {
        if (vkBindImageMemory(r->device, t->image, sub.memory, sub.bind_offset) == VK_SUCCESS) {
            t->suballoc = sub;
            t->suballocated = true;
            return true;
        }
        // Bind attempted -> image can't be rebound via the dedicated path; fail (OOM-grade,
        // effectively never happens).
        vkr_suballoc_free(r, &sub);
        vkDestroyImage(r->device, t->image, NULL);
        t->image = VK_NULL_HANDLE;
        return false;
    }

    // Fallback: dedicated allocation (pool OOM / no DEVICE_LOCAL type). No bind attempted yet.
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(r->device, t->image, &mr);

    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = vkr_find_memory_type(r, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) {
        vkDestroyImage(r->device, t->image, NULL);
        t->image = VK_NULL_HANDLE;
        return false;
    }

    if (vkAllocateMemory(r->device, &ai, NULL, &t->memory) != VK_SUCCESS) {
        vkDestroyImage(r->device, t->image, NULL);
        t->image = VK_NULL_HANDLE;
        return false;
    }
    vkBindImageMemory(r->device, t->image, t->memory, 0);
    return true;
}

VkTexture* vkr_texture_create_uploaded(VkRenderer* r, uint32_t width, uint32_t height,
                                       const void* data, size_t data_size, uint32_t stride_pixels) {
    if (width == 0 || height == 0) return NULL;

    VkTexture* t = calloc(1, sizeof(VkTexture));
    if (!t) return NULL;
    t->width = width;
    t->height = height;
    t->format = r->caps.upload_format;

    VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (!create_image_basic(r, width, height, t->format, usage, t)) {
        free(t);
        return NULL;
    }

    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = t->image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = t->format;
    vi.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
    vi.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
    vi.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
    vi.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    if (vkCreateImageView(r->device, &vi, NULL, &t->view) != VK_SUCCESS) {
        destroy_texture_resources(r, t);
        return NULL;
    }

    // CPU-uploaded textures all want the same sampler config, so use the renderer's shared
    // sampler. tex->sampler stays VK_NULL_HANDLE; destroy_texture_resources skips it.
    if (r->shared_sampler == VK_NULL_HANDLE) {
        VK_LOGE("vkr_texture_create_uploaded: shared_sampler not initialized");
        destroy_texture_resources(r, t);
        return NULL;
    }

    t->descriptor_set = vkr_alloc_descriptor_set(r);
    if (t->descriptor_set == VK_NULL_HANDLE) {
        destroy_texture_resources(r, t);
        return NULL;
    }
    write_descriptor_set(r, t->descriptor_set, t->view, active_shared_sampler(r));

    if (data && data_size > 0) {
        vkr_texture_update(r, t, width, height, data, data_size, stride_pixels,
                           0, 0, width, height);
    } else {
        // No initial data — async transition to SHADER_READ so the texture is safe to sample
        // as black. Doesn't block the caller; the barrier orders before the next render submit
        // on the same queue per Vulkan spec.
        if (!vkr_submit_async_transition(r, t->image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, VK_ACCESS_SHADER_READ_BIT)) {
            VK_LOGW("vkr_texture_create_uploaded: async transition failed; texture may render undefined contents");
        }
    }
    t->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    t->ready = true;
    if (!track_live_texture(r, t)) {
        destroy_texture_resources(r, t);
        return NULL;
    }
    return t;
}

bool vkr_texture_update(VkRenderer* r, VkTexture* tex, uint32_t width, uint32_t height,
                        const void* data, size_t data_size, uint32_t stride_pixels,
                        uint32_t dirty_x, uint32_t dirty_y,
                        uint32_t dirty_w, uint32_t dirty_h) {
    if (!tex || tex->external || !data || data_size == 0) return false;
    if (width != tex->width || height != tex->height) {
        // Caller is expected to size-match. Reject mismatches to avoid silent corruption.
        VK_LOGW("vkr_texture_update size mismatch (have %ux%u, got %ux%u)",
                tex->width, tex->height, width, height);
        return false;
    }

    // BGRA8 = 4 bytes per pixel. Caller provides stride_pixels (per-row pixel count).
    if (stride_pixels == 0) stride_pixels = width;
    if (dirty_w == 0 || dirty_h == 0) {
        dirty_x = 0;
        dirty_y = 0;
        dirty_w = width;
        dirty_h = height;
    }
    if (dirty_x >= width || dirty_y >= height) return false;
    if (dirty_x + dirty_w > width) dirty_w = width - dirty_x;
    if (dirty_y + dirty_h > height) dirty_h = height - dirty_y;
    if (dirty_w == 0 || dirty_h == 0) return false;

    size_t needed = (size_t)dirty_w * dirty_h * 4;
    size_t src_pitch = (size_t)stride_pixels * 4;
    size_t row = (size_t)dirty_w * 4;
    size_t src_offset = ((size_t)dirty_y * stride_pixels + dirty_x) * 4;
    size_t last_row = src_offset + (size_t)(dirty_h - 1) * src_pitch + row;
    if (last_row > data_size) {
        VK_LOGW("vkr_texture_update dirty rect exceeds source buffer");
        return false;
    }

    VkStagingSlot* slot = vkr_staging_pool_acquire(r, needed);
    if (!slot) {
        VK_LOGE("vkr_texture_update: staging pool acquire failed");
        return false;
    }

    bool swizzle = r->caps.upload_needs_bgra_swizzle;
    if (dirty_x == 0 && dirty_y == 0 && dirty_w == width && dirty_h == height
        && stride_pixels == width) {
        copy_pixels_maybe_swizzle(slot->mapped, data, needed, swizzle);
    } else {
        const uint8_t* src = (const uint8_t*)data + src_offset;
        uint8_t* dst = (uint8_t*)slot->mapped;
        for (uint32_t y = 0; y < dirty_h; y++) {
            copy_pixels_maybe_swizzle(dst + (size_t)y * row,
                                      src + (size_t)y * src_pitch, row, swizzle);
        }
    }

    VkCommandBufferBeginInfo cbi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(slot->cmd, &cbi) != VK_SUCCESS) {
        vkr_staging_pool_release(slot);
        return false;
    }

    UploadCtx ctx = {
        slot->buffer, tex->image, 0,
        dirty_x, dirty_y, dirty_w, dirty_h,
        tex->layout, true
    };
    upload_cmds(slot->cmd, &ctx);

    if (vkEndCommandBuffer(slot->cmd) != VK_SUCCESS) {
        vkr_staging_pool_release(slot);
        return false;
    }

    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &slot->cmd;

    // Reset fence here, not in acquire — guarantees that the only path that leaves a fence
    // unsignaled is one where vkQueueSubmit also runs to take ownership of it.
    vkResetFences(r->device, 1, &slot->fence);

    pthread_mutex_lock(&r->queue_mutex);
    VkResult sr = vkQueueSubmit(r->graphics_queue, 1, &si, slot->fence);
    pthread_mutex_unlock(&r->queue_mutex);
    if (sr != VK_SUCCESS) {
        VK_LOGE("vkr_texture_update: vkQueueSubmit -> %d", sr);
        // Submit failed but we already reset the fence, so it's unsignaled and would deadlock
        // the next acquire. Replace with a signaled fence. (Submit failures usually mean
        // device-lost; the renderer is going to need a restart anyway.)
        vkDestroyFence(r->device, slot->fence, NULL);
        VkFenceCreateInfo rfi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        rfi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(r->device, &rfi, NULL, &slot->fence);
        vkr_staging_pool_release(slot);
        return false;
    }

    // The barrier emitted by upload_cmds (TRANSFER_WRITE → SHADER_READ, dstStage=
    // FRAGMENT_SHADER) extends into all subsequent submits on the same queue, so the next
    // render submit will observe the writes without any additional renderer-side barrier.
    tex->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    vkr_staging_pool_release(slot);
    return true;
}

typedef struct PreparedBatchUpload {
    VkTexture* texture;
    const uint8_t* data;
    size_t data_size;
    size_t src_offset;
    size_t src_pitch;
    size_t row_bytes;
    VkDeviceSize staging_offset;
    VkDeviceSize byte_count;
    uint32_t x, y, w, h;
} PreparedBatchUpload;

static VkDeviceSize align4(VkDeviceSize v) {
    return (v + 3ull) & ~(VkDeviceSize)3ull;
}

static void batch_transition_to_transfer(VkCommandBuffer cmd, VkTexture* tex) {
    VkPipelineStageFlags src_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkAccessFlags src_access = 0;
    if (tex->layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        src_stage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        src_access = VK_ACCESS_SHADER_READ_BIT;
    } else if (tex->layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        src_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        src_access = VK_ACCESS_TRANSFER_WRITE_BIT;
    }

    vkr_image_barrier(cmd, tex->image,
        tex->layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        src_stage, VK_PIPELINE_STAGE_TRANSFER_BIT,
        src_access, VK_ACCESS_TRANSFER_WRITE_BIT);
}

static void batch_transition_to_shader_read(VkCommandBuffer cmd, VkTexture* tex) {
    vkr_image_barrier(cmd, tex->image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
}

// Grow-only PreparedBatchUpload[] scratch. Render-thread-only, so unlocked; every element is
// fully overwritten before use, so no zeroing.
static PreparedBatchUpload* get_prepared_scratch(VkRenderer* r, uint32_t count) {
    if (r->batch_prepared_cap < count) {
        uint32_t new_cap = r->batch_prepared_cap ? r->batch_prepared_cap : 64;
        while (new_cap < count) new_cap *= 2;
        void* p = realloc(r->batch_prepared_scratch, (size_t)new_cap * sizeof(PreparedBatchUpload));
        if (!p) return NULL;
        r->batch_prepared_scratch = p;
        r->batch_prepared_cap = new_cap;
    }
    return (PreparedBatchUpload*)r->batch_prepared_scratch;
}

bool vkr_texture_batch_update(VkRenderer* r, const VkTextureBatchUpload* uploads,
                              uint32_t upload_count) {
    if (!r || !uploads || upload_count == 0) return false;

    PreparedBatchUpload* prepared = get_prepared_scratch(r, upload_count);
    if (!prepared) return false;

    VkDeviceSize total = 0;
    for (uint32_t i = 0; i < upload_count; i++) {
        const VkTextureBatchUpload* in = &uploads[i];
        VkTexture* tex = in->texture;
        if (!tex || tex->external || !in->data || in->data_size == 0
            || in->width != tex->width || in->height != tex->height) {
            return false;
        }

        uint32_t stride_pixels = in->stride_pixels ? in->stride_pixels : in->width;
        uint32_t dirty_x = in->dirty_x;
        uint32_t dirty_y = in->dirty_y;
        uint32_t dirty_w = in->dirty_w;
        uint32_t dirty_h = in->dirty_h;
        if (dirty_w == 0 || dirty_h == 0) {
            dirty_x = 0;
            dirty_y = 0;
            dirty_w = in->width;
            dirty_h = in->height;
        }
        if (dirty_x >= in->width || dirty_y >= in->height) {
            return false;
        }
        if (dirty_x + dirty_w > in->width) dirty_w = in->width - dirty_x;
        if (dirty_y + dirty_h > in->height) dirty_h = in->height - dirty_y;
        if (dirty_w == 0 || dirty_h == 0) {
            return false;
        }

        size_t src_pitch = (size_t)stride_pixels * 4;
        size_t row = (size_t)dirty_w * 4;
        size_t src_offset = ((size_t)dirty_y * stride_pixels + dirty_x) * 4;
        size_t last_row = src_offset + (size_t)(dirty_h - 1) * src_pitch + row;
        if (last_row > in->data_size) {
            return false;
        }

        total = align4(total);
        prepared[i].texture = tex;
        prepared[i].data = (const uint8_t*)in->data;
        prepared[i].data_size = in->data_size;
        prepared[i].src_offset = src_offset;
        prepared[i].src_pitch = src_pitch;
        prepared[i].row_bytes = row;
        prepared[i].staging_offset = total;
        prepared[i].byte_count = (VkDeviceSize)row * dirty_h;
        prepared[i].x = dirty_x;
        prepared[i].y = dirty_y;
        prepared[i].w = dirty_w;
        prepared[i].h = dirty_h;
        total += prepared[i].byte_count;
    }

    VkStagingSlot* slot = vkr_staging_pool_acquire(r, total);
    if (!slot) {
        VK_LOGE("vkr_texture_batch_update: staging pool acquire failed");
        return false;
    }

    bool swizzle = r->caps.upload_needs_bgra_swizzle;
    for (uint32_t i = 0; i < upload_count; i++) {
        const PreparedBatchUpload* u = &prepared[i];
        const uint8_t* src = u->data + u->src_offset;
        uint8_t* dst = (uint8_t*)slot->mapped + u->staging_offset;
        if (u->row_bytes == u->src_pitch) {
            copy_pixels_maybe_swizzle(dst, src, (size_t)u->byte_count, swizzle);
        } else {
            for (uint32_t y = 0; y < u->h; y++) {
                copy_pixels_maybe_swizzle(dst + (size_t)y * u->row_bytes,
                                          src + (size_t)y * u->src_pitch,
                                          u->row_bytes, swizzle);
            }
        }
    }

    VkCommandBufferBeginInfo cbi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(slot->cmd, &cbi) != VK_SUCCESS) {
        vkr_staging_pool_release(slot);
        return false;
    }

    VkTexture* current = NULL;
    for (uint32_t i = 0; i < upload_count; i++) {
        PreparedBatchUpload* u = &prepared[i];
        if (u->texture != current) {
            if (current) batch_transition_to_shader_read(slot->cmd, current);
            current = u->texture;
            batch_transition_to_transfer(slot->cmd, current);
        }

        VkBufferImageCopy bic = {0};
        bic.bufferOffset = u->staging_offset;
        bic.imageOffset.x = (int32_t)u->x;
        bic.imageOffset.y = (int32_t)u->y;
        bic.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        bic.imageSubresource.layerCount = 1;
        bic.imageExtent.width = u->w;
        bic.imageExtent.height = u->h;
        bic.imageExtent.depth = 1;
        vkCmdCopyBufferToImage(slot->cmd, slot->buffer, current->image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bic);
    }
    if (current) batch_transition_to_shader_read(slot->cmd, current);

    if (vkEndCommandBuffer(slot->cmd) != VK_SUCCESS) {
        vkr_staging_pool_release(slot);
        return false;
    }

    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &slot->cmd;
    vkResetFences(r->device, 1, &slot->fence);

    pthread_mutex_lock(&r->queue_mutex);
    VkResult sr = vkQueueSubmit(r->graphics_queue, 1, &si, slot->fence);
    pthread_mutex_unlock(&r->queue_mutex);
    if (sr != VK_SUCCESS) {
        VK_LOGE("vkr_texture_batch_update: vkQueueSubmit -> %d", sr);
        vkDestroyFence(r->device, slot->fence, NULL);
        VkFenceCreateInfo rfi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        rfi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(r->device, &rfi, NULL, &slot->fence);
        vkr_staging_pool_release(slot);
        return false;
    }

    for (uint32_t i = 0; i < upload_count; i++) {
        prepared[i].texture->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }
    vkr_staging_pool_release(slot);
    return true;
}

// ----------------------------------------------------------------------
// AHB import path (zero-copy)
// ----------------------------------------------------------------------

VkTexture* vkr_texture_import_ahb(VkRenderer* r, AHardwareBuffer* ahb, bool transfer_ownership) {
    if (!ahb) {
        VK_LOGW("AHB import skipped: null AHardwareBuffer");
        return NULL;
    }
    if (!r->ext_ahb || !r->fnGetAhbProps) {
        VK_LOGW("AHB import skipped: ext_ahb=%d fnGetAhbProps=%d",
                r->ext_ahb, r->fnGetAhbProps != NULL);
        return NULL;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID format_props = {
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID
    };
    VkAndroidHardwareBufferPropertiesANDROID props = {
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID
    };
    props.pNext = &format_props;
    if (r->fnGetAhbProps(r->device, ahb, &props) != VK_SUCCESS) {
        VK_LOGW("vkGetAndroidHardwareBufferPropertiesANDROID failed");
        return NULL;
    }

    AHardwareBuffer_Desc desc = {0};
    AHardwareBuffer_describe(ahb, &desc);
    VK_LOGI("AHB Vulkan import begin: %ux%u stride=%u format=%u usage=0x%llx allocation=%llu memoryBits=0x%x vkFormat=%d",
            desc.width, desc.height, desc.stride, desc.format,
            (unsigned long long)desc.usage,
            (unsigned long long)props.allocationSize,
            props.memoryTypeBits,
            format_props.format);

    VkTexture* t = calloc(1, sizeof(VkTexture));
    if (!t) return NULL;
    t->width = desc.width;
    t->height = desc.height;
    t->external = true;
    t->ahb = transfer_ownership ? ahb : NULL;
    if (transfer_ownership) AHardwareBuffer_acquire(ahb);

    // External-format AHB sampling requires a YCbCr conversion bound through an immutable
    // sampler in the descriptor-set layout. This renderer uses one mutable combined
    // image/sampler layout for all regular textures, so accepting external-format AHBs here
    // would be Vulkan-invalid on strict drivers. Keep the import path to RGB formats until a
    // separate immutable-sampler pipeline/layout path exists.
    if (format_props.format == VK_FORMAT_UNDEFINED) {
        VK_LOGW("AHB external-format import unsupported by current descriptor layout");
        if (t->ahb) AHardwareBuffer_release(t->ahb);
        free(t);
        return NULL;
    }

    VkExternalMemoryImageCreateInfo emi = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ic = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ic.pNext = &emi;
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = format_props.format;  // may be UNDEFINED for vendor formats
    ic.extent.width = desc.width;
    ic.extent.height = desc.height;
    ic.extent.depth = 1;
    ic.mipLevels = 1;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(r->device, &ic, NULL, &t->image) != VK_SUCCESS) {
        VK_LOGW("AHB vkCreateImage failed");
        if (t->ahb) AHardwareBuffer_release(t->ahb);
        free(t);
        return NULL;
    }
    t->format = format_props.format;

    // Dedicated allocation pulls memory from the AHB handle.
    VkImportAndroidHardwareBufferInfoANDROID import = {
        VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID
    };
    import.buffer = ahb;

    VkMemoryDedicatedAllocateInfo dedicated = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = t->image;
    dedicated.pNext = &import;

    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.pNext = &dedicated;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = vkr_find_memory_type(r, props.memoryTypeBits, 0);
    if (mai.memoryTypeIndex == UINT32_MAX) {
        VK_LOGW("AHB no compatible memory type");
        vkDestroyImage(r->device, t->image, NULL);
        if (t->ahb) AHardwareBuffer_release(t->ahb);
        free(t);
        return NULL;
    }

    if (vkAllocateMemory(r->device, &mai, NULL, &t->memory) != VK_SUCCESS) {
        VK_LOGW("AHB vkAllocateMemory failed");
        vkDestroyImage(r->device, t->image, NULL);
        if (t->ahb) AHardwareBuffer_release(t->ahb);
        free(t);
        return NULL;
    }
    vkBindImageMemory(r->device, t->image, t->memory, 0);

    VkSamplerYcbcrConversionInfo yview = {VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
    yview.conversion = t->ycbcr;

    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    if (t->ycbcr != VK_NULL_HANDLE) vi.pNext = &yview;
    vi.image = t->image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = format_props.format;
    // samplerYcbcrConversionComponents is only defined when a Ycbcr conversion is in use;
    // some non-Adreno drivers populate non-identity swizzles for RGB AHBs.
    if (t->ycbcr != VK_NULL_HANDLE) {
        vi.components = format_props.samplerYcbcrConversionComponents;
    } else {
        bool swizzle_rb = format_props.format == VK_FORMAT_R8G8B8A8_UNORM
            && desc.format == HAL_PIXEL_FORMAT_BGRA_8888;
        vi.components.r = swizzle_rb ? VK_COMPONENT_SWIZZLE_B : VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components.b = swizzle_rb ? VK_COMPONENT_SWIZZLE_R : VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
    }

    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    if (vkCreateImageView(r->device, &vi, NULL, &t->view) != VK_SUCCESS) {
        VK_LOGW("AHB vkCreateImageView failed");
        if (t->ycbcr && r->fnDestroyYcbcr) r->fnDestroyYcbcr(r->device, t->ycbcr, NULL);
        vkDestroyImage(r->device, t->image, NULL);
        vkFreeMemory(r->device, t->memory, NULL);
        if (t->ahb) AHardwareBuffer_release(t->ahb);
        free(t);
        return NULL;
    }

    // Ycbcr-bound samplers must be created per-texture (driver pairs them with the conversion).
    // For plain RGB AHB imports we can reuse the renderer's shared sampler.
    VkSampler sampler_for_descriptor;
    if (t->ycbcr != VK_NULL_HANDLE) {
        if (!vkr_create_sampler(r, t->ycbcr, &t->sampler)) {
            VK_LOGW("AHB vkr_create_sampler failed");
            vkDestroyImageView(r->device, t->view, NULL);
            if (r->fnDestroyYcbcr) r->fnDestroyYcbcr(r->device, t->ycbcr, NULL);
            vkDestroyImage(r->device, t->image, NULL);
            vkFreeMemory(r->device, t->memory, NULL);
            if (t->ahb) AHardwareBuffer_release(t->ahb);
            free(t);
            return NULL;
        }
        sampler_for_descriptor = t->sampler;
    } else if (r->shared_sampler != VK_NULL_HANDLE) {
        sampler_for_descriptor = active_shared_sampler(r);
    } else {
        VK_LOGE("AHB import: shared_sampler not initialized");
        vkDestroyImageView(r->device, t->view, NULL);
        vkDestroyImage(r->device, t->image, NULL);
        vkFreeMemory(r->device, t->memory, NULL);
        if (t->ahb) AHardwareBuffer_release(t->ahb);
        free(t);
        return NULL;
    }

    t->descriptor_set = vkr_alloc_descriptor_set(r);
    if (t->descriptor_set == VK_NULL_HANDLE) {
        if (t->sampler) vkDestroySampler(r->device, t->sampler, NULL);
        vkDestroyImageView(r->device, t->view, NULL);
        if (t->ycbcr && r->fnDestroyYcbcr) r->fnDestroyYcbcr(r->device, t->ycbcr, NULL);
        vkDestroyImage(r->device, t->image, NULL);
        vkFreeMemory(r->device, t->memory, NULL);
        if (t->ahb) AHardwareBuffer_release(t->ahb);
        free(t);
        return NULL;
    }
    write_descriptor_set(r, t->descriptor_set, t->view, sampler_for_descriptor);

    // Async transition to SHADER_READ. The barrier orders before all subsequent submits on
    // the same queue per Vulkan spec, so the next render submit safely samples this image
    // without an additional renderer-side wait.
    if (!vkr_submit_async_transition(r, t->image,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, VK_ACCESS_SHADER_READ_BIT)) {
        VK_LOGW("AHB import: async transition failed; sampling may yield undefined contents");
    }

    t->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    t->ready = true;
    if (!track_live_texture(r, t)) {
        destroy_texture_resources(r, t);
        return NULL;
    }
    VK_LOGI("AHB Vulkan import ready: %ux%u vkFormat=%d memoryType=%u texture=%p",
            t->width, t->height, t->format, mai.memoryTypeIndex,
            (void*)t);
    return t;
}

// ----------------------------------------------------------------------
// Destruction
// ----------------------------------------------------------------------

void vkr_texture_destroy(VkRenderer* r, VkTexture* tex) {
    if (!tex) return;
    untrack_live_texture(r, tex);
    destroy_texture_resources(r, tex);
}

void vkr_texture_destroy_all_live(VkRenderer* r) {
    for (;;) {
        VkTexture* tex = pop_live_texture(r);
        if (!tex) break;
        destroy_texture_resources(r, tex);
    }
}

void vkr_texture_schedule_destroy(VkRenderer* r, VkTexture* tex) {
    if (!tex) return;
    pthread_mutex_lock(&r->scene_mutex);

    if (tex->destroy_scheduled) {
        pthread_mutex_unlock(&r->scene_mutex);
        return;
    }
    tex->destroy_scheduled = true;

    // Defensive: drop any references in the live scene state.
    for (uint32_t i = 0; i < r->scene.window_count; i++) {
        if (r->scene.windows[i].texture == tex) {
            r->scene.windows[i].texture = NULL;
        }
    }
    if (r->scene.cursor_texture == tex) r->scene.cursor_texture = NULL;

    uint32_t retire_slot = (r->graveyard_index + VK_FRAMES_IN_FLIGHT)
                         % (VK_FRAMES_IN_FLIGHT + 1);
    VkGraveSlot* slot = &r->graveyard[retire_slot];
    if (slot->count >= slot->capacity) {
        uint32_t new_cap = slot->capacity ? slot->capacity * 2 : 16;
        VkTexture** ng = realloc(slot->textures, new_cap * sizeof(VkTexture*));
        if (!ng) {
            pthread_mutex_unlock(&r->scene_mutex);
            // As a last resort, leak rather than crash. Better than UAF.
            VK_LOGE("graveyard alloc failed; leaking texture %p", (void*)tex);
            return;
        }
        slot->textures = ng;
        slot->capacity = new_cap;
    }
    slot->textures[slot->count++] = tex;

    pthread_mutex_unlock(&r->scene_mutex);
}
