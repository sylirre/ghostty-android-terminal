package dev.androidterm.term;

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
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, boolean debian,
            TerminalSession.Listener listener) throws IOException {
        SessionCommand command = debian
                ? DebianRootfs.command(context)
                : SessionCommand.androidShell(
                        context.getFilesDir().getAbsolutePath(),
                        context.getCacheDir().getAbsolutePath());
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, command, listener);
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

    public boolean isEmpty() {
        return sessions.isEmpty();
    }
}
