package sh.easycli.proot;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.hamcrest.Matchers.not;

import static sh.easycli.proot.TestUtil.waitFor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.MotionEvents;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Tap;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import sh.easycli.proot.term.ScreenSnapshot;
import sh.easycli.proot.term.SessionManager;
import sh.easycli.proot.term.TerminalSession;
import sh.easycli.proot.ui.AppSettings;
import sh.easycli.proot.ui.ExtraKeysConfig;
import sh.easycli.proot.ui.MainActivity;
import sh.easycli.proot.ui.TerminalView;

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
        // SessionManager is process-wide, and Android may run this class after
        // another instrumented test in the same app process. Start each UI test
        // from a single-session baseline instead of inheriting stale tabs.
        SessionManager.get().closeAll();
        // Preferences are process/app scoped and instrumentation does not
        // guarantee this class runs first. Start from the stock toolbar so
        // matchers below are not affected by another test's saved layout.
        new ExtraKeysConfig(ApplicationProvider.getApplicationContext()).reset();
        new AppSettings(ApplicationProvider.getApplicationContext())
                .setExtraKeysEnabled(true);
        // Force the plain Android shell: these tests assert sh-specific
        // behavior and tab titles, and must not depend on whether a Debian
        // rootfs is bundled/installed (DebianSessionTest covers PRoot).
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(),
                MainActivity.class).putExtra(MainActivity.EXTRA_FORCE_SHELL, true);
        scenario = ActivityScenario.launch(intent);
        waitFor("first session", TIMEOUT_MS, () -> !SessionManager.get().isEmpty());
    }

    @After
    public void cleanup() {
        // Sessions outlive activities by design; kill them between tests.
        List<TerminalSession> copy = new ArrayList<>(SessionManager.get().sessions());
        for (TerminalSession s : copy) {
            SessionManager.get().close(s);
        }
        // Don't leak extra-keys edits into other tests / the app's real config.
        new ExtraKeysConfig(ApplicationProvider.getApplicationContext()).reset();
        new AppSettings(ApplicationProvider.getApplicationContext())
                .setExtraKeysEnabled(true);
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
            sb.append(" newTabShown=").append(
                    viewShown(a.findViewById(R.id.tabs), R.string.tab_new_description));
            sb.append(" closeTabShown=").append(
                    viewShown(a.findViewById(R.id.tabs), R.string.tab_close_description));
        });
        return sb.toString();
    }

    private boolean viewShown(View root, int contentDescriptionRes) {
        CharSequence needle = ApplicationProvider.getApplicationContext()
                .getString(contentDescriptionRes);
        return viewShown(root, needle);
    }

    private boolean viewShown(View view, CharSequence contentDescription) {
        if (view == null) return false;
        CharSequence own = view.getContentDescription();
        if (contentDescription.equals(own) && view.isShown()) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (viewShown(group.getChildAt(i), contentDescription)) return true;
            }
        }
        return false;
    }

    private void clickTabControl(int contentDescriptionRes) {
        waitFor("tab control " + ApplicationProvider.getApplicationContext()
                        .getString(contentDescriptionRes), TIMEOUT_MS,
                () -> {
                    AtomicBoolean shown = new AtomicBoolean();
                    scenario.onActivity(a -> shown.set(viewShown(
                            a.findViewById(R.id.tabs), contentDescriptionRes)));
                    return shown.get();
                }, this::diagnose);
        scenario.onActivity(a -> {
            View button = findViewWithContentDescription(
                    a.findViewById(R.id.tabs),
                    ApplicationProvider.getApplicationContext()
                            .getString(contentDescriptionRes));
            assertTrue("tab control click handled", button != null && button.performClick());
        });
    }

    private View findViewWithContentDescription(View view, CharSequence contentDescription) {
        if (view == null) return null;
        if (contentDescription.equals(view.getContentDescription())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewWithContentDescription(
                        group.getChildAt(i), contentDescription);
                if (found != null) return found;
            }
        }
        return null;
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
        // The stock default is all special keys (no printable caps), so put two
        // literal keys on the toolbar and rebuild it — the process-wide session
        // survives recreation, so the prompt is still there afterwards.
        new ExtraKeysConfig(ApplicationProvider.getApplicationContext())
                .setOrder(Arrays.asList("slash", "dash"));
        scenario.recreate();
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        // The toolbar is now a full-width flex grid with no horizontal scroller,
        // so every key is on screen and scrollTo() (which requires a scrolling
        // ancestor) must not be used here — a plain click reaches each cap.
        onView(withText("/")).perform(click());
        onView(withText("─")).perform(click()); // sends "-"
        // Run the typed "/-" instead of asserting on the edit line: mksh
        // may cosmetically wipe the line on a late IME resize (SIGWINCH),
        // but the command error output ("/-: ... not found") persists.
        dispatchText("\n");
        waitFor("toolbar chars echoed", TIMEOUT_MS,
                () -> currentScreen().contains("/-"), this::diagnose);
    }

    @Test
    public void extraKeysSettingOpensEditor() {
        onView(withId(R.id.settings_button)).perform(click());
        // Settings is now a full screen (SettingsActivity), not a dialog.
        onView(withText(R.string.setting_extra_keys_title))
                .perform(scrollTo(), click());
        // The editor's top bar (Done button) is unique to ExtraKeysActivity and
        // pinned on screen — its presence proves the editor opened.
        onView(withId(R.id.extra_keys_done)).check(matches(isDisplayed()));
        pressBack(); // close the editor, back to the settings screen
    }

    @Test
    public void extraKeysToggleHidesToolbarButKeepsConfig() {
        // Toolbar visible by default; remember how many keys are configured.
        onView(withText("ESC")).check(matches(isDisplayed()));
        int keyCount = new ExtraKeysConfig(
                ApplicationProvider.getApplicationContext()).order().size();

        // Turn the row off via the settings toggle, then return to the terminal;
        // MainActivity.onResume re-applies the persisted toggle.
        onView(withId(R.id.settings_button)).perform(click());
        onView(withText(R.string.setting_extra_keys_enabled_title))
                .perform(scrollTo(), click());
        pressBack();

        // The toolbar is gone, but the configured keys are untouched.
        onView(withText("ESC")).check(matches(not(isDisplayed())));
        scenario.onActivity(a -> assertEquals(View.GONE,
                a.findViewById(R.id.extra_keys).getVisibility()));
        assertEquals(keyCount, new ExtraKeysConfig(
                ApplicationProvider.getApplicationContext()).order().size());
    }

    @Test
    public void extraKeysConfigDrivesToolbar() {
        // Default toolbar shows ESC.
        onView(withText("ESC")).check(matches(isDisplayed()));
        // Drop ESC from the config, then recreate so the toolbar rebuilds.
        ExtraKeysConfig cfg = new ExtraKeysConfig(
                ApplicationProvider.getApplicationContext());
        List<String> ids = cfg.order();
        ids.remove("esc");
        cfg.setOrder(ids);
        scenario.recreate();
        onView(withText("ESC")).check(doesNotExist());
        onView(withText("CTRL")).check(matches(isDisplayed()));
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
        clickTabControl(R.string.tab_new_description);
        waitFor("two sessions", TIMEOUT_MS,
                () -> SessionManager.get().sessions().size() == 2,
                this::diagnose);
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
    public void fontSizeChangeReflowsGridAndSession() {
        // Drives the same path as pinch-zoom (ScaleGestureDetector ends in
        // setFontSizeSp); the gesture math itself is framework code.
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        float[] origSp = new float[1];
        int[] colsBefore = new int[1];
        scenario.onActivity(a -> {
            TerminalView v = a.findViewById(R.id.terminal);
            origSp[0] = v.fontSizeSp();
            colsBefore[0] = v.gridCols();
            v.setFontSizeSp(origSp[0] * 2);
        });
        try {
            int[] after = new int[2];
            scenario.onActivity(a -> {
                TerminalView v = a.findViewById(R.id.terminal);
                after[0] = v.gridCols();
                ScreenSnapshot snap = new ScreenSnapshot();
                v.session().emulator.snapshot(snap);
                after[1] = snap.cols;
            });
            assertTrue("columns shrink when the font grows", after[0] < colsBefore[0]);
            assertEquals("session follows the view grid", after[0], after[1]);
        } finally {
            // Font size persists in SharedPreferences; restore for other tests.
            scenario.onActivity(a -> ((TerminalView) a.findViewById(R.id.terminal))
                    .setFontSizeSp(origSp[0]));
        }
    }

    @Test
    public void settingsMenuTogglesKeepScreenOn() {
        // Off by default: the window flag is clear at launch.
        assertFalse("keep-screen-on starts off", keepScreenOnFlag());

        // Open the settings screen (gear in the top bar), tap the row to enable
        // the toggle, then return — MainActivity.onResume applies the change.
        onView(withId(R.id.settings_button)).perform(click());
        onView(withText("Keep screen on")).perform(scrollTo(), click());
        pressBack();
        assertTrue("flag set after enabling", keepScreenOnFlag());

        // Reopen and disable it, leaving the shared preference clean for
        // other tests (the setting persists across activity instances).
        onView(withId(R.id.settings_button)).perform(click());
        onView(withText("Keep screen on")).perform(scrollTo(), click());
        pressBack();
        assertFalse("flag cleared after disabling", keepScreenOnFlag());
    }

    private boolean keepScreenOnFlag() {
        AtomicBoolean on = new AtomicBoolean();
        scenario.onActivity(a -> on.set((a.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0));
        return on.get();
    }

    // --- Selection ---

    /** Long-presses the center of terminal cell (cx, cy). */
    private static ViewAction longPressAtCell(final int cx, final int cy) {
        return new GeneralClickAction(Tap.LONG,
                view -> cellCenterOnScreen(view, cx, cy),
                Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY);
    }

    /** Screen coordinates of the center of terminal cell (cx, cy). */
    private static float[] cellCenterOnScreen(View view, float cx, float cy) {
        TerminalView tv = (TerminalView) view;
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);
        // width/cols slightly overestimates the cell width; exact enough for
        // the small coordinates these tests use (and it never undershoots).
        float cw = view.getWidth() / (float) tv.gridCols();
        float ch = view.getHeight() / (float) tv.gridRows();
        return new float[] {xy[0] + (cx + 0.5f) * cw, xy[1] + (cy + 0.5f) * ch};
    }

    /**
     * Presses cell (cx0,cy0), holds past the long-press timeout, then drags to
     * cell (cx1,cy1) before releasing — the long-press-and-extend gesture in a
     * single motion.
     */
    private static ViewAction longPressDragCells(
            final int cx0, final int cy0, final int cx1, final int cy1) {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isDisplayed();
            }

            @Override
            public String getDescription() {
                return "long-press then drag across cells";
            }

            @Override
            public void perform(UiController uc, View view) {
                float[] precision = Press.FINGER.describePrecision();
                float[] start = cellCenterOnScreen(view, cx0, cy0);
                float[] end = cellCenterOnScreen(view, cx1, cy1);
                MotionEvents.DownResultHolder down =
                        MotionEvents.sendDown(uc, start, precision);
                // Hold still so the gesture detector reports a long press.
                uc.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout()
                        + ViewConfiguration.getTapTimeout() + 300);
                int steps = 12;
                for (int i = 1; i <= steps; i++) {
                    float[] p = {
                            start[0] + (end[0] - start[0]) * i / steps,
                            start[1] + (end[1] - start[1]) * i / steps};
                    MotionEvents.sendMovement(uc, down.down, p);
                    uc.loopMainThreadForAtLeast(16);
                }
                MotionEvents.sendUp(uc, down.down, end);
            }
        };
    }

    /** First screen row whose trimmed text equals exactly the given line. */
    private int screenRowWith(String exact) {
        AtomicInteger row = new AtomicInteger(-1);
        scenario.onActivity(a -> {
            TerminalView v = a.findViewById(R.id.terminal);
            if (v.session() == null) return;
            ScreenSnapshot snap = new ScreenSnapshot();
            v.session().emulator.snapshot(snap);
            for (int y = 0; y < snap.rows; y++) {
                if (snap.rowText(y).equals(exact)) {
                    row.set(y);
                    break;
                }
            }
        });
        return row.get();
    }

    private String selectionText() {
        AtomicReference<String> out = new AtomicReference<>();
        scenario.onActivity(a -> {
            TerminalView v = a.findViewById(R.id.terminal);
            if (v.session() != null) out.set(v.session().emulator.selectionText());
        });
        return out.get();
    }

    private boolean selectionActive() {
        AtomicBoolean active = new AtomicBoolean();
        scenario.onActivity(a -> {
            TerminalView v = a.findViewById(R.id.terminal);
            if (v.session() != null) {
                ScreenSnapshot snap = new ScreenSnapshot();
                v.session().emulator.snapshot(snap);
                active.set(snap.hasSelection());
            }
        });
        return active.get();
    }

    private String clipboardText() {
        AtomicReference<String> out = new AtomicReference<>();
        scenario.onActivity(a -> {
            ClipboardManager cm = a.getSystemService(ClipboardManager.class);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).getText();
                out.set(t == null ? null : t.toString());
            }
        });
        return out.get();
    }

    @Test
    public void longPressSelectsWordAndCopyFillsClipboard() {
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        dispatchText("echo selectme123\n");
        waitFor("echoed output line", TIMEOUT_MS,
                () -> screenRowWith("selectme123") >= 0, this::diagnose);

        int row = screenRowWith("selectme123");
        onView(withId(R.id.terminal)).perform(longPressAtCell(3, row));
        waitFor("word selected", TIMEOUT_MS,
                () -> "selectme123".equals(selectionText()), this::diagnose);

        // Copy lives on the floating selection toolbar (a popup window).
        onView(withText("Copy")).inRoot(isPlatformPopup()).perform(click());
        waitFor("clipboard filled", TIMEOUT_MS,
                () -> "selectme123".equals(clipboardText()));
        waitFor("selection dismissed", TIMEOUT_MS, () -> !selectionActive());
    }

    @Test
    public void longPressDragExtendsSelection() {
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        dispatchText("echo aa bbbbbbbbbb\n");
        waitFor("echoed output line", TIMEOUT_MS,
                () -> screenRowWith("aa bbbbbbbbbb") >= 0, this::diagnose);

        int row = screenRowWith("aa bbbbbbbbbb");
        // Long-press "aa", then drag the end past "bbbbbbbbbb" without lifting.
        // The drag target overshoots the text so it covers every 'b'; trailing
        // blanks are trimmed back out of the copied range.
        onView(withId(R.id.terminal)).perform(longPressDragCells(0, row, 16, row));
        waitFor("selection extended across the drag", TIMEOUT_MS,
                () -> {
                    String s = selectionText();
                    return s != null && s.startsWith("aa bbbbbbbbbb");
                }, this::diagnose);
    }

    @Test
    public void pasteButtonTypesClipboardIntoShell() {
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        scenario.onActivity(a -> a.getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("test", "pasted-xyz")));

        // Long-pressing a blank area still enters selection mode (a
        // single-cell selection), which is the paste affordance.
        onView(withId(R.id.terminal)).perform(longPressAtCell(2, 2));
        waitFor("selection active", TIMEOUT_MS, this::selectionActive,
                this::diagnose);

        onView(withText("Paste")).inRoot(isPlatformPopup()).perform(click());
        // The pasted text is echoed on the shell's input line; it may
        // soft-wrap mid-word, so compare without line breaks.
        waitFor("clipboard text reaches the shell", TIMEOUT_MS,
                () -> currentScreen().replace("\n", "").contains("pasted-xyz"),
                this::diagnose);
    }

    // --- Search ---

    @Test
    public void searchBarFindsAndHighlightsToken() {
        waitFor("shell prompt", TIMEOUT_MS, () -> currentScreen().contains("$"));
        dispatchText("echo searchtoken\n");
        waitFor("token on screen", TIMEOUT_MS,
                () -> screenRowWith("searchtoken") >= 0, this::diagnose);

        onView(withId(R.id.search_button)).perform(click());
        onView(withId(R.id.search_bar)).check(matches(isDisplayed()));

        // Typing the query runs the (debounced) scan, which installs the
        // current match as the selection — the search highlight.
        onView(withHint(R.string.search_hint))
                .perform(typeText("searchtoken"), closeSoftKeyboard());
        waitFor("match highlighted", TIMEOUT_MS,
                () -> "searchtoken".equals(selectionText()), this::diagnose);

        // Closing the bar clears the highlight and hides it.
        onView(withContentDescription(R.string.search_close_description)).perform(click());
        waitFor("highlight cleared", TIMEOUT_MS, () -> !selectionActive());
        onView(withId(R.id.search_bar)).check(matches(not(isDisplayed())));
    }

    @Test
    public void closingActiveTabSwitchesToRemaining() {
        clickTabControl(R.string.tab_new_description);
        waitFor("two sessions", TIMEOUT_MS,
                () -> SessionManager.get().sessions().size() == 2,
                this::diagnose);
        clickTabControl(R.string.tab_close_description);
        waitFor("one session", TIMEOUT_MS,
                () -> SessionManager.get().sessions().size() == 1,
                this::diagnose);
        onView(withText("sh:1")).check(matches(isDisplayed()));
    }
}
