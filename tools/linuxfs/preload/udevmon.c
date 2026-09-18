/*
 * udev's netlink monitor, refused before libudev can trip over refusing it itself.
 *
 * The app sandbox denies a NETLINK_KOBJECT_UEVENT socket outright, so a monitor can never be
 * created here. Asked for one anyway, libudev unwinds through a path that closes a descriptor a
 * stream still owns; systemd's safe_fclose() then asserts on EBADF and aborts the process. Steam
 * asks on a worker thread, by way of SDL and libusb, often enough that the client dies a minute
 * or two into every session and takes the running game down with it.
 *
 * Answering with the NULL that libudev would have returned keeps the caller on the failure path
 * it already handles. The socket is tried first so this stays out of the way wherever the kernel
 * would genuinely allow a monitor.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <linux/netlink.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

/* True while the sandbox refuses the socket every monitor is built on. */
static int netlink_denied(void) {
  int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_KOBJECT_UEVENT);
  if (fd < 0)
    return 1;
  close(fd);
  return 0;
}

/*
 * SDL reaches udev through dlopen() and dlsym() rather than the linker, so interposing the
 * monitor call alone leaves its copy pointed straight at libudev. Refusing the library is the
 * same answer a system without udev gives, and SDL already falls back to reading /dev/input
 * directly there, which is where the session's controllers appear anyway.
 *
 * Only SDL is answered this way. The Steam client's web helper loads udev for its own device
 * enumeration and does not survive being told it is missing, so every other caller is passed
 * straight through.
 */
static int caller_is_sdl(void *caller) {
  Dl_info info;
  if (dladdr(caller, &info) == 0 || info.dli_fname == NULL)
    return 0;
  /* The client carries a second copy of SDL inside steamclient.so for controller discovery,
   * which asks for udev on its own account. */
  return strstr(info.dli_fname, "libSDL") != NULL
      || strstr(info.dli_fname, "steamclient.so") != NULL;
}

void *dlopen(const char *file, int mode) {
  static void *(*real_dlopen)(const char *, int);
  void *caller = __builtin_return_address(0);
  const char *base;

  if (file != NULL) {
    base = strrchr(file, '/');
    base = base != NULL ? base + 1 : file;
    if (strncmp(base, "libudev.so", 10) == 0 && caller_is_sdl(caller) && netlink_denied())
      return NULL;
  }
  if (real_dlopen == NULL) real_dlopen = dlsym(RTLD_NEXT, "dlopen");
  return real_dlopen(file, mode);
}

void *udev_monitor_new_from_netlink(void *udev, const char *name) {
  static void *(*real_monitor_new)(void *, const char *);
  int denied;

  denied = netlink_denied();
  if (denied) {
    errno = EACCES;
    return NULL;
  }
  if (real_monitor_new == NULL)
    real_monitor_new = dlsym(RTLD_NEXT, "udev_monitor_new_from_netlink");
  if (real_monitor_new == NULL) {
    errno = ENOSYS;
    return NULL;
  }
  return real_monitor_new(udev, name);
}
