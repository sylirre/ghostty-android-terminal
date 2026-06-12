package dev.androidterm;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static dev.androidterm.TestUtil.waitFor;

import static org.junit.Assert.assertEquals;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import dev.androidterm.term.ScreenSnapshot;
import dev.androidterm.term.SessionManager;
import dev.androidterm.term.TerminalSession;
import dev.androidterm.ui.MainActivity;
import dev.androidterm.ui.TerminalView;

/**
 * Activity-level integration: tabs, extra-keys toolbar, and typing all the
 * way into the shell and back onto the rendered screen.
 */
@RunWith(AndroidJUnit4.class)
public class TerminalUiTest {

    private static final long TIMEOUT_MS = 15_000;

    private ActivityScenario<MainActivity> scenario;

    @Before
    public void launch() {
        scenario = ActivityScenario.launch(MainActivity.class);
        waitFor("first session", TIMEOUT_MS, () -> !SessionManager.get().isEmpty());
    }

    @After
    public void cleanup() {
        // Sessions outlive activities by design; kill them between tests.
        List<TerminalSession> copy = new ArrayList<>(SessionManager.get().sessions());
        for (TerminalSession s : copy) {
            SessionManager.get().close(s);
        }
        scenario.close();
    }

    private String currentScreen() {
        AtomicReference<String> out = new AtomicReference<>("");
        scenario.onActivity(a -> {
            TerminalView v = a.findViewById(R.id.terminal);
            if (v.session() != null) {
                ScreenSnapshot snap = new ScreenSnapshot();
                v.session().emulator.snapshot(snap);
                out.set(snap.text());
            }
        });
        return out.get();
    }

    private void dispatchText(String text) {
        scenario.onActivity(a ->
                ((TerminalView) a.findViewById(R.id.terminal)).dispatchText(text));
    }

    private String diagnose() {
        StringBuilder sb = new StringBuilder();
        List<TerminalSession> all = SessionManager.get().sessions();
        sb.append("sessions=").append(all.size());
        scenario.onActivity(a -> {
            TerminalView v = a.findViewById(R.id.terminal);
            TerminalSession s = v.session();
            sb.append(" viewSession=").append(s == null ? "null"
                    : (all.indexOf(s) >= 0 ? "idx" + all.indexOf(s) : "DETACHED"));
            if (s != null) {
                int[] sbar = new int[3];
                s.emulator.scrollbar(sbar);
                sb.append(" exit=").append(s.exitCode())
                  .append(" scrollbar=").append(sbar[0]).append('/')
                  .append(sbar[1]).append('/').append(sbar[2]);
                ScreenSnapshot snap = new ScreenSnapshot();
                s.emulator.snapshot(snap);
                sb.append(" dims=").append(snap.cols).append('x').append(snap.rows);
                sb.append("\nscreen:[").append(snap.text().trim()).append(']');
            }
        });
        return sb.toString();
    }

    @Test
    public void launchShowsShellTabAndToolbar() {
        onView(withText("sh:1")).check(matches(isDisplayed()));
        onView(withText("ESC")).check(matches(isDisplayed()));
        onView(withText("CTRL")).check(matches(isDisplayed()));
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
    }

    @Test
    public void typedCommandRunsInShell() {
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"),
                this::diagnose);
        dispatchText("echo ui-roundtrip\n");
        waitFor("command output", TIMEOUT_MS,
                () -> currentScreen().contains("ui-roundtrip"), this::diagnose);
    }

    @Test
    public void extraKeysTypeIntoShell() {
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        // The "/" and "─" keys live at the right end of the scrollable
        // toolbar; bring them on screen so Espresso can click them.
        scenario.onActivity(a -> ((android.widget.HorizontalScrollView)
                a.findViewById(R.id.extra_keys)).fullScroll(View.FOCUS_RIGHT));
        onView(withText("/")).perform(click());
        onView(withText("─")).perform(click()); // sends "-"
        waitFor("toolbar chars echoed", TIMEOUT_MS,
                () -> currentScreen().contains("/-"));
    }

    @Test
    public void stickyCtrlInterruptsCommand() {
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        dispatchText("sleep 100\n");
        onView(withText("CTRL")).perform(click());
        dispatchText("c"); // becomes ^C via the sticky modifier
        dispatchText("echo rc=$?\n");
        // 130 = 128 + SIGINT: proves the encoder produced a real ^C.
        waitFor("interrupt exit code", TIMEOUT_MS,
                () -> currentScreen().contains("rc=130"));
    }

    @Test
    public void newTabCreatesAndSwitchesSessions() {
        onView(withText("+")).perform(click());
        waitFor("two sessions", TIMEOUT_MS,
                () -> SessionManager.get().sessions().size() == 2);
        onView(withText("sh:2")).check(matches(isDisplayed()));

        // Leave a marker in tab 2, switch to tab 1, verify the view rebinds.
        dispatchText("echo marker-tab2\n");
        waitFor("marker in tab 2", TIMEOUT_MS,
                () -> currentScreen().contains("marker-tab2"));
        onView(withText("sh:1")).perform(click());
        scenario.onActivity(a -> {
            TerminalView v = a.findViewById(R.id.terminal);
            assertEquals(SessionManager.get().sessions().get(0), v.session());
        });
        waitFor("tab 1 has no marker", TIMEOUT_MS,
                () -> !currentScreen().contains("marker-tab2"));
    }

    @Test
    public void closingActiveTabSwitchesToRemaining() {
        onView(withText("+")).perform(click());
        waitFor("two sessions", TIMEOUT_MS,
                () -> SessionManager.get().sessions().size() == 2);
        onView(withText("×")).perform(click());
        waitFor("one session", TIMEOUT_MS,
                () -> SessionManager.get().sessions().size() == 1);
        onView(withText("sh:1")).check(matches(isDisplayed()));
    }
}
