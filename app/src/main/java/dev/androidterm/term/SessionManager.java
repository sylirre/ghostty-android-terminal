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

    public TerminalSession create(Context context, TerminalSession.Listener listener)
            throws IOException {
        TerminalSession s = new TerminalSession(80, 24,
                context.getFilesDir().getAbsolutePath(),
                context.getCacheDir().getAbsolutePath(),
                listener);
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
