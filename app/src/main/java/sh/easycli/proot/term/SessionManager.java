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
     * @param scrollbackLines lines of history the new session keeps.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean debian,
            TerminalSession.Listener listener) throws IOException {
        SessionCommand command = debian
                ? DebianRootfs.command(context)
                : SessionCommand.androidShell(
                        context.getFilesDir().getAbsolutePath(),
                        context.getCacheDir().getAbsolutePath());
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, command, listener);
        sessions.add(s);
        return s;
    }

    public List<TerminalSession> sessions() {
        return sessions;
    }

    public int indexOf(TerminalSession s) {
        return sessions.indexOf(s);
    }

    public void close(TerminalSession s) {
        s.close();
        sessions.remove(s);
    }

    /**
     * Kills every shell and empties the list. Used by the "Exit" action in
     * the foreground-service notification, which can fire while no Activity
     * is alive — so it must leave no dead sessions behind for a later
     * relaunch to re-attach to.
     */
    public void closeAll() {
        for (TerminalSession s : sessions) {
            s.close();
        }
        sessions.clear();
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }
}
