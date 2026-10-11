/*
 * Adreno revisions the runtime's Turnip does not know, reported as the revision it does.
 *
 * Retail SM8850 phones report their A840 as chip_id 0x44050A21 (KGSL gpu_model "Adreno840v2").
 * Mesa only lists 0x44050A31, so the runtime's glibc Turnip refuses the device outright
 * ("device (chip_id = 44050A21, gpu_id = 6860) is unsupported") and gamescope exits before Steam
 * starts. Banners-Turnip fixes its own builds by registering the v2 id on the same A840 GPU info
 * (patches/a840v2.py); answering KGSL's device-info query with the known id has the same effect
 * on any Turnip in the session, including the one the runtime was built with. A driver that
 * already knows the v2 id resolves the rewritten one to the same entry.
 *
 * Only the KGSL device-info property is touched. Its layout is the kernel's uapi (msm_kgsl.h).
 */
#define _GNU_SOURCE 1
#include <stddef.h>
#include <sys/ioctl.h>

struct kgsl_devinfo {
    unsigned int device_id;
    unsigned int chip_id;
    unsigned int mmu_enabled;
    unsigned long gmem_gpubaseaddr;
    unsigned int gpu_id;
    size_t gmem_sizebytes;
};

struct kgsl_device_getproperty {
    unsigned int type;
    void *value;
    size_t sizebytes;
};

#define KGSL_IOC_TYPE 0x09
#define IOCTL_KGSL_DEVICE_GETPROPERTY _IOWR(KGSL_IOC_TYPE, 0x2, struct kgsl_device_getproperty)
#define KGSL_PROP_DEVICE_INFO 0x1

static const struct {
    unsigned int reported;
    unsigned int known;
} CHIP_IDS[] = {
    {0x44050A21, 0x44050A31}, /* Adreno 840v2 -> Adreno 840 */
};

__attribute__((visibility("hidden"))) void wn_kgsl_chip_id_fixup(unsigned long request, void *arg, int rc) {
    if (rc != 0 || request != IOCTL_KGSL_DEVICE_GETPROPERTY || arg == NULL) return;
    struct kgsl_device_getproperty *prop = arg;
    if (prop->type != KGSL_PROP_DEVICE_INFO || prop->value == NULL
        || prop->sizebytes < sizeof(struct kgsl_devinfo)) {
        return;
    }
    struct kgsl_devinfo *info = prop->value;
    for (size_t i = 0; i < sizeof(CHIP_IDS) / sizeof(CHIP_IDS[0]); i++) {
        if (info->chip_id == CHIP_IDS[i].reported) {
            info->chip_id = CHIP_IDS[i].known;
            return;
        }
    }
}
