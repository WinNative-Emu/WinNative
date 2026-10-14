/*
 * Hard links, which Android denies apps, for the one caller that asks.
 *
 * localedef names a finished locale archive by linking its temporary file into place, so it fails
 * here and the session never gets the archive (see prepare_locale in winnative-session). With
 * WN_LINK_RENAME=1, which the session sets for localedef alone, a refused link becomes a rename
 * that will not replace an existing name, which is all the link promised there. Every other link
 * keeps the real answer: a program that links a file into place and then deletes the original
 * would otherwise lose it.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int refused(int error) { return error == EPERM || error == EACCES; }

static int moves_instead(void) {
  const char *v = getenv("WN_LINK_RENAME");
  return v != NULL && strcmp(v, "1") == 0;
}

/* The answer to link(oldpath, newpath), given the real call's. */
__attribute__((visibility("hidden"))) int wn_link_answer(const char *oldpath, const char *newpath, int result) {
  if (result == 0 || !refused(errno) || !moves_instead()) return result;
  return renameat2(AT_FDCWD, oldpath, AT_FDCWD, newpath, RENAME_NOREPLACE);
}

/* 64-bit builds wrap link() in dircache.c, which hands its result to wn_link_answer. */
#if __SIZEOF_POINTER__ != 8
int link(const char *oldpath, const char *newpath) {
  static int (*real_link)(const char *, const char *);
  if (real_link == NULL) real_link = dlsym(RTLD_NEXT, "link");
  return wn_link_answer(oldpath, newpath, real_link(oldpath, newpath));
}
#endif

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
  static int (*real_linkat)(int, const char *, int, const char *, int);
  if (real_linkat == NULL) real_linkat = dlsym(RTLD_NEXT, "linkat");
  int result = real_linkat(olddirfd, oldpath, newdirfd, newpath, flags);
  if (result != 0 && refused(errno) && flags == 0 && moves_instead())
    return renameat2(olddirfd, oldpath, newdirfd, newpath, RENAME_NOREPLACE);
  return result;
}
