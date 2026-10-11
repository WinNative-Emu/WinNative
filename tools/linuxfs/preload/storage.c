/*
 * Opt-in observations for file allocation and writes to the selected Steam library.
 *
 * The session enables them only when storage diagnostics are turned on. Counters are local to
 * each process, allocation details are capped, writes are sampled, and storage.log has a global
 * size limit because every Steam child shares it.
 *
 * LD_PRELOAD cannot see direct syscalls or libc-internal calls that bypass public symbols. A
 * missing entry therefore does not prove the operation did not happen.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static int (*next_fstat)(int, struct stat *);
static int (*next_fallocate)(int, int, off_t, off_t);
static int (*next_posix_fallocate)(int, off_t, off_t);
static ssize_t (*next_pwrite)(int, const void *, size_t, off_t);
static int (*next_ftruncate)(int, off_t);
static int (*next_fsync)(int);

struct counters {
  volatile uint64_t alloc_calls, alloc_failures, alloc_elapsed_ns;
  volatile uint64_t pwrite_samples, pwrite_bytes, pwrite_failures, pwrite_elapsed_ns, pwrite_max_ns;
  volatile uint64_t ftruncate_calls, ftruncate_failures, ftruncate_elapsed_ns, ftruncate_max_ns;
  volatile uint64_t fstat_calls, fstat_elapsed_ns, fstat_max_ns;
  volatile uint64_t fsync_calls, fsync_failures, fsync_elapsed_ns, fsync_max_ns;
};

static struct counters stats;
static volatile uint64_t pwrite_seen;
static volatile unsigned int alloc_details;
static long long selected_device;
static int init_state;
static int enabled;

static uint64_t add(volatile uint64_t *slot, uint64_t value) {
  return __atomic_add_fetch(slot, value, __ATOMIC_RELAXED);
}

static uint64_t get(const volatile uint64_t *slot) {
  return __atomic_load_n(slot, __ATOMIC_RELAXED);
}

static void update_max(volatile uint64_t *slot, uint64_t value) {
  uint64_t old = get(slot);
  while (old < value && !__atomic_compare_exchange_n(slot, &old, value, 0,
                                                       __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
  }
}

static void initialize(void) {
  int state = __atomic_load_n(&init_state, __ATOMIC_ACQUIRE);
  if (state == 2) return;
  int expected = 0;
  if (!__atomic_compare_exchange_n(&init_state, &expected, 1, 0,
                                  __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
    while (__atomic_load_n(&init_state, __ATOMIC_ACQUIRE) != 2) { }
    return;
  }
  const char *on = getenv("WN_STORAGE_DIAGNOSTICS");
  const char *device = getenv("WN_STORAGE_DEVICE");
  if (on != NULL && strcmp(on, "1") == 0 && device != NULL && *device != '\0') {
    char *end = NULL;
    errno = 0;
    long long parsed = strtoll(device, &end, 10);
    if (errno == 0 && end != device && *end == '\0') {
      selected_device = parsed;
      enabled = 1;
    }
  }
  __atomic_store_n(&init_state, 2, __ATOMIC_RELEASE);
}

static uint64_t now_ns(void) {
  struct timespec ts;
  if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
  return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static int on_selected_device(int fd, const struct stat *known) {
  struct stat local;
  initialize();
  if (!enabled) return 0;
  if (known == NULL) {
    if (next_fstat == NULL) next_fstat = dlsym(RTLD_NEXT, "fstat");
    if (next_fstat == NULL || next_fstat(fd, &local) != 0) return 0;
    known = &local;
  }
  return (long long)known->st_dev == selected_device;
}

static void comm_name(char *out, size_t size) {
  const char *name = program_invocation_short_name;
  size_t i = 0;
  if (name == NULL || *name == '\0') name = "unknown";
  while (name[i] && i + 1 < size && i < 40) {
    unsigned char c = (unsigned char)name[i];
    out[i] = ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') ? (char)c : '_';
    i++;
  }
  out[i] = '\0';
}

static void append_line(const char *line, size_t length) {
  const char *path = getenv("WN_STORAGE_LOG");
  struct stat st;
  int saved = errno;
  if (path == NULL || *path == '\0' || length == 0 || length > 2048) goto done;
  int fd = (int)syscall(SYS_openat, (long)AT_FDCWD, (long)path,
                       (long)(O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC), 0600L, 0L, 0L);
  if (fd < 0) goto done;
  if (syscall(SYS_fstat, (long)fd, (long)&st, 0L, 0L, 0L, 0L) != 0 || st.st_size < 0 ||
      (uint64_t)st.st_size + length > 524288ULL) {
    syscall(SYS_close, (long)fd, 0L, 0L, 0L, 0L, 0L);
    goto done;
  }
  /* One small append write per complete line; no per-write log flood. */
  (void)syscall(SYS_write, (long)fd, (long)line, (long)length, 0L, 0L, 0L);
  syscall(SYS_close, (long)fd, 0L, 0L, 0L, 0L, 0L);
done:
  errno = saved;
}

static void allocation_line(const char *api, int result, int error, int flags,
                            off_t offset, off_t length, uint64_t elapsed) {
  char line[512], comm[48];
  int api_error = api[0] == 'f' ? (result < 0 ? error : 0) : result;
  unsigned int detail = __atomic_add_fetch(&alloc_details, 1, __ATOMIC_RELAXED);
  add(&stats.alloc_calls, 1);
  add(&stats.alloc_elapsed_ns, elapsed);
  if ((api[0] == 'f' && result < 0) || (api[0] == 'p' && result != 0))
    add(&stats.alloc_failures, 1);
  if (detail > 64) return;
  comm_name(comm, sizeof(comm));
  int n = snprintf(line, sizeof(line),
                   "allocation pid=%ld comm=%s api=%s result=%d api_error=%d errno_after=%d flags=0x%x offset=%lld length=%lld elapsed_us=%llu\n",
                   (long)getpid(), comm, api, result, api_error, error, flags,
                   (long long)offset, (long long)length,
                   (unsigned long long)(elapsed / 1000ULL));
  if (n > 0 && (size_t)n < sizeof(line)) append_line(line, (size_t)n);
}

static int steam_client(void) {
  static int cached = -1;
  if (cached < 0)
    cached = strcmp(program_invocation_short_name, "steam") == 0 &&
             strstr(program_invocation_name, "steamrtarm64") != NULL;
  return cached;
}

static int staging_file(int fd) {
  char link[32], path[PATH_MAX];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  long n = syscall(SYS_readlinkat, (long)AT_FDCWD, (long)link, (long)path, (long)sizeof(path) - 1);
  if (n <= 0) return 0;
  path[n] = '\0';
  return strstr(path, "/steamapps/downloading/") != NULL;
}

static long long unreserved[16];
static unsigned int unreserved_count;

static int cannot_reserve(long long device) {
  unsigned int n = __atomic_load_n(&unreserved_count, __ATOMIC_ACQUIRE);
  for (unsigned int i = 0; i < n && i < 16; i++)
    if (__atomic_load_n(&unreserved[i], __ATOMIC_RELAXED) == device) return 1;
  return 0;
}

static void note_cannot_reserve(int fd) {
  struct stat st;
  if (next_fstat == NULL) next_fstat = dlsym(RTLD_NEXT, "fstat");
  if (next_fstat == NULL || next_fstat(fd, &st) != 0 || cannot_reserve((long long)st.st_dev)) return;
  unsigned int slot = __atomic_load_n(&unreserved_count, __ATOMIC_RELAXED);
  while (slot < 16 && !__atomic_compare_exchange_n(&unreserved_count, &slot, slot + 1, 0, __ATOMIC_ACQ_REL,
                                                   __ATOMIC_RELAXED)) {
  }
  if (slot < 16) __atomic_store_n(&unreserved[slot], (long long)st.st_dev, __ATOMIC_RELEASE);
}

int fallocate(int fd, int mode, off_t offset, off_t length) {
  int entry_errno = errno;
  if (next_fallocate == NULL) next_fallocate = dlsym(RTLD_NEXT, "fallocate");
  if (next_fallocate == NULL) { errno = ENOSYS; return -1; }
  int selected = on_selected_device(fd, NULL);
  errno = entry_errno;
  uint64_t start = selected ? now_ns() : 0;
  errno = entry_errno;
  int result = next_fallocate(fd, mode, offset, length);
  int call_errno = errno;
  if (result != 0 && (call_errno == EOPNOTSUPP || call_errno == ENOSYS) && steam_client()) note_cannot_reserve(fd);
  if (selected) allocation_line("fallocate", result, call_errno, mode, offset, length, now_ns() - start);
  errno = call_errno;
  return result;
}

int posix_fallocate(int fd, off_t offset, off_t length) {
  int entry_errno = errno;
  if (next_posix_fallocate == NULL) next_posix_fallocate = dlsym(RTLD_NEXT, "posix_fallocate");
  if (next_posix_fallocate == NULL) { errno = entry_errno; return ENOSYS; }
  int selected = on_selected_device(fd, NULL);
  errno = entry_errno;
  if (!selected) {
    int result = next_posix_fallocate(fd, offset, length);
    errno = entry_errno;
    return result;
  }
  uint64_t start = now_ns();
  errno = entry_errno;
  int result = next_posix_fallocate(fd, offset, length);
  int call_errno = errno;
  uint64_t elapsed = now_ns() - start;
  allocation_line("posix_fallocate", result, call_errno, 0, offset, length, elapsed);
  errno = entry_errno; /* POSIX requires the error to be returned, leaving errno unchanged. */
  return result;
}

ssize_t pwrite(int fd, const void *buffer, size_t count, off_t offset) {
  int entry_errno = errno;
  if (next_pwrite == NULL) next_pwrite = dlsym(RTLD_NEXT, "pwrite");
  if (next_pwrite == NULL) { errno = ENOSYS; return -1; }
  initialize();
  uint64_t sequence = enabled ? add(&pwrite_seen, 1) : 1;
  int sample = enabled && (sequence % 32ULL) == 0;
  int selected = sample && on_selected_device(fd, NULL);
  errno = entry_errno;
  if (!selected)
    return next_pwrite(fd, buffer, count, offset);
  uint64_t start = now_ns();
  errno = entry_errno;
  ssize_t result = next_pwrite(fd, buffer, count, offset);
  int call_errno = errno;
  uint64_t elapsed = now_ns() - start;
  add(&stats.pwrite_samples, 1);
  add(&stats.pwrite_bytes, result > 0 ? (uint64_t)result : 0);
  add(&stats.pwrite_elapsed_ns, elapsed);
  if (result < 0) add(&stats.pwrite_failures, 1);
  update_max(&stats.pwrite_max_ns, elapsed);
  errno = call_errno;
  return result;
}

int ftruncate(int fd, off_t length) {
  int entry_errno = errno;
  if (next_ftruncate == NULL) next_ftruncate = dlsym(RTLD_NEXT, "ftruncate");
  if (next_ftruncate == NULL) { errno = ENOSYS; return -1; }
  int selected = on_selected_device(fd, NULL);
  uint64_t start = selected ? now_ns() : 0;
  struct stat st;
  int known = 0;
  if (steam_client()) {
    if (next_fstat == NULL) next_fstat = dlsym(RTLD_NEXT, "fstat");
    /* staging_file() reads /proc/self/fd, a round trip through proot: skip it for a shrink, which
     * is passed through untouched either way. */
    known = next_fstat != NULL && next_fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
            length >= st.st_size && staging_file(fd);
  }
  int result = 0, call_errno = entry_errno;
  if (!known || length <= st.st_size || !cannot_reserve((long long)st.st_dev)) {
    errno = entry_errno;
    result = next_ftruncate(fd, length);
    call_errno = errno;
    if (result != 0 && known && length >= st.st_size && (call_errno == EPERM || call_errno == EOPNOTSUPP)) {
      if (length > st.st_size) note_cannot_reserve(fd);
      result = 0;
      call_errno = entry_errno;
    }
  }
  if (!selected) {
    errno = call_errno;
    return result;
  }
  uint64_t elapsed = now_ns() - start;
  add(&stats.ftruncate_calls, 1);
  if (result != 0) add(&stats.ftruncate_failures, 1);
  add(&stats.ftruncate_elapsed_ns, elapsed);
  update_max(&stats.ftruncate_max_ns, elapsed);
  errno = call_errno;
  return result;
}

int fstat(int fd, struct stat *out) {
  int entry_errno = errno;
  if (next_fstat == NULL) next_fstat = dlsym(RTLD_NEXT, "fstat");
  if (next_fstat == NULL) { errno = ENOSYS; return -1; }
  initialize();
  errno = entry_errno;
  if (!enabled) return next_fstat(fd, out);
  uint64_t start = now_ns();
  errno = entry_errno;
  int result = next_fstat(fd, out);
  int call_errno = errno;
  uint64_t elapsed = now_ns() - start;
  if (result == 0 && on_selected_device(fd, out)) {
    add(&stats.fstat_calls, 1);
    add(&stats.fstat_elapsed_ns, elapsed);
    update_max(&stats.fstat_max_ns, elapsed);
  }
  errno = call_errno;
  return result;
}

int fsync(int fd) {
  int entry_errno = errno;
  if (next_fsync == NULL) next_fsync = dlsym(RTLD_NEXT, "fsync");
  if (next_fsync == NULL) { errno = ENOSYS; return -1; }
  int selected = on_selected_device(fd, NULL);
  errno = entry_errno;
  if (!selected) return next_fsync(fd);
  uint64_t start = now_ns();
  errno = entry_errno;
  int result = next_fsync(fd);
  int call_errno = errno;
  uint64_t elapsed = now_ns() - start;
  add(&stats.fsync_calls, 1);
  if (result != 0) add(&stats.fsync_failures, 1);
  add(&stats.fsync_elapsed_ns, elapsed);
  update_max(&stats.fsync_max_ns, elapsed);
  errno = call_errno;
  return result;
}

static void flush_summary(void) __attribute__((destructor));
static void flush_summary(void) {
  char line[1024], comm[48];
  initialize();
  if (!enabled) return;
  uint64_t alloc = get(&stats.alloc_calls), pwrite_samples = get(&stats.pwrite_samples);
  uint64_t truncates = get(&stats.ftruncate_calls), fstats = get(&stats.fstat_calls), syncs = get(&stats.fsync_calls);
  if (alloc == 0 && pwrite_samples == 0 && truncates == 0 && fstats == 0 && syncs == 0) return;
  comm_name(comm, sizeof(comm));
  int n = snprintf(line, sizeof(line),
      "summary pid=%ld comm=%s allocation_calls=%llu allocation_failures=%llu allocation_total_us=%llu pwrite_sample_every=32 pwrite_samples=%llu pwrite_bytes=%llu pwrite_failures=%llu pwrite_sample_total_us=%llu pwrite_sample_max_us=%llu ftruncate_calls=%llu ftruncate_failures=%llu ftruncate_total_us=%llu ftruncate_max_us=%llu fstat_calls=%llu fstat_total_us=%llu fstat_max_us=%llu fsync_calls=%llu fsync_failures=%llu fsync_total_us=%llu fsync_max_us=%llu\n",
      (long)getpid(), comm,
      (unsigned long long)alloc, (unsigned long long)get(&stats.alloc_failures),
      (unsigned long long)(get(&stats.alloc_elapsed_ns) / 1000ULL),
      (unsigned long long)pwrite_samples, (unsigned long long)get(&stats.pwrite_bytes),
      (unsigned long long)get(&stats.pwrite_failures),
      (unsigned long long)(get(&stats.pwrite_elapsed_ns) / 1000ULL),
      (unsigned long long)(get(&stats.pwrite_max_ns) / 1000ULL),
      (unsigned long long)truncates, (unsigned long long)get(&stats.ftruncate_failures),
      (unsigned long long)(get(&stats.ftruncate_elapsed_ns) / 1000ULL),
      (unsigned long long)(get(&stats.ftruncate_max_ns) / 1000ULL),
      (unsigned long long)fstats,
      (unsigned long long)(get(&stats.fstat_elapsed_ns) / 1000ULL),
      (unsigned long long)(get(&stats.fstat_max_ns) / 1000ULL),
      (unsigned long long)syncs, (unsigned long long)get(&stats.fsync_failures),
      (unsigned long long)(get(&stats.fsync_elapsed_ns) / 1000ULL),
      (unsigned long long)(get(&stats.fsync_max_ns) / 1000ULL));
  if (n > 0 && (size_t)n < sizeof(line)) append_line(line, (size_t)n);
}

#if defined(__USE_LARGEFILE64)
int fallocate64(int fd, int mode, off64_t offset, off64_t length) __attribute__((alias("fallocate")));
int posix_fallocate64(int fd, off64_t offset, off64_t length) __attribute__((alias("posix_fallocate")));
ssize_t pwrite64(int fd, const void *buffer, size_t count, off64_t offset) __attribute__((alias("pwrite")));
int ftruncate64(int fd, off64_t length) __attribute__((alias("ftruncate")));
int fstat64(int fd, struct stat64 *out) __attribute__((alias("fstat")));
#endif
