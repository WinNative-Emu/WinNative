/*
 * The Steam client lists a whole content directory about ten times per file it creates, moves or
 * deletes, to match names case-insensitively. On Android's FUSE storage that made reserving space
 * quadratic. Listings under steamapps/ on FUSE are kept for at most DIR_TTL_NS and dropped on any
 * inotify event the client's own patched changes don't account for; directory timestamps alone
 * can't be trusted, as NTFS volumes report whole seconds. WN_NO_DIR_CACHE=1 turns it off.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <time.h>
#include <unistd.h>

#define DIR_TTL_NS 5000000000LL
#define MAX_LISTINGS 16
#define MAX_ENTRIES 100000
#define MAX_HANDLES 64
#define FUSE_MAGIC 0x65735546
#define WATCHED (IN_CREATE | IN_DELETE | IN_MOVED_FROM | IN_MOVED_TO | IN_DELETE_SELF | IN_MOVE_SELF | IN_ONLYDIR)

/* The client is 64-bit; 32-bit builds only pass creates through. */
#if __SIZEOF_POINTER__ == 8

_Static_assert(sizeof(struct dirent) == sizeof(struct dirent64), "dirent layouts differ");

struct entry { ino_t ino; unsigned char type; char *name; };

struct listing {
  dev_t dev;
  ino_t ino;
  struct timespec mtime, ctime;
  off_t size;
  nlink_t nlink;
  long long filled;
  long long used;
  int refs;
  int detached;
  int wd;
  size_t n, cap;
  struct entry *e;
  char *recs;
  size_t len;
};

struct handle {
  struct listing *snap;
  struct listing *fill;
  DIR *real;
  size_t pos;
  int fd;
  int wd;
  int dirty;
  char *path;
  struct dirent64 out;
};

struct expect { int wd; uint32_t mask; const char *name; };

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t oplock = PTHREAD_MUTEX_INITIALIZER;
static struct listing *table[MAX_LISTINGS];
static struct handle *handles[MAX_HANDLES];
static int high_water;
static int cached_listings;
static int changing;
static int notify = -1;
static int forked;
static struct { dev_t dev; int fuse; } devs[8];
static int ndevs;

static long long now_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC_COARSE, &t);
  return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

/* A child shares the parent's inotify queue, so it stops keeping listings. */
static void after_fork(void) {
  pthread_mutex_init(&lock, NULL);
  pthread_mutex_init(&oplock, NULL);
  forked = 1;
}

static int enabled(void) {
  static int cached = -1;
  if (cached < 0) {
    const char *off = getenv("WN_NO_DIR_CACHE");
    cached = strcmp(program_invocation_short_name, "steam") == 0 &&
             strstr(program_invocation_name, "steamrtarm64") != NULL && !(off && strcmp(off, "1") == 0);
    if (cached) pthread_atfork(NULL, NULL, after_fork);
  }
  return cached && !forked;
}

static int eligible(const char *path) {
  if (path == NULL || path[0] != '/' || !enabled()) return 0;
  const char *s = strstr(path, "/steamapps");
  return s && (s[10] == '/' || s[10] == 0);
}

static int on_fuse(dev_t dev, const char *path) {
  pthread_mutex_lock(&lock);
  for (int i = 0; i < ndevs; i++) {
    if (devs[i].dev == dev) {
      int fuse = devs[i].fuse;
      pthread_mutex_unlock(&lock);
      return fuse;
    }
  }
  pthread_mutex_unlock(&lock);
  struct statfs fs;
  int saved = errno;
  int fuse = statfs(path, &fs) == 0 && (unsigned long)fs.f_type == FUSE_MAGIC;
  errno = saved;
  pthread_mutex_lock(&lock);
  if (ndevs < 8) {
    devs[ndevs].dev = dev;
    devs[ndevs].fuse = fuse;
    ndevs++;
  }
  pthread_mutex_unlock(&lock);
  return fuse;
}

static int same(const struct listing *l, const struct stat *st) {
  return l->ino == st->st_ino && l->dev == st->st_dev && l->size == st->st_size && l->nlink == st->st_nlink &&
         l->mtime.tv_sec == st->st_mtim.tv_sec && l->mtime.tv_nsec == st->st_mtim.tv_nsec &&
         l->ctime.tv_sec == st->st_ctim.tv_sec && l->ctime.tv_nsec == st->st_ctim.tv_nsec;
}

static void stamp(struct listing *l, const struct stat *st) {
  l->dev = st->st_dev;
  l->ino = st->st_ino;
  l->size = st->st_size;
  l->nlink = st->st_nlink;
  l->mtime = st->st_mtim;
  l->ctime = st->st_ctim;
}

static void destroy(struct listing *l) {
  for (size_t i = 0; i < l->n; i++) free(l->e[i].name);
  free(l->e);
  free(l->recs);
  free(l);
}

static void unref(struct listing *l) {
  if (--l->refs == 0 && l->detached) destroy(l);
}

/* The rest is called with lock held. */

static void release(int wd) {
  if (wd < 0 || notify < 0) return;
  for (int i = 0; i < MAX_LISTINGS; i++)
    if (table[i] && table[i]->wd == wd) return;
  for (int i = 0; i < high_water; i++)
    if (handles[i] && handles[i]->wd == wd) return;
  inotify_rm_watch(notify, wd);
}

static void detach(struct listing *l) {
  __atomic_sub_fetch(&cached_listings, 1, __ATOMIC_RELEASE);
  l->detached = 1;
  if (l->refs == 0) destroy(l);
}

/* Takes the listing out of the table; freed now or when its last reader closes. */
static void drop(int slot) {
  struct listing *l = table[slot];
  int wd = l->wd;
  table[slot] = NULL;
  detach(l);
  release(wd);
}

static void changed(int wd) {
  for (int i = 0; i < MAX_LISTINGS; i++)
    if (table[i] && (table[i]->wd == wd || wd < 0)) drop(i);
  for (int i = 0; i < high_water; i++)
    if (handles[i] && (handles[i]->wd == wd || wd < 0)) __atomic_store_n(&handles[i]->dirty, 1, __ATOMIC_RELAXED);
}

/* Applies queued events; the expected ones are the client's own changes, already patched in. */
static void pump(struct expect *x, int nx) {
  char buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
  if (notify < 0 || forked) return;
  int saved = errno;
  for (;;) {
    ssize_t n = read(notify, buf, sizeof(buf));
    if (n <= 0) {
      if (n < 0 && errno != EAGAIN && errno != EINTR) {
        notify = -2;
        changed(-1);
      }
      break;
    }
    for (char *p = buf; p < buf + n;) {
      struct inotify_event *ev = (struct inotify_event *)p;
      p += sizeof(*ev) + ev->len;
      if (ev->mask & IN_Q_OVERFLOW) {
        changed(-1);
        continue;
      }
      int k = 0;
      while (k < nx && !(x[k].wd == ev->wd && (ev->mask & x[k].mask) && ev->len && strcmp(ev->name, x[k].name) == 0)) k++;
      if (k < nx) {
        x[k].wd = -1;
        for (int i = 0; i < high_water; i++)
          if (handles[i] && handles[i]->wd == ev->wd) __atomic_store_n(&handles[i]->dirty, 1, __ATOMIC_RELAXED);
      } else {
        changed(ev->wd);
      }
    }
  }
  errno = saved;
}

static int watch(const char *path) {
  if (notify == -1) notify = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
  if (notify < 0) {
    notify = -2;
    return -1;
  }
  int saved = errno;
  int wd = inotify_add_watch(notify, path, WATCHED);
  errno = saved;
  return wd;
}

static int find(dev_t dev, ino_t ino) {
  for (int i = 0; i < MAX_LISTINGS; i++)
    if (table[i] && table[i]->dev == dev && table[i]->ino == ino) return i;
  return -1;
}

static int push(struct listing *l, ino_t ino, unsigned char type, const char *name) {
  if (l->n == l->cap) {
    size_t cap = l->cap ? l->cap * 2 : 256;
    struct entry *e = realloc(l->e, cap * sizeof(*e));
    if (e == NULL) return -1;
    l->e = e;
    l->cap = cap;
  }
  char *copy = strdup(name);
  if (copy == NULL) return -1;
  l->e[l->n++] = (struct entry){ino, type, copy};
  return 0;
}

static void install(struct listing *l) {
  l->detached = 0;
  __atomic_add_fetch(&cached_listings, 1, __ATOMIC_RELEASE);
  int slot = find(l->dev, l->ino);
  if (slot >= 0) {
    struct listing *old = table[slot];
    table[slot] = l;
    detach(old);
    if (old->wd != l->wd) release(old->wd);
    return;
  }
  int victim = -1;
  for (int i = 0; i < MAX_LISTINGS; i++) {
    if (table[i] == NULL) { victim = i; break; }
    if (victim < 0 || table[i]->used < table[victim]->used) victim = i;
  }
  if (table[victim]) drop(victim);
  table[victim] = l;
}

static int registered(DIR *d) {
  int n = __atomic_load_n(&high_water, __ATOMIC_ACQUIRE);
  for (int i = 0; i < n; i++)
    if (__atomic_load_n(&handles[i], __ATOMIC_ACQUIRE) == (struct handle *)d) return i;
  return -1;
}

static DIR *wrap(struct handle *h) {
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handles[i] == NULL) {
      __atomic_store_n(&handles[i], h, __ATOMIC_RELEASE);
      if (i >= high_water) __atomic_store_n(&high_water, i + 1, __ATOMIC_RELEASE);
      return (DIR *)h;
    }
  }
  return NULL;
}

#define RECLEN(namelen) ((offsetof(struct dirent64, d_name) + (namelen) + 1 + 7) & ~(size_t)7)

/* The listing as packed dirent64 records, built once per version. */
static int records(struct listing *l) {
  if (l->recs) return 0;
  size_t len = 0;
  for (size_t i = 0; i < l->n; i++) len += RECLEN(strlen(l->e[i].name));
  char *recs = malloc(len ? len : 1);
  if (recs == NULL) return -1;
  size_t off = 0;
  for (size_t i = 0; i < l->n; i++) {
    size_t namelen = strlen(l->e[i].name), reclen = RECLEN(namelen);
    struct dirent64 *r = (struct dirent64 *)(recs + off);
    memset(r, 0, reclen);
    off += reclen;
    r->d_ino = l->e[i].ino;
    r->d_off = (off_t)off;
    r->d_reclen = (unsigned short)reclen;
    r->d_type = l->e[i].type;
    memcpy(r->d_name, l->e[i].name, namelen + 1);
  }
  l->recs = recs;
  l->len = len;
  return 0;
}

/* Lock-free from here on unless noted: a stream is only ever used by one thread at a time. */

static struct handle *lookup(DIR *d) {
  return registered(d) >= 0 ? (struct handle *)d : NULL;
}

#define REAL(name) static __typeof__(&name) next_##name
#define NEXT(name) \
  if (next_##name == NULL) next_##name = (__typeof__(&name))dlsym(RTLD_NEXT, #name)

REAL(opendir);
REAL(readdir64);
REAL(readdir);
REAL(closedir);
REAL(dirfd);
REAL(rewinddir);
REAL(telldir);
REAL(seekdir);
REAL(mkdir);
REAL(rmdir);
REAL(unlink);
REAL(remove);
REAL(rename);
REAL(link);
REAL(symlink);
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
REAL(readdir64_r);
REAL(readdir_r);

/* Resolved before the client starts threads; the calls below only fill gaps for earlier callers. */
__attribute__((constructor)) static void resolve(void) {
  NEXT(opendir);
  NEXT(readdir64);
  NEXT(readdir);
  NEXT(closedir);
  NEXT(dirfd);
  NEXT(rewinddir);
  NEXT(telldir);
  NEXT(seekdir);
  NEXT(mkdir);
  NEXT(rmdir);
  NEXT(unlink);
  NEXT(remove);
  NEXT(rename);
  NEXT(link);
  NEXT(symlink);
  NEXT(readdir64_r);
  NEXT(readdir_r);
}
#pragma GCC diagnostic pop

/* A reader of the kept listing when it is still current. */
static DIR *hit(const struct stat *st, struct handle *h, long long now) {
  pthread_mutex_lock(&lock);
  if (!changing) pump(NULL, 0);
  int slot = find(st->st_dev, st->st_ino);
  if (slot >= 0 && same(table[slot], st) && now - table[slot]->filled < DIR_TTL_NS && records(table[slot]) == 0) {
    h->snap = table[slot];
    h->snap->refs++;
    h->snap->used = now;
    DIR *d = wrap(h);
    if (d) {
      pthread_mutex_unlock(&lock);
      return d;
    }
    unref(h->snap);
    h->snap = NULL;
  } else if (slot >= 0) {
    drop(slot);
  }
  pthread_mutex_unlock(&lock);
  return NULL;
}

/* Registers the stream and its watch before anything is read, so no change goes unseen. */
static int track(struct handle *h, const char *path) {
  pthread_mutex_lock(&lock);
  h->wd = watch(path);
  DIR *d = h->wd >= 0 ? wrap(h) : NULL;
  if (d == NULL && h->wd >= 0) release(h->wd);
  pthread_mutex_unlock(&lock);
  return d != NULL;
}

/* Reads through the real stream, keeping what it returns. */
static void filling(struct handle *h, DIR *real, const struct stat *st, long long now) {
  h->real = real;
  h->fill = calloc(1, sizeof(*h->fill));
  if (h->fill) {
    stamp(h->fill, st);
    h->fill->filled = now;
    h->fill->used = now;
    h->fill->wd = h->wd;
  }
}

static void untrack(struct handle *h) {
  pthread_mutex_lock(&lock);
  int i = registered((DIR *)h);
  if (i >= 0) __atomic_store_n(&handles[i], NULL, __ATOMIC_RELEASE);
  release(h->wd);
  pthread_mutex_unlock(&lock);
}

DIR *opendir(const char *path) {
  NEXT(opendir);
  struct stat st;
  if (!eligible(path) || stat(path, &st) != 0 || !S_ISDIR(st.st_mode) || !on_fuse(st.st_dev, path))
    return next_opendir(path);
  struct handle *h = calloc(1, sizeof(*h));
  char *copy = strdup(path);
  if (h == NULL || copy == NULL) {
    free(h);
    free(copy);
    return next_opendir(path);
  }
  h->fd = -1;
  h->wd = -1;
  h->path = copy;
  long long now = now_ns();
  DIR *d = hit(&st, h, now);
  if (d) return d;
  if (!track(h, path)) {
    free(h->path);
    free(h);
    return next_opendir(path);
  }
  DIR *real = stat(path, &st) == 0 ? next_opendir(path) : NULL;
  if (real == NULL) {
    untrack(h);
    free(h->path);
    free(h);
    return next_opendir(path);
  }
  filling(h, real, &st, now);
  return (DIR *)h;
}

static void abandon(struct handle *h) {
  if (h->fill) destroy(h->fill);
  h->fill = NULL;
}

static void finish(struct handle *h) {
  struct listing *l = h->fill;
  struct stat st;
  h->fill = NULL;
  int current = stat(h->path, &st) == 0 && same(l, &st);
  pthread_mutex_lock(&lock);
  if (!changing) pump(NULL, 0);
  if (current && !__atomic_load_n(&h->dirty, __ATOMIC_RELAXED)) install(l);
  else destroy(l);
  pthread_mutex_unlock(&lock);
}

static struct dirent64 *entry(struct handle *h) {
  NEXT(readdir64);
  if (h->real) {
    int saved = errno;
    errno = 0;
    struct dirent64 *r = next_readdir64(h->real);
    int error = errno;
    if (h->fill) {
      if (r) {
        if (h->fill->n >= MAX_ENTRIES || push(h->fill, r->d_ino, r->d_type, r->d_name) != 0) abandon(h);
      } else if (error == 0) {
        finish(h);
      } else {
        abandon(h);
      }
    }
    errno = error ? error : saved;
    return r;
  }
  if (h->pos >= h->snap->len) return NULL;
  const struct dirent64 *r = (const struct dirent64 *)(h->snap->recs + h->pos);
  h->pos += r->d_reclen;
  memcpy(&h->out, r, r->d_reclen);
  return &h->out;
}

struct dirent64 *readdir64(DIR *d) {
  NEXT(readdir64);
  struct handle *h = lookup(d);
  return h ? entry(h) : next_readdir64(d);
}

struct dirent *readdir(DIR *d) {
  NEXT(readdir);
  struct handle *h = lookup(d);
  return h ? (struct dirent *)entry(h) : next_readdir(d);
}

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
int readdir64_r(DIR *d, struct dirent64 *out, struct dirent64 **result) {
  NEXT(readdir64_r);
  struct handle *h = lookup(d);
  if (h == NULL) return next_readdir64_r(d, out, result);
  if (h->real) {
    abandon(h);
    return next_readdir64_r(h->real, out, result);
  }
  struct dirent64 *r = entry(h);
  if (r) memcpy(out, r, r->d_reclen);
  *result = r ? out : NULL;
  return 0;
}

int readdir_r(DIR *d, struct dirent *out, struct dirent **result) {
  NEXT(readdir_r);
  if (lookup(d) == NULL) return next_readdir_r(d, out, result);
  return readdir64_r(d, (struct dirent64 *)out, (struct dirent64 **)result);
}

#pragma GCC diagnostic pop

int closedir(DIR *d) {
  NEXT(closedir);
  pthread_mutex_lock(&lock);
  int i = registered(d);
  struct handle *h = i >= 0 ? handles[i] : NULL;
  if (h) {
    __atomic_store_n(&handles[i], NULL, __ATOMIC_RELEASE);
    if (h->snap) unref(h->snap);
    release(h->wd);
  }
  pthread_mutex_unlock(&lock);
  if (h == NULL) return next_closedir(d);
  int r = 0;
  if (h->real) r = next_closedir(h->real);
  abandon(h);
  if (h->fd >= 0) close(h->fd);
  free(h->path);
  free(h);
  return r;
}

int dirfd(DIR *d) {
  NEXT(dirfd);
  struct handle *h = lookup(d);
  if (h == NULL) return next_dirfd(d);
  if (h->real) return next_dirfd(h->real);
  if (h->fd < 0) h->fd = open(h->path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  return h->fd;
}

void rewinddir(DIR *d) {
  NEXT(rewinddir);
  struct handle *h = lookup(d);
  if (h == NULL) { next_rewinddir(d); return; }
  if (h->real) {
    abandon(h);
    next_rewinddir(h->real);
  } else {
    h->pos = 0;
  }
}

long telldir(DIR *d) {
  NEXT(telldir);
  struct handle *h = lookup(d);
  if (h == NULL) return next_telldir(d);
  return h->real ? next_telldir(h->real) : (long)h->pos;
}

void seekdir(DIR *d, long pos) {
  NEXT(seekdir);
  struct handle *h = lookup(d);
  if (h == NULL) { next_seekdir(d, pos); return; }
  if (h->real) {
    abandon(h);
    next_seekdir(h->real, pos);
  } else {
    size_t off = 0;
    while (off < (size_t)pos && off < h->snap->len) off += ((const struct dirent64 *)(h->snap->recs + off))->d_reclen;
    if (off == (size_t)pos) h->pos = off;
  }
}

struct side { char dir[PATH_MAX]; const char *name; struct stat before; int known; };

static int split(const char *path, struct side *s) {
  size_t n = strlen(path);
  while (n > 1 && path[n - 1] == '/') n--;
  const char *slash = memrchr(path, '/', n);
  if (slash == NULL || (size_t)(slash - path) >= sizeof(s->dir) || slash[1] == 0) return 0;
  size_t d = slash == path ? 1 : (size_t)(slash - path);
  memcpy(s->dir, path, d);
  s->dir[d] = 0;
  s->name = slash + 1;
  return strchr(s->name, '/') == NULL && strcmp(s->name, ".") != 0 && strcmp(s->name, "..") != 0;
}

static int watching(const char *path) {
  return eligible(path) && __atomic_load_n(&cached_listings, __ATOMIC_ACQUIRE) > 0;
}

/* Called with oplock held: the client's tracked changes happen one at a time. */
static void begin(void) {
  pthread_mutex_lock(&lock);
  pump(NULL, 0);
  changing = 1;
  pthread_mutex_unlock(&lock);
}

static void before(struct side *s) {
  s->known = stat(s->dir, &s->before) == 0;
}

enum { ADDED, REMOVED };

/* Patches the listing after the client's own change, or drops it when unsure. Called with lock held. */
static int patch(struct side *s, int what, uint32_t event, int ok, int error, const char *target, struct expect *x) {
  struct stat now;
  if (!s->known) return 0;
  int slot = find(s->before.st_dev, s->before.st_ino);
  if (slot < 0) return 0;
  struct listing *l = table[slot];
  if (!same(l, &s->before)) {
    drop(slot);
    return 0;
  }
  size_t exact = l->n, folded = l->n;
  for (size_t i = 0; i < l->n; i++) {
    if (strcmp(l->e[i].name, s->name) == 0) { exact = i; break; }
    if (folded == l->n && strcasecmp(l->e[i].name, s->name) == 0) folded = i;
  }
  if (!ok) {
    if ((what == ADDED && error == EEXIST && exact == l->n && folded == l->n) || (what == REMOVED && error == ENOENT && exact < l->n))
      drop(slot);
    return 0;
  }
  if (stat(s->dir, &now) != 0 || now.st_ino != l->ino) {
    drop(slot);
    return 0;
  }
  if (l->refs > 0) {
    struct listing *copy = calloc(1, sizeof(*copy));
    int failed = copy == NULL;
    for (size_t i = 0; !failed && i < l->n; i++) failed = push(copy, l->e[i].ino, l->e[i].type, l->e[i].name) != 0;
    if (failed) {
      if (copy) destroy(copy);
      drop(slot);
      return 0;
    }
    copy->filled = l->filled;
    copy->used = l->used;
    copy->wd = l->wd;
    copy->detached = 0;
    stamp(copy, &s->before);
    table[slot] = copy;
    l->detached = 1;
    l = copy;
  }
  free(l->recs);
  l->recs = NULL;
  l->len = 0;
  int fine = 1, expected = 1;
  struct stat t;
  if (what == ADDED) {
    if (exact < l->n) {
      if (lstat(target, &t) == 0) l->e[exact].ino = t.st_ino;
      expected = event == IN_MOVED_TO;
    } else if (folded < l->n || lstat(target, &t) != 0) {
      fine = 0;
    } else {
      unsigned char type = S_ISDIR(t.st_mode) ? DT_DIR : S_ISLNK(t.st_mode) ? DT_LNK : S_ISREG(t.st_mode) ? DT_REG : DT_UNKNOWN;
      fine = l->n < MAX_ENTRIES && push(l, t.st_ino, type, s->name) == 0;
    }
  } else if (exact < l->n) {
    free(l->e[exact].name);
    memmove(&l->e[exact], &l->e[exact + 1], (l->n - exact - 1) * sizeof(l->e[0]));
    l->n--;
  } else {
    fine = 0;
  }
  if (!fine) {
    drop(slot);
    return 0;
  }
  stamp(l, &now);
  if (!expected) return 0;
  *x = (struct expect){l->wd, event, s->name};
  return 1;
}

static void end(struct expect *x, int nx) {
  pthread_mutex_lock(&lock);
  pump(x, nx);
  changing = 0;
  pthread_mutex_unlock(&lock);
}

int wn_dircache_create(int dirfd, const char *path, int flags, mode_t mode,
                       int (*real)(int, const char *, int, mode_t)) __attribute__((visibility("hidden")));
int wn_dircache_create(int dirfd, const char *path, int flags, mode_t mode,
                       int (*real)(int, const char *, int, mode_t)) {
  struct side s;
  if (!(flags & O_CREAT) || (dirfd != AT_FDCWD && (path == NULL || path[0] != '/')) || !watching(path) || !split(path, &s))
    return real(dirfd, path, flags, mode);
  pthread_mutex_lock(&oplock);
  begin();
  before(&s);
  int fd = real(dirfd, path, flags, mode);
  int saved = errno;
  struct expect x;
  pthread_mutex_lock(&lock);
  int nx = patch(&s, ADDED, IN_CREATE, fd >= 0, saved, path, &x);
  pthread_mutex_unlock(&lock);
  end(&x, nx);
  pthread_mutex_unlock(&oplock);
  errno = saved;
  return fd;
}

#define ONE_PATH(name, what, event, call, ...) \
  int name(const char *path, ##__VA_ARGS__) { \
    NEXT(name); \
    struct side s; \
    if (!watching(path) || !split(path, &s)) return call; \
    pthread_mutex_lock(&oplock); \
    begin(); \
    before(&s); \
    int r = call; \
    int saved = errno; \
    struct expect x; \
    pthread_mutex_lock(&lock); \
    int nx = patch(&s, what, event, r == 0, saved, what == ADDED ? path : NULL, &x); \
    pthread_mutex_unlock(&lock); \
    end(&x, nx); \
    pthread_mutex_unlock(&oplock); \
    errno = saved; \
    return r; \
  }

ONE_PATH(mkdir, ADDED, IN_CREATE, next_mkdir(path, mode), mode_t mode)
ONE_PATH(rmdir, REMOVED, IN_DELETE, next_rmdir(path))
ONE_PATH(unlink, REMOVED, IN_DELETE, next_unlink(path))
ONE_PATH(remove, REMOVED, IN_DELETE, next_remove(path))

#define TWO_PATHS(name, gone, event, call) \
  int name(const char *from, const char *to) { \
    NEXT(name); \
    struct side a, b; \
    int track_a = gone && watching(from) && split(from, &a), track_b = watching(to) && split(to, &b); \
    if (!track_a && !track_b) return call(from, to); \
    pthread_mutex_lock(&oplock); \
    begin(); \
    if (track_a) before(&a); \
    if (track_b) before(&b); \
    int r = call(from, to); \
    int saved = errno; \
    struct expect x[2]; \
    int nx = 0; \
    pthread_mutex_lock(&lock); \
    if (track_a) nx += patch(&a, REMOVED, IN_MOVED_FROM, r == 0, saved, NULL, &x[nx]); \
    if (track_b) { \
      if (track_a && strcmp(a.dir, b.dir) == 0) b.known = stat(b.dir, &b.before) == 0; \
      nx += patch(&b, ADDED, event, r == 0, saved, to, &x[nx]); \
    } \
    pthread_mutex_unlock(&lock); \
    end(x, nx); \
    pthread_mutex_unlock(&oplock); \
    errno = saved; \
    return r; \
  }

/* A refused link may be answered as a rename (links.c). */
int wn_link_answer(const char *oldpath, const char *newpath, int result) __attribute__((visibility("hidden")));
static int link_or_rename(const char *from, const char *to) { return wn_link_answer(from, to, next_link(from, to)); }

TWO_PATHS(rename, 1, IN_MOVED_TO, next_rename)
TWO_PATHS(link, 0, IN_CREATE, link_or_rename)
TWO_PATHS(symlink, 0, IN_CREATE, next_symlink)

#else

int wn_dircache_create(int dirfd, const char *path, int flags, mode_t mode,
                       int (*real)(int, const char *, int, mode_t)) __attribute__((visibility("hidden")));
int wn_dircache_create(int dirfd, const char *path, int flags, mode_t mode,
                       int (*real)(int, const char *, int, mode_t)) {
  return real(dirfd, path, flags, mode);
}

#endif
