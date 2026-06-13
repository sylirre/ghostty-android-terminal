package dev.androidterm.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.androidterm.R;
import dev.androidterm.term.DebianRootfs;
import dev.androidterm.term.SessionManager;
import dev.androidterm.term.TerminalSession;

/**
 * Hosts the tab strip, terminal view, and extra-keys toolbar.
 *
 * Sessions live in {@link SessionManager}, not here; this activity only
 * binds the current one to the view and re-attaches after recreation.
 * The root insets listener keeps the toolbar riding directly above the
 * IME (the window is edge-to-edge on targetSdk 36+).
 *
 * New tabs default to a Debian-under-PRoot login shell once the rootfs is
 * installed (extracted from an optional APK asset on first launch);
 * long-pressing + opens the other session type. Builds without the rootfs
 * asset behave as before: plain /system/bin/sh.
 */
public class MainActivity extends Activity implements TerminalSession.Listener {

    /**
     * Test seam: forces the plain Android shell as the default session type
     * so UI tests are deterministic whether or not a rootfs is bundled.
     */
    public static final String EXTRA_FORCE_SHELL = "dev.androidterm.FORCE_SHELL";

    private final SessionManager sessions = SessionManager.get();
    private TerminalView terminal;
    private TabStripView tabs;
    private TextView installStatus;
    private TerminalSession current;
    private boolean forceShell;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminal = findViewById(R.id.terminal);
        tabs = findViewById(R.id.tabs);
        installStatus = findViewById(R.id.install_status);
        forceShell = getIntent().getBooleanExtra(EXTRA_FORCE_SHELL, false);
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
                createSession(debianByDefault());
            }

            @Override
            public void onNewTabLongPress() {
                if (!DebianRootfs.isInstalled(MainActivity.this)
                        && DebianRootfs.assetAvailable(MainActivity.this)) {
                    installDebianThenCreateSession();
                } else {
                    createSession(!debianByDefault());
                }
            }
        });

        for (TerminalSession s : sessions.sessions()) {
            s.setListener(this);
        }
        if (sessions.isEmpty()) {
            // Spawn the first shell only once the view is laid out so the
            // PTY starts at its real size (see SessionManager.create).
            terminal.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                        int ol, int ot, int or, int ob) {
                    terminal.removeOnLayoutChangeListener(this);
                    // Defer past the current layout traversal: createFirstSession
                    // may make the install_status overlay VISIBLE, and a
                    // requestLayout() issued from inside layout() is dropped until
                    // the next traversal — which otherwise only arrives on the
                    // first touch, so the message appeared only after a tap.
                    terminal.post(() -> {
                        if (sessions.isEmpty()) createFirstSession();
                    });
                }
            });
        } else {
            switchTo(sessions.sessions().get(0));
        }
    }

    private boolean debianByDefault() {
        return !forceShell && DebianRootfs.isInstalled(this);
    }

    /** First tab: Debian when installed, install flow when only bundled. */
    private void createFirstSession() {
        if (debianByDefault()) {
            createSession(true);
        } else if (!forceShell && DebianRootfs.assetAvailable(this)) {
            installDebianThenCreateSession();
        } else {
            createSession(false);
        }
    }

    private void createSession(boolean debian) {
        try {
            TerminalSession s = sessions.create(this,
                    terminal.gridCols(), terminal.gridRows(), debian, this);
            switchTo(s);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to start shell: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            if (sessions.isEmpty()) finish();
        }
    }

    /**
     * One-time rootfs extraction on a background thread; the overlay shows
     * progress. Install is idempotent/synchronized, so a racing second
     * activity instance at worst waits and then finds it installed.
     */
    private void installDebianThenCreateSession() {
        installStatus.setText("Installing Debian…");
        installStatus.setVisibility(View.VISIBLE);
        new Thread(() -> {
            IOException failure = null;
            try {
                DebianRootfs.install(getApplicationContext(), bytes ->
                        runOnUiThread(() -> installStatus.setText(
                                "Installing Debian… " + (bytes >> 20) + " MB")));
            } catch (IOException e) {
                failure = e;
            }
            final IOException error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                installStatus.setVisibility(View.GONE);
                if (error == null) {
                    createSession(true);
                } else {
                    Toast.makeText(this, "Debian install failed: "
                            + error.getMessage(), Toast.LENGTH_LONG).show();
                    if (sessions.isEmpty()) createSession(false);
                }
            });
        }, "debian-install").start();
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
            titles.add(t == null || t.isEmpty()
                    ? all.get(i).label() + ":" + (i + 1) : t);
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
