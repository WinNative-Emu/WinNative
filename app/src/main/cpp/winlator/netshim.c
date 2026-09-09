#define _GNU_SOURCE 1
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <errno.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <linux/if_packet.h>
#include <linux/if_arp.h>
#include <linux/sockios.h>
#include <netinet/tcp.h>
#include <netdb.h>
#include <time.h>

#define WN_IF_NAME  "eth0"
#define WN_IF_INDEX 2
#define WN_IF_MTU   1500
#define WN_IF_ADDR  0x0a000002u
#define WN_IF_MASK  0xffffff00u
#define WN_IF_BCAST 0x0a0000ffu

static int wn_enabled = 1;
static int wn_tap = 0;
static int wn_tap_events = 0;
static int wn_logfd = -2;
static int wn_mac_fixed = 0;
static unsigned char wn_mac_value[6];
static struct timespec wn_t0;

static long wn_elapsed_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec - wn_t0.tv_sec) * 1000L + (now.tv_nsec - wn_t0.tv_nsec) / 1000000L;
}

static void wn_log(const char *fmt, ...) {
    if (wn_logfd == -2) {
        const char *p = getenv("WN_NET_LOG");
        wn_logfd = p && *p ? open(p, O_WRONLY | O_CREAT | O_APPEND, 0644) : -1;
    }
    if (wn_logfd < 0) return;
    char buf[512];
    int n = snprintf(buf, sizeof(buf), "[%6ld ms netshim %d] ", wn_elapsed_ms(), (int) getpid());
    va_list ap;
    va_start(ap, fmt);
    n += vsnprintf(buf + n, sizeof(buf) - n - 2, fmt, ap);
    va_end(ap);
    if (n > (int) sizeof(buf) - 2) n = sizeof(buf) - 2;
    buf[n++] = '\n';
    ssize_t ignored = write(wn_logfd, buf, n);
    (void) ignored;
}

static int wn_parse_mac(const char *text, unsigned char *out) {
    int nibbles = 0;
    unsigned char acc[6] = {0};
    for (const char *p = text; *p; ++p) {
        int v;
        if (*p >= '0' && *p <= '9') v = *p - '0';
        else if (*p >= 'a' && *p <= 'f') v = *p - 'a' + 10;
        else if (*p >= 'A' && *p <= 'F') v = *p - 'A' + 10;
        else if (*p == ':' || *p == '-' || *p == '.' || *p == ' ') continue;
        else return 0;
        if (nibbles >= 12) return 0;
        acc[nibbles / 2] = (unsigned char) ((acc[nibbles / 2] << 4) | v);
        nibbles++;
    }
    if (nibbles != 12) return 0;
    if (acc[0] & 0x03) return 0;
    memcpy(out, acc, 6);
    return 1;
}

static const unsigned char wn_vendor_ouis[][3] = {
    {0x3c, 0x5a, 0xb4}, {0x00, 0x1a, 0x11}, {0xac, 0x5f, 0x3e}, {0x8c, 0xf5, 0xa3},
    {0x94, 0x65, 0x2d}, {0xc0, 0xee, 0xfb}, {0x64, 0xa2, 0xf9}, {0x00, 0xa0, 0xc6},
};

static int wn_iface_is_uplink(const char *iface) {
    if (!iface) return 1;
    return strncmp(iface, "wlan", 4) == 0 || strncmp(iface, "eth", 3) == 0
        || strncmp(iface, "rmnet", 5) == 0 || strncmp(iface, "ccmni", 5) == 0
        || strncmp(iface, "usb", 3) == 0 || strncmp(iface, "swlan", 5) == 0;
}

static void wn_mac_for(const char *iface, unsigned char *out) {
    if (wn_mac_fixed && wn_iface_is_uplink(iface)) {
        memcpy(out, wn_mac_value, 6);
        return;
    }
    const char *seed = getenv("WN_NET_MAC_SEED");
    unsigned long h = 0x811c9dc5UL;
    if (seed && *seed) {
        for (const char *p = seed; *p; ++p) {
            h ^= (unsigned char) *p;
            h *= 16777619UL;
        }
    } else {
        h = 0x5741524eUL;
    }
    if (iface) {
        for (const char *p = iface; *p; ++p) {
            h ^= (unsigned char) *p;
            h *= 16777619UL;
        }
    }
    const unsigned char *oui = wn_vendor_ouis[(h >> 40) % (sizeof(wn_vendor_ouis) / sizeof(wn_vendor_ouis[0]))];
    out[0] = oui[0];
    out[1] = oui[1];
    out[2] = oui[2];
    out[3] = (unsigned char) (h >> 16);
    out[4] = (unsigned char) (h >> 8);
    out[5] = (unsigned char) h;
}

static void wn_mac(unsigned char *out) {
    wn_mac_for(NULL, out);
}

__attribute__((constructor)) static void wn_init(void) {
    clock_gettime(CLOCK_MONOTONIC, &wn_t0);
    const char *off = getenv("WN_NET_SHIM");
    if (off && off[0] == '0') wn_enabled = 0;
    const char *tap = getenv("WN_NET_TAP");
    wn_tap = tap && tap[0] == '1';
    const char *fixed = getenv("WN_NET_MAC");
    wn_mac_fixed = fixed && *fixed && wn_parse_mac(fixed, wn_mac_value);
    unsigned char m[6];
    wn_mac(m);
    wn_log("loaded enabled=%d fixed=%d mac=%02x:%02x:%02x:%02x:%02x:%02x",
           wn_enabled, wn_mac_fixed, m[0], m[1], m[2], m[3], m[4], m[5]);
}

struct wn_block {
    struct ifaddrs inet;
    struct ifaddrs pkt;
    struct sockaddr_in addr;
    struct sockaddr_in mask;
    struct sockaddr_in bcast;
    struct sockaddr_ll ll;
    char name_inet[IFNAMSIZ];
    char name_pkt[IFNAMSIZ];
};

static int wn_real_ifaddrs_usable(struct ifaddrs *list) {
    int usable = 0;
    for (struct ifaddrs *e = list; e; e = e->ifa_next) {
        if (!e->ifa_addr) continue;
        if (e->ifa_addr->sa_family != AF_INET) continue;
        if (e->ifa_flags & IFF_LOOPBACK) continue;
        usable++;
    }
    return usable;
}

int getifaddrs(struct ifaddrs **ifap) {
    static int (*real)(struct ifaddrs **);
    if (!real) real = (int (*)(struct ifaddrs **)) dlsym(RTLD_NEXT, "getifaddrs");
    if (!ifap) return -1;

    if (wn_enabled && real) {
        struct ifaddrs *probe = NULL;
        int rc = real(&probe);
        int usable = rc == 0 ? wn_real_ifaddrs_usable(probe) : -1;
        wn_log("getifaddrs real rc=%d usable_inet=%d", rc, usable);
        if (rc == 0 && usable > 0) {
            *ifap = probe;
            return 0;
        }
        if (rc == 0 && probe) {
            static void (*realfree)(struct ifaddrs *);
            if (!realfree) realfree = (void (*)(struct ifaddrs *)) dlsym(RTLD_NEXT, "freeifaddrs");
            if (realfree) realfree(probe);
        }
    } else if (!wn_enabled && real) {
        return real(ifap);
    }

    struct wn_block *b = (struct wn_block *) calloc(1, sizeof(*b));
    if (!b) return -1;

    snprintf(b->name_inet, sizeof(b->name_inet), "%s", WN_IF_NAME);
    snprintf(b->name_pkt, sizeof(b->name_pkt), "%s", WN_IF_NAME);

    b->addr.sin_family = AF_INET;
    b->addr.sin_addr.s_addr = htonl(WN_IF_ADDR);
    b->mask.sin_family = AF_INET;
    b->mask.sin_addr.s_addr = htonl(WN_IF_MASK);
    b->bcast.sin_family = AF_INET;
    b->bcast.sin_addr.s_addr = htonl(WN_IF_BCAST);

    b->ll.sll_family = AF_PACKET;
    b->ll.sll_ifindex = WN_IF_INDEX;
    b->ll.sll_hatype = ARPHRD_ETHER;
    b->ll.sll_halen = 6;
    wn_mac(b->ll.sll_addr);

    b->inet.ifa_next = &b->pkt;
    b->inet.ifa_name = b->name_inet;
    b->inet.ifa_flags = IFF_UP | IFF_RUNNING | IFF_BROADCAST | IFF_MULTICAST;
    b->inet.ifa_addr = (struct sockaddr *) &b->addr;
    b->inet.ifa_netmask = (struct sockaddr *) &b->mask;
    b->inet.ifa_broadaddr = (struct sockaddr *) &b->bcast;

    b->pkt.ifa_next = NULL;
    b->pkt.ifa_name = b->name_pkt;
    b->pkt.ifa_flags = b->inet.ifa_flags;
    b->pkt.ifa_addr = (struct sockaddr *) &b->ll;

    *ifap = &b->inet;
    wn_log("getifaddrs -> SYNTHETIC %s", WN_IF_NAME);
    return 0;
}

void freeifaddrs(struct ifaddrs *ifa) {
    static void (*real)(struct ifaddrs *);
    if (!real) real = (void (*)(struct ifaddrs *)) dlsym(RTLD_NEXT, "freeifaddrs");
    if (!ifa) return;
    if (ifa->ifa_name && strcmp(ifa->ifa_name, WN_IF_NAME) == 0 && ifa->ifa_next
        && ifa->ifa_next->ifa_addr && ifa->ifa_next->ifa_addr->sa_family == AF_PACKET) {
        free(ifa);
        return;
    }
    if (real) real(ifa);
}

struct if_nameindex *if_nameindex(void) {
    static struct if_nameindex *(*real)(void);
    if (!real) real = (struct if_nameindex *(*)(void)) dlsym(RTLD_NEXT, "if_nameindex");

    if (wn_enabled && real) {
        struct if_nameindex *probe = real();
        int n = 0;
        if (probe) for (struct if_nameindex *e = probe; e->if_index; e++) n++;
        wn_log("if_nameindex real -> %d entries", n);
        if (n > 0) return probe;
        if (probe) {
            static void (*realfree)(struct if_nameindex *);
            if (!realfree) realfree = (void (*)(struct if_nameindex *)) dlsym(RTLD_NEXT, "if_freenameindex");
            if (realfree) realfree(probe);
        }
    } else if (!wn_enabled && real) {
        return real();
    }

    struct if_nameindex *a = (struct if_nameindex *) calloc(2, sizeof(*a));
    if (!a) return NULL;
    a[0].if_index = WN_IF_INDEX;
    a[0].if_name = strdup(WN_IF_NAME);
    wn_log("if_nameindex -> SYNTHETIC %s idx=%d", WN_IF_NAME, WN_IF_INDEX);
    return a;
}

void if_freenameindex(struct if_nameindex *p) {
    static void (*real)(struct if_nameindex *);
    if (!real) real = (void (*)(struct if_nameindex *)) dlsym(RTLD_NEXT, "if_freenameindex");
    if (!p) return;
    if (p[0].if_index == WN_IF_INDEX && p[0].if_name && strcmp(p[0].if_name, WN_IF_NAME) == 0
        && p[1].if_index == 0) {
        free(p[0].if_name);
        free(p);
        return;
    }
    if (real) real(p);
}

unsigned int if_nametoindex(const char *name) {
    static unsigned int (*real)(const char *);
    if (!real) real = (unsigned int (*)(const char *)) dlsym(RTLD_NEXT, "if_nametoindex");
    unsigned int r = real ? real(name) : 0;
    if (r == 0 && wn_enabled && name && strcmp(name, WN_IF_NAME) == 0) return WN_IF_INDEX;
    return r;
}

char *if_indextoname(unsigned int idx, char *name) {
    static char *(*real)(unsigned int, char *);
    if (!real) real = (char *(*)(unsigned int, char *)) dlsym(RTLD_NEXT, "if_indextoname");
    char *r = real ? real(idx, name) : NULL;
    if (!r && wn_enabled && idx == WN_IF_INDEX && name) {
        snprintf(name, IFNAMSIZ, "%s", WN_IF_NAME);
        return name;
    }
    return r;
}

static int wn_mac_unusable(const struct ifreq *ifr) {
    const unsigned char *a = (const unsigned char *) ifr->ifr_hwaddr.sa_data;
    if (ifr->ifr_hwaddr.sa_family != ARPHRD_ETHER) return 1;
    int zero = 1, placeholder = (a[0] == 0x02);
    for (int i = 0; i < 6; i++) {
        if (a[i]) zero = 0;
        if (i && a[i]) placeholder = 0;
    }
    return zero || placeholder || (a[0] & 0x03);
}

int ioctl(int fd, int req, ...) {
    static int (*real)(int, int, void *);
    if (!real) real = (int (*)(int, int, void *)) dlsym(RTLD_NEXT, "ioctl");
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    int rc = real(fd, req, arg);
    if (!wn_enabled || !arg) return rc;

    unsigned int ureq = (unsigned int) req;
    if (ureq != SIOCGIFHWADDR && ureq != SIOCGIFFLAGS && ureq != SIOCGIFMTU
        && ureq != SIOCGIFINDEX && ureq != SIOCGIFADDR && ureq != SIOCGIFNETMASK
        && ureq != SIOCGIFBRDADDR)
        return rc;

    struct ifreq *ifr = (struct ifreq *) arg;
    char name[IFNAMSIZ + 1];
    memcpy(name, ifr->ifr_name, IFNAMSIZ);
    name[IFNAMSIZ] = 0;
    int synth = 0;

    if (ureq == SIOCGIFHWADDR) {
        const unsigned char *a = (const unsigned char *) ifr->ifr_hwaddr.sa_data;
        wn_log("ioctl SIOCGIFHWADDR name=%s rc=%d family=%d mac=%02x:%02x:%02x:%02x:%02x:%02x",
               name, rc, rc == 0 ? ifr->ifr_hwaddr.sa_family : -1,
               a[0], a[1], a[2], a[3], a[4], a[5]);
        int loopback = strcmp(name, "lo") == 0;
        if (rc != 0 || wn_mac_unusable(ifr)) {
            memset(&ifr->ifr_hwaddr, 0, sizeof(ifr->ifr_hwaddr));
            if (loopback) {
                ifr->ifr_hwaddr.sa_family = ARPHRD_LOOPBACK;
            } else {
                ifr->ifr_hwaddr.sa_family = ARPHRD_ETHER;
                wn_mac_for(name, (unsigned char *) ifr->ifr_hwaddr.sa_data);
            }
            synth = 1;
        }
    } else if (rc != 0) {
        switch (ureq) {
        case SIOCGIFFLAGS:
            ifr->ifr_flags = IFF_UP | IFF_RUNNING | IFF_BROADCAST | IFF_MULTICAST;
            synth = 1;
            break;
        case SIOCGIFMTU:
            ifr->ifr_mtu = WN_IF_MTU;
            synth = 1;
            break;
        case SIOCGIFINDEX:
            ifr->ifr_ifindex = WN_IF_INDEX;
            synth = 1;
            break;
        case SIOCGIFADDR:
        case SIOCGIFNETMASK:
        case SIOCGIFBRDADDR: {
            struct sockaddr_in *sin = (struct sockaddr_in *) &ifr->ifr_addr;
            memset(sin, 0, sizeof(*sin));
            sin->sin_family = AF_INET;
            sin->sin_addr.s_addr = htonl(ureq == SIOCGIFADDR ? WN_IF_ADDR
                                       : ureq == SIOCGIFNETMASK ? WN_IF_MASK : WN_IF_BCAST);
            synth = 1;
            break;
        }
        default:
            break;
        }
    }

    if (synth) {
        const unsigned char *a = (const unsigned char *) ifr->ifr_hwaddr.sa_data;
        wn_log("ioctl 0x%x name=%s real_rc=%d -> SYNTHETIC family=%d mac=%02x:%02x:%02x:%02x:%02x:%02x",
               ureq, name, rc, ifr->ifr_hwaddr.sa_family,
               a[0], a[1], a[2], a[3], a[4], a[5]);
        return 0;
    }
    return rc;
}

FILE *fopen(const char *path, const char *mode) {
    static FILE *(*real)(const char *, const char *);
    if (!real) real = (FILE *(*)(const char *, const char *)) dlsym(RTLD_NEXT, "fopen");
    if (wn_enabled && path && strncmp(path, "/sys/class/net/", 15) == 0
        && strstr(path, "/carrier")) {
        FILE *f = real(path, mode);
        if (f) return f;
        wn_log("fopen(%s) -> SYNTHETIC carrier=1", path);
        return fmemopen((void *) "1\n", 2, "r");
    }
    return real(path, mode);
}

static int wn_peer_port(int fd) {
    struct sockaddr_in sa;
    socklen_t sl = sizeof(sa);
    if (getpeername(fd, (struct sockaddr *) &sa, &sl) != 0) return -1;
    if (sa.sin_family != AF_INET) return -1;
    return ntohs(sa.sin_port);
}

static void wn_tap_dump(const char *dir, int fd, const unsigned char *buf, long len) {
    if (!wn_tap || len <= 0 || !buf) return;
    if (wn_tap_events > 400) return;
    int port = wn_peer_port(fd);
    if (port != 23001 && port != 23002) return;
    wn_tap_events++;
    char hex[3 * 40 + 1];
    long n = len < 40 ? len : 40;
    for (long i = 0; i < n; i++) snprintf(hex + i * 3, 4, "%02x ", buf[i]);
    hex[n * 3] = 0;
    wn_log("TAP %s port=%d len=%ld %s", dir, port, len, hex);
}

ssize_t send(int fd, const void *buf, size_t len, int flags) {
    static ssize_t (*real)(int, const void *, size_t, int);
    if (!real) real = (ssize_t (*)(int, const void *, size_t, int)) dlsym(RTLD_NEXT, "send");
    ssize_t rc = real(fd, buf, len, flags);
    if (rc > 0) wn_tap_dump("OUT", fd, (const unsigned char *) buf, rc);
    return rc;
}

ssize_t sendto(int fd, const void *buf, size_t len, int flags,
               const struct sockaddr *dst, socklen_t dstlen) {
    static ssize_t (*real)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
    if (!real) real = (ssize_t (*)(int, const void *, size_t, int, const struct sockaddr *, socklen_t))
        dlsym(RTLD_NEXT, "sendto");
    ssize_t rc = real(fd, buf, len, flags, dst, dstlen);
    if (rc > 0) wn_tap_dump("OUT", fd, (const unsigned char *) buf, rc);
    return rc;
}

ssize_t recv(int fd, void *buf, size_t len, int flags) {
    static ssize_t (*real)(int, void *, size_t, int);
    if (!real) real = (ssize_t (*)(int, void *, size_t, int)) dlsym(RTLD_NEXT, "recv");
    ssize_t rc = real(fd, buf, len, flags);
    if (rc > 0) wn_tap_dump("IN", fd, (const unsigned char *) buf, rc);
    return rc;
}

ssize_t sendmsg(int fd, const struct msghdr *msg, int flags) {
    static ssize_t (*real)(int, const struct msghdr *, int);
    if (!real) real = (ssize_t (*)(int, const struct msghdr *, int)) dlsym(RTLD_NEXT, "sendmsg");
    ssize_t rc = real(fd, msg, flags);
    if (rc > 0 && msg && msg->msg_iovlen > 0)
        wn_tap_dump("OUT", fd, (const unsigned char *) msg->msg_iov[0].iov_base,
                    rc < (ssize_t) msg->msg_iov[0].iov_len ? rc : (ssize_t) msg->msg_iov[0].iov_len);
    return rc;
}

ssize_t recvmsg(int fd, struct msghdr *msg, int flags) {
    static ssize_t (*real)(int, struct msghdr *, int);
    if (!real) real = (ssize_t (*)(int, struct msghdr *, int)) dlsym(RTLD_NEXT, "recvmsg");
    ssize_t rc = real(fd, msg, flags);
    if (rc > 0 && msg && msg->msg_iovlen > 0)
        wn_tap_dump("IN", fd, (const unsigned char *) msg->msg_iov[0].iov_base,
                    rc < (ssize_t) msg->msg_iov[0].iov_len ? rc : (ssize_t) msg->msg_iov[0].iov_len);
    return rc;
}

static int wn_conn_events = 0;

int connect(int fd, const struct sockaddr *addr, socklen_t len) {
    static int (*real)(int, const struct sockaddr *, socklen_t);
    if (!real) real = (int (*)(int, const struct sockaddr *, socklen_t)) dlsym(RTLD_NEXT, "connect");
    int rc = real(fd, addr, len);
    int saved = errno;
    if (wn_tap && wn_conn_events < 400 && addr && addr->sa_family == AF_INET) {
        const struct sockaddr_in *sa = (const struct sockaddr_in *) addr;
        unsigned int a = ntohl(sa->sin_addr.s_addr);
        wn_conn_events++;
        wn_log("CONNECT %u.%u.%u.%u:%d rc=%d errno=%d",
               (a >> 24) & 255u, (a >> 16) & 255u, (a >> 8) & 255u, a & 255u,
               (int) ntohs(sa->sin_port), rc, rc == 0 ? 0 : saved);
    }
    errno = saved;
    return rc;
}

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    static int (*real)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
    if (!real) real = (int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **))
                      dlsym(RTLD_NEXT, "getaddrinfo");
    int rc = real(node, service, hints, res);
    if (wn_tap && wn_conn_events < 400 && node) {
        wn_conn_events++;
        wn_log("RESOLVE %s%s%s -> rc=%d", node,
               service ? ":" : "", service ? service : "", rc);
    }
    return rc;
}
