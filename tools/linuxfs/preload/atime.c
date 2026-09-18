/*
 * O_NOATIME on the game library.
 *
 * The user's games are bound in from shared storage, which Android serves over FUSE with every
 * file owned by its media provider rather than by the app. O_NOATIME is reserved for a file's
 * owner, so the kernel refuses such an open outright with EPERM. The Steam client opens every
 * content file it reads that way, and treats the refusal as the file being unreadable or
 * missing: a validation reports corrupt or missing files and an update is cancelled.
 *
 * The flag only asks the filesystem to skip an access-time update; the open is retried without
 * it. Every other refusal is passed through untouched, and a file the process may open with the
 * flag still is.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <sys/types.h>

static int retry_without_noatime(int fd, int flags) {
  return fd < 0 && errno == EPERM && (flags & O_NOATIME);
}

int open(const char *path, int flags, ...) {
  static int (*real_open)(const char *, int, ...);
  va_list ap;
  mode_t mode;
  int fd;

  va_start(ap, flags);
  mode = va_arg(ap, mode_t);
  va_end(ap);
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
