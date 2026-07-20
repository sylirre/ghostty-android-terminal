/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

/**
 * What a {@link TerminalSession} spawns: either a command to execve() or an
 * arm64chroot argv to run in-process ({@link TerminalNative#ptyCreateEmulator}).
 * Built by {@link #androidShell} or {@link UserlandRootfs#command}.
 */
public final class SessionCommand {

    /** execve() path; null means "enter arm64chroot_main() with argv". */
    public final String cmd;
    public final String[] argv;
    public final String[] env;
    public final String cwd;
    /** Default tab-title prefix while the shell hasn't set one (OSC 0/2). */
    public final String label;
    /** True for a arm64chroot session (vs. the plain Android shell). */
    public final boolean userland;

    SessionCommand(String cmd, String[] argv, String[] env, String cwd,
            String label, boolean userland) {
        this.cmd = cmd;
        this.argv = argv;
        this.env = env;
        this.cwd = cwd;
        this.label = label;
        this.userland = userland;
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
                homeDir, labelForShell("/system/bin/sh"), false);
    }

    /**
     * Default tab label for a login shell: the basename of its executable path,
     * e.g. {@code /system/bin/sh -> "sh"}, {@code /bin/bash -> "bash"}. Any
     * arguments must already be stripped by the caller. Falls back to
     * {@code "sh"} for a null/empty or slash-only path.
     */
    static String labelForShell(String shellPath) {
        if (shellPath == null) return "sh";
        String p = shellPath.trim();
        int end = p.length();
        while (end > 0 && p.charAt(end - 1) == '/') end--; // drop trailing slashes
        String base = p.substring(p.lastIndexOf('/', end - 1) + 1, end);
        return base.isEmpty() ? "sh" : base;
    }
}
