/*
 * A zero-timeout KGSL timestamp wait answered as the poll it was meant to be.
 *
 * Turnip's KGSL backend emulates timeline semaphores with timestamps, and asks "has this one
 * retired yet?" by waiting on it with IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID and a timeout of 0.
 * KGSL reads a timeout of 0 as no timeout at all, so the poll blocks until the GPU gets there.
 * Mesa's timeline bookkeeping polls like that under a lock on every signalling submit, which put
 * vkd3d-proton, DXVK, gamescope and Zink in lock-step with the GPU: one frame queued, never two.
 * Found and measured by Bannerlator (PROGRESS_LOG 2026-09-29: a D3D12 demo went from 614 to
 * 4335 fps with the same answer given in its Vulkan adapter); the driver-side fix is on
 * Banners-Turnip, and this covers the drivers that do not carry it yet.
 *
 * The answer comes from the context's retired timestamp, compared the way KGSL compares them
 * (with wrap-around). Anything the read does not answer - an unknown context, a device that is not
 * KGSL - goes to the kernel as before. WN_NO_KGSL_POLL_FIX=1 turns it off.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/ioctl.h>

#define KGSL_IOC_TYPE 0x09
#define KGSL_TIMESTAMP_RETIRED 0x00000002

struct kgsl_device_waittimestamp_ctxtid {
  unsigned int context_id;
  unsigned int timestamp;
  unsigned int timeout;
};

struct kgsl_cmdstream_readtimestamp_ctxtid {
  unsigned int context_id;
  unsigned int type;
  unsigned int timestamp;
};

#define IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID \
  _IOW(KGSL_IOC_TYPE, 0x7, struct kgsl_device_waittimestamp_ctxtid)
#define IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID \
  _IOWR(KGSL_IOC_TYPE, 0x16, struct kgsl_cmdstream_readtimestamp_ctxtid)

static int enabled(void) {
  static int cached = -1;
  const char *off;

  if (cached >= 0) return cached;
  off = getenv("WN_NO_KGSL_POLL_FIX");
  cached = !(off != NULL && off[0] == '1');
  return cached;
}

/* True when the call was answered here, with its result in *rc (and errno set on failure). */
__attribute__((visibility("hidden"))) int wn_kgsl_poll(int (*real)(int, unsigned long, void *),
                                                       int fd, unsigned long request, void *arg,
                                                       int *rc) {
  const struct kgsl_device_waittimestamp_ctxtid *wait = arg;
  struct kgsl_cmdstream_readtimestamp_ctxtid read;
  int saved = errno;

  if (request != IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID || arg == NULL || wait->timeout != 0 ||
      !enabled())
    return 0;
  read.context_id = wait->context_id;
  read.type = KGSL_TIMESTAMP_RETIRED;
  read.timestamp = 0;
  if (real(fd, IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID, &read) != 0) {
    errno = saved;
    return 0;
  }
  if ((int32_t)(read.timestamp - wait->timestamp) >= 0) {
    errno = saved;
    *rc = 0;
  } else {
    errno = ETIMEDOUT;
    *rc = -1;
  }
  return 1;
}
