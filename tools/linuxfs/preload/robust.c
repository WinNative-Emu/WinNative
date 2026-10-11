/*
 * get_robust_list(2) for the session's own threads.
 *
 * Android's app policy refuses the call, and Steam's cross-process mutex insists on seeing the
 * head glibc registered. The kernel's list protocol gives it away: a robust mutex this thread
 * holds is linked directly behind the registered head, so locking a private one and following
 * the link back finds the head without depending on libc's layout.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

#ifndef SYS_openat2
#define SYS_openat2 437
#endif

#define WN_RESOLVE_BENEATH 0x08
#define WN_RESOLVE_IN_ROOT 0x10

struct wn_open_how {
  uint64_t flags;
  uint64_t mode;
  uint64_t resolve;
};

static long (*real_syscall)(long, ...);
static int openat2_missing;

static struct robust_list_head *thread_robust_head(void) {
  pthread_mutexattr_t attr;
  pthread_mutex_t mutex;
  struct robust_list_head *head = NULL;
  pthread_mutexattr_init(&attr);
  pthread_mutexattr_setrobust(&attr, PTHREAD_MUTEX_ROBUST);
  pthread_mutex_init(&mutex, &attr);
  pthread_mutexattr_destroy(&attr);
  if (pthread_mutex_lock(&mutex) != 0) {
    return NULL;
  }
  /* The mutex holds tids and lock words beside the link, so a word is only followed
   * through a read that fails instead of faulting on a non-pointer. */
  uintptr_t lo = (uintptr_t)&mutex, hi = lo + sizeof(mutex);
  for (uintptr_t p = lo; p + sizeof(void *) <= hi && !head; p += sizeof(void *)) {
    struct robust_list_head *candidate = *(struct robust_list_head **)p;
    struct robust_list_head copy;
    struct iovec local = {&copy, sizeof(copy)}, remote = {candidate, sizeof(copy)};
    if ((uintptr_t)candidate & (sizeof(void *) - 1)) {
      continue;
    }
    if (process_vm_readv(getpid(), &local, 1, &remote, 1, 0) != (ssize_t)sizeof(copy)) {
      continue;
    }
    uintptr_t entry = (uintptr_t)copy.list.next;
    if (entry >= lo && entry < hi && copy.futex_offset < 0 && copy.futex_offset > -256) {
      head = candidate;
    }
  }
  pthread_mutex_unlock(&mutex);
  pthread_mutex_destroy(&mutex);
  return head;
}

static long openat2_via_openat(const long *args) {
  const char *path = (const char *)args[1];
  const struct wn_open_how *how = (const struct wn_open_how *)args[2];
  if (!path || !how || (size_t)args[3] < sizeof(*how)) {
    errno = EINVAL;
    return -1;
  }
  if (how->resolve & WN_RESOLVE_IN_ROOT) {
    while (*path == '/') {
      path++;
    }
    if (!*path) {
      path = ".";
    }
  } else if ((how->resolve & WN_RESOLVE_BENEATH) && *path == '/') {
    errno = EXDEV;
    return -1;
  }
  return real_syscall(SYS_openat, args[0], (long)path, (long)(int)how->flags, (long)how->mode);
}

static int execveat_is_execve(const long *args) {
  const char *path = (const char *)args[1];
  if (!path || (args[4] & (AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW))) {
    return 0;
  }
  return path[0] == '/' || (int)args[0] == AT_FDCWD;
}

long syscall(long number, ...) {
  va_list ap;
  long args[6];
  va_start(ap, number);
  for (int i = 0; i < 6; i++) {
    args[i] = va_arg(ap, long);
  }
  va_end(ap);
  if (number == SYS_get_robust_list && (args[0] == 0 || args[0] == gettid())) {
    struct robust_list_head *head = thread_robust_head();
    if (!head) {
      errno = ENOSYS;
      return -1;
    }
    *(struct robust_list_head **)args[1] = head;
    *(size_t *)args[2] = sizeof(*head);
    return 0;
  }
  if (!real_syscall) {
    real_syscall = (long (*)(long, ...)) dlsym(RTLD_NEXT, "syscall");
  }
  if (number == SYS_openat2) {
    if (!__atomic_load_n(&openat2_missing, __ATOMIC_RELAXED)) {
      long fd = real_syscall(number, args[0], args[1], args[2], args[3]);
      if (fd != -1 || errno != ENOSYS) {
        return fd;
      }
      __atomic_store_n(&openat2_missing, 1, __ATOMIC_RELAXED);
    }
    return openat2_via_openat(args);
  }
  if (number == SYS_execveat && execveat_is_execve(args)) {
    return real_syscall(SYS_execve, args[1], args[2], args[3]);
  }
  return real_syscall(number, args[0], args[1], args[2], args[3], args[4], args[5]);
}
