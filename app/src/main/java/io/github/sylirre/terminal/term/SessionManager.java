/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide session list backing the tab strip.
 *
 * A singleton (not Activity state) so shells survive rotation and Activity
 * recreation. Sessions die with the process; there is deliberately no
 * foreground service.
 */
public final class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();

    public static SessionManager get() {
        return INSTANCE;
    }

    private final List<TerminalSession> sessions = new ArrayList<>();

    private SessionManager() {}

    /**
     * Spawns a shell at the given grid size. Callers should pass the real
     * view size: spawning at a wrong size triggers a SIGWINCH on first
     * layout, and mksh reacts by wiping its initial prompt.
     *
     * @param userland Login shell under arm64chroot (rootfs must be
     *               installed) instead of /system/bin/sh.
     * @param userlandOptions arm64chroot inputs (login shell, identity, home,
     *                   working directory, /proc isolation, storage binding);
     *                   used only when {@code userland} is true.
     * @param scrollbackLines lines of history the new session keeps.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean userland,
            UserlandOptions userlandOptions, boolean terminateProcessesOnExit,
            TerminalSession.Listener listener) throws IOException {
        SessionCommand command = userland
                ? UserlandRootfs.command(context, userlandOptions)
                : SessionCommand.androidShell(
                        context.getFilesDir().getAbsolutePath(),
                        context.getCacheDir().getAbsolutePath());
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, command, terminateProcessesOnExit,
                listener);
        synchronized (this) {
            sessions.add(s);
        }
        return s;
    }

    /**
     * Attaches a tab to one terminal of the running guest machine.
     *
     * Nothing is spawned here — the machine booted its own gettys, and this
     * only opens a view onto one of them, so unlike {@link #create} it cannot
     * fail for want of a rootfs or a shell. {@code terminal} indexes the
     * machine's terminals: 0 is the serial console the guest boots on.
     */
    public TerminalSession attachVm(VmMachine machine, int terminal, int cols,
            int rows, int cellWidthPx, int cellHeightPx, int scrollbackLines,
            TerminalSession.Listener listener) throws IOException {
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, machine, terminal, listener);
        synchronized (this) {
            sessions.add(s);
        }
        return s;
    }

    /**
     * The machine terminals a tab is currently open on, so a caller can pick
     * one that is not. Detaching frees an index for reuse: the guest side is
     * untouched by a tab closing, so reattaching lands back in the same shell.
     */
    public boolean isVmTerminalOpen(int terminal) {
        synchronized (this) {
            for (TerminalSession s : sessions) {
                if (s.isVm() && s.vmTerminal() == terminal) return true;
            }
        }
        return false;
    }

    public List<TerminalSession> sessions() {
        synchronized (this) {
            return new ArrayList<>(sessions);
        }
    }

    public int indexOf(TerminalSession s) {
        synchronized (this) {
            return sessions.indexOf(s);
        }
    }

    public boolean close(TerminalSession s) {
        boolean removed;
        synchronized (this) {
            removed = sessions.remove(s);
        }
        if (removed) s.close();
        return removed;
    }

    /**
     * Kills every shell and empties the list. Used by the "Exit" action in
     * the foreground-service notification, which can fire while no Activity
     * is alive — so it must leave no dead sessions behind for a later
     * relaunch to re-attach to.
     */
    public void closeAll() {
        List<TerminalSession> copy;
        synchronized (this) {
            copy = new ArrayList<>(sessions);
            sessions.clear();
        }
        for (TerminalSession s : copy) {
            s.close();
        }
        // Closing a VM tab only detaches it, by design — so the machine would
        // otherwise outlive the app that started it, with no tab left to reach
        // it from and no way to stop it. "Exit" means exit.
        VmMachine.stopIfRunning();
    }

    public boolean isEmpty() {
        synchronized (this) {
            return sessions.isEmpty();
        }
    }
}
