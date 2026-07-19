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
    /** When true, /proc is private to this session (no {@code --shared-proc}). */
    public final boolean isolateProc;

    public UserlandOptions(String loginShell, boolean bindExternalStorage,
            String identity, String home, String workDir, boolean isolateProc) {
        this.loginShell = loginShell;
        this.bindExternalStorage = bindExternalStorage;
        this.identity = identity;
        this.home = home;
        this.workDir = workDir;
        this.isolateProc = isolateProc;
    }

    /**
     * Options matching the {@code AppSettings} first-use defaults, so a caller
     * that only has a login shell — the convenience {@link UserlandRootfs#command
     * command(Context, String)} overload and the tests — reproduces the historical
     * behavior: root identity ({@code 0:0}), {@code /root} home, a home-derived
     * working directory, isolated /proc, and no storage binding.
     */
    public static UserlandOptions defaults(String loginShell) {
        return new UserlandOptions(loginShell, false, "0:0", "/root", "", true);
    }
}
