/*
 * PTY creation and process control for TerminalSession.
 *
 * Java owns the master fd (via ParcelFileDescriptor) for I/O; this file only
 * covers what Java can't: openpt/fork/exec, TIOCSWINSZ, waitpid, kill.
 *
 * Two spawn flavors share the PTY/fork setup: execve() for Android shells,
 * and proot_main() for Debian sessions. PRoot is linked into libterm.so and
 * entered directly in the fork()ed child because Android's W^X policy
 * (targetSdk >= 29) forbids execve() of anything under app data, so there
 * is no proot binary to exec (see native/proot/ANDROID.md).
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

extern char **environ;

/* PRoot's main(), renamed under PROOT_JNI (native/proot/src/cli/cli.c). */
extern int proot_main(int argc, char *const argv[]);

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

/*
 * Opens a PTY and forks a child on it. If cmd is non-NULL the child
 * execve()s it; otherwise the child enters proot_main(argv) in-process.
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
        close(master);
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
        if (cwd) chdir(cwd);
        /* fork() copies the calling (ART) thread's signal mask and execve()
         * does not reset it; both the shell and PRoot expect a clear one. */
        sigset_t mask;
        sigemptyset(&mask);
        sigprocmask(SIG_SETMASK, &mask, NULL);
        if (cmd) {
            execve(cmd, argv, envp);
        } else {
            int argc = 0;
            while (argv[argc] != NULL) argc++;
            environ = envp; /* proot_main reads PROOT_* via getenv */
            proot_main(argc, argv); /* never returns: _exit()s */
        }
        _exit(127);
    }

    for (char **p = argv; *p; p++) free(*p);
    for (char **p = envp; *p; p++) free(*p);
    free(argv);
    free(envp);
    if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);

    jint pid_out = (jint)pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &pid_out);
    return master;
}

JNIEXPORT jint JNICALL
Java_sh_easycli_proot_term_TerminalNative_ptyCreate(
    JNIEnv *env, jclass clazz, jstring jcmd, jobjectArray jargs,
    jobjectArray jenv, jstring jcwd, jint cols, jint rows, jint cell_w,
    jint cell_h, jintArray jpid) {
    (void)clazz;
    return spawn_on_pty(env, jcmd, jargs, jenv, jcwd, cols, rows, cell_w, cell_h,
                        jpid);
}

JNIEXPORT jint JNICALL
Java_sh_easycli_proot_term_TerminalNative_ptyCreateProot(
    JNIEnv *env, jclass clazz, jobjectArray jargs, jobjectArray jenv,
    jstring jcwd, jint cols, jint rows, jint cell_w, jint cell_h,
    jintArray jpid) {
    (void)clazz;
    return spawn_on_pty(env, NULL, jargs, jenv, jcwd, cols, rows, cell_w, cell_h,
                        jpid);
}

JNIEXPORT void JNICALL
Java_sh_easycli_proot_term_TerminalNative_ptySetSize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows, jint cell_w,
    jint cell_h) {
    (void)env; (void)clazz;
    struct winsize ws = {.ws_row = (unsigned short)rows,
                         .ws_col = (unsigned short)cols,
                         .ws_xpixel = (unsigned short)(cols * cell_w),
                         .ws_ypixel = (unsigned short)(rows * cell_h)};
    ioctl(fd, TIOCSWINSZ, &ws);
}

/* Blocks until the child exits. Returns exit code, or -signal if killed. */
JNIEXPORT jint JNICALL
Java_sh_easycli_proot_term_TerminalNative_processWaitFor(
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
Java_sh_easycli_proot_term_TerminalNative_processKill(
    JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void)env; (void)clazz;
    kill((pid_t)pid, sig);
}
