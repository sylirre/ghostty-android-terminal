package dev.androidterm.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.androidterm.R;
import dev.androidterm.term.SessionManager;
import dev.androidterm.term.TerminalSession;

/**
 * Hosts the tab strip, terminal view, and extra-keys toolbar.
 *
 * Sessions live in {@link SessionManager}, not here; this activity only
 * binds the current one to the view and re-attaches after recreation.
 * The root insets listener keeps the toolbar riding directly above the
 * IME (the window is edge-to-edge on targetSdk 36+).
 */
public class MainActivity extends Activity implements TerminalSession.Listener {

    private final SessionManager sessions = SessionManager.get();
    private TerminalView terminal;
    private TabStripView tabs;
    private TerminalSession current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminal = findViewById(R.id.terminal);
        tabs = findViewById(R.id.tabs);
        ExtraKeysView extraKeys = findViewById(R.id.extra_keys);
        extraKeys.attachTerminal(terminal);

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return WindowInsets.CONSUMED;
        });

        tabs.setListener(new TabStripView.Listener() {
            @Override
            public void onTabSelected(int index) {
                switchTo(sessions.sessions().get(index));
            }

            @Override
            public void onTabClosed(int index) {
                closeTab(sessions.sessions().get(index));
            }

            @Override
            public void onNewTab() {
                createSession();
            }
        });

        for (TerminalSession s : sessions.sessions()) {
            s.setListener(this);
        }
        if (sessions.isEmpty()) {
            createSession();
        } else {
            switchTo(sessions.sessions().get(0));
        }
    }

    private void createSession() {
        try {
            TerminalSession s = sessions.create(this, this);
            switchTo(s);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to start shell: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            if (sessions.isEmpty()) finish();
        }
    }

    private void switchTo(TerminalSession s) {
        current = s;
        terminal.attachSession(s);
        updateTabs();
    }

    private void closeTab(TerminalSession s) {
        sessions.close(s);
        if (sessions.isEmpty()) {
            finish();
            return;
        }
        if (s == current) {
            switchTo(sessions.sessions().get(sessions.sessions().size() - 1));
        } else {
            updateTabs();
        }
    }

    private void updateTabs() {
        List<String> titles = new ArrayList<>();
        List<TerminalSession> all = sessions.sessions();
        for (int i = 0; i < all.size(); i++) {
            String t = all.get(i).title();
            titles.add(t == null || t.isEmpty() ? "sh:" + (i + 1) : t);
        }
        tabs.update(titles, sessions.indexOf(current));
    }

    // --- TerminalSession.Listener (main thread) ---

    @Override
    public void onUpdate(TerminalSession session) {
        if (session == current) terminal.invalidate();
    }

    @Override
    public void onTitleChanged(TerminalSession session) {
        updateTabs();
    }

    @Override
    public void onBell(TerminalSession session) {
        // Deliberate no-op; a vibrate/flash option can hook in here.
    }

    @Override
    public void onExited(TerminalSession session, int exitCode) {
        if (sessions.indexOf(session) >= 0) {
            closeTab(session);
        }
    }
}
