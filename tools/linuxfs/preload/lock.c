/*
 * File locking for the game library on external storage.
 *
 * The user's games are bound in from /storage/emulated, which Android serves over FUSE, and that
 * filesystem implements no locking at all: flock() and fcntl()'s lock commands answer ENOSYS for
 * every file under it. The Steam client takes a shared lock on each content file as it validates
 * or updates a title and treats the refusal as a failure, so a download walks its progress back
 * and forth instead of finishing; a game reading its own data hits the same wall.
 *
 * Where the kernel can lock, it does: the real call runs first and its answer stands, so files on
 * the app's own f2fs storage keep genuine cross-process exclusion. Only the filesystem that cannot
 * lock at all is answered here, and granting is the honest reading of it - a filesystem with no
 * lock state has nothing to contend over, and the one other party that could contend is inside
 * this same session. Reporting failure instead buys no safety and costs the download.
 *
 * F_GETLK is the exception: it asks who holds a lock, and on a filesystem holding none the true
 * answer is "nobody", so it is reported unlocked rather than granted.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <sys/file.h>
#include <unistd.h>

/* The filesystem refuses the whole operation, rather than declining this particular lock. */
static int unsupported(int error) {
  return error == ENOSYS || error == ENOTSUP || error == EOPNOTSUPP;
}

int flock(int fd, int operation) {
  static int (*real_flock)(int, int);
  int result;

  if (real_flock == NULL) real_flock = dlsym(RTLD_NEXT, "flock");
  if (real_flock == NULL) return 0;
  result = real_flock(fd, operation);
  if (result < 0 && unsupported(errno)) return 0;
  return result;
}

/*
 * fcntl()'s third argument is an int, a pointer, or absent depending on the command; only the
 * lock commands are of interest here and all of those take a struct flock *. Anything else is
 * passed straight through as a void * - wide enough for both an int and a pointer on every ABI
 * the runtime is built for.
 */
static int is_lock_command(int cmd) {
  switch (cmd) {
    case F_SETLK:
    case F_SETLKW:
#ifdef F_OFD_SETLK
    case F_OFD_SETLK:
#endif
#ifdef F_OFD_SETLKW
    case F_OFD_SETLKW:
#endif
      return 1;
    default:
      return 0;
  }
}

static int is_lock_query(int cmd) {
  switch (cmd) {
    case F_GETLK:
#ifdef F_OFD_GETLK
    case F_OFD_GETLK:
#endif
      return 1;
    default:
      return 0;
  }
}

int fcntl(int fd, int cmd, ...) {
  static int (*real_fcntl)(int, int, ...);
  va_list ap;
  void *arg;
  int result;

  va_start(ap, cmd);
  arg = va_arg(ap, void *);
  va_end(ap);

  if (real_fcntl == NULL) real_fcntl = dlsym(RTLD_NEXT, "fcntl");
  if (real_fcntl == NULL) {
    errno = ENOSYS;
    return -1;
  }
  result = real_fcntl(fd, cmd, arg);
  if (result >= 0 || !unsupported(errno)) return result;
  if (is_lock_command(cmd)) return 0;
  if (is_lock_query(cmd) && arg != NULL) {
    ((struct flock *)arg)->l_type = F_UNLCK;
    return 0;
  }
  return result;
}

#if defined(__GLIBC__) && defined(F_SETLK64)
/* glibc's large-file build redirects both names; the runtime is 64-bit, so they are the same call. */
int fcntl64(int fd, int cmd, ...) __attribute__((alias("fcntl")));
#endif
