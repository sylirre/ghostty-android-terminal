/*
 * Minimal stand-in for Samba's libreplace, just enough to compile the
 * vendored talloc.c against bionic with the NDK. The real replace.h
 * polyfills pre-C99 platforms; Android (API 29+) needs none of that, so
 * this only provides the macros talloc.c actually references.
 */
#ifndef _REPLACE_H
#define _REPLACE_H

#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Normally injected by Samba's waf build; must match talloc.h. */
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2

#define _PUBLIC_ __attribute__((visibility("default")))

#ifndef likely
#define likely(x) __builtin_expect(!!(x), 1)
#endif
#ifndef unlikely
#define unlikely(x) __builtin_expect(!!(x), 0)
#endif

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))

/* Features bionic has had since long before our minSdk. */
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_INTPTR_T 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1

#endif /* _REPLACE_H */
