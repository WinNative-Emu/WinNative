/*
 * A process status without the container's tracer in it.
 *
 * proot runs the session under ptrace, so every process here reads a TracerPid in its
 * /proc/<pid>/status. The Steam client takes that to mean a debugger is attached, and its arm64
 * build breaks into the supposed debugger with a bare "svc #0" after every failed assertion. That
 * is no breakpoint: the registers still hold the call Plat_IsInDebugSession() just made, close(),
 * with its own result, 1, as the argument - so each assertion closes descriptor 1. The number then
 * goes to whatever the client opens next, and its standard output is written into that: app
 * manifests and content chunks, its own connections, and the socket a game's Steam API talks
 * over, which is how a game that stayed silent while loading found its pipe dead.
 *
 * The tracer is part of the container, not something a program should react to, so the status
 * files are served with TracerPid 0.
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define STATUS_MAX 16384
#define TRACER_FIELD "\nTracerPid:\t"

static const char *skip_digits(const char *s) {
  const char *end = s;
  while (isdigit((unsigned char)*end)) end++;
  return end == s ? NULL : end;
}

/* /proc/{self,thread-self,<pid>}[/task/<tid>]/status */
static int is_status_path(const char *path) {
  const char *rest;
  if (path == NULL || strncmp(path, "/proc/", 6) != 0) return 0;
  path += 6;
  if (strncmp(path, "self/", 5) == 0) rest = path + 4;
  else if (strncmp(path, "thread-self/", 12) == 0) rest = path + 11;
  else if ((rest = skip_digits(path)) == NULL) return 0;
  if (strncmp(rest, "/task/", 6) == 0 && (rest = skip_digits(rest + 6)) == NULL) return 0;
  return strcmp(rest, "/status") == 0;
}

/* Chromium stands its hang watchdogs down when it sees a tracer, and the client's web helper does
 * not come up within their patience here: it is left reading the truth. */
static int is_web_helper(void) {
  return strcmp(program_invocation_short_name, "steamwebhelper") == 0;
}

static int write_all(int fd, const char *data, size_t len) {
  while (len > 0) {
    ssize_t n = write(fd, data, len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    data += n;
    len -= (size_t)n;
  }
  return 0;
}

/* The descriptor to hand back for a read-only open of a status file, or -1 with errno untouched
 * when the path is something else or the copy cannot be made, and the real open should run. */
__attribute__((visibility("hidden"))) int wn_status_without_tracer(const char *path, int flags) {
  char text[STATUS_MAX];
  size_t len = 0;
  int saved = errno;
  int out = -1;

  if ((flags & O_ACCMODE) != O_RDONLY || !is_status_path(path) || is_web_helper()) return -1;
  int in = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
  if (in < 0) goto done;
  for (;;) {
    ssize_t n = read(in, text + len, sizeof(text) - 1 - len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    len += (size_t)n;
  }
  close(in);
  text[len] = '\0';

  char *field = strstr(text, TRACER_FIELD);
  if (field == NULL) goto done;
  char *value = field + strlen(TRACER_FIELD);
  char *line_end = strchr(value, '\n');
  if (line_end == NULL) goto done;

  out = memfd_create("status", (flags & O_CLOEXEC) ? MFD_CLOEXEC : 0);
  if (out < 0) goto done;
  if (write_all(out, text, (size_t)(value - text)) != 0 || write_all(out, "0", 1) != 0
      || write_all(out, line_end, len - (size_t)(line_end - text)) != 0
      || lseek(out, 0, SEEK_SET) != 0) {
    close(out);
    out = -1;
  }
done:
  errno = saved;
  return out;
}
