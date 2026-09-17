/*
 * openat2() and faccessat2() for the emulator inside the app sandbox.
 *
 * Android's zygote seccomp filter answers both with ENOSYS, and a filter that returns an errno
 * outranks proot's trace action, so the tracer never sees the call and cannot answer it either.
 * FEX resolves every guest path with openat2(rootfs_fd, path, RESOLVE_IN_ROOT); denied that, it
 * falls back to the host's own root and the guest finds none of its libraries.
 *
 * Preloaded into the FEX processes only, this answers both calls with the syscalls the filter
 * does allow. A rooted resolution is walked component by component so that an absolute symlink
 * inside the tree lands back at the root rather than at the host's, which is the whole point of
 * the flag and what the runtime's own /usr/lib symlinks rely on.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define WN_SYS_OPENAT2 437
#define WN_SYS_FACCESSAT2 439

#define WN_RESOLVE_NO_XDEV 0x01
#define WN_RESOLVE_NO_MAGICLINKS 0x02
#define WN_RESOLVE_NO_SYMLINKS 0x04
#define WN_RESOLVE_BENEATH 0x08
#define WN_RESOLVE_IN_ROOT 0x10

#define WN_MAX_LINKS 40
#define WN_MAX_DEPTH 64

struct wn_open_how {
  uint64_t flags;
  uint64_t mode;
  uint64_t resolve;
};

static long (*real_syscall)(long, ...);

/*
 * Opens `path` with `rootfd` standing in for "/", as RESOLVE_IN_ROOT and RESOLVE_BENEATH do.
 * The difference between the two is what happens at the top: IN_ROOT clamps an escape back to
 * the root, BENEATH refuses it.
 */
static int open_rooted(int rootfd, const char *path, int flags, mode_t mode, uint64_t resolve) {
  char rest[PATH_MAX];
  char next[PATH_MAX];
  char link[PATH_MAX];
  int stack[WN_MAX_DEPTH];
  int depth = 0;
  int links = 0;
  int result = -1;
  int beneath = (resolve & WN_RESOLVE_BENEATH) != 0;
  int no_symlinks = (resolve & WN_RESOLVE_NO_SYMLINKS) != 0;
  int no_xdev = (resolve & WN_RESOLVE_NO_XDEV) != 0;
  dev_t root_dev = 0;
  int saved;
  char *pos;

  if (path[0] == '\0') {
    errno = ENOENT;
    return -1;
  }
  if (strlen(path) >= sizeof(rest)) {
    errno = ENAMETOOLONG;
    return -1;
  }
  if (beneath && path[0] == '/') {
    errno = EXDEV;
    return -1;
  }
  if (no_xdev) {
    struct stat root_st;
    if (fstat(rootfd, &root_st) < 0) return -1;
    root_dev = root_st.st_dev;
  }
  strcpy(rest, path);
  pos = rest;

  for (;;) {
    char *comp;
    char *tail;
    int here = depth > 0 ? stack[depth - 1] : rootfd;
    int last;
    int fd;
    struct stat st;

    while (*pos == '/') pos++;
    if (*pos == '\0') {
      result = openat(here, ".", flags, mode);
      break;
    }
    comp = pos;
    while (*pos != '\0' && *pos != '/') pos++;
    tail = pos;
    if (*tail != '\0') {
      *tail = '\0';
      tail++;
    }
    while (*tail == '/') tail++;
    last = *tail == '\0';

    if (strcmp(comp, ".") == 0) {
      pos = tail;
      continue;
    }
    if (strcmp(comp, "..") == 0) {
      if (depth > 0) {
        close(stack[--depth]);
      } else if (beneath) {
        errno = EXDEV;
        break;
      }
      pos = tail;
      continue;
    }
    if (last && (flags & O_NOFOLLOW) != 0) {
      result = openat(here, comp, flags, mode);
      break;
    }

    fd = openat(here, comp, O_PATH | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) {
      if (last) result = openat(here, comp, flags, mode);
      break;
    }
    if (fstat(fd, &st) < 0) {
      close(fd);
      break;
    }
    if (no_xdev && st.st_dev != root_dev) {
      close(fd);
      errno = EXDEV;
      break;
    }
    if (S_ISLNK(st.st_mode)) {
      ssize_t length;
      close(fd);
      if (no_symlinks) {
        errno = ELOOP;
        break;
      }
      if (++links > WN_MAX_LINKS) {
        errno = ELOOP;
        break;
      }
      length = readlinkat(here, comp, link, sizeof(link) - 1);
      if (length < 0) break;
      link[length] = '\0';
      if ((size_t)length + 1 + strlen(tail) >= sizeof(next)) {
        errno = ENAMETOOLONG;
        break;
      }
      if (beneath && link[0] == '/') {
        errno = EXDEV;
        break;
      }
      strcpy(next, link);
      if (*tail != '\0') {
        strcat(next, "/");
        strcat(next, tail);
      }
      if (link[0] == '/')
        while (depth > 0) close(stack[--depth]);
      strcpy(rest, next);
      pos = rest;
      continue;
    }
    close(fd);
    if (last) {
      result = openat(here, comp, flags, mode);
      break;
    }
    if (depth == WN_MAX_DEPTH) {
      errno = ELOOP;
      break;
    }
    fd = openat(here, comp, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) break;
    stack[depth++] = fd;
    pos = tail;
  }

  saved = errno;
  while (depth > 0) close(stack[--depth]);
  errno = saved;
  return result;
}

long syscall(long number, ...) {
  va_list ap;
  long a[6];
  int i;

  va_start(ap, number);
  for (i = 0; i < 6; i++) a[i] = va_arg(ap, long);
  va_end(ap);

  if (number == WN_SYS_OPENAT2) {
    const struct wn_open_how *how = (const struct wn_open_how *)a[2];
    const char *path = (const char *)a[1];
    int dirfd = (int)a[0];
    int flags;

    if (how == NULL || path == NULL || (size_t)a[3] < sizeof(*how)) {
      errno = EINVAL;
      return -1;
    }
    flags = (int)how->flags;
    if ((how->resolve & (WN_RESOLVE_IN_ROOT | WN_RESOLVE_BENEATH)) != 0)
      return open_rooted(dirfd, path, flags, (mode_t)how->mode, how->resolve);
    /* Without a root to resolve against there is nothing to walk: the one remaining flag that
     * changes the result is NO_SYMLINKS, which O_NOFOLLOW gives for the path's own last
     * component. RESOLVE_NO_MAGICLINKS and RESOLVE_CACHED only matter to a caller guarding
     * against a hostile path, which the emulator's own lookups are not. */
    if ((how->resolve & WN_RESOLVE_NO_SYMLINKS) != 0) flags |= O_NOFOLLOW;
    return openat(dirfd, path, flags, (mode_t)how->mode);
  }
  if (number == WN_SYS_FACCESSAT2)
    return faccessat((int)a[0], (const char *)a[1], (int)a[2], (int)a[3]);

  if (real_syscall == NULL) real_syscall = dlsym(RTLD_NEXT, "syscall");
  return real_syscall(number, a[0], a[1], a[2], a[3], a[4], a[5]);
}
