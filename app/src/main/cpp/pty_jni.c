/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
/*
 * PTY creation and process control for TerminalSession.
 *
 * Java owns the master fd (via ParcelFileDescriptor) for I/O; this file only
 * covers what Java can't: openpt/fork/exec, TIOCSWINSZ, waitpid, kill.
 *
 * Two spawn flavors share the PTY/fork setup: execve() for Android shells,
 * and an in-process userland engine for userland sessions — arm64chroot_main()
 * (all ABIs) or chroot_ng_main() (arm64-v8a only), selected by argv[0].
 * Both engines are linked into libterm.so and entered directly in the
 * fork()ed child: Android's W^X policy (targetSdk >= 29) forbids execve() of
 * anything under app data, and neither engine ever execs a guest binary
 * (guest execve is an in-process reload in both), so there is no loader and
 * nothing to exec (see native/arm64chroot, native/chroot-ng).
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

extern char **environ;

/* arm64chroot's main(), renamed under ANDROID_JNI (native/arm64chroot/
 * src/main.c). Returns the guest exit code. */
extern int arm64chroot_main(int argc, char **argv);

#ifdef HAVE_CHROOT_NG
/* chroot-ng's embedded entry (chroot_ng_embed.c → native/chroot-ng), the
 * native AArch64 engine. Returns the guest exit code. */
extern int chroot_ng_main(int argc, char **argv);
#endif

/* arm64emu's entry (native/arm64emu, built as its own libarm64emu.so — see
 * CMakeLists.txt for why it is not linked into this library). Returns the
 * guest machine's exit code. */
extern int arm64emu_main(int argc, char **argv);

/* Guest terminals a VM may have: ttyAMA0 plus hvc0..hvc(MAX-1). The emulator's
 * own ceiling is 8 virtio-consoles; Linux stops at 16 hvc devices. */
#define VM_MAX_HVC 8

static int throw_errno(JNIEnv *env, const char *what) {
    char msg[256];
    snprintf(msg, sizeof(msg), "%s: %s", what, strerror(errno));
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), msg);
    return -1;
}

/* Copies a Java String[] into a NULL-terminated char*[] for execve. */
static char **to_cstr_array(JNIEnv *env, jobjectArray arr) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **out = calloc(n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

/* Frees an array built by to_cstr_array, strings included. */
static void free_cstr_array(char **a) {
    if (!a) return;
    for (char **p = a; *p; p++) free(*p);
    free(a);
}

/*
 * Opens a PTY and forks a child on it. If cmd is non-NULL the child
 * execve()s it; otherwise the child enters arm64chroot_main(argv) in-process.
 * Returns the master fd, or throws and returns -1.
 */
static jint spawn_on_pty(JNIEnv *env, jstring jcmd, jobjectArray jargs,
                         jobjectArray jenv, jstring jcwd, jint cols, jint rows,
                         jint cell_w, jint cell_h, jintArray jpid) {
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) return throw_errno(env, "open /dev/ptmx");

    char slave_path[64];
    if (grantpt(master) != 0 || unlockpt(master) != 0 ||
        ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        close(master);
        return throw_errno(env, "ptsname");
    }

    /* Pixel fields too: programs like Kitty's icat read them via TIOCGWINSZ
     * to size images, and otherwise give up reporting "screen sizes in
     * pixels". They must be in the initial winsize because the session spawns
     * at its final grid size and never resizes (see TerminalSession.resize). */
    struct winsize ws = {.ws_row = (unsigned short)rows,
                         .ws_col = (unsigned short)cols,
                         .ws_xpixel = (unsigned short)(cols * cell_w),
                         .ws_ypixel = (unsigned short)(rows * cell_h)};
    ioctl(master, TIOCSWINSZ, &ws);

    const char *cmd = jcmd ? (*env)->GetStringUTFChars(env, jcmd, NULL) : NULL;
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    char **argv = to_cstr_array(env, jargs);
    char **envp = to_cstr_array(env, jenv);

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno; /* the cleanup below may clobber it */
        close(master);
        free_cstr_array(argv);
        free_cstr_array(envp);
        if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        errno = err;
        return throw_errno(env, "fork");
    }

    if (pid == 0) {
        setsid();
        int slave = open(slave_path, O_RDWR); /* becomes controlling tty */
        if (slave < 0) _exit(127);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        /* The master belongs to the parent. O_CLOEXEC would drop it on the
         * execve path, but the in-process engines never exec: without this the
         * emulator carries a writable descriptor for its own tty for the whole
         * session, and the pair only half-closes when the parent hangs up. */
        close(master);
        if (cwd) chdir(cwd);
        /* fork() copies the calling (ART) thread's signal mask and signal
         * dispositions. execve() resets handled dispositions (though not
         * ignored ones), but the in-process engines keep the inherited ART
         * handlers — harmless for arm64chroot (guest faults are detected in
         * emulation, and it installs its own handlers), fatal for chroot-ng,
         * whose guests fault natively: an inherited ART SIGSEGV/SIGQUIT
         * handler would try to build a Java crash report in a process that
         * no longer runs ART. Clear both mask and dispositions for every
         * flavor (SIGKILL/SIGSTOP refusals are expected and ignored). */
        sigset_t mask;
        sigemptyset(&mask);
        sigprocmask(SIG_SETMASK, &mask, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);
        if (cmd) {
            execve(cmd, argv, envp);
        } else {
            int argc = 0;
            while (argv[argc] != NULL) argc++;
            /* Both engines give the guest a clean environment, inheriting only
             * TERM/COLORTERM from this host environ; the rest of the guest env
             * is set explicitly via -E flags in the argv (envp is just
             * PATH=/system/bin plus TMPDIR). Java picks the engine by argv[0];
             * an argv[0] this build has no engine for falls through to
             * arm64chroot, whose parser rejects the unknown flags loudly. */
            environ = envp;
#ifdef HAVE_CHROOT_NG
            if (argc > 0 && strcmp(argv[0], "chroot-ng") == 0)
                _exit(chroot_ng_main(argc, argv)); /* guest exit code */
#endif
            _exit(arm64chroot_main(argc, argv)); /* returns the guest exit code */
        }
        _exit(127);
    }

    free_cstr_array(argv);
    free_cstr_array(envp);
    if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);

    jint pid_out = (jint)pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &pid_out);
    return master;
}

/*
 * Spawns the full-system emulator with a channel per guest terminal.
 *
 * Unlike the two userland engines, one VM is not one session: it is a machine
 * that several sessions attach to. So there is no PTY here at all — the guest's
 * own ttyAMA0/hvcN *are* the terminals, complete with their own line discipline
 * and job control, and a host PTY in front of them would only add a second one.
 * Each gets a socketpair instead: one bidirectional byte channel, which is
 * exactly what the emulator's --console-fd takes.
 *
 * fds_out receives 1 + n_hvc + 2 descriptors: [0] ttyAMA0, [1..n_hvc] hvc0.., then
 * the control channel (window sizes, which a socketpair cannot report) and the
 * read end of the emulator's diagnostics. The caller owns all of them.
 */
static jint vm_start(JNIEnv *env, jobjectArray jargs, jobjectArray jenv,
                     jstring jcwd, jint n_hvc, jintArray jfds, jintArray jpid) {
    if (n_hvc < 0 || n_hvc > VM_MAX_HVC) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                         "vmStart: hvc count out of range");
        return -1;
    }
    int n_term = 1 + (int)n_hvc;              /* ttyAMA0 + hvc0..hvc(n_hvc-1) */
    int n_chan = n_term + 1;                  /* ... + the control channel */

    /* near[] stay here, far[] go to the child. The child dup2()s its ends onto
     * 3..3+n_term, and socketpair hands out exactly those low numbers, so park
     * them well above first — otherwise the shuffle overwrites a descriptor it
     * has not copied yet. */
    int near[VM_MAX_HVC + 2], far[VM_MAX_HVC + 2];
    for (int i = 0; i < n_chan; i++) { near[i] = far[i] = -1; }
    for (int i = 0; i < n_chan; i++) {
        int sv[2];
        if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) goto fail;
        near[i] = sv[0];
        far[i] = fcntl(sv[1], F_DUPFD, 32);
        close(sv[1]);
        if (far[i] < 0) goto fail;
        fcntl(near[i], F_SETFD, FD_CLOEXEC);  /* never inherited by a shell spawn */
    }
    int logp[2];
    if (pipe(logp) != 0) goto fail;
    fcntl(logp[0], F_SETFD, FD_CLOEXEC);

    /* The emulator's own args, then the bindings for the channels above. The
     * descriptor numbers are this function's business, not the caller's. */
    char **base = to_cstr_array(env, jargs);
    int base_n = 0;
    while (base[base_n]) base_n++;
    char **argv = calloc((size_t)base_n + 2 * (size_t)n_term + 3, sizeof(char *));
    int n = 0;
    for (int i = 0; i < base_n; i++) argv[n++] = base[i];
    for (int i = 0; i < n_term; i++) {
        char b[48];
        if (i == 0) snprintf(b, sizeof b, "ttyAMA0=%d", 3 + i);
        else        snprintf(b, sizeof b, "hvc%d=%d", i - 1, 3 + i);
        argv[n++] = strdup("--console-fd");
        argv[n++] = strdup(b);
    }
    {
        char b[24];
        snprintf(b, sizeof b, "%d", 3 + n_term);
        argv[n++] = strdup("--ctrl-fd");
        argv[n++] = strdup(b);
    }
    argv[n] = NULL;
    free(base);                                /* the strings moved into argv */

    char **envp = to_cstr_array(env, jenv);
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno; /* the cleanup below may clobber it */
        close(logp[0]);
        close(logp[1]);
        free_cstr_array(argv);
        free_cstr_array(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        errno = err;
        goto fail;
    }

    if (pid == 0) {
        /* Its own session: no controlling terminal to inherit, and one process
         * group the whole machine can be signalled through. */
        setsid();
        if (cwd) chdir(cwd);
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) dup2(devnull, STDIN_FILENO);
        /* The emulator's diagnostics — device wiring, JIT fallbacks, warnings —
         * would otherwise go nowhere in an app process. Standard output is
         * merged in: with every console bound, nothing else writes there. */
        dup2(logp[1], STDOUT_FILENO);
        dup2(logp[1], STDERR_FILENO);
        for (int i = 0; i < n_chan; i++) dup2(far[i], 3 + i);
        /* Everything above the bindings belongs to the parent: our ends of every
         * pair, the parked copies, and whatever else this process had open. No
         * exec happens here, so FD_CLOEXEC cannot do it for us. */
        for (int f = 3 + n_chan; f < 256; f++) close(f);
        /* Same reasoning as the exec path below, and it matters more here: this
         * child never execs, so nothing else would reset the ART signal
         * dispositions it inherited. */
        sigset_t mask;
        sigemptyset(&mask);
        sigprocmask(SIG_SETMASK, &mask, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);
        environ = envp;
        _exit(arm64emu_main(n, argv));
    }

    for (int i = 0; i < n_chan; i++) close(far[i]);
    close(logp[1]);
    free_cstr_array(argv);
    free_cstr_array(envp);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);

    jint out[VM_MAX_HVC + 3];
    for (int i = 0; i < n_chan; i++) out[i] = near[i];
    out[n_chan] = logp[0];
    (*env)->SetIntArrayRegion(env, jfds, 0, n_chan + 1, out);
    jint pid_out = (jint)pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &pid_out);
    return 0;

fail:
    for (int i = 0; i < n_chan; i++) {
        if (near[i] >= 0) close(near[i]);
        if (far[i] >= 0) close(far[i]);
    }
    return throw_errno(env, "vmStart");
}

JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_vmStart(
    JNIEnv *env, jclass clazz, jobjectArray jargs, jobjectArray jenv,
    jstring jcwd, jint n_hvc, jintArray jfds, jintArray jpid) {
    (void)clazz;
    return vm_start(env, jargs, jenv, jcwd, n_hvc, jfds, jpid);
}

JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptyCreate(
    JNIEnv *env, jclass clazz, jstring jcmd, jobjectArray jargs,
    jobjectArray jenv, jstring jcwd, jint cols, jint rows, jint cell_w,
    jint cell_h, jintArray jpid) {
    (void)clazz;
    return spawn_on_pty(env, jcmd, jargs, jenv, jcwd, cols, rows, cell_w, cell_h,
                        jpid);
}

JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptyCreateEmulator(
    JNIEnv *env, jclass clazz, jobjectArray jargs, jobjectArray jenv,
    jstring jcwd, jint cols, jint rows, jint cell_w, jint cell_h,
    jintArray jpid) {
    (void)clazz;
    return spawn_on_pty(env, NULL, jargs, jenv, jcwd, cols, rows, cell_w, cell_h,
                        jpid);
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptySetSize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows, jint cell_w,
    jint cell_h) {
    (void)env; (void)clazz;
    struct winsize ws = {.ws_row = (unsigned short)rows,
                         .ws_col = (unsigned short)cols,
                         .ws_xpixel = (unsigned short)(cols * cell_w),
                         .ws_ypixel = (unsigned short)(rows * cell_h)};
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptyHangupForeground(
    JNIEnv *env, jclass clazz, jint fd, jint fallback_pid) {
    (void)env; (void)clazz;

    pid_t pgrp = 0;
    if (ioctl(fd, TIOCGPGRP, &pgrp) == 0 && pgrp > 0 && pgrp != getpgrp()) {
        if (kill(-pgrp, SIGHUP) == 0) {
            kill(-pgrp, SIGCONT);
            return;
        }
    }

    if (fallback_pid > 0) {
        pgrp = getpgid((pid_t)fallback_pid);
        if (pgrp > 0 && pgrp != getpgrp() && kill(-pgrp, SIGHUP) == 0) {
            kill(-pgrp, SIGCONT);
        } else {
            kill((pid_t)fallback_pid, SIGHUP);
            kill((pid_t)fallback_pid, SIGCONT);
        }
    }
}

/* Blocks until the child exits. Returns exit code, or -signal if killed. */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_processWaitFor(
    JNIEnv *env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_processKill(
    JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void)env; (void)clazz;
    kill((pid_t)pid, sig);
}

/* Whether this libterm.so carries the chroot-ng engine (arm64-v8a builds
 * only). Java gates the engine setting and the argv[0] choice on this, so a
 * persisted "chroot-ng" preference can never reach an x86_64 build's child. */
JNIEXPORT jboolean JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_hasChrootNg(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
#ifdef HAVE_CHROOT_NG
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
