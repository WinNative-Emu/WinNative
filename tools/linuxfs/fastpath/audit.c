/*
 * libwnaudit: an LD_AUDIT module that keeps ld.so from asking proot about libraries that are not
 * there. ld.so finds a library by trying to open it in each directory of its search path in turn,
 * and under proot every one of those opens is a round trip through the tracer. A game's environment
 * puts the game's own directory, Steam's runtime and video directories and Proton's library
 * directories ahead of /usr/lib, so most of the opens of every Wine process fail.
 *
 * la_objsearch() is asked about each candidate before ld.so opens it. One that provably does not
 * exist - its parent directory is reached through no symlink, so proot would walk the same path,
 * and the name itself is missing there - is skipped. Everything else is left to ld.so, which then
 * behaves exactly as it would have.
 *
 * Built without libc: an audit module is loaded into a link namespace of its own, and a libc there
 * would be searched for along that same path. Its calls go through the trampoline page of
 * libwnfastpath.so, mapped here first (libwnfastpath finds it in place), and only in a process traced
 * by the proot the environment describes (PROOT_FP_KEY, as libwnfastpath checks it).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>

#define FP_STUB_ADDR 0xffff00000UL /* as in fastpath.c */
#define FP_STUB_SIZE 4096UL
#define MAX_BINDS 256
#define PATH 4096
#define VERIFIED 32
#define TTL_NS 2000000000LL

typedef long (*stub_fn)(long, long, long, long, long, long, long);
static stub_fn sys;

static long sc(long nr, long a, long b, long c, long d) { return sys(nr, a, b, c, d, 0, 0); }

/* A syscall of its own, before the trampoline exists: only mmap, mprotect and munmap, which proot
 * does not stop. */
static long raw(long nr, long a, long b, long c, long d) {
  register long x8 __asm__("x8") = nr;
  register long x0 __asm__("x0") = a;
  register long x1 __asm__("x1") = b;
  register long x2 __asm__("x2") = c;
  register long x3 __asm__("x3") = d;
  register long x4 __asm__("x4") = -1;
  register long x5 __asm__("x5") = 0;
  __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory");
  return x0;
}

/* What __builtin___clear_cache does on aarch64, without libgcc. */
static void sync_code(char *b, char *e) {
  for (char *p = b; p < e; p += 4) __asm__ volatile("dc cvau, %0" : : "r"(p) : "memory");
  __asm__ volatile("dsb ish" : : : "memory");
  for (char *p = b; p < e; p += 4) __asm__ volatile("ic ivau, %0" : : "r"(p) : "memory");
  __asm__ volatile("dsb ish\n\tisb" : : : "memory");
}

static size_t len_of(const char *s) {
  size_t n = 0;
  while (s[n]) n++;
  return n;
}

static int same(const char *a, const char *b, size_t n) {
  for (size_t i = 0; i < n; i++)
    if (a[i] != b[i]) return 0;
  return 1;
}

static void copy(char *d, const char *s, size_t n) {
  for (size_t i = 0; i < n; i++) d[i] = s[i];
}

static int under(const char *path, const char *dir, size_t dlen) {
  return same(path, dir, dlen) && (path[dlen] == 0 || path[dlen] == '/');
}

/* ---------------------------------------------------------------- setup */

static char env[65536], tracer_env[32768];
static size_t env_len;
static const char *root;
static size_t rootlen;
static char root_canon[PATH];
struct bind { const char *host; size_t hlen; const char *guest; size_t glen; int state; char canon[PATH]; };
static struct bind binds[MAX_BINDS];
static int nbinds;
static int ready;

static long slurp(const char *path, char *buf, size_t size) {
  long fd = sc(SYS_openat, AT_FDCWD, (long)path, O_RDONLY | O_CLOEXEC, 0);
  if (fd < 0) return -1;
  size_t have = 0;
  for (long r; have < size - 1 && (r = sc(SYS_read, fd, (long)buf + have, size - 1 - have, 0)) > 0;) have += r;
  sc(SYS_close, fd, 0, 0, 0);
  buf[have] = 0;
  return (long)have;
}

static const char *var(const char *buf, size_t len, const char *name) {
  size_t n = len_of(name);
  for (size_t i = 0; i < len; i += len_of(buf + i) + 1)
    if (same(buf + i, name, n) && buf[i + n] == '=') return buf + i + n + 1;
  return NULL;
}

/* The canonical form of a host path, as proot's realpath() made it; 0 when it has none. */
static int canonical(const char *path, char *out) {
  long fd = sc(SYS_openat, AT_FDCWD, (long)path, O_PATH | O_CLOEXEC, 0);
  if (fd < 0) return 0;
  char link[40] = "/proc/self/fd/";
  char digits[20];
  int nd = 0;
  for (long v = fd; nd == 0 || v; v /= 10) digits[nd++] = '0' + v % 10;
  for (int i = 0; i < nd; i++) link[14 + i] = digits[nd - 1 - i];
  link[14 + nd] = 0;
  long n = sc(SYS_readlinkat, AT_FDCWD, (long)link, (long)out, PATH - 1);
  sc(SYS_close, fd, 0, 0, 0);
  if (n <= 0 || out[0] != '/') return 0;
  out[n] = 0;
  return 1;
}

static int traced_by_match(const char *key) {
  char status[4096], path[48] = "/proc/";
  long n = slurp("/proc/self/status", status, sizeof status);
  const char *line = NULL;
  for (long i = 0; n > 0 && i < n; i++)
    if (status[i] == '\n' && same(status + i + 1, "TracerPid:", 10)) { line = status + i + 11; break; }
  if (!line) return 0;
  while (*line == ' ' || *line == '\t') line++;
  size_t d = 0;
  while (line[d] >= '0' && line[d] <= '9' && d < 16) d++;
  if (d == 0 || (d == 1 && line[0] == '0')) return 0;
  copy(path + 6, line, d);
  copy(path + 6 + d, "/environ", 9);
  long have = slurp(path, tracer_env, sizeof tracer_env);
  if (have <= 0 || var(tracer_env, have, "PROOT_NO_SECCOMP")) return 0;
  const char *theirs = var(tracer_env, have, "PROOT_FASTPATH");
  return theirs && len_of(theirs) == len_of(key) && same(theirs, key, len_of(key));
}

static void setup(void) {
  long p = raw(SYS_mmap, FP_STUB_ADDR, FP_STUB_SIZE, PROT_READ | PROT_WRITE,
               MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE);
  /* Loaded before anything else maps, so whatever is already there is not the trampoline. */
  if (p != (long)FP_STUB_ADDR) {
    if (p > 0) raw(SYS_munmap, p, FP_STUB_SIZE, 0, 0);
    return;
  }
  static const uint32_t code[] = {
      0xAA0003E8, 0xAA0103E0, 0xAA0203E1, 0xAA0303E2, 0xAA0403E3, 0xAA0503E4, 0xAA0603E5,
      0xD4000001, 0xD65F03C0, /* mov x8,x0; mov x0..x5,x1..x6; svc #0; ret */
  };
  copy((char *)p, (const char *)code, sizeof code);
  sync_code((char *)p, (char *)p + sizeof code);
  if (raw(SYS_mprotect, FP_STUB_ADDR, FP_STUB_SIZE, PROT_READ | PROT_EXEC, 0) != 0) {
    raw(SYS_munmap, FP_STUB_ADDR, FP_STUB_SIZE, 0, 0);
    return;
  }
  sys = (stub_fn)FP_STUB_ADDR;
  long n = slurp("/proc/self/environ", env, sizeof env);
  if (n <= 0 || (size_t)n >= sizeof env - 1) return;
  env_len = n;
  const char *key = var(env, env_len, "PROOT_FP_KEY"), *b = var(env, env_len, "PROOT_FP_BINDS");
  root = var(env, env_len, "PROOT_FP_ROOT");
  if (!key || !*key || !root || !*root || var(env, env_len, "PROOT_FP_OFF") || !traced_by_match(key)) return;
  if (!canonical(root, root_canon)) return;
  root = root_canon;
  rootlen = len_of(root);
  if (rootlen <= 1) return;
  /* "host[:guest]|..." taken apart in place; a list this cannot hold leaves everything to ld.so. */
  for (char *s = (char *)b; s && *s;) {
    if (nbinds == MAX_BINDS) return;
    char *end = s;
    while (*end && *end != '|') end++;
    char *colon = s;
    while (colon < end && *colon != ':') colon++;
    struct bind *bd = &binds[nbinds];
    bd->host = s;
    bd->hlen = colon - s;
    bd->guest = colon < end ? colon + 1 : s;
    bd->glen = colon < end ? (size_t)(end - colon - 1) : bd->hlen;
    if (bd->guest[0] == '/' && bd->glen > 1 && bd->hlen > 0) nbinds++;
    int last = *end == 0;
    *end = 0;
    if (colon < end) *colon = 0;
    if (last) break;
    s = end + 1;
  }
  ready = 1;
}

/* ---------------------------------------------------------------- lookups */

/* The host path proot binds there, as it canonicalised it: NULL for one it dropped (or /proc/self). */
static const char *bind_host(struct bind *bd) {
  if (!bd->state) {
    bd->state = under(bd->host, "/proc/self", 10) || !canonical(bd->host, bd->canon) ? 2 : 1;
  }
  return bd->state == 1 ? bd->canon : NULL;
}

static long long now(void) {
  struct timespec ts;
  if (sc(SYS_clock_gettime, CLOCK_MONOTONIC_COARSE, (long)&ts, 0, 0) != 0) return 0;
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* The host path proot gives a canonical guest path: under the longest binding over it (the last of
 * two for the same path), else the rootfs. 0 for a binding proot dropped, or no room. */
static int to_host(const char *g, char *host, int *bound) {
  size_t n = len_of(g);
  struct bind *bd = NULL;
  for (int i = 0; i < nbinds; i++)
    if (under(g, binds[i].guest, binds[i].glen) && (!bd || binds[i].glen >= bd->glen)) bd = &binds[i];
  const char *base = root, *rest = g;
  if (bd) {
    base = bind_host(bd);
    if (!base) return 0;
    rest = g + bd->glen;
  } else if (n == 1) {
    rest = "";
  }
  size_t blen = len_of(base), rlen = len_of(rest);
  if (blen + rlen >= PATH) return 0;
  copy(host, base, blen);
  copy(host + blen, rest, rlen + 1);
  *bound = bd != NULL;
  return 1;
}

/* Under a directory proot or the kernel answers for in its own way. */
static int special(const char *g) {
  return under(g, "/proc", 5) || under(g, "/dev", 4) || under(g, "/sys", 4);
}

/* A guest path proot only shows as glue, or one with a binding below it. */
static int above_binding(const char *g) {
  size_t n = len_of(g);
  for (int i = 0; i < nbinds; i++)
    if (binds[i].glen > n && under(binds[i].guest, g, n)) return 1;
  return 0;
}

/*
 * The canonical guest path of the directory `g`, walked as canonicalize() walks it: a symlink in the
 * rootfs is followed in guest terms, its text losing the rootfs prefix as detranslate_path() has it.
 * -1 when a component is missing, so proot fails the path with ENOENT. Anything this cannot follow
 * exactly (a link inside a binding, glue, a component not a directory) gives 0.
 */
static int canonical_dir(const char *g, char *out) {
  char path[PATH], next[PATH], host[PATH], text[PATH];
  char st_buf[256] __attribute__((aligned(16)));
  struct stat *st = (struct stat *)st_buf;
  size_t glen = len_of(g), olen = 0;
  int links = 0, bound;
  if (glen >= PATH) return 0;
  copy(path, g, glen + 1);
  out[0] = '/';
  out[1] = 0;
  for (char *c = path;;) {
    while (*c == '/') c++;
    if (!*c) break;
    char *e = c;
    while (*e && *e != '/') e++;
    size_t n = e - c;
    if (n == 1 && c[0] == '.') { c = e; continue; }
    if (n == 2 && c[0] == '.' && c[1] == '.') {
      while (olen > 0 && out[olen] != '/') olen--;
      out[olen ? olen : 1] = 0;
      c = e;
      continue;
    }
    if (olen + n + 2 >= PATH) return 0;
    out[olen] = '/';
    copy(out + olen + 1, c, n);
    out[olen + 1 + n] = 0;
    if (!to_host(out, host, &bound)) return 0;
    long r = sc(SYS_newfstatat, AT_FDCWD, (long)host, (long)st, AT_SYMLINK_NOFOLLOW);
    if (r == -ENOENT && !special(out) && !above_binding(out)) return -1;
    if (r != 0) return 0;
    if (S_ISDIR(st->st_mode)) {
      olen += 1 + n;
      c = e;
      continue;
    }
    if (!S_ISLNK(st->st_mode) || bound || ++links > 16) return 0;
    long t = sc(SYS_readlinkat, AT_FDCWD, (long)host, (long)text, PATH - 1);
    if (t <= 0 || t >= PATH - 1) return 0;
    text[t] = 0;
    const char *guest_text = text;
    if (under(text, root, rootlen)) guest_text = text[rootlen] ? text + rootlen : "/";
    size_t tl = len_of(guest_text), rest = len_of(e);
    if (tl + rest + 1 >= PATH) return 0;
    copy(next, guest_text, tl);
    copy(next + tl, e, rest + 1);
    copy(path, next, tl + rest + 1);
    c = path;
    if (guest_text[0] == '/') olen = 0;
    out[olen ? olen : 1] = 0;
  }
  if (olen == 0) out[1] = 0;
  return 1;
}

static struct { char dir[PATH]; char canon[PATH]; long long at; } known[VERIFIED];
static int next_slot;

/* canonical_dir(), remembered for a while: ld.so looks in the same few directories for every
 * library. */
static int known_dir(const char *g, char *out) {
  size_t n = len_of(g);
  long long t = now();
  for (int i = 0; i < VERIFIED; i++)
    if (known[i].at && t - known[i].at < TTL_NS && same(known[i].dir, g, n + 1)) {
      copy(out, known[i].canon, len_of(known[i].canon) + 1);
      return 1;
    }
  int r = canonical_dir(g, out);
  if (r != 1) return r;
  copy(known[next_slot].dir, g, n + 1);
  copy(known[next_slot].canon, out, len_of(out) + 1);
  known[next_slot].at = t;
  next_slot = (next_slot + 1) % VERIFIED;
  return 1;
}

/* The guest path certainly names nothing: its directory is one proot reaches the same way, and the
 * name is missing there. */
static int missing(const char *g) {
  char dir[PATH], cand[PATH], host[PATH];
  size_t n = len_of(g);
  if (g[0] != '/' || n < 2 || n >= PATH - 1) return 0;
  size_t slash = n;
  while (slash > 0 && g[slash - 1] != '/') slash--;
  const char *name = g + slash;
  size_t nl = n - slash;
  if (nl == 0 || (nl == 1 && name[0] == '.') || (nl == 2 && name[0] == '.' && name[1] == '.')) return 0;
  copy(dir, g, slash);
  dir[slash > 1 ? slash - 1 : 1] = 0;
  int known_as = known_dir(dir, cand);
  if (known_as != 1) return known_as < 0;
  size_t cl = len_of(cand);
  if (cl + nl + 2 >= PATH) return 0;
  if (cl > 1) cand[cl++] = '/';
  copy(cand + cl, name, nl + 1);
  int bound;
  if (special(cand) || above_binding(cand) || !to_host(cand, host, &bound))
    return 0;
  char st[256] __attribute__((aligned(16)));
  return sc(SYS_newfstatat, AT_FDCWD, (long)host, (long)st, AT_SYMLINK_NOFOLLOW) == -ENOENT;
}

unsigned int la_version(unsigned int version) {
  (void)version;
  setup();
  return LAV_CURRENT;
}

/*
 * A candidate skipped here is one whose open "failed" with ld.so's errno as it stands, and ld.so
 * gives up on the whole search path unless that is ENOENT or EACCES. So each lookup's first
 * candidate is always opened for real: once that has failed, errno says so, and nothing ld.so
 * does between candidates changes it unless the search ends anyway. The default directories are
 * left alone too: what ld.so meets there is what it reports when a library is nowhere.
 */
static int tried;

char *la_objsearch(const char *name, uintptr_t *cookie, unsigned int flag) {
  (void)cookie;
  if (flag == LA_SER_ORIG) {
    tried = 0;
    return (char *)name;
  }
  if (!ready || !tried || flag == LA_SER_DEFAULT || !missing(name)) {
    tried = 1;
    return (char *)name;
  }
  return NULL;
}
