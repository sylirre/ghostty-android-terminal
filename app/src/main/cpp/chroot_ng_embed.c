/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
/*
 * Embedded entry for chroot-ng (native/chroot-ng), the native AArch64
 * userland engine. The standalone binary enters through its own _start
 * (src/rt/start.S), which hands cng_bootstrap the kernel-built initial stack
 * to carve argc/argv/envp/auxv out of. Linked into libterm.so there is no
 * such stack, so this shim reconstructs the same contract in the fork()ed
 * PTY child: argv comes from Java, envp is the process environ, and the
 * auxv is read back from /proc/self/auxv — the kernel's own record of it,
 * which the loader consults for AT_RANDOM and AT_UID..AT_EGID when
 * synthesizing the guest's auxv.
 *
 * Bionic is safe here: the shim runs before any guest code exists, on the
 * child's own TLS. Only past cng_main() does the no-libc rule apply.
 */
#include <fcntl.h>
#include <unistd.h>

/* chroot-ng's real main (native/chroot-ng/src/main.c). */
int cng_main(int argc, char **argv, char **envp, unsigned long *auxv);
/* Loader page granularity, normally set by cng_bootstrap from AT_PAGESZ
 * (native/chroot-ng/src/rt/rt.c). Defaults to 4096; 16 KB-page devices need
 * the real value or every mmap/mprotect rounding in the loader is wrong. */
extern unsigned long cng_page_size;

extern char **environ;

int chroot_ng_main(int argc, char **argv) {
    /* Static is fine: the fork()ed child is single-threaded and enters this
     * once. 64 pairs is far above any kernel's auxv (~20 pairs); a larger
     * one is silently truncated, which only costs optional entries. */
    static unsigned long auxv[130];
    ssize_t n = 0;
    int fd = open("/proc/self/auxv", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        n = read(fd, auxv, sizeof(auxv) - 2 * sizeof(auxv[0]));
        close(fd);
        if (n < 0) n = 0;
    }
    size_t words = ((size_t)n / sizeof(auxv[0])) & ~(size_t)1;
    auxv[words] = 0; /* AT_NULL terminator, also for a truncated read */
    auxv[words + 1] = 0;
    for (unsigned long *a = auxv; a[0] != 0; a += 2) {
        if (a[0] == 6 /* AT_PAGESZ */ && a[1]) cng_page_size = a[1];
    }
    return cng_main(argc, argv, environ, auxv);
}
