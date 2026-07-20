/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

/**
 * Immutable bundle of the Userland-specific inputs read from user settings and
 * turned into an arm64chroot command line by {@link UserlandRootfs#command}.
 * Grouped into one object so the session-spawn call chain
 * ({@link SessionManager#create} → {@code command}) does not grow a scalar
 * parameter per setting. The raw setting strings are resolved and validated
 * against the installed rootfs inside {@code command} (see
 * {@link UserlandIdentity}); this object only carries them.
 */
public final class UserlandOptions {

    /** Default guest {@code PATH} used until the user sets an "Executable path". */
    public static final String DEFAULT_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** Guest-absolute path of the login shell (e.g. {@code /bin/bash}). */
    public final String loginShell;
    /** When true, Android shared storage is bound under /mnt for this session. */
    public final boolean bindExternalStorage;
    /** Raw "User identity" setting: {@code user}, {@code user:group}, {@code uid} or {@code uid:gid}. */
    public final String identity;
    /** Raw "Home directory" setting; an empty value derives home from /etc/passwd. */
    public final String home;
    /** Raw "Working directory" setting; an empty value derives from /etc/passwd. */
    public final String workDir;
    /** Raw "Locale" setting passed as {@code LANG}; an empty value falls back to {@code C.UTF-8}. */
    public final String locale;
    /** Raw "Executable path" setting passed as {@code PATH}; an empty value falls back to {@link #DEFAULT_PATH}. */
    public final String path;
    /** When true, /proc is private to this session (no {@code --shared-proc}). */
    public final boolean isolateProc;

    public UserlandOptions(String loginShell, boolean bindExternalStorage,
            String identity, String home, String workDir, String locale,
            String path, boolean isolateProc) {
        this.loginShell = loginShell;
        this.bindExternalStorage = bindExternalStorage;
        this.identity = identity;
        this.home = home;
        this.workDir = workDir;
        this.locale = locale;
        this.path = path;
        this.isolateProc = isolateProc;
    }

    /**
     * Options matching the {@code AppSettings} first-use defaults, so a caller
     * that only has a login shell — the convenience {@link UserlandRootfs#command
     * command(Context, String)} overload and the tests — reproduces the historical
     * behavior: root identity ({@code 0:0}), {@code /root} home, a home-derived
     * working directory, the {@code C.UTF-8} locale, the {@link #DEFAULT_PATH}
     * search path, isolated /proc, and no storage binding.
     */
    public static UserlandOptions defaults(String loginShell) {
        return new UserlandOptions(loginShell, false, "0:0", "/root", "",
                "C.UTF-8", DEFAULT_PATH, true);
    }
}
