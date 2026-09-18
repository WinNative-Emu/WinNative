/*
 * Loopback socket ownership for Steam's IPC peer check.
 *
 * Steam validates a websocket peer by running lsof to find which process owns a 127.0.0.1 port.
 * The Android sandbox denies both /proc/net/tcp and sock_diag, so no tool can answer. Every Steam
 * process is preloaded with this library, so each records the ports it binds and connects, and the
 * lsof Steam spawns is answered from that shared registry instead of being run.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <spawn.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>
#include <unistd.h>

static const char *net_dir(void) {
  static char dir[256];
  if (!dir[0]) {
    const char *base = getenv("WN_SYSV_DIR");
    snprintf(dir, sizeof(dir), "%s/net", base && *base ? base : "/dev/shm/wnsysv");
    char parent[256];
    snprintf(parent, sizeof(parent), "%s", dir);
    char *slash = strrchr(parent, '/');
    if (slash) {
      *slash = '\0';
      mkdir(parent, 0777);
    }
    mkdir(dir, 0777);
  }
  return dir;
}

/* Record that this process owns a loopback socket on its local port; peer is the far end's port,
 * 0 for a listening socket. */
static void record_port(int fd, unsigned peer) {
  struct sockaddr_storage ss;
  socklen_t len = sizeof(ss);
  if (getsockname(fd, (struct sockaddr *)&ss, &len) != 0) {
    return;
  }
  unsigned port;
  if (ss.ss_family == AF_INET) {
    struct sockaddr_in *in = (struct sockaddr_in *)&ss;
    if (in->sin_addr.s_addr != htonl(INADDR_LOOPBACK)) {
      return;
    }
    port = ntohs(in->sin_port);
  } else if (ss.ss_family == AF_INET6) {
    port = ntohs(((struct sockaddr_in6 *)&ss)->sin6_port);
  } else {
    return;
  }
  if (port == 0) {
    return;
  }
  char path[300];
  snprintf(path, sizeof(path), "%s/p%u", net_dir(), port);
  char line[64];
  int n = snprintf(line, sizeof(line), "%d %d %d %u\n", (int)getpid(), (int)getppid(), (int)getuid(), peer);
  int out = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
  if (out >= 0) {
    if (write(out, line, n) != n) { /* best effort */ }
    close(out);
  }
}

static unsigned addr_port(const struct sockaddr *addr, socklen_t len) {
  if (addr && addr->sa_family == AF_INET && len >= sizeof(struct sockaddr_in)) {
    return ntohs(((const struct sockaddr_in *)addr)->sin_port);
  }
  if (addr && addr->sa_family == AF_INET6 && len >= sizeof(struct sockaddr_in6)) {
    return ntohs(((const struct sockaddr_in6 *)addr)->sin6_port);
  }
  return 0;
}

static unsigned peer_port(int fd) {
  struct sockaddr_storage ps;
  socklen_t plen = sizeof(ps);
  if (getpeername(fd, (struct sockaddr *)&ps, &plen) != 0) {
    return 0;
  }
  return addr_port((struct sockaddr *)&ps, plen);
}

typedef int (*bind_fn)(int, const struct sockaddr *, socklen_t);
typedef int (*listen_fn)(int, int);
typedef int (*connect_fn)(int, const struct sockaddr *, socklen_t);
typedef int (*accept4_fn)(int, struct sockaddr *, socklen_t *, int);

/* udevmon.c: a stand-in for the netlink socket the sandbox refuses, bound here without the kernel. */
__attribute__((visibility("hidden"))) int wn_udevmon_stand_in(int fd);

int bind(int fd, const struct sockaddr *addr, socklen_t len) {
  static bind_fn real;
  if (!real) real = (bind_fn)dlsym(RTLD_NEXT, "bind");
  if (wn_udevmon_stand_in(fd)) {
    if (addr == NULL || len < sizeof(struct sockaddr_nl) || addr->sa_family != AF_NETLINK) {
      errno = EINVAL;
      return -1;
    }
    return 0;
  }
  int ret = real(fd, addr, len);
  if (ret == 0) record_port(fd, 0);
  return ret;
}

int listen(int fd, int backlog) {
  static listen_fn real;
  if (!real) real = (listen_fn)dlsym(RTLD_NEXT, "listen");
  int ret = real(fd, backlog);
  if (ret == 0) record_port(fd, 0);
  return ret;
}

int connect(int fd, const struct sockaddr *addr, socklen_t len) {
  static connect_fn real;
  if (!real) real = (connect_fn)dlsym(RTLD_NEXT, "connect");
  int ret = real(fd, addr, len);
  if (ret == 0 || errno == EINPROGRESS) {
    /* The caller goes on to test for EINPROGRESS, which the bookkeeping must not disturb. */
    int saved = errno;
    record_port(fd, addr_port(addr, len));
    errno = saved;
  }
  return ret;
}

int accept4(int fd, struct sockaddr *addr, socklen_t *len, int flags) {
  static accept4_fn real;
  if (!real) real = (accept4_fn)dlsym(RTLD_NEXT, "accept4");
  int ret = real(fd, addr, len, flags);
  if (ret >= 0) record_port(ret, peer_port(ret));
  return ret;
}

int accept(int fd, struct sockaddr *addr, socklen_t *len) {
  return accept4(fd, addr, len, 0);
}

/* lsof would read the denied /proc/net/tcp; answer its -i TCP@host:port query from the registry
 * instead of executing it. Recognised only when invoked as lsof with that query. */
static int maybe_answer_lsof(const char *path, char *const argv[]) {
  const char *base = strrchr(path, '/');
  base = base ? base + 1 : path;
  if (strcmp(base, "lsof") != 0) {
    return 0;
  }
  const char *port_str = NULL;
  for (int i = 0; argv[i]; i++) {
    const char *at = strstr(argv[i], "TCP@");
    if (at) {
      const char *colon = strrchr(at, ':');
      if (colon) port_str = colon + 1;
    }
  }
  if (!port_str) {
    return 0;
  }
  char path_buf[300];
  snprintf(path_buf, sizeof(path_buf), "%s/p%u", net_dir(), (unsigned)atoi(port_str));
  int in = open(path_buf, O_RDONLY);
  if (in < 0) {
    _exit(1); /* lsof exit code for "nothing found" */
  }
  char buf[64];
  int n = read(in, buf, sizeof(buf) - 1);
  close(in);
  if (n <= 0) {
    _exit(1);
  }
  buf[n] = '\0';
  int pid = 0, ppid = 0, uid = 0;
  unsigned peer = 0;
  sscanf(buf, "%d %d %d %u", &pid, &ppid, &uid, &peer);
  /* Steam splits the address on "->" and wants exactly two halves, taking the port it asked
   * about from the left one; only a listening socket has no far end. */
  char out[160];
  int m = peer ? snprintf(out, sizeof(out), "p%d\nR%d\nu%d\nn127.0.0.1:%u->127.0.0.1:%u\n",
                          pid, ppid, uid, (unsigned)atoi(port_str), peer)
                : snprintf(out, sizeof(out), "p%d\nR%d\nu%d\nn127.0.0.1:%u\n",
                          pid, ppid, uid, (unsigned)atoi(port_str));
  if (write(1, out, m) != m) { /* best effort */ }
  _exit(0);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
  static int (*real)(const char *, char *const[], char *const[]);
  if (!real) real = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
  maybe_answer_lsof(path, argv);
  return real(path, argv, envp);
}

/* popen(3) and posix_spawn(3) reach lsof without going through the execve symbol above. The
 * check is cheap, so a spawned lsof is answered here too by handling it in a short-lived child. */
static int answer_lsof_via_spawn(const char *path, char *const argv[], pid_t *pid) {
  pid_t child = fork();
  if (child < 0) {
    return errno;
  }
  if (child == 0) {
    maybe_answer_lsof(path, argv);
    _exit(127);
  }
  if (pid) {
    *pid = child;
  }
  return 0;
}

int execvp(const char *file, char *const argv[]) {
  static int (*real)(const char *, char *const[]);
  if (!real) real = (int (*)(const char *, char *const[]))dlsym(RTLD_NEXT, "execvp");
  maybe_answer_lsof(file, argv);
  return real(file, argv);
}

int execv(const char *path, char *const argv[]) {
  static int (*real)(const char *, char *const[]);
  if (!real) real = (int (*)(const char *, char *const[]))dlsym(RTLD_NEXT, "execv");
  maybe_answer_lsof(path, argv);
  return real(path, argv);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
  const char *base = strrchr(path, '/');
  base = base ? base + 1 : path;
  if (strcmp(base, "lsof") == 0 && !fa) {
    return answer_lsof_via_spawn(path, argv, pid);
  }
  static int (*real)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                     const posix_spawnattr_t *, char *const[], char *const[]);
  if (!real) real = (int (*)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                             const posix_spawnattr_t *, char *const[], char *const[]))dlsym(RTLD_NEXT, "posix_spawn");
  return real(pid, path, fa, attr, argv, envp);
}
