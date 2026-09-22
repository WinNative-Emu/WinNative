#!/bin/sh
set -eu
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
cat > "$work/identity.c" <<'C'
#define _GNU_SOURCE
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
int main(void) {
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_setgid, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_setuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
    struct sock_fprog filter = { sizeof(code) / sizeof(code[0]), code };
    uid_t uid = getuid();
    gid_t gid = getgid();
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter)) return 2;
    if (setgid(gid) || setuid(uid)) return 1;
    if (getuid() != uid || getgid() != gid) return 3;
    puts("identity calls succeeded; uid/gid unchanged");
    return 0;
}
C
cc -O2 -Wall -Werror "$work/identity.c" -o "$work/identity"
status=0
"$work/identity" || status=$?
[ "$status" -eq 1 ]
proot -i "$(id -u):$(id -g)" "$work/identity"
