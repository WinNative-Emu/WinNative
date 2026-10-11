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

#include <assert.h>    /* assert(3), */
#include <errno.h>     /* E*, */
#include <fcntl.h>     /* open(2), O_*, */
#include <limits.h>    /* PATH_MAX, */
#include <stdio.h>     /* sscanf(3), */
#include <string.h>    /* string(3), */
#include <sys/param.h> /* MAXSYMLINKS, */
#include <sys/stat.h>  /* lstat(2), S_ISREG(), */
#include <sys/types.h> /* pid_t */
#include <unistd.h>    /* access(2), lstat(2), */

#include "path/binding.h"
#include "path/canon.h"
#include "path/glue.h"
#include "path/path.h"
#include "path/proc.h"

/**
 * Put an end-of-string ('\0') right before the last component of @path.
 */
static inline void pop_component(char *path) {
  int offset;

  offset = strlen(path) - 1;
  assert(offset >= 0);

  /* Don't pop over "/", it doesn't mean anything. */
  if (offset == 0) {
    assert(path[0] == '/' && path[1] == '\0');
    return;
  }

  /* Skip trailing path separators. */
  while (offset > 1 && path[offset] == '/')
    offset--;

  /* Search for the previous path separator. */
  while (offset > 1 && path[offset] != '/')
    offset--;

  /* Cut the end of the string before the last component. */
  path[offset] = '\0';
  assert(path[0] == '/');
}

/**
 * Copy in @component the first path component pointed to by @cursor,
 * this later is updated to point to the next component for a further
 * call. This function returns:
 *
 *     - -errno if an error occured.
 *
 *     - FINAL_SLASH if it the last component of the path but we
 *       really expect a directory.
 *
 *     - FINAL_NORMAL if it the last component of the path.
 *
 *     - 0 otherwise.
 */
static inline Finality next_component(char component[NAME_MAX],
                                      const char **cursor) {
  const char *start;
  ptrdiff_t length;
  bool want_dir;

  /* Skip leading path separators. */
  while (**cursor == '/')
    (*cursor)++;

  /* Find the next component. */
  start = *cursor;
  while (**cursor != '\0' && **cursor != '/')
    (*cursor)++;
  length = *cursor - start;

  if (length >= NAME_MAX)
    return -ENAMETOOLONG;

  /* Extract the component. */
  strncpy(component, start, length);
  component[length] = '\0';

  /* Check if a [link to a] directory is expected. */
  want_dir = (**cursor == '/');

  /* Skip trailing path separators. */
  while (**cursor == '/')
    (*cursor)++;

  if (**cursor == '\0')
    return (want_dir ? FINAL_SLASH : FINAL_NORMAL);

  return NOT_FINAL;
}

/**
 * Resolve bindings (if any) in @guest_path and copy the translated
 * path into @host_path.  Also, this function checks that a non-final
 * component is either a directory (returned value is 0) or a symlink
 * (returned value is 1), otherwise it returns -errno (-ENOENT or
 * -ENOTDIR).
 */
static inline int substitute_binding_stat(Tracee *tracee, Finality finality,
                                          const char guest_path[PATH_MAX],
                                          char host_path[PATH_MAX]) {
  struct stat statl;
  int status;

  strcpy(host_path, guest_path);
  status = substitute_binding(tracee, GUEST, host_path);
  if (status < 0)
    return status;

  statl.st_mode = 0;
  status = lstat(host_path, &statl);

  /* Build the glue between the hostfs and the guestfs during
   * the initialization of a binding.  */
  if (status < 0 && tracee->glue_type != 0) {
    statl.st_mode = build_glue(tracee, guest_path, host_path, finality);
    if (statl.st_mode == 0)
      status = -1;
  }

  /* Return an error if a non-final component isn't a directory
   * nor a symlink.  The error depends on why the component
   * could not be accessed (ENOENT, EACCES, ...), otherwise the
   * error is "Not a directory".  */
  if (!IS_FINAL(finality) && !S_ISDIR(statl.st_mode) && !S_ISLNK(statl.st_mode))
    return (status < 0 ? -ENOENT : -ENOTDIR);

  return (S_ISLNK(statl.st_mode) ? 1 : 0);
}

/* Below this many directories, lstat(2)-ing each costs less than
 * resolve_parent_at_once().  */
#define RESOLVE_AT_ONCE_MIN_DEPTH 3

/**
 * Canonicalize at once the directory holding the last component of
 * the absolute @user_path, instead of with an lstat(2) per component:
 * the kernel resolves it, and it is canonical if the kernel's path is
 * the one PRoot would build, ie. no link on the way.  Only for a
 * lexically clean path under the rootfs binding alone.  This function
 * returns the offset of the last component in @user_path and puts the
 * directory in @guest_path, or returns 0 when the component-wise walk
 * has to be used.
 */
static size_t resolve_parent_at_once(Tracee *tracee, const char *user_path,
                                     char guest_path[PATH_MAX]) {
  char parent[PATH_MAX];
  char expected[PATH_MAX];
  char resolved[PATH_MAX];
  char link[32];
  const Binding *binding;
  const char *last;
  const char *cursor;
  size_t parent_length;
  size_t prefix_length;
  size_t expected_length;
  ssize_t length;
  int depth;
  int fd;

  if (tracee->glue_type != 0)
    return 0;

  last = strrchr(user_path, '/');
  if (last == NULL || last == user_path || last[1] == '\0' ||
      strcmp(last + 1, ".") == 0 || strcmp(last + 1, "..") == 0)
    return 0;

  parent_length = last - user_path;
  if (parent_length >= PATH_MAX)
    return 0;

  /* No empty, "." or ".." component.  */
  depth = 0;
  for (cursor = user_path; cursor < last;) {
    const char *end;

    end = memchr(cursor + 1, '/', last - cursor);
    if (end == NULL)
      end = last;
    if (end == cursor + 1 || (end == cursor + 2 && cursor[1] == '.') ||
        (end == cursor + 3 && cursor[1] == '.' && cursor[2] == '.'))
      return 0;
    depth++;
    cursor = end;
  }
  if (depth < RESOLVE_AT_ONCE_MIN_DEPTH)
    return 0;

  memcpy(parent, user_path, parent_length);
  parent[parent_length] = '\0';

  /* Bindings are ordered deepest first, so the rootfs binding
   * matching means no other binding is on the way.  */
  binding = get_binding(tracee, GUEST, parent);
  if (binding == NULL || binding->guest.length != 1)
    return 0;

  /* A rootfs at "/" adds no prefix.  */
  prefix_length = (binding->host.length == 1 ? 0 : binding->host.length);
  expected_length = prefix_length + parent_length;
  if (expected_length >= PATH_MAX)
    return 0;
  memcpy(expected, binding->host.path, prefix_length);
  strcpy(expected + prefix_length, parent);

  fd = open(expected, O_PATH | O_DIRECTORY | O_CLOEXEC);
  if (fd < 0)
    return 0;

  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  length = readlink(link, resolved, sizeof(resolved));
  close(fd);

  if (length < 0 || (size_t)length != expected_length ||
      memcmp(resolved, expected, expected_length) != 0)
    return 0;

  strcpy(guest_path, parent);
  return last + 1 - user_path;
}

/**
 * Copy in @guest_path the canonicalization (see `man 3 realpath`) of
 * @user_path regarding to @tracee->root.  The path to canonicalize
 * could be either absolute or relative to @guest_path. When the last
 * component of @user_path is a link, it is dereferenced only if
 * @deref_final is true -- it is useful for syscalls like lstat(2).
 * The parameter @recursion_level should be set to 0 unless you know
 * what you are doing. This function returns -errno if an error
 * occured, otherwise it returns 0.
 */
int canonicalize(Tracee *tracee, const char *user_path, bool deref_final,
                 char guest_path[PATH_MAX], unsigned int recursion_level) {
  char scratch_path[PATH_MAX];
  char host_path[PATH_MAX];
  Finality finality;
  const char *cursor;
  size_t offset;
  int status;

  /* Avoid infinite loop on circular links.  */
  if (recursion_level > MAXSYMLINKS)
    return -ELOOP;

  if (user_path[0] != '/') {
    /* Ensure 'guest_path' contains an absolute base of
     * the relative `user_path`.  */
    if (guest_path[0] != '/')
      return -EINVAL;
  } else
    strcpy(guest_path, "/");

  offset = (user_path[0] == '/'
                ? resolve_parent_at_once(tracee, user_path, guest_path)
                : 0);
  if (offset == 0) {
    /* Resolve bindings for the initial '/' component or user_path,
     * which is not handled in the loop below.
     * In particular HOST_PATH extensions are called from there.  */
    status = substitute_binding_stat(tracee, NOT_FINAL, guest_path, host_path);
    if (status < 0)
      return status;
  }

  /* Canonicalize recursely 'user_path' into 'guest_path'.  */
  cursor = user_path + offset;
  finality = NOT_FINAL;
  while (!IS_FINAL(finality)) {
    Comparison comparison;
    char component[NAME_MAX];

    finality = next_component(component, &cursor);
    status = (int)finality;
    if (status < 0)
      return status;

    if (strcmp(component, ".") == 0) {
      if (IS_FINAL(finality))
        finality = FINAL_DOT;
      continue;
    }

    if (strcmp(component, "..") == 0) {
      pop_component(guest_path);
      if (IS_FINAL(finality))
        finality = FINAL_SLASH;
      continue;
    }

    join_paths(scratch_path, guest_path, component);

    /* A final component that is not dereferenced needs no
     * lstat(2): whether it is a link only matters to follow
     * it.  Glue is still built from that lstat(2).  */
    if (finality == FINAL_NORMAL && !deref_final && tracee->glue_type == 0) {
      strcpy(host_path, scratch_path);
      status = substitute_binding(tracee, GUEST, host_path);
      if (status < 0)
        return status;

      strcpy(scratch_path, guest_path);
      join_paths(guest_path, scratch_path, component);
      continue;
    }

    /* Resolve bindings and check that a non-final
     * component exists and either is a directory or is a
     * symlink.  For this latter case, we check that the
     * symlink points to a directory once it is
     * canonicalized, at the end of this loop.  */
    status = substitute_binding_stat(tracee, finality, scratch_path, host_path);
    if (status < 0)
      return status;

    /* Nothing special to do if it's not a link or if we
     * explicitly ask to not dereference 'user_path', as
     * required by syscalls like lstat(2). Obviously, this
     * later condition does not apply to intermediate path
     * components.  Errors are explicitly ignored since
     * they should be handled by the caller. */
    if (status <= 0 || (finality == FINAL_NORMAL && !deref_final)) {
      strcpy(scratch_path, guest_path);
      join_paths(guest_path, scratch_path, component);
      continue;
    }

    /* It's a link, so we have to dereference *and*
     * canonicalize to ensure we are not going outside the
     * new root.  */
    comparison = compare_paths("/proc", guest_path);
    switch (comparison) {
    case PATHS_ARE_EQUAL:
    case PATH1_IS_PREFIX:
      /* Some links in "/proc" are generated
       * dynamically by the kernel.  PRoot has to
       * emulate some of them.  */
      status = readlink_proc(tracee, scratch_path, guest_path, component,
                             comparison);
      switch (status) {
      case CANONICALIZE:
        /* The symlink is already dereferenced,
         * now canonicalize it.  */
        goto canon;

      case DONT_CANONICALIZE:
        /* If and only very final, this symlink
         * shouldn't be dereferenced nor canonicalized.  */
        if (finality == FINAL_NORMAL) {
          strcpy(guest_path, scratch_path);
          return 0;
        }
        break;

      default:
        if (status < 0)
          return status;
      }

    default:
      break;
    }

    status = readlink(host_path, scratch_path, sizeof(scratch_path));
    if (status < 0)
      return status;
    else if (status == sizeof(scratch_path))
      return -ENAMETOOLONG;
    scratch_path[status] = '\0';

    /* Remove the leading "root" part if needed, it's
     * useful for "/proc/self/cwd/" for instance.  */
    status = detranslate_path(tracee, scratch_path, host_path);
    if (status < 0)
      return status;

  canon:
    /* Canonicalize recursively the referee in case it
     * is/contains a link, moreover if it is not an
     * absolute link then it is relative to
     * 'guest_path'. */
    status = canonicalize(tracee, scratch_path, true, guest_path,
                          recursion_level + 1);
    if (status < 0)
      return status;

    /* Check that a non-final canonicalized/dereferenced
     * symlink exists and is a directory.  */
    status = substitute_binding_stat(tracee, finality, guest_path, host_path);
    if (status < 0)
      return status;

    /* Here, 'guest_path' shouldn't be a symlink anymore,
     * unless it is a named file descriptor.  */
    assert(status != 1 || sscanf(guest_path, "/proc/%*d/fd/%d", &status) == 1);
  }

  /* At the exit stage of the first level of recursion,
   * `guest_path` is fully canonicalized but a terminating '/'
   * or a terminating '.' may be required to keep the initial
   * semantic of `user_path`.  */
  if (recursion_level == 0) {
    switch (finality) {
    case FINAL_NORMAL:
      break;

    case FINAL_SLASH:
      strcpy(scratch_path, guest_path);
      join_paths(guest_path, scratch_path, "");
      break;

    case FINAL_DOT:
      strcpy(scratch_path, guest_path);
      join_paths(guest_path, scratch_path, ".");
      break;

    default:
      assert(0);
    }
  }

  return 0;
}
