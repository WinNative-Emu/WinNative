/*
 * The adjustments a session's opens need.
 *
 * O_NOATIME on the game library: the user's games are bound in from shared storage, which Android
 * serves over FUSE with every file owned by its media provider rather than by the app. O_NOATIME
 * is reserved for a file's owner, so the kernel refuses such an open outright with EPERM. The
 * Steam client opens every content file it reads that way, and treats the refusal as the file
 * being unreadable or missing: a validation reports corrupt or missing files and an update is
 * cancelled. The flag only asks the filesystem to skip an access-time update; the open is retried
 * without it. Every other refusal is passed through untouched, and a file the process may open
 * with the flag still is.
 *
 * The kernel's trace marker: tracefs is world-writable here, so the client's tier0 opens
 * /sys/kernel/tracing/trace_marker at startup - three times over, once per module that profiles -
 * and writes a "[%s] tgid=%d begin_ctx=%lld" line for every scope it enters. Nothing in the
 * session reads those markers, and they are one more thing that can land in the wrong file when
 * a descriptor number changes hands (tracer.c has the cause). They have been found appended to an
 * app manifest, which the client then cannot parse, to a download's state file, and to content
 * chunks, which it quarantines as corrupt. Refused the descriptor, tier0 writes nothing at all and
 * has nothing to lose.
 *
 * A process status is served without the container's tracer in it; see tracer.c.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <string.h>
#include <sys/types.h>

int wn_status_without_tracer(const char *path, int flags) __attribute__((visibility("hidden")));

static int retry_without_noatime(int fd, int flags) {
  return fd < 0 && errno == EPERM && (flags & O_NOATIME);
}

/* Both spellings tier0 tries, the debugfs one first. */
static int is_trace_marker(const char *path) {
  return path != NULL
      && (strcmp(path, "/sys/kernel/tracing/trace_marker") == 0
          || strcmp(path, "/sys/kernel/debug/tracing/trace_marker") == 0);
}

int open(const char *path, int flags, ...) {
  static int (*real_open)(const char *, int, ...);
  va_list ap;
  mode_t mode;
  int fd;

  va_start(ap, flags);
  mode = va_arg(ap, mode_t);
  va_end(ap);
  if (is_trace_marker(path)) {
    errno = EACCES;
    return -1;
  }
  if ((fd = wn_status_without_tracer(path, flags)) >= 0) return fd;
  if (real_open == NULL) real_open = dlsym(RTLD_NEXT, "open");
  if (real_open == NULL) {
    errno = ENOSYS;
    return -1;
  }
  fd = real_open(path, flags, mode);
  if (retry_without_noatime(fd, flags)) fd = real_open(path, flags & ~O_NOATIME, mode);
  return fd;
}

int openat(int dirfd, const char *path, int flags, ...) {
  static int (*real_openat)(int, const char *, int, ...);
  va_list ap;
  mode_t mode;
  int fd;

  va_start(ap, flags);
  mode = va_arg(ap, mode_t);
  va_end(ap);
  if (is_trace_marker(path)) {
    errno = EACCES;
    return -1;
  }
  if ((fd = wn_status_without_tracer(path, flags)) >= 0) return fd;
  if (real_openat == NULL) real_openat = dlsym(RTLD_NEXT, "openat");
  if (real_openat == NULL) {
    errno = ENOSYS;
    return -1;
  }
  fd = real_openat(dirfd, path, flags, mode);
  if (retry_without_noatime(fd, flags)) fd = real_openat(dirfd, path, flags & ~O_NOATIME, mode);
  return fd;
}

/* The runtime is 64-bit, so the large-file names are the same calls. */
int open64(const char *path, int flags, ...) __attribute__((alias("open")));
int openat64(int dirfd, const char *path, int flags, ...) __attribute__((alias("openat")));
