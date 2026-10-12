/* -*- c-set-style: "K&R"; c-basic-offset: 8 -*-
 *
 * This file is part of PRoot.
 *
 * Copyright (C) 2015 STMicroelectronics
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

#include "build.h"
#include "arch.h"

#if defined(HAVE_SECCOMP_FILTER)

#include <sys/prctl.h>     /* prctl(2), PR_* */
#include <linux/filter.h>  /* struct sock_*, */
#include <linux/seccomp.h> /* SECCOMP_MODE_FILTER, */
#include <linux/filter.h>  /* struct sock_*, */
#include <linux/audit.h>   /* AUDIT_, */
#include <sys/queue.h>     /* LIST_FOREACH, */
#include <sys/types.h>     /* size_t, */
#include <talloc.h>        /* talloc_*, */
#include <errno.h>         /* E*, */
#include <string.h>        /* memcpy(3), */
#include <stdlib.h>        /* getenv(3), */
#include <stddef.h>        /* offsetof(3), */
#include <stdint.h>        /* uint*_t, UINT*_MAX, */
#include <stdbool.h>
#include <sys/resource.h>
#include <sys/utsname.h>   /* uname(2), */
#include <stdio.h>         /* sscanf(3), */
#include <asm/ioctls.h>    /* TCGETS2, TCSETS*2, */
#include <asm/termbits.h>  /* struct termios2, */
#include <linux/fs.h>      /* FICLONE, */
#include <assert.h>        /* assert(3), */

#include "syscall/seccomp.h"
#include "tracee/tracee.h"
#include "syscall/syscall.h"
#include "syscall/sysnum.h"
#include "extension/extension.h"
#include "cli/note.h"

#include "compat.h"
#include "attribute.h"

#define DEBUG_FILTER(...) /* fprintf(stderr, __VA_ARGS__) */

/**
 * Allocate an empty @program->filter.  This function returns -errno
 * if an error occurred, otherwise 0.
 */
static int new_program_filter(struct sock_fprog *program)
{
	program->filter = talloc_array(NULL, struct sock_filter, 0);
	if (program->filter == NULL)
		return -ENOMEM;

	program->len = 0;
	return 0;
}

/**
 * Append to @program->filter the given @statements (@nb_statements
 * items).  This function returns -errno if an error occurred,
 * otherwise 0.
 */
/* The fast path's trampoline page (tools/proot/fastpath): a traced syscall made from it was
 * already translated inside the tracee and is let through without a stop.  With PROOT_FASTPATH
 * set, every SECCOMP_RET_TRACE is preceded by a check of the caller's address.  The check sits
 * after the syscall-number dispatch, so syscalls proot never traces stay constant-ALLOW and keep
 * the kernel's seccomp action cache.  FASTPATH_STUB_ADDR must match FP_STUB_ADDR in fastpath.c. */
#define FASTPATH_STUB_ADDR 0xffff00000ULL
#define FASTPATH_STUB_SIZE 0x1000ULL
#define LENGTH_FASTPATH_TAIL 7

static bool fastpath_enabled(void)
{
	static int enabled = -1;
	if (enabled < 0)
		enabled = getenv("PROOT_FASTPATH") != NULL;
	return enabled;
}

static bool is_ret_trace(const struct sock_filter *statement)
{
	return statement->code == (BPF_RET + BPF_K)
		&& (statement->k & SECCOMP_RET_ACTION_FULL) == SECCOMP_RET_TRACE;
}

static int add_statements_raw(struct sock_fprog *program, size_t nb_statements,
			struct sock_filter *statements);

/* Append @statements, each RET_TRACE expanded into the trampoline check when the fast path is
 * on, every jump within (or out of) the block adjusted for the expansion.  */
static int add_statements(struct sock_fprog *program, size_t nb_statements,
			struct sock_filter *statements)
{
	struct sock_filter out[64];
	size_t position[33];
	size_t i, o;

	if (!fastpath_enabled())
		return add_statements_raw(program, nb_statements, statements);

	assert(nb_statements <= 32);
	for (i = 0, o = 0; i < nb_statements; i++) {
		position[i] = o;
		o += is_ret_trace(&statements[i]) ? LENGTH_FASTPATH_TAIL : 1;
	}
	position[nb_statements] = o;

#define NEW_TARGET(index) (((index) <= nb_statements) ? position[(index)] : position[nb_statements] + (index) - nb_statements)

	for (i = 0, o = 0; i < nb_statements; i++) {
		struct sock_filter statement = statements[i];

		if (BPF_CLASS(statement.code) == BPF_JMP) {
			if (BPF_OP(statement.code) == BPF_JA)
				statement.k = NEW_TARGET(i + 1 + statement.k) - position[i] - 1;
			else {
				statement.jt = NEW_TARGET(i + 1 + statement.jt) - position[i] - 1;
				statement.jf = NEW_TARGET(i + 1 + statement.jf) - position[i] - 1;
			}
		}

		if (!is_ret_trace(&statement)) {
			out[o++] = statement;
			continue;
		}

		{
			const size_t ip = offsetof(struct seccomp_data, instruction_pointer);
			const uint32_t hi = (uint32_t) (FASTPATH_STUB_ADDR >> 32);
			const uint32_t lo = (uint32_t) FASTPATH_STUB_ADDR;
			struct sock_filter tail[LENGTH_FASTPATH_TAIL] = {
				BPF_STMT(BPF_LD + BPF_W + BPF_ABS, ip + sizeof(uint32_t)),
				BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, hi, 0, 4),
				BPF_STMT(BPF_LD + BPF_W + BPF_ABS, ip),
				BPF_JUMP(BPF_JMP + BPF_JGE + BPF_K, lo, 0, 2),
				BPF_JUMP(BPF_JMP + BPF_JGE + BPF_K, lo + (uint32_t) FASTPATH_STUB_SIZE, 1, 0),
				BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
				statement,
			};
			memcpy(&out[o], tail, sizeof(tail));
			o += LENGTH_FASTPATH_TAIL;
		}
	}
#undef NEW_TARGET

	return add_statements_raw(program, o, out);
}

static int add_statements_raw(struct sock_fprog *program, size_t nb_statements,
			struct sock_filter *statements)
{
	size_t length;
	void *tmp;
	size_t i;

	length = talloc_array_length(program->filter);
	tmp  = talloc_realloc(NULL, program->filter, struct sock_filter, length + nb_statements);
	if (tmp == NULL)
		return -ENOMEM;
	program->filter = tmp;

	for (i = 0; i < nb_statements; i++, length++)
		memcpy(&program->filter[length], &statements[i], sizeof(struct sock_filter));

	return 0;
}

/**
 * Append to @program->filter the statements required to notify PRoot
 * about the given @syscall made by a tracee, with the given @flag.
 * This function returns -errno if an error occurred, otherwise 0.
 */
static int add_trace_syscall(struct sock_fprog *program, word_t syscall, int flag)
{
	int status;

	/* Sanity check.  */
	if (syscall > UINT32_MAX)
		return -ERANGE;

	#define LENGTH_TRACE_SYSCALL 2
	struct sock_filter statements[LENGTH_TRACE_SYSCALL] = {
		/* Compare the accumulator with the expected syscall:
		 * skip the next statement if not equal.  */
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, syscall, 0, 1),

		/* Notify the tracer.  */
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRACE + flag)
	};

	DEBUG_FILTER("FILTER:     trace if syscall == %ld\n", syscall);

	status = add_statements(program, LENGTH_TRACE_SYSCALL, statements);
	if (status < 0)
		return status;

	return 0;
}

static int add_trace_syscall_if_arg(struct sock_fprog *program, word_t syscall,
				size_t arg, uint32_t value, int flag)
{
	const size_t arg_offset = offsetof(struct seccomp_data, args[arg]);

	#define LENGTH_TRACE_SYSCALL_IF_ARG 5
	struct sock_filter statements[LENGTH_TRACE_SYSCALL_IF_ARG] = {
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, syscall, 0, 4),
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, arg_offset),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, value, 0, 1),
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRACE + flag),
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW)
	};

	DEBUG_FILTER("FILTER:     trace if syscall == %ld and arg %zd == %u\n",
		syscall, arg, value);

	return add_statements(program, LENGTH_TRACE_SYSCALL_IF_ARG, statements);
}

static int add_trace_prlimit_stack(struct sock_fprog *program, word_t syscall, int flag)
{
	const size_t resource_offset = offsetof(struct seccomp_data, args[1]);
	const size_t limit_offset    = offsetof(struct seccomp_data, args[2]);

	#define LENGTH_TRACE_PRLIMIT_STACK 9
	struct sock_filter statements[LENGTH_TRACE_PRLIMIT_STACK] = {
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, syscall, 0, 8),
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, resource_offset),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, RLIMIT_STACK, 0, 5),
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, limit_offset),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, 0, 0, 2),
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, limit_offset + sizeof(uint32_t)),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, 0, 1, 0),
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRACE + flag),
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW)
	};

	DEBUG_FILTER("FILTER:     trace if syscall == %ld sets RLIMIT_STACK\n", syscall);

	return add_statements(program, LENGTH_TRACE_PRLIMIT_STACK, statements);
}

/**
 * Append to @program->filter the statements that trace @syscall
 * (ioctl) only for the requests enter.c and exit.c act on - the
 * termios requests Android's policy denies an app on a pty, rewritten
 * to ones it allows, and FICLONE, whose EACCES is turned into
 * EOPNOTSUPP - and allow any other request at once.  Every GPU submit
 * and wait is an ioctl, so they no longer stop in the tracer, and the
 * block is emitted first so they are also spared the rest of the list.
 * (After WinNative, maxjivi05, 53836ca9.)
 */
static int add_trace_ioctl_requests(struct sock_fprog *program, word_t syscall, int flag)
{
	const size_t request_offset = offsetof(struct seccomp_data, args[1]);

	#define LENGTH_TRACE_IOCTL 10
	struct sock_filter statements[LENGTH_TRACE_IOCTL] = {
		/* Not this syscall: skip the block, the accumulator
		 * still holds the syscall number.  */
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, syscall, 0, 9),

		/* Load the request's low word into the accumulator.  */
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, request_offset),

		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, TCSETSF,  5, 0),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, TCGETS2,  4, 0),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, TCSETS2,  3, 0),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, TCSETSW2, 2, 0),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, TCSETSF2, 1, 0),
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, (uint32_t) FICLONE, 0, 1),

		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRACE + flag),
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW)
	};

	DEBUG_FILTER("FILTER:     trace if syscall == %ld and a termios2/TCSETSF/FICLONE request\n",
		syscall);

	return add_statements(program, LENGTH_TRACE_IOCTL, statements);
}

static size_t trace_length_raw(Sysnum sysnum);

static size_t trace_length(Sysnum sysnum)
{
	/* Each trace block has exactly one RET_TRACE (see add_statements).  */
	return trace_length_raw(sysnum) + (fastpath_enabled() ? LENGTH_FASTPATH_TAIL - 1 : 0);
}

static size_t trace_length_raw(Sysnum sysnum)
{
	switch (sysnum) {
	case PR_prctl:
	case PR_setrlimit:
		return LENGTH_TRACE_SYSCALL_IF_ARG;
	case PR_prlimit64:
		return LENGTH_TRACE_PRLIMIT_STACK;
	case PR_ioctl:
		return LENGTH_TRACE_IOCTL;
	default:
		return LENGTH_TRACE_SYSCALL;
	}
}

static int add_trace(struct sock_fprog *program, Sysnum sysnum, word_t syscall, int flag)
{
	if (syscall > UINT32_MAX)
		return -ERANGE;

	switch (sysnum) {
	case PR_prctl:
		return add_trace_syscall_if_arg(program, syscall, 0, PR_SET_DUMPABLE, flag);
	case PR_setrlimit:
		return add_trace_syscall_if_arg(program, syscall, 0, RLIMIT_STACK, flag);
	case PR_prlimit64:
		return add_trace_prlimit_stack(program, syscall, flag);
	case PR_ioctl:
		return add_trace_ioctl_requests(program, syscall, flag);
	default:
		return add_trace_syscall(program, syscall, flag);
	}
}

/**
 * Append to @program->filter the statements that allow anything (if
 * unfiltered).  Note that @section_length is used to make a sanity
 * check.  This function returns -errno if an error occurred,
 * otherwise 0.
 */
static int end_arch_section(struct sock_fprog *program, size_t section_length)
{
	int status;

	#define LENGTH_END_SECTION 1
	struct sock_filter statements[LENGTH_END_SECTION] = {
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW)
	};

	DEBUG_FILTER("FILTER:     allow\n");

	status = add_statements(program, LENGTH_END_SECTION, statements);
	if (status < 0)
		return status;

	/* Sanity check, see start_arch_section().  */
	if (talloc_array_length(program->filter) - program->len != section_length)
		return -ERANGE;

	return 0;
}

/**
 * Append to @program->filter the statements that check the current
 * @architecture.  Note that @section_length is used to make a
 * sanity check.  This function returns -errno if an error occurred,
 * otherwise 0.
 */
static int start_arch_section(struct sock_fprog *program, uint32_t arch, size_t section_length)
{
	const size_t arch_offset    = offsetof(struct seccomp_data, arch);
	const size_t syscall_offset = offsetof(struct seccomp_data, nr);
	int status;

	/* Sanity checks.  */
	if (   arch_offset    > UINT32_MAX
	    || syscall_offset > UINT32_MAX
	    || section_length > UINT32_MAX - 1)
		return -ERANGE;

	#define LENGTH_START_SECTION 4
	struct sock_filter statements[LENGTH_START_SECTION] = {
		/* Load the current architecture into the
		 * accumulator.  */
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, arch_offset),

		/* Compare the accumulator with the expected
		 * architecture: skip the following statement if
		 * equal.  */
		BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, arch, 1, 0),

		/* This is not the expected architecture, so jump
		 * unconditionally to the end of this section.  */
		BPF_STMT(BPF_JMP + BPF_JA + BPF_K, section_length + 1),

		/* This is the expected architecture, so load the
		 * current syscall into the accumulator.  */
		BPF_STMT(BPF_LD + BPF_W + BPF_ABS, syscall_offset)
	};

	DEBUG_FILTER("FILTER: if arch == %ld, up to %zdth statement\n",
		arch, section_length);

	status = add_statements(program, LENGTH_START_SECTION, statements);
	if (status < 0)
		return status;

	/* See the sanity check in end_arch_section().  */
	program->len = talloc_array_length(program->filter);

	return 0;
}

/**
 * Append to @program->filter the statements that forbid anything (if
 * unfiltered) and update @program->len.  This function returns -errno
 * if an error occurred, otherwise 0.
 */
static int finalize_program_filter(struct sock_fprog *program)
{
	int status;

	#define LENGTH_FINALIZE 1
	struct sock_filter statements[LENGTH_FINALIZE] = {
		BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_KILL)
	};

	DEBUG_FILTER("FILTER: kill\n");

	status = add_statements(program, LENGTH_FINALIZE, statements);
	if (status < 0)
		return status;

	program->len = talloc_array_length(program->filter);

	return 0;
}

/**
 * Free @program->filter and set @program->len to 0.
 */
static void free_program_filter(struct sock_fprog *program)
{
	TALLOC_FREE(program->filter);
	program->len = 0;
}

/**
 * Convert the given @sysnums into BPF filters according to the
 * following pseudo-code, then enabled them for the given @tracee and
 * all of its future children:
 *
 *     for each handled architectures
 *         for each filtered syscall
 *             trace
 *         allow
 *     kill
 *
 * This function returns -errno if an error occurred, otherwise 0.
 */
static int set_seccomp_filters(const FilteredSysnum *sysnums)
{
	SeccompArch seccomp_archs[] = SECCOMP_ARCHS;
	size_t nb_archs = sizeof(seccomp_archs) / sizeof(SeccompArch);

	struct sock_fprog program = { .len = 0, .filter = NULL };
	size_t section_length;
	size_t i, j, k, pass;
	int status;

	status = new_program_filter(&program);
	if (status < 0)
		goto end;

	/* For each handled architectures */
	for (i = 0; i < nb_archs; i++) {
		word_t syscall;

		section_length = LENGTH_END_SECTION;

		for (j = 0; j < seccomp_archs[i].nb_abis; j++) {
			for (k = 0; sysnums[k].value != PR_void; k++) {
				syscall = detranslate_sysnum(seccomp_archs[i].abis[j], sysnums[k].value);
				if (syscall != SYSCALL_AVOIDER)
					section_length += trace_length(sysnums[k].value);
			}
		}

		/* Filter: if handled architecture */
		status = start_arch_section(&program, seccomp_archs[i].value, section_length);
		if (status < 0)
			goto end;

		/* Two passes: ioctl first, it is by far the most frequent
		 * syscall and almost every one of them is allowed.  */
		for (pass = 0; pass < 2; pass++)
		for (j = 0; j < seccomp_archs[i].nb_abis; j++) {
			for (k = 0; sysnums[k].value != PR_void; k++) {
				if ((sysnums[k].value == PR_ioctl) != (pass == 0))
					continue;

				/* Get the architecture specific syscall number.  */
				syscall = detranslate_sysnum(seccomp_archs[i].abis[j], sysnums[k].value);
				if (syscall == SYSCALL_AVOIDER)
					continue;

				/* Filter: trace if handled syscall */
				status = add_trace(&program, sysnums[k].value, syscall, sysnums[k].flags);
				if (status < 0)
					goto end;
			}
		}

		/* Filter: allow untraced syscalls for this architecture */
		status = end_arch_section(&program, section_length);
		if (status < 0)
			goto end;
	}

	status = finalize_program_filter(&program);
	if (status < 0)
		goto end;

	status = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
	if (status < 0)
		goto end;

	/* To output this BPF program for debug purpose:
	 *
	 *     write(2, program.filter, program.len * sizeof(struct sock_filter));
	 */

	status = prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program);
	if (status < 0)
		goto end;

	status = 0;
end:
	free_program_filter(&program);
	return status;
}

/* List of sysnums handled by PRoot.  */
static FilteredSysnum proot_sysnums[] = {
	{ PR_accept,		FILTER_SYSEXIT },
	{ PR_accept4,		FILTER_SYSEXIT },
	{ PR_access,		0 },
	{ PR_acct,		0 },
	{ PR_bind,		0 },
	{ PR_brk,		FILTER_SYSEXIT },
	{ PR_chdir,		FILTER_SYSEXIT },
	{ PR_chmod,		0 },
	{ PR_chown,		0 },
	{ PR_chown32,		0 },
	{ PR_chroot,		0 },
	{ PR_connect,		0 },
	{ PR_creat,		0 },
	{ PR_execve,		FILTER_SYSEXIT },
	{ PR_execveat,		FILTER_SYSEXIT },
	{ PR_faccessat,		0 },
	{ PR_faccessat2,	FILTER_SYSEXIT },
	{ PR_fchdir,		FILTER_SYSEXIT },
	{ PR_fchmodat,		0 },
	{ PR_fchmodat2,		0 },
	{ PR_fchownat,		0 },
	{ PR_fstatat64,		0 },
	{ PR_futimesat,		0 },
	{ PR_getcwd,		FILTER_SYSEXIT },
	{ PR_getpeername,	FILTER_SYSEXIT },
	{ PR_getsockname,	FILTER_SYSEXIT },
	{ PR_getxattr,		0 },
	{ PR_inotify_add_watch,	0 },
#ifdef __ANDROID__
	{ PR_ioctl,		FILTER_SYSEXIT },
#endif
	{ PR_lchown,		0 },
	{ PR_lchown32,		0 },
	{ PR_lgetxattr,		0 },
#ifdef __ANDROID__
	{ PR_link,		FILTER_SYSEXIT },
	{ PR_linkat,		FILTER_SYSEXIT },
#else
	{ PR_link,		0 },
	{ PR_linkat,		0 },
#endif
	{ PR_listxattr,		0 },
	{ PR_llistxattr,	0 },
	{ PR_lremovexattr,	0 },
	{ PR_lsetxattr,		0 },
	{ PR_lstat,		0 },
	{ PR_lstat64,		0 },
#ifdef __ANDROID__
	{ PR_memfd_create,	0 },
#endif
	{ PR_mkdir,		0 },
	{ PR_mkdirat,		0 },
	{ PR_mknod,		0 },
	{ PR_mknodat,		0 },
	{ PR_mount,		0 },
	{ PR_name_to_handle_at,	0 },
	{ PR_newfstatat,	0 },
	{ PR_oldlstat,		0 },
	{ PR_oldstat,		0 },
	{ PR_open,		0 },
	{ PR_openat,		0 },
	{ PR_openat2,		0 },
	{ PR_open_tree,		0 },
	{ PR_move_mount,	0 },
	{ PR_fspick,		0 },
	{ PR_mount_setattr,	0 },
	{ PR_pivot_root,	0 },
	{ PR_prctl, 		0 },
	{ PR_prlimit64,		FILTER_SYSEXIT },
	{ PR_ptrace,		FILTER_SYSEXIT },
	{ PR_readlink,		FILTER_SYSEXIT },
	{ PR_readlinkat,	FILTER_SYSEXIT },
	{ PR_removexattr,	0 },
	{ PR_rename,		FILTER_SYSEXIT },
	{ PR_renameat,		FILTER_SYSEXIT },
	{ PR_renameat2,		FILTER_SYSEXIT },
	{ PR_rmdir,		0 },
	{ PR_setrlimit,		FILTER_SYSEXIT },
	{ PR_setxattr,		0 },
	{ PR_socketcall,	FILTER_SYSEXIT },
	{ PR_stat,		0 },
	{ PR_stat64,		0 },
	{ PR_statfs,		FILTER_SYSEXIT },
	{ PR_statfs64,		FILTER_SYSEXIT },
	{ PR_statx,		FILTER_SYSEXIT },
	{ PR_swapoff,		0 },
	{ PR_swapon,		0 },
	{ PR_symlink,		0 },
	{ PR_symlinkat,		0 },
	{ PR_truncate,		0 },
	{ PR_truncate64,	0 },
	{ PR_umount,		0 },
	{ PR_umount2,		0 },
#if defined(ARCH_X86_64)
	{ PR_uname,		FILTER_SYSEXIT },
#endif
	{ PR_unlink,		0 },
	{ PR_unlinkat,		0 },
	{ PR_uselib,		0 },
	{ PR_utime,		FILTER_SYSEXIT },
	{ PR_utimensat,		0 },
	{ PR_utimes,		0 },
	{ PR_wait4,		FILTER_SYSEXIT },
	{ PR_waitpid,		FILTER_SYSEXIT },
	FILTERED_SYSNUM_END,
};

/**
 * Add the @new_sysnums to the list of filtered @sysnums, using the
 * given Talloc @context.  This function returns -errno if an error
 * occurred, otherwise 0.
 */
static int merge_filtered_sysnums(TALLOC_CTX *context, FilteredSysnum **sysnums,
				const FilteredSysnum *new_sysnums)
{
	size_t i, j;

	assert(sysnums != NULL);

	if (*sysnums == NULL) {
		/* Start with no sysnums but the terminator.  */
		*sysnums = talloc_array(context, FilteredSysnum, 1);
		if (*sysnums == NULL)
			return -ENOMEM;

		(*sysnums)[0].value = PR_void;
	}

	for (i = 0; new_sysnums[i].value != PR_void; i++) {
		/* Search for the given sysnum.  */
		for (j = 0; (*sysnums)[j].value != PR_void
			 && (*sysnums)[j].value != new_sysnums[i].value; j++)
			;

		if ((*sysnums)[j].value == PR_void) {
			/* No such sysnum, allocate a new entry.  */
			(*sysnums) = talloc_realloc(context, (*sysnums), FilteredSysnum, j + 2);
			if ((*sysnums) == NULL)
				return -ENOMEM;

			(*sysnums)[j] = new_sysnums[i];

			/* The last item is the terminator.  */
			(*sysnums)[j + 1].value = PR_void;
		}
		else
			/* The sysnum is already filtered, merge the
			 * flags.  */
			(*sysnums)[j].flags |= new_sysnums[i].flags;
	}

	return 0;
}

static int collect_filtered_sysnums(TALLOC_CTX *context, const Tracee *tracee,
				FilteredSysnum **filtered_sysnums);

/**
 * Tell the kernel to trace only syscalls handled by PRoot and its
 * extensions.  This filter will be enabled for the given @tracee and
 * all of its future children.  This function returns -errno if an
 * error occurred, otherwise 0.
 */
int enable_syscall_filtering(const Tracee *tracee)
{
	FilteredSysnum *filtered_sysnums = NULL;
	int status;

	assert(tracee != NULL && tracee->ctx != NULL);

	status = collect_filtered_sysnums(tracee->ctx, tracee, &filtered_sysnums);
	if (status < 0)
		return status;

	status = set_seccomp_filters(filtered_sysnums);
	if (status < 0)
		return status;

	return 0;
}

/**
 * Drop the exit stops PRoot only needs on kernels older than the one it
 * runs on.  The faccessat2 exit stop restores the arguments for glibc's
 * fallback to faccessat on ENOSYS (kernels before 5.8, termux 5ba8b95);
 * the statx one emulates the call when it fails, which only a kernel
 * before 4.11 does for want of it - a statx Android's policy traps is
 * emulated from SIGSYS instead.  Both stops re-read and re-translate
 * the path, and are paid on every call.
 */
static void drop_unneeded_exit_stops(void)
{
	static bool done = false;
	struct utsname utsname;
	int major, minor;
	size_t i;

	if (done)
		return;
	done = true;

	if (uname(&utsname) < 0 || sscanf(utsname.release, "%d.%d", &major, &minor) != 2)
		return;

	for (i = 0; proot_sysnums[i].value != PR_void; i++) {
		switch (proot_sysnums[i].value) {
		case PR_faccessat2:
			if (major > 5 || (major == 5 && minor >= 8))
				proot_sysnums[i].flags &= ~FILTER_SYSEXIT;
			break;
		case PR_statx:
			if (major > 4 || (major == 4 && minor >= 11))
				proot_sysnums[i].flags &= ~FILTER_SYSEXIT;
			break;
		default:
			break;
		}
	}
}

static int collect_filtered_sysnums(TALLOC_CTX *context, const Tracee *tracee,
				FilteredSysnum **filtered_sysnums)
{
	Extension *extension;
	int status;

	drop_unneeded_exit_stops();

	/* Add the sysnums required by PRoot to the list of filtered
	 * sysnums.  TODO: only if path translation is required.  */
	status = merge_filtered_sysnums(context, filtered_sysnums, proot_sysnums);
	if (status < 0)
		return status;

	/* Merge the sysnums required by the extensions to the list
	 * of filtered sysnums.  */
	if (tracee->extensions != NULL) {
		LIST_FOREACH(extension, tracee->extensions, link) {
			if (extension->filtered_sysnums == NULL)
				continue;

			status = merge_filtered_sysnums(context, filtered_sysnums,
							extension->filtered_sysnums);
			if (status < 0)
				return status;
		}
	}

	return 0;
}

word_t filtered_sysnum_flags(const Tracee *tracee, Sysnum sysnum)
{
	static word_t flags[PR_NB_SYSNUM];
	static bool initialized = false;
	FilteredSysnum *filtered_sysnums = NULL;
	size_t i;

	if (!initialized) {
		if (collect_filtered_sysnums(NULL, tracee, &filtered_sysnums) < 0) {
			TALLOC_FREE(filtered_sysnums);
			return FILTER_SYSEXIT;
		}
		for (i = 0; filtered_sysnums[i].value != PR_void; i++)
			flags[filtered_sysnums[i].value] |= filtered_sysnums[i].flags;
		TALLOC_FREE(filtered_sysnums);
		initialized = true;
	}

	return sysnum < PR_NB_SYSNUM ? flags[sysnum] : FILTER_SYSEXIT;
}

#else

#include "tracee/tracee.h"
#include "attribute.h"

int enable_syscall_filtering(const Tracee *tracee UNUSED)
{
	return 0;
}

#endif /* defined(HAVE_SECCOMP_FILTER) */
