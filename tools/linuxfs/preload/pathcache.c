/*
 * Path lookups the Steam client repeats, answered from a short-lived cache.
 *
 * While it registers its compatibility tools after logon, the client resolves the same few
 * directories - its local tools under compatibilitytools.d and its own platform directory - with
 * realpath() and access() thousands of times a second. Each realpath() is one readlinkat() per
 * path component, and under proot every one of those is a trapped syscall with a round trip
 * through the tracer. Measured on an AYN Thor, that loop was 23 of the client's 31 seconds of
 * startup, with proot busy for 16 of them.
 *
 * Only the client itself uses the cache, never a game or a helper. Only successful answers are
 * kept, and only for a moment: a path that stops existing is noticed within PATH_CACHE_TTL_NS, and
 * one that starts existing is never hidden, since failures always go to the filesystem.
 * WN_NO_PATH_CACHE=1 turns it off.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define PATH_CACHE_SLOTS 256
#define PATH_CACHE_KEY_MAX 512
#define PATH_CACHE_TTL_NS 2000000000LL

enum { KIND_REALPATH, KIND_ACCESS };

struct entry {
  long long stamp;
  unsigned hash;
  int kind;
  int mode;
  int flags;
  char key[PATH_CACHE_KEY_MAX];
  char *value;
};

static struct entry table[PATH_CACHE_SLOTS];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static long long now_ns(void) {
  struct timespec t;

  clock_gettime(CLOCK_MONOTONIC_COARSE, &t);
  return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

static int enabled(void) {
  static int cached = -1;
  const char *off;

  if (cached >= 0) return cached;
  off = getenv("WN_NO_PATH_CACHE");
  cached = strcmp(program_invocation_short_name, "steam") == 0 &&
           strstr(program_invocation_name, "steamrtarm64") != NULL &&
           !(off != NULL && off[0] == '1');
  return cached;
}

static unsigned hash_of(const char *s, int kind, int mode, int flags) {
  unsigned h = 2166136261u ^ (unsigned)(kind * 31 + mode * 7 + flags);

  while (*s) h = (h ^ (unsigned char)*s++) * 16777619u;
  return h;
}

static int cacheable(const char *path) {
  return path != NULL && path[0] == '/' && strlen(path) < PATH_CACHE_KEY_MAX && enabled();
}

static struct entry *find(const char *path, int kind, int mode, int flags, unsigned h) {
  struct entry *e = &table[h % PATH_CACHE_SLOTS];

  if (e->stamp == 0 || e->hash != h || e->kind != kind || e->mode != mode || e->flags != flags) return NULL;
  if (now_ns() - e->stamp > PATH_CACHE_TTL_NS || strcmp(e->key, path) != 0) return NULL;
  return e;
}

static void store(const char *path, int kind, int mode, int flags, unsigned h, const char *value) {
  struct entry *e = &table[h % PATH_CACHE_SLOTS];
  char *copy = value != NULL ? strdup(value) : NULL;

  if (value != NULL && copy == NULL) return;
  free(e->value);
  e->value = copy;
  e->hash = h;
  e->kind = kind;
  e->mode = mode;
  e->flags = flags;
  memcpy(e->key, path, strlen(path) + 1);
  e->stamp = now_ns();
}

__attribute__((visibility("hidden")))
char *wn_cached_realpath(const char *path, char *resolved, char *(*real)(const char *, char *)) {
  char out[PATH_MAX];
  unsigned h;
  struct entry *e;

  if (!cacheable(path)) return real(path, resolved);
  h = hash_of(path, KIND_REALPATH, 0, 0);
  pthread_mutex_lock(&lock);
  e = find(path, KIND_REALPATH, 0, 0, h);
  if (e != NULL && e->value != NULL) {
    size_t n = strlen(e->value) + 1;
    char *result = resolved != NULL ? resolved : malloc(n);
    if (result != NULL) memcpy(result, e->value, n);
    pthread_mutex_unlock(&lock);
    return result;
  }
  pthread_mutex_unlock(&lock);
  if (real(path, out) == NULL) return NULL;
  pthread_mutex_lock(&lock);
  store(path, KIND_REALPATH, 0, 0, h, out);
  pthread_mutex_unlock(&lock);
  if (resolved != NULL) {
    memcpy(resolved, out, strlen(out) + 1);
    return resolved;
  }
  return strdup(out);
}

static int cached_access(const char *path, int mode, int flags, int (*check)(const char *, int, int)) {
  unsigned h;
  int hit;
  int result;

  if (!cacheable(path)) return check(path, mode, flags);
  h = hash_of(path, KIND_ACCESS, mode, flags);
  pthread_mutex_lock(&lock);
  hit = find(path, KIND_ACCESS, mode, flags, h) != NULL;
  pthread_mutex_unlock(&lock);
  if (hit) return 0;
  result = check(path, mode, flags);
  if (result == 0) {
    pthread_mutex_lock(&lock);
    store(path, KIND_ACCESS, mode, flags, h, NULL);
    pthread_mutex_unlock(&lock);
  }
  return result;
}

static int (*next_faccessat(void))(int, const char *, int, int) {
  static int (*real)(int, const char *, int, int);

  if (real == NULL) real = (int (*)(int, const char *, int, int))dlsym(RTLD_NEXT, "faccessat");
  return real;
}

static int check_cwd(const char *path, int mode, int flags) {
  int (*real)(int, const char *, int, int) = next_faccessat();

  if (real == NULL) {
    errno = ENOSYS;
    return -1;
  }
  return real(AT_FDCWD, path, mode, flags);
}

int access(const char *path, int mode) {
  return cached_access(path, mode, 0, check_cwd);
}

int euidaccess(const char *path, int mode) {
  return cached_access(path, mode, AT_EACCESS, check_cwd);
}

int eaccess(const char *path, int mode) {
  return cached_access(path, mode, AT_EACCESS, check_cwd);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
  int (*real)(int, const char *, int, int);

  if (dirfd == AT_FDCWD) {
    return cached_access(path, mode, flags, check_cwd);
  }
  real = next_faccessat();
  if (real == NULL) {
    errno = ENOSYS;
    return -1;
  }
  return real(dirfd, path, mode, flags);
}
