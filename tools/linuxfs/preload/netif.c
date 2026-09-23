/*
 * Network interfaces for a sandbox that hides them.
 *
 * Android denies an app the rtnetlink dump behind getifaddrs() and if_nameindex(), the hardware
 * address ioctl, and every table under /proc/net. Wine builds its adapter, address, route and
 * neighbour tables from exactly those, so a game saw a machine with no adapter, no address, no
 * gateway and no MAC, and one that checks its adapters before going online waited for good.
 * The app knows the real link from ConnectivityManager and writes it to /etc/winnative-net;
 * whatever the kernel refuses is answered from that file. Anything the kernel does answer wins.
 */
#define _GNU_SOURCE 1
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netpacket/packet.h>
#include <netinet/in.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>

#define LINK_FILE "/etc/winnative-net"
#define LO_NAME "lo"
#define LO_INDEX 1
#define LO_MTU 65536
#define MAX_ADDRS 16

struct link_addr {
    int family;
    unsigned char bytes[16];
    unsigned prefix;
};

struct link {
    char name[IFNAMSIZ];
    unsigned index;
    int mtu;
    unsigned char mac[6];
    struct link_addr addrs[MAX_ADDRS];
    int addr_count;
    struct link_addr gateways[2];
    int gateway_count;
};

static int parse_addr(const char *text, struct link_addr *out) {
    memset(out, 0, sizeof(*out));
    if (inet_pton(AF_INET, text, out->bytes) == 1) out->family = AF_INET;
    else if (inet_pton(AF_INET6, text, out->bytes) == 1) out->family = AF_INET6;
    else return 0;
    return 1;
}

/* Read on every call: the app rewrites the file when the device changes network. */
static int load_link(struct link *link) {
    typedef FILE *(*fopen_fn)(const char *, const char *);
    fopen_fn real_fopen = (fopen_fn) dlsym(RTLD_NEXT, "fopen");
    FILE *f = real_fopen ? real_fopen(LINK_FILE, "re") : NULL;
    if (f == NULL) return 0;

    memset(link, 0, sizeof(*link));
    char line[160], text[64];
    unsigned m[6], prefix;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "if %15s %u %d", link->name, &link->index, &link->mtu) == 3) continue;
        if (sscanf(line, "mac %2x:%2x:%2x:%2x:%2x:%2x", m, m + 1, m + 2, m + 3, m + 4, m + 5) == 6) {
            for (int i = 0; i < 6; i++) link->mac[i] = (unsigned char) m[i];
        } else if (sscanf(line, "addr %63s %u", text, &prefix) == 2) {
            struct link_addr addr;
            if (link->addr_count < MAX_ADDRS && parse_addr(text, &addr)
                    && prefix <= (addr.family == AF_INET ? 32u : 128u)) {
                addr.prefix = prefix;
                link->addrs[link->addr_count++] = addr;
            }
        } else if (sscanf(line, "gw %63s", text) == 1) {
            struct link_addr addr;
            if (link->gateway_count < 2 && parse_addr(text, &addr)) {
                link->gateways[link->gateway_count++] = addr;
            }
        }
    }
    fclose(f);
    return link->name[0] != '\0' && link->index > LO_INDEX && link->addr_count > 0;
}

static void prefix_mask(int family, unsigned prefix, unsigned char *out) {
    int len = family == AF_INET ? 4 : 16;
    for (int i = 0; i < len; i++, prefix -= prefix < 8 ? prefix : 8) {
        out[i] = prefix >= 8 ? 0xff : (unsigned char) (0xff00 >> prefix);
    }
}

static int is_link_local(const struct link_addr *addr) {
    return addr->family == AF_INET6 && addr->bytes[0] == 0xfe && (addr->bytes[1] & 0xc0) == 0x80;
}

/* One allocation per list. ifa_data of the head points at the head itself, which glibc never
 * does, so freeifaddrs() can tell the two kinds of list apart without keeping any state. */
struct entry {
    struct ifaddrs ifa;
    struct sockaddr_storage addr, mask, extra;
    char name[IFNAMSIZ];
};

static struct entry *fill_entry(struct entry *e, const char *name, unsigned flags) {
    snprintf(e->name, sizeof(e->name), "%s", name);
    e->ifa.ifa_name = e->name;
    e->ifa.ifa_flags = flags;
    e->ifa.ifa_addr = (struct sockaddr *) &e->addr;
    e[-1].ifa.ifa_next = &e->ifa;
    return e;
}

static void fill_packet(struct entry *e, unsigned index, const unsigned char *mac) {
    struct sockaddr_ll *ll = (struct sockaddr_ll *) &e->addr;
    ll->sll_family = AF_PACKET;
    ll->sll_ifindex = (int) index;
    ll->sll_hatype = mac ? ARPHRD_ETHER : ARPHRD_LOOPBACK;
    ll->sll_halen = 6;
    if (mac) memcpy(ll->sll_addr, mac, 6);
}

static void fill_inet(struct entry *e, const struct link_addr *addr, unsigned index) {
    unsigned char mask[16];
    prefix_mask(addr->family, addr->prefix, mask);
    e->ifa.ifa_netmask = (struct sockaddr *) &e->mask;
    if (addr->family == AF_INET) {
        struct sockaddr_in *sin = (struct sockaddr_in *) &e->addr;
        struct sockaddr_in *smask = (struct sockaddr_in *) &e->mask;
        sin->sin_family = smask->sin_family = AF_INET;
        memcpy(&sin->sin_addr, addr->bytes, 4);
        memcpy(&smask->sin_addr, mask, 4);
        if (e->ifa.ifa_flags & IFF_BROADCAST) {
            struct sockaddr_in *bcast = (struct sockaddr_in *) &e->extra;
            bcast->sin_family = AF_INET;
            bcast->sin_addr.s_addr = sin->sin_addr.s_addr | ~smask->sin_addr.s_addr;
            e->ifa.ifa_broadaddr = (struct sockaddr *) bcast;
        }
    } else {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *) &e->addr;
        struct sockaddr_in6 *smask = (struct sockaddr_in6 *) &e->mask;
        sin6->sin6_family = smask->sin6_family = AF_INET6;
        memcpy(&sin6->sin6_addr, addr->bytes, 16);
        memcpy(&smask->sin6_addr, mask, 16);
        if (is_link_local(addr)) sin6->sin6_scope_id = index;
    }
}

static const struct link_addr LO_V4 = { AF_INET, { 127, 0, 0, 1 }, 8 };
static const struct link_addr LO_V6 = { AF_INET6, { [15] = 1 }, 128 };

static int real_list_usable(const struct ifaddrs *list) {
    for (const struct ifaddrs *e = list; e; e = e->ifa_next) {
        if (e->ifa_addr && e->ifa_addr->sa_family == AF_INET && !(e->ifa_flags & IFF_LOOPBACK)) {
            return 1;
        }
    }
    return 0;
}

int getifaddrs(struct ifaddrs **out) {
    typedef int (*getifaddrs_fn)(struct ifaddrs **);
    typedef void (*freeifaddrs_fn)(struct ifaddrs *);
    getifaddrs_fn real = (getifaddrs_fn) dlsym(RTLD_NEXT, "getifaddrs");
    struct ifaddrs *list = NULL;
    int rc = real(&list);
    int saved = errno;
    if (rc == 0 && real_list_usable(list)) {
        *out = list;
        return 0;
    }

    struct link link;
    if (!load_link(&link)) {
        if (rc == 0) *out = list;
        errno = saved;
        return rc;
    }
    if (rc == 0 && list) ((freeifaddrs_fn) dlsym(RTLD_NEXT, "freeifaddrs"))(list);

    /* A leading slot keeps fill_entry()'s back link in bounds; the list starts after it. */
    struct entry *block = calloc((size_t) link.addr_count + 5, sizeof(*block));
    if (block == NULL) {
        errno = ENOMEM;
        return -1;
    }
    unsigned lo_flags = IFF_UP | IFF_RUNNING | IFF_LOOPBACK;
    unsigned up_flags = IFF_UP | IFF_RUNNING | IFF_BROADCAST | IFF_MULTICAST;
    struct entry *e = block;
    fill_packet(fill_entry(++e, LO_NAME, lo_flags), LO_INDEX, NULL);
    fill_inet(fill_entry(++e, LO_NAME, lo_flags), &LO_V4, LO_INDEX);
    fill_inet(fill_entry(++e, LO_NAME, lo_flags), &LO_V6, LO_INDEX);
    fill_packet(fill_entry(++e, link.name, up_flags), link.index, link.mac);
    for (int i = 0; i < link.addr_count; i++) {
        fill_inet(fill_entry(++e, link.name, up_flags), &link.addrs[i], link.index);
    }
    block[1].ifa.ifa_data = &block[1].ifa;
    *out = &block[1].ifa;
    return 0;
}

void freeifaddrs(struct ifaddrs *list) {
    typedef void (*freeifaddrs_fn)(struct ifaddrs *);
    if (list == NULL) return;
    if (list->ifa_data == list) {
        free((struct entry *) list - 1);
        return;
    }
    ((freeifaddrs_fn) dlsym(RTLD_NEXT, "freeifaddrs"))(list);
}

/* Names live in the same allocation as the array, right behind it; glibc strdup()s each one. */
struct name_block {
    struct if_nameindex index[3];
    char names[2][IFNAMSIZ];
};

struct if_nameindex *if_nameindex(void) {
    typedef struct if_nameindex *(*if_nameindex_fn)(void);
    struct if_nameindex *list = ((if_nameindex_fn) dlsym(RTLD_NEXT, "if_nameindex"))();
    int saved = errno;
    if (list && list[0].if_index != 0) return list;

    struct link link;
    struct name_block *block;
    if (!load_link(&link) || (block = calloc(1, sizeof(*block))) == NULL) {
        errno = saved;
        return list;
    }
    if (list) if_freenameindex(list);
    snprintf(block->names[0], IFNAMSIZ, "%s", LO_NAME);
    snprintf(block->names[1], IFNAMSIZ, "%s", link.name);
    block->index[0] = (struct if_nameindex) { LO_INDEX, block->names[0] };
    block->index[1] = (struct if_nameindex) { link.index, block->names[1] };
    return block->index;
}

void if_freenameindex(struct if_nameindex *list) {
    typedef void (*if_freenameindex_fn)(struct if_nameindex *);
    if (list == NULL) return;
    if (list[0].if_name == ((struct name_block *) list)->names[0]) {
        free(list);
        return;
    }
    ((if_freenameindex_fn) dlsym(RTLD_NEXT, "if_freenameindex"))(list);
}

unsigned int if_nametoindex(const char *name) {
    typedef unsigned int (*if_nametoindex_fn)(const char *);
    unsigned int index = ((if_nametoindex_fn) dlsym(RTLD_NEXT, "if_nametoindex"))(name);
    if (index != 0 || name == NULL) return index;
    int saved = errno;
    struct link link;
    if (strcmp(name, LO_NAME) == 0) return LO_INDEX;
    if (load_link(&link) && strcmp(name, link.name) == 0) return link.index;
    errno = saved;
    return 0;
}

char *if_indextoname(unsigned int index, char name[IF_NAMESIZE]) {
    typedef char *(*if_indextoname_fn)(unsigned int, char *);
    char *found = ((if_indextoname_fn) dlsym(RTLD_NEXT, "if_indextoname"))(index, name);
    if (found || name == NULL) return found;
    int saved = errno;
    struct link link;
    if (index == LO_INDEX) return strcpy(name, LO_NAME);
    if (load_link(&link) && index == link.index) return strcpy(name, link.name);
    errno = saved;
    return NULL;
}

static int mac_unusable(const struct ifreq *ifr) {
    const unsigned char *a = (const unsigned char *) ifr->ifr_hwaddr.sa_data;
    if (ifr->ifr_hwaddr.sa_family != ARPHRD_ETHER) return 1;
    /* Android hands out 02:00:00:00:00:00 where it hides the real address. */
    int rest = a[1] | a[2] | a[3] | a[4] | a[5];
    return (a[0] & 0x03) || (rest == 0 && (a[0] == 0 || a[0] == 0x02));
}

static const struct link_addr *first_v4(const struct link *link) {
    for (int i = 0; i < link->addr_count; i++) {
        if (link->addrs[i].family == AF_INET) return &link->addrs[i];
    }
    return NULL;
}

static int answer_ifreq(unsigned long request, struct ifreq *ifr, int real_rc) {
    char name[IFNAMSIZ + 1] = { 0 };
    memcpy(name, ifr->ifr_name, IFNAMSIZ);
    int loopback = strcmp(name, LO_NAME) == 0;
    struct link link;
    if (!load_link(&link) || (!loopback && strcmp(name, link.name) != 0)) return 0;

    if (request == SIOCGIFHWADDR) {
        if (real_rc == 0 && (loopback || !mac_unusable(ifr))) return 0;
        memset(&ifr->ifr_hwaddr, 0, sizeof(ifr->ifr_hwaddr));
        ifr->ifr_hwaddr.sa_family = loopback ? ARPHRD_LOOPBACK : ARPHRD_ETHER;
        if (!loopback) memcpy(ifr->ifr_hwaddr.sa_data, link.mac, 6);
        return 1;
    }
    if (real_rc == 0) return 0;

    const struct link_addr *v4 = loopback ? &LO_V4 : first_v4(&link);
    struct sockaddr_in *sin = (struct sockaddr_in *) &ifr->ifr_addr;
    unsigned char mask[16];
    switch (request) {
    case SIOCGIFFLAGS:
        ifr->ifr_flags = loopback ? IFF_UP | IFF_RUNNING | IFF_LOOPBACK
                                  : IFF_UP | IFF_RUNNING | IFF_BROADCAST | IFF_MULTICAST;
        return 1;
    case SIOCGIFMTU:
        ifr->ifr_mtu = loopback ? LO_MTU : link.mtu;
        return 1;
    case SIOCGIFINDEX:
        ifr->ifr_ifindex = loopback ? LO_INDEX : (int) link.index;
        return 1;
    case SIOCGIFADDR:
    case SIOCGIFNETMASK:
    case SIOCGIFBRDADDR:
        if (v4 == NULL) return 0;
        prefix_mask(AF_INET, v4->prefix, mask);
        memset(sin, 0, sizeof(*sin));
        sin->sin_family = AF_INET;
        for (int i = 0; i < 4; i++) {
            unsigned char *out = (unsigned char *) &sin->sin_addr + i;
            *out = request == SIOCGIFNETMASK ? mask[i]
                 : request == SIOCGIFADDR ? v4->bytes[i]
                 : (unsigned char) (v4->bytes[i] | ~mask[i]);
        }
        return 1;
    default:
        return 0;
    }
}

static int is_link_query(unsigned long request) {
    return request == SIOCGIFHWADDR || request == SIOCGIFFLAGS || request == SIOCGIFMTU
        || request == SIOCGIFINDEX || request == SIOCGIFADDR || request == SIOCGIFNETMASK
        || request == SIOCGIFBRDADDR;
}

/* Every DRM and evdev call comes through here, so the lookup is done once. Two threads racing
 * to store it store the same pointer. */
int ioctl(int fd, unsigned long request, ...) {
    typedef int (*ioctl_fn)(int, unsigned long, void *);
    static ioctl_fn real;
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    if (real == NULL) real = (ioctl_fn) dlsym(RTLD_NEXT, "ioctl");
    int rc = real(fd, request, arg);
    if (arg == NULL || !is_link_query(request)) return rc;
    int saved = errno;
    if (answer_ifreq(request, (struct ifreq *) arg, rc)) return 0;
    errno = saved;
    return rc;
}

static void print_v6(FILE *out, const unsigned char *bytes) {
    for (int i = 0; i < 16; i++) fprintf(out, "%02x", bytes[i]);
}

static unsigned v6_scope(const struct link_addr *addr) {
    if (memcmp(addr, &LO_V6, sizeof(*addr)) == 0) return 0x10;
    return is_link_local(addr) ? 0x20 : 0x00;
}

static void write_table(FILE *out, const char *table, const struct link *link) {
    static const unsigned char zero[16];
    unsigned char mask[16];
    if (strcmp(table, "route") == 0) {
        fprintf(out, "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n");
        for (int i = 0; i < link->gateway_count; i++) {
            if (link->gateways[i].family != AF_INET) continue;
            unsigned gw;
            memcpy(&gw, link->gateways[i].bytes, 4);
            fprintf(out, "%s\t00000000\t%08X\t0003\t0\t0\t0\t00000000\t0\t0\t0\n", link->name, gw);
        }
        for (int i = 0; i < link->addr_count; i++) {
            if (link->addrs[i].family != AF_INET) continue;
            unsigned addr, net;
            prefix_mask(AF_INET, link->addrs[i].prefix, mask);
            memcpy(&addr, link->addrs[i].bytes, 4);
            memcpy(&net, mask, 4);
            fprintf(out, "%s\t%08X\t00000000\t0001\t0\t0\t0\t%08X\t0\t0\t0\n",
                    link->name, addr & net, net);
        }
    } else if (strcmp(table, "ipv6_route") == 0) {
        for (int i = 0; i < link->gateway_count; i++) {
            if (link->gateways[i].family != AF_INET6) continue;
            print_v6(out, zero);
            fprintf(out, " 00 ");
            print_v6(out, zero);
            fprintf(out, " 00 ");
            print_v6(out, link->gateways[i].bytes);
            fprintf(out, " 00000400 00000001 00000000 00000003 %8s\n", link->name);
        }
        for (int i = 0; i < link->addr_count; i++) {
            if (link->addrs[i].family != AF_INET6) continue;
            unsigned char net[16];
            prefix_mask(AF_INET6, link->addrs[i].prefix, mask);
            for (int b = 0; b < 16; b++) net[b] = link->addrs[i].bytes[b] & mask[b];
            print_v6(out, net);
            fprintf(out, " %02x ", link->addrs[i].prefix);
            print_v6(out, zero);
            fprintf(out, " 00 ");
            print_v6(out, zero);
            fprintf(out, " 00000100 00000001 00000000 00000001 %8s\n", link->name);
        }
    } else if (strcmp(table, "if_inet6") == 0) {
        print_v6(out, LO_V6.bytes);
        fprintf(out, " %02x %02x %02x 80 %8s\n", LO_INDEX, LO_V6.prefix, v6_scope(&LO_V6), LO_NAME);
        for (int i = 0; i < link->addr_count; i++) {
            if (link->addrs[i].family != AF_INET6) continue;
            print_v6(out, link->addrs[i].bytes);
            fprintf(out, " %02x %02x %02x 80 %8s\n", link->index, link->addrs[i].prefix,
                    v6_scope(&link->addrs[i]), link->name);
        }
    } else if (strcmp(table, "dev") == 0) {
        fprintf(out, "Inter-|   Receive                                                |  Transmit\n"
                     " face |bytes    packets errs drop fifo frame compressed multicast|"
                     "bytes    packets errs drop fifo colls carrier compressed\n");
        fprintf(out, "%6s: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n", LO_NAME);
        fprintf(out, "%6s: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n", link->name);
    } else if (strcmp(table, "arp") == 0) {
        fprintf(out, "IP address       HW type     Flags       HW address            Mask     Device\n");
    } else {
        fprintf(out, "1\n");
    }
}

static const char *denied_table(const char *path) {
    static const char *const tables[] = { "route", "ipv6_route", "if_inet6", "dev", "arp" };
    if (strncmp(path, "/proc/net/", 10) == 0) {
        for (size_t i = 0; i < sizeof(tables) / sizeof(tables[0]); i++) {
            if (strcmp(path + 10, tables[i]) == 0) return tables[i];
        }
        return NULL;
    }
    size_t len = strlen(path);
    if (strncmp(path, "/sys/class/net/", 15) == 0 && len > 8 && strcmp(path + len - 8, "/carrier") == 0) {
        return "carrier";
    }
    return NULL;
}

/* A memfd rather than fmemopen(): the stream owns its contents, so fclose() frees everything. */
static FILE *synthesize(const char *table) {
    struct link link;
    if (!load_link(&link)) return NULL;
    int fd = memfd_create("winnative-net", MFD_CLOEXEC);
    if (fd < 0) return NULL;
    FILE *out = fdopen(fd, "w+");
    if (out == NULL) {
        close(fd);
        return NULL;
    }
    write_table(out, table, &link);
    if (fflush(out) != 0) {
        fclose(out);
        return NULL;
    }
    rewind(out);
    return out;
}

/* tracer.c */
int wn_status_without_tracer(const char *path, int flags) __attribute__((visibility("hidden")));

static FILE *open_stream(const char *symbol, const char *path, const char *mode) {
    typedef FILE *(*fopen_fn)(const char *, const char *);
    int status = mode && mode[0] == 'r' && !strchr(mode, '+')
            ? wn_status_without_tracer(path, O_RDONLY | O_CLOEXEC) : -1;
    if (status >= 0) {
        FILE *copy = fdopen(status, "r");
        if (copy == NULL) close(status);
        return copy;
    }
    FILE *f = ((fopen_fn) dlsym(RTLD_NEXT, symbol))(path, mode);
    if (f || path == NULL || mode == NULL || mode[0] != 'r') return f;
    int saved = errno;
    const char *table = denied_table(path);
    if (table && (f = synthesize(table)) != NULL) return f;
    errno = saved;
    return NULL;
}

FILE *fopen(const char *path, const char *mode) {
    return open_stream("fopen", path, mode);
}

FILE *fopen64(const char *path, const char *mode) {
    return open_stream("fopen64", path, mode);
}
