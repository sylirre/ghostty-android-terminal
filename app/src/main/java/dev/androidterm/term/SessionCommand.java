package dev.androidterm.term;

/**
 * What a {@link TerminalSession} spawns: either a command to execve() or a
 * PRoot argv to run in-process ({@link TerminalNative#ptyCreateProot}).
 * Built by {@link #androidShell} or {@link DebianRootfs#command}.
 */
public final class SessionCommand {

    /** execve() path; null means "enter proot_main() with argv". */
    public final String cmd;
    public final String[] argv;
    public final String[] env;
    public final String cwd;
    /** Default tab-title prefix while the shell hasn't set one (OSC 0/2). */
    public final String label;

    SessionCommand(String cmd, String[] argv, String[] env, String cwd,
            String label) {
        this.cmd = cmd;
        this.argv = argv;
        this.env = env;
        this.cwd = cwd;
        this.label = label;
    }

    /**
     * /system/bin/sh with PATH=/system/bin.
     *
     * @param homeDir HOME and initial working directory (app files dir —
     *                the only generally writable place).
     * @param tmpDir  TMPDIR (app cache dir).
     */
    public static SessionCommand androidShell(String homeDir, String tmpDir) {
        String[] env = {
                "PATH=/system/bin",
                "HOME=" + homeDir,
                "TMPDIR=" + tmpDir,
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "ANDROID_ROOT=/system",
                "ANDROID_DATA=/data",
        };
        return new SessionCommand("/system/bin/sh", new String[] {"sh"}, env,
                homeDir, "sh");
    }
}
