package sh.easycli.proot.term;

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
     * @param debian Debian login shell under PRoot (rootfs must be
     *               installed) instead of /system/bin/sh.
     * @param loginShell guest-absolute path of the login shell (e.g.
     *                   {@code /bin/bash}); ignored when {@code debian} is false.
     * @param scrollbackLines lines of history the new session keeps.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean debian,
            String loginShell, TerminalSession.Listener listener) throws IOException {
        return create(context, cols, rows, cellWidthPx, cellHeightPx, scrollbackLines,
                debian, loginShell, false, false, listener);
    }

    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean debian,
            String loginShell, boolean bindExternalStorage,
            boolean terminateProcessesOnExit,
            TerminalSession.Listener listener) throws IOException {
        SessionCommand command = debian
                ? DebianRootfs.command(context, loginShell, bindExternalStorage)
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
    }

    public boolean isEmpty() {
        synchronized (this) {
            return sessions.isEmpty();
        }
    }
}
