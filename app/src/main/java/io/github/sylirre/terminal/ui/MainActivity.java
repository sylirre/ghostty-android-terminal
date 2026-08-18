/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.UserlandRootfs;
import io.github.sylirre.terminal.term.RootfsBackup;
import io.github.sylirre.terminal.term.SessionManager;
import io.github.sylirre.terminal.term.SessionService;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.VmImages;
import io.github.sylirre.terminal.term.VmMachine;
import io.github.sylirre.terminal.term.VmOptions;

/**
 * Hosts the tab strip, terminal view, and extra-keys toolbar.
 *
 * Sessions live in {@link SessionManager}, not here; this activity only
 * binds the current one to the view and re-attaches after recreation.
 * The root insets listener keeps the toolbar riding directly above the
 * IME (the window is edge-to-edge on targetSdk 36+).
 *
 * New tabs default to a userland login shell once the rootfs is
 * installed; long-pressing + opens the other session type. First launch
 * (no rootfs, onboarding never completed) runs {@link OnboardingActivity},
 * which explains the app, lets the user pick a bundled distribution and
 * extracts it; the first session spawns when it returns. Builds without
 * rootfs assets end up with the plain /system/bin/sh.
 */
public class MainActivity extends Activity implements TerminalSession.Listener {

    /**
     * Test seam: forces the plain Android shell as the default session type
     * so UI tests are deterministic whether or not a rootfs is bundled.
     */
    public static final String EXTRA_FORCE_SHELL = "io.github.sylirre.terminal.FORCE_SHELL";

    private static final int REQ_POST_NOTIFICATIONS = 1;
    private static final int REQ_BACKUP = 100;
    private static final int REQ_RESTORE = 101;
    private static final int REQ_SETTINGS = 102;
    private static final int REQ_ONBOARDING = 103;
    private static final String PREF_ASKED_BATTERY_OPT = "asked_ignore_battery_opt";
    private static final long BELL_VIBRATION_MS = 300;
    private static final long BELL_THROTTLE_MS = BELL_VIBRATION_MS;

    private final SessionManager sessions = SessionManager.get();
    private TerminalView terminal;
    private TabStripView tabs;
    private SearchBarView searchBar;
    private TextView searchButton;
    private TextView promptPrevButton;
    private TextView promptNextButton;
    private View mainTopBar;
    /** Active chrome palette, derived from the terminal theme (see applyChrome). */
    private ChromePalette chrome;
    private long lastClipToastUptime;
    private ExtraKeysView extraKeys;
    private TerminalSession current;
    private AppSettings settings;
    private ThemeStore themeStore;
    private ExtraKeysConfig extraKeysConfig;
    private boolean forceShell;
    /** Back interception while the find bar is open (predictive back). */
    private BackGesture backGesture;
    /** First-session spawn is held back while the onboarding wizard runs. */
    private boolean awaitingOnboarding;
    private long lastBellUptime;

    /** Fired by {@link SessionService} when the user taps "Exit" in the notification. */
    private final BroadcastReceiver exitReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Sessions are already torn down by the service; just drop the UI.
            finishAndRemoveTask();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminal = findViewById(R.id.terminal);
        tabs = findViewById(R.id.tabs);
        forceShell = getIntent().getBooleanExtra(EXTRA_FORCE_SHELL, false);
        extraKeys = findViewById(R.id.extra_keys);
        extraKeys.attachTerminal(terminal);
        mainTopBar = findViewById(R.id.main_top_bar);
        mainTopBar.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        chrome = ChromePalette.from(this, 0xFF000000);

        settings = new AppSettings(this);
        // Existing installs never see the intro: a rootfs on disk means the
        // user was set up before onboarding existed.
        if (!settings.onboardingCompleted() && UserlandRootfs.isInstalled(this)) {
            settings.setOnboardingCompleted(true);
        }
        if (!forceShell && !settings.onboardingCompleted() && sessions.isEmpty()) {
            // First run: the wizard picks the session type (and installs the
            // rootfs); the first session spawns when it returns.
            awaitingOnboarding = true;
            startActivityForResult(
                    new Intent(this, OnboardingActivity.class), REQ_ONBOARDING);
        }
        themeStore = new ThemeStore(this);
        extraKeysConfig = new ExtraKeysConfig(this);
        extraKeys.setConfig(extraKeysConfig);
        extraKeys.setRowEnabled(settings.extraKeysEnabled());
        extraKeys.setHideWhenKeyboardHidden(settings.hideExtraKeysWhenKeyboardHidden());
        extraKeys.setShowSwitch(settings.showExtraKeysSwitch());
        extraKeys.setKeyVerticalPaddingDp(settings.extraKeysVerticalPadding());
        applyKeepScreenOn(settings.keepScreenOn());
        applyImmersiveMode(settings.immersiveMode());
        terminal.setRichKeyboard(settings.richKeyboard());
        terminal.setTouchKeyboardEnabled(settings.touchKeyboard());
        terminal.setSmoothScroll(settings.smoothScroll());
        terminal.setMouseTracking(settings.mouseTracking());
        terminal.setTapToOpenLinks(settings.tapToOpenLinks());
        applyTextMargins();
        findViewById(R.id.settings_button).setOnClickListener(this::openSettings);
        Glyphs.applyTo(findViewById(R.id.settings_button));

        searchBar = findViewById(R.id.search_bar);
        searchBar.setListener(new SearchBarView.Listener() {
            @Override public void onQueryChanged(String query, boolean caseSensitive) {
                terminal.searchSetQuery(query, caseSensitive);
            }
            @Override public void onNext() { terminal.searchNext(); }
            @Override public void onPrev() { terminal.searchPrev(); }
            @Override public void onClose() { hideSearch(); }
        });
        terminal.setSearchListener(searchBar);
        backGesture = BackGesture.install(this, this::handleBack);
        searchButton = findViewById(R.id.search_button);
        searchButton.setOnClickListener(v -> {
            if (searchBar.isOpen()) hideSearch();
            else showSearch();
        });
        Glyphs.applyTo(searchButton);

        promptPrevButton = findViewById(R.id.prompt_prev_button);
        promptNextButton = findViewById(R.id.prompt_next_button);
        promptPrevButton.setOnClickListener(v -> terminal.jumpToPrevPrompt());
        promptNextButton.setOnClickListener(v -> terminal.jumpToNextPrompt());
        Glyphs.applyTo(promptPrevButton);  // ▲ ▼ → the same vector arrows as the extra keys
        Glyphs.applyTo(promptNextButton);
        // The prompt-navigation buttons appear only while scrolled into history —
        // where jumping between prompts is useful — so the tab strip keeps the
        // full top-bar width during normal typing at the live bottom.
        terminal.setScrollStateListener(atBottom -> updatePromptNav(atBottom));
        applyPromptNav();

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean imeVisible;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                                | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                imeVisible = insets.isVisible(WindowInsets.Type.ime());
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                // On API 29, the stable bottom inset is the nav bar; any extra
                // bottom space in the system-window insets is the IME.
                imeVisible = insets.getSystemWindowInsetBottom()
                        > insets.getStableInsetBottom();
            }
            extraKeys.setKeyboardVisible(imeVisible);
            // WindowInsets.CONSUMED is API 30; on 29 (our minSdk) touching it
            // throws NoSuchFieldError, so consume the old way there.
            return Build.VERSION.SDK_INT >= 30
                    ? WindowInsets.CONSUMED
                    : insets.consumeSystemWindowInsets();
        });

        tabs.setListener(new TabStripView.Listener() {
            @Override
            public void onTabSelected(int index) {
                switchTo(sessions.sessions().get(index));
            }

            @Override
            public void onTabClosed(int index) {
                confirmCloseTab(sessions.sessions().get(index));
            }

            @Override
            public void onNewTab() {
                createSession(userlandByDefault());
            }

            @Override
            public void onNewTabLongPress() {
                showSessionTypeChooser();
            }
        });

        for (TerminalSession s : sessions.sessions()) {
            s.setListener(this);
        }
        applyTerminateProcessesOnExit(settings.terminateProcessesOnExit());
        if (sessions.isEmpty()) {
            // Spawn the first shell only once the view is laid out so the
            // PTY starts at its real size (see SessionManager.create).
            terminal.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                        int ol, int ot, int or, int ob) {
                    terminal.removeOnLayoutChangeListener(this);
                    // Defer past the current layout traversal: spawning can
                    // toggle view state, and a requestLayout() issued from
                    // inside layout() is dropped until the next traversal —
                    // which otherwise only arrives on the first touch.
                    terminal.post(() -> {
                        if (sessions.isEmpty()) createFirstSession();
                    });
                }
            });
        } else {
            switchTo(sessions.sessions().get(0));
        }

        IntentFilter filter = new IntentFilter(SessionService.ACTION_EXITED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(exitReceiver, filter);
        }
        // Deferred while onboarding runs — the system dialog must not land on
        // top of the wizard's first impression; re-requested when it returns.
        if (!awaitingOnboarding) maybeRequestNotificationsPermission();
    }

    /**
     * Asks for POST_NOTIFICATIONS (33+), needed for the foreground-service
     * notification to be visible; the service still runs if denied. Skipped
     * under the test seam so the system dialog never lands on top of Espresso.
     */
    private void maybeRequestNotificationsPermission() {
        if (!forceShell && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply the whole settings surface: edits made in SettingsActivity /
        // ThemeActivity / ExtraKeysActivity only persist to the stores, so
        // returning here is what pushes them to the live terminal, window and
        // sessions (and seeds the first paint).
        applyAllSettings();
        disableStorageBindingIfPermissionRevoked();
        if (current != null && settings.touchKeyboard()) showKeyboard();
    }

    /**
     * Re-applies every persisted setting to the live terminal, window and open
     * sessions. Idempotent; called from {@code onResume} so changes made on the
     * settings/theme/extra-keys screens take effect when the user comes back.
     */
    private void applyAllSettings() {
        applyTheme();               // colors + cursor style/blink + grapheme clustering
        applyBackgroundImage();
        applyTerminalFonts();
        applyTextMargins();
        applyKeepScreenOn(settings.keepScreenOn());
        applyImmersiveMode(settings.immersiveMode());
        terminal.setTouchKeyboardEnabled(settings.touchKeyboard());
        terminal.setRichKeyboard(settings.richKeyboard());
        terminal.setSmoothScroll(settings.smoothScroll());
        terminal.setMouseTracking(settings.mouseTracking());
        terminal.setTapToOpenLinks(settings.tapToOpenLinks());
        applyPromptNav();
        applyTerminateProcessesOnExit(settings.terminateProcessesOnExit());
        extraKeys.setShowSwitch(settings.showExtraKeysSwitch());
        extraKeys.setRowEnabled(settings.extraKeysEnabled());
        extraKeys.setHideWhenKeyboardHidden(settings.hideExtraKeysWhenKeyboardHidden());
        extraKeys.setKeyVerticalPaddingDp(settings.extraKeysVerticalPadding());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(exitReceiver);
    }

    /**
     * Pushes the selected theme colors and the global cursor-style/blink and
     * grapheme-clustering preferences to every open session, then repaints.
     * Called on resume (to pick up edits made in {@link ThemeActivity} or the
     * settings dialog) and after creating a session (to style it before any
     * output arrives).
     */
    private void applyTheme() {
        TerminalTheme theme = themeStore.current();
        int[] palette = theme.toPalette256();
        int cursorStyle = settings.cursorStyle();
        boolean cursorBlink = settings.cursorBlink();
        boolean grapheme = settings.graphemeClustering();
        for (TerminalSession s : sessions.sessions()) {
            s.emulator.setColors(theme.foreground, theme.background, theme.cursor, palette);
            s.emulator.setCursorStyle(cursorStyle, cursorBlink);
            s.emulator.setGraphemeClustering(grapheme);
        }
        terminal.setDefaultBackground(theme.background);
        applyChrome(ChromePalette.from(this, theme.background));
        terminal.invalidate();
    }

    /**
     * Recolors the main-screen chrome from the terminal theme's palette: the
     * bars, tab strip, keycaps, search bar, the system-bar bands (the root
     * fill) and the status/nav icon appearance. Dark themes resolve to the
     * stock token chrome, so the default look is unchanged; light themes get
     * a light chrome instead of a black frame.
     */
    private void applyChrome(ChromePalette p) {
        chrome = p;
        findViewById(R.id.root).setBackgroundColor(p.surface1);
        mainTopBar.setBackground(p.barSurface(true));
        styleTopBarButton(promptPrevButton);
        styleTopBarButton(promptNextButton);
        styleTopBarButton(findViewById(R.id.settings_button));
        setSearchButtonActive(searchBar.isOpen());
        tabs.applyPalette(p);
        extraKeys.applyPalette(p);
        searchBar.applyPalette(p);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(p.isLight ? mask : 0, mask);
            }
        } else {
            View decor = getWindow().getDecorView();
            int vis = decor.getSystemUiVisibility();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(p.isLight ? vis | flags : vis & ~flags);
        }
    }

    private void styleTopBarButton(TextView b) {
        b.setBackground(chrome.ripple(chrome.surface2,
                Chrome.dimen(this, R.dimen.radius_md), chrome.border));
        b.setTextColor(chrome.textSecondary);
        Glyphs.applyTo(b);  // re-tint the vector glyph from the new ink
    }

    /**
     * Loads the global background wallpaper (if any) and hands it to the view.
     * Decoded downsampled to the screen so a large photo stays cheap; a path
     * that no longer decodes is forgotten. The view takes ownership of the
     * bitmap and recycles it when replaced.
     */
    private void applyBackgroundImage() {
        String path = settings.backgroundImagePath();
        Bitmap bmp = null;
        if (path != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            bmp = BackgroundImageStore.decode(path, dm.widthPixels, dm.heightPixels,
                    settings.backgroundImageBlur());
            if (bmp == null) settings.setBackgroundImagePath(null); // stale/corrupt
        }
        int alpha = Math.round(settings.backgroundImageOpacity() * 2.55f);
        terminal.setBackgroundImage(bmp, alpha);
    }

    /** Loads custom terminal font files, forgetting paths that no longer decode. */
    private void applyTerminalFonts() {
        String regularPath = settings.terminalFontPath();
        Typeface regular = TerminalFontStore.load(regularPath);
        if (regularPath != null && regular == null) settings.setTerminalFontPath(null);

        String boldPath = settings.terminalBoldFontPath();
        Typeface bold = TerminalFontStore.load(boldPath);
        if (boldPath != null && bold == null) settings.setTerminalBoldFontPath(null);

        String italicPath = settings.terminalItalicFontPath();
        Typeface italic = TerminalFontStore.load(italicPath);
        if (italicPath != null && italic == null) settings.setTerminalItalicFontPath(null);

        String boldItalicPath = settings.terminalBoldItalicFontPath();
        Typeface boldItalic = TerminalFontStore.load(boldItalicPath);
        if (boldItalicPath != null && boldItalic == null) {
            settings.setTerminalBoldItalicFontPath(null);
        }

        terminal.setTerminalFonts(regular, bold, italic, boldItalic);
    }

    private boolean userlandByDefault() {
        return !forceShell && UserlandRootfs.isUsable(this);
    }

    /**
     * First tab: userland when usable, else a plain Android shell (no asset,
     * an installed-but-unusable rootfs we won't wipe, or a skipped setup;
     * createSession also falls back to the shell if a userland spawn fails).
     * Held back while the onboarding wizard is on top — install and the
     * distro choice happen there, and {@code onActivityResult} re-enters here.
     */
    private void createFirstSession() {
        if (awaitingOnboarding) return;
        createSession(userlandByDefault());
    }

    private void createSession(boolean userland) {
        try {
            UserlandOptions userlandOptions = new UserlandOptions(
                    settings.userlandLoginShell(), storageBindingEnabledForNewSession(),
                    settings.userlandIdentity(), settings.userlandHome(),
                    settings.userlandWorkDir(), settings.userlandLocale(),
                    settings.userlandPath(),
                    settings.userlandJitEnabled(), settings.userlandJitBufferMb(),
                    settings.userlandChrootNgEnabled());
            TerminalSession s = sessions.create(this,
                    terminal.gridCols(), terminal.gridRows(),
                    terminal.cellWidthPx(), terminal.cellHeightPx(),
                    settings.scrollbackLines(), userland, userlandOptions,
                    settings.terminateProcessesOnExit(), this);
            switchTo(s);
            applyTheme(); // color the new session before any output arrives
            if (settings.touchKeyboard()) showKeyboard();
            // Promote the process to foreground priority so the shell
            // survives backgrounding; refresh updates the session count.
            SessionService.refresh(this);
            maybePromptBatteryOptimization();
        } catch (IOException e) {
            if (userland) {
                // The rootfs went missing or is incomplete (deleted from a
                // shell, half-installed). Don't strand the user: fall back to
                // a plain Android shell rather than failing with nothing.
                Toast.makeText(this, getString(R.string.toast_userland_unavailable,
                        e.getMessage()), Toast.LENGTH_LONG).show();
                createSession(false);
            } else {
                Toast.makeText(this, getString(R.string.toast_shell_start_failed,
                        e.getMessage()), Toast.LENGTH_LONG).show();
                if (sessions.isEmpty()) finishAndRemoveTask();
            }
        }
    }

    // --- new-tab chooser ----------------------------------------------------

    /**
     * Long-pressing {@code +} offers every session type this build can open,
     * rather than toggling between two.
     *
     * With three of them a toggle no longer works, and the third is not a peer
     * of the other two anyway: a guest machine is started and stopped as a
     * whole, and its tabs are terminals on the machine that is already running.
     * So the list is built from what is actually possible right now — no
     * "Linux" row without a usable rootfs, no machine row without images, and
     * "start" and "stop" never offered at once.
     */
    private void showSessionTypeChooser() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (userlandByDefault()) {
            labels.add(getString(R.string.session_type_userland));
            actions.add(() -> createSession(true));
        } else if (!UserlandRootfs.isInstalled(this) && UserlandRootfs.assetAvailable(this)) {
            // Nothing installed yet, but we ship a rootfs: offer setup. An
            // installed-but-broken rootfs is deliberately never offered here —
            // it holds the user's data and is not wiped behind their back.
            labels.add(getString(R.string.session_type_install_linux));
            actions.add(this::startOnboardingSetup);
        }

        labels.add(getString(R.string.session_type_shell));
        actions.add(() -> createSession(false));

        VmMachine vm = VmMachine.get();
        if (VmMachine.isRunning() && vm != null) {
            int free = freeVmTerminal(vm);
            if (free >= 0) {
                labels.add(getString(R.string.session_type_vm_terminal,
                        vm.terminalName(free)));
                actions.add(() -> openVmTab(vm, free));
            }
            labels.add(getString(R.string.session_type_vm_stop));
            actions.add(this::confirmStopVm);
        } else if (VmImages.isInstalled(this) || VmImages.assetsAvailable(this)) {
            labels.add(getString(R.string.session_type_vm_start));
            actions.add(this::startVm);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.session_type_title)
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // --- guest machine ------------------------------------------------------

    /** Lowest machine terminal no tab is on, or -1 when they are all open. */
    private int freeVmTerminal(VmMachine vm) {
        for (int i = 0; i < vm.terminalCount(); i++) {
            if (!sessions.isVmTerminalOpen(i)) return i;
        }
        return -1;
    }

    /**
     * Boots a guest machine and opens its console as a tab.
     *
     * The images have to be real files first — the emulator opens them by path,
     * and an APK asset is an offset inside a zip — so a first run copies them
     * out, which is on the order of a hundred megabytes and belongs on a
     * background thread behind a progress dialog. After that the boot itself
     * needs no spinner: the console tab shows the firmware and kernel coming up
     * live, which is both the honest progress indicator and the thing to look
     * at when a boot goes wrong.
     */
    private void startVm() {
        if (VmImages.isInstalled(this)) {
            bootVm();
            return;
        }
        if (!VmImages.assetsAvailable(this)) {
            Toast.makeText(this, R.string.toast_vm_no_images, Toast.LENGTH_LONG).show();
            return;
        }

        TextView message = new TextView(this);
        int pad = Chrome.dp(this, R.dimen.space_5);
        message.setPaddingRelative(pad, pad, pad, pad);
        message.setText(R.string.vm_extracting);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.vm_extract_title)
                .setView(message)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            IOException failure = null;
            try {
                VmImages.install(this, (copied, total) -> {
                    if (total <= 0) return;
                    int percent = (int) (copied * 100 / total);
                    runOnUiThread(() -> message.setText(
                            getString(R.string.vm_extracting_percent, percent)));
                });
            } catch (IOException e) {
                failure = e;
            }
            IOException error = failure;
            runOnUiThread(() -> {
                dialog.dismiss();
                if (error != null) {
                    Toast.makeText(this, getString(R.string.toast_vm_extract_failed,
                            error.getMessage()), Toast.LENGTH_LONG).show();
                    return;
                }
                bootVm();
            });
        }, "vm-images").start();
    }

    private void bootVm() {
        java.io.File firmware = VmImages.firmware(this);
        java.io.File image = VmImages.image(this);
        if (image == null || !firmware.isFile()) {
            Toast.makeText(this, R.string.toast_vm_no_images, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            VmMachine vm = VmMachine.start(new VmOptions(firmware, image,
                    settings.vmMemoryMb(), settings.vmTerminals(),
                    settings.vmJitEnabled()));
            vm.setListener(code -> runOnUiThread(() -> onVmExited(code)));
            openVmTab(vm, 0);
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.toast_vm_start_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** {@code index} names the machine's terminal, not this activity's view. */
    private void openVmTab(VmMachine vm, int index) {
        try {
            TerminalSession s = sessions.attachVm(vm, index,
                    terminal.gridCols(), terminal.gridRows(),
                    terminal.cellWidthPx(), terminal.cellHeightPx(),
                    settings.scrollbackLines(), this);
            switchTo(s);
            applyTheme();       // color the new tab before any output arrives
            if (settings.touchKeyboard()) showKeyboard();
            SessionService.refresh(this);
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.toast_vm_attach_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Stopping the machine kills every guest process at once, so it asks first
     * — and says so, because an ISO guest is diskless and nothing in it
     * survives.
     */
    private void confirmStopVm() {
        Dialogs.confirmDanger(this, getString(R.string.vm_stop_confirm_title),
                getString(R.string.vm_stop_confirm_message),
                R.string.vm_stop_confirm_button, VmMachine::stopIfRunning);
    }

    /**
     * The machine went away — stopped from the chooser, or died. Its tabs are
     * views onto terminals that no longer exist, so they go with it; each
     * session's channel has already hit EOF and reported itself, but a tab the
     * user was not looking at may not have been torn down yet.
     */
    private void onVmExited(int exitCode) {
        for (TerminalSession s : sessions.sessions()) {
            if (s.isVm()) closeTab(s);
        }
    }

    /**
     * Opens the onboarding wizard in setup-only mode (distro chooser +
     * install, no intro) — the path for adding a userland after it was
     * skipped or never bundled-and-installed. The result opens the first
     * userland tab.
     */
    private void startOnboardingSetup() {
        startActivityForResult(new Intent(this, OnboardingActivity.class)
                .putExtra(OnboardingActivity.EXTRA_SETUP_ONLY, true), REQ_ONBOARDING);
    }

    private void switchTo(TerminalSession s) {
        if (searchBar != null) hideSearch(); // matches belong to the old session
        current = s;
        terminal.attachSession(s);
        updateTabs();
    }

    private void showKeyboard() {
        terminal.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(terminal, 0);
    }

    /** Opens the find bar and highlights the search button. */
    private void showSearch() {
        searchBar.show();
        setSearchButtonActive(true);
        backGesture.setEnabled(true); // back closes the bar, not the app
    }

    /** Collapses the find bar and clears its match highlight. */
    private void hideSearch() {
        boolean wasOpen = searchBar.isOpen();
        searchBar.hide();
        setSearchButtonActive(false);
        backGesture.setEnabled(false);
        terminal.searchClose();
        // Always restore the keyboard when search was actually open; search
        // requires it and the user expects it back when dismissing the bar.
        // When called from switchTo() with a closed bar, skip it — the caller
        // handles keyboard visibility based on the touch-keyboard setting.
        if (wasOpen) showKeyboard();
    }

    /** Swaps the search button between the idle chip and the accent-fill active look. */
    private void setSearchButtonActive(boolean active) {
        float r = Chrome.dimen(this, R.dimen.radius_md);
        if (active) {
            searchButton.setBackground(chrome.ripple(chrome.accent, r, 0));
            searchButton.setTextColor(chrome.onAccent);
        } else {
            searchButton.setBackground(chrome.ripple(chrome.surface2, r, chrome.border));
            searchButton.setTextColor(chrome.textSecondary);
        }
        // Glyphs bakes the icon tint from the current text color, so re-span
        // the 🔍 to pick up the new color.
        Glyphs.applyTo(searchButton);
    }

    @Override
    public void onBackPressed() {
        if (handleBack()) return;
        super.onBackPressed();
    }

    /**
     * Back handling shared by {@code onBackPressed} and the predictive-back
     * callback: an open find bar closes first. Returns true when back was
     * consumed here rather than leaving the app.
     */
    private boolean handleBack() {
        if (searchBar == null || !searchBar.isOpen()) return false;
        hideSearch();
        return true;
    }

    /**
     * Gate in front of {@link #closeTab}: closing kills the shell
     * irrecoverably (and closing the last tab exits the app), and with a
     * close button on every pill a stray tap while scrolling the strip is
     * easy — so ask first, unless the user turned the guard off in Settings.
     */
    private void confirmCloseTab(TerminalSession s) {
        if (!settings.confirmSessionClose()) {
            closeTab(s);
            return;
        }
        int index = sessions.indexOf(s);
        if (index < 0) return; // already gone (e.g. the shell exited)
        Dialogs.confirmDanger(this, getString(R.string.session_close_confirm_title),
                getString(R.string.session_close_confirm_message, tabTitle(s, index)),
                R.string.session_close_confirm_button, () -> closeTab(s));
    }

    private void closeTab(TerminalSession s) {
        int closedIndex = sessions.indexOf(s);
        if (closedIndex < 0) {
            if (sessions.isEmpty()) {
                terminal.attachSession(null);
                current = null;
            }
            updateTabs();
            return;
        }
        sessions.close(s);
        List<TerminalSession> remaining = sessions.sessions();
        if (remaining.isEmpty()) {
            SessionService.stop(this);
            finishAndRemoveTask();
            return;
        }
        SessionService.refresh(this); // reflect the new count in the notification
        if (s == current) {
            switchTo(remaining.get(Math.min(closedIndex, remaining.size() - 1)));
        } else {
            updateTabs();
        }
    }

    /**
     * Asks once (ever) to exempt the app from Doze battery optimization, so
     * shells keep getting CPU during long idle periods. Silently skipped if
     * already exempt or if the device has no such settings screen.
     */
    private void maybePromptBatteryOptimization() {
        // The test seam must never trigger a system dialog over Espresso.
        if (forceShell) return;
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean(PREF_ASKED_BATTERY_OPT, false)) return;
        prefs.edit().putBoolean(PREF_ASKED_BATTERY_OPT, true).apply();
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException ignored) {
            // No battery-optimization UI on this device.
        }
    }

    /** Opens the dedicated settings screen from the gear button in the top bar. */
    private void openSettings(View ignored) {
        // SettingsActivity persists changes to the stores; onResume re-applies
        // them. Backup/restore are terminal-coupled, so it hands those back as an
        // activity result rather than running them itself.
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    private void applyTerminateProcessesOnExit(boolean enabled) {
        for (TerminalSession s : sessions.sessions()) {
            s.setTerminateProcessesOnExit(enabled);
        }
    }

    private boolean storageBindingEnabledForNewSession() {
        if (!settings.bindExternalStorage()) return false;
        if (StoragePermission.granted(this)) return true;
        settings.setBindExternalStorage(false);
        return false;
    }

    private void disableStorageBindingIfPermissionRevoked() {
        if (settings.bindExternalStorage() && !StoragePermission.granted(this)) {
            settings.setBindExternalStorage(false);
        }
    }

    // --- Userland rootfs backup & restore ---

    /** Lets the user pick a destination, then streams the rootfs into it. */
    private void startBackup() {
        String name = "userland-backup-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date())
                + ".tar.gz";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/gzip")
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQ_BACKUP);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    /** Restore is destructive (replaces the rootfs, closes sessions): confirm first. */
    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.restore_confirm_choose, (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"); // gzip MIME detection is unreliable across providers
                    try {
                        startActivityForResult(intent, REQ_RESTORE);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, R.string.backup_no_picker,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ONBOARDING) {
            awaitingOnboarding = false;
            maybeRequestNotificationsPermission(); // deferred from onCreate
            if (sessions.isEmpty()) {
                // First run: the deferred first spawn was held back while the
                // wizard ran. The layout pass usually happened underneath it —
                // spawn now; otherwise the pending layout listener does it.
                // A canceled wizard leaves the pref unset (it re-shows next
                // launch) but still gets a shell for this run.
                if (terminal.getWidth() > 0) createFirstSession();
            } else if (resultCode == RESULT_OK && UserlandRootfs.isUsable(this)) {
                // Setup-only mode: open a tab into the fresh userland.
                createSession(true);
            }
            return;
        }
        // Settings hands back the terminal-coupled userland flows to run here.
        if (requestCode == REQ_SETTINGS) {
            if (resultCode == RESULT_OK && data != null) {
                String action = data.getStringExtra(SettingsActivity.EXTRA_ACTION);
                if (SettingsActivity.ACTION_BACKUP.equals(action)) {
                    startBackup();
                } else if (SettingsActivity.ACTION_RESTORE.equals(action)) {
                    confirmRestore();
                } else if (SettingsActivity.ACTION_SETUP_USERLAND.equals(action)) {
                    startOnboardingSetup();
                }
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_BACKUP) {
            runBackup(uri);
        } else if (requestCode == REQ_RESTORE) {
            runRestore(uri);
        }
    }

    private void runBackup(Uri uri) {
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressHandle ui = showProgress(R.string.backup_in_progress,
                R.string.backup_progress, cancelled);
        new Thread(() -> {
            IOException failure = null;
            boolean wasCancelled = false;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("cannot open destination");
                RootfsBackup.backup(getApplicationContext(), out, ui::update, cancelled);
            } catch (InterruptedIOException e) {
                wasCancelled = true;
            } catch (IOException e) {
                failure = e;
            }
            final IOException error = failure;
            final boolean cancelledFinal = wasCancelled;
            runOnUiThread(() -> {
                ui.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (cancelledFinal) {
                    Toast.makeText(this, R.string.backup_cancelled, Toast.LENGTH_LONG).show();
                } else if (error == null) {
                    Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.backup_failed,
                            error.getMessage()), Toast.LENGTH_LONG).show();
                }
            });
        }, "rootfs-backup").start();
    }

    private void runRestore(Uri uri) {
        // Tear down every session before the rootfs is swapped: a live emulator
        // process must not hold the tree we are about to delete and replace.
        terminal.attachSession(null);
        current = null;
        sessions.closeAll();
        updateTabs();
        SessionService.refresh(this);

        long archiveSize = querySize(uri); // restore progress denominator
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressHandle ui = showProgress(R.string.restore_in_progress,
                R.string.restore_progress, cancelled);
        new Thread(() -> {
            IOException failure = null;
            boolean wasCancelled = false;
            // restore() reads the archive twice (probe + extract), so hand it a
            // reopener rather than a single stream.
            try {
                RootfsBackup.restore(getApplicationContext(), () -> {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("cannot open backup file");
                    return in;
                }, archiveSize, ui::update, cancelled);
            } catch (InterruptedIOException e) {
                wasCancelled = true;
            } catch (IOException e) {
                failure = e;
            }
            final IOException error = failure;
            final boolean cancelledFinal = wasCancelled;
            runOnUiThread(() -> {
                ui.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (error == null && !cancelledFinal) {
                    // The archive was installed as given. If it doesn't look like
                    // a launchable userland rootfs (no /bin/bash — could be a custom
                    // image), keep it but warn and fall back to the Android
                    // shell rather than failing to open a session.
                    boolean usable = UserlandRootfs.isUsable(this);
                    Toast.makeText(this,
                            usable ? R.string.restore_done : R.string.restore_no_shell,
                            usable ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                    createSession(usable);
                } else {
                    // Failed or cancelled: the swap is atomic, so the original
                    // rootfs is intact — just reopen a session on whatever's there.
                    if (cancelledFinal) {
                        Toast.makeText(this, R.string.restore_cancelled,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, getString(R.string.restore_failed,
                                error.getMessage()), Toast.LENGTH_LONG).show();
                    }
                    createSession(UserlandRootfs.isUsable(this));
                }
            });
        }, "rootfs-restore").start();
    }

    /** The byte length the picker reports for a SAF document, or 0 if unknown. */
    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
            // size is a nicety; an unknown size just falls back to indeterminate
        }
        return 0;
    }

    /**
     * Shows a modal progress dialog with a horizontal bar. It starts
     * indeterminate and switches to a determinate percentage on the first
     * {@link ProgressHandle#update} that carries a known total. The Cancel
     * button flips {@code cancelled} and shows "Cancelling…" without dismissing
     * — the worker dismisses it as it unwinds, and later progress ticks no
     * longer overwrite the cancelling message. {@code progressFmtRes} is a
     * three-arg string (percent, done MB, total MB). The returned handle's
     * methods are safe to call from a background thread.
     */
    private ProgressHandle showProgress(int titleRes, int progressFmtRes,
            AtomicBoolean cancelled) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView status = new TextView(this);
        status.setText(titleRes);
        status.setTextColor(getColor(R.color.text_primary));
        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        box.addView(status);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        box.addView(bar, barLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        // Replacing the button listener after show() suppresses the default
        // auto-dismiss, so the dialog stays up until the worker tears it down.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled.set(true);
            status.setText(R.string.progress_cancelling);
        });
        return new ProgressHandle(dialog, status, bar, progressFmtRes, cancelled);
    }

    /** A live progress dialog with thread-safe, determinate updates. */
    private final class ProgressHandle {
        private final AlertDialog dialog;
        private final TextView status;
        private final ProgressBar bar;
        private final int progressFmtRes;
        private final AtomicBoolean cancelled;

        ProgressHandle(AlertDialog dialog, TextView status, ProgressBar bar,
                int progressFmtRes, AtomicBoolean cancelled) {
            this.dialog = dialog;
            this.status = status;
            this.bar = bar;
            this.progressFmtRes = progressFmtRes;
            this.cancelled = cancelled;
        }

        /**
         * Sets the bar to {@code done / total}. A {@code total} of 0 (unknown)
         * leaves the bar indeterminate; once cancelling, updates are dropped so
         * the "Cancelling…" message stays put.
         */
        void update(long done, long total) {
            runOnUiThread(() -> {
                if (!dialog.isShowing() || cancelled.get() || total <= 0) return;
                int pct = (int) Math.min(100, done * 100 / total);
                bar.setIndeterminate(false);
                bar.setProgress(pct);
                status.setText(getString(progressFmtRes, pct,
                        done >> 20, total >> 20));
            });
        }

        void dismiss() {
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    /**
     * Re-evaluates the prompt-navigation buttons' visibility for the current
     * scroll position (they show only while scrolled into history).
     */
    private void applyPromptNav() {
        updatePromptNav(terminal.isAtBottom());
    }

    /**
     * Shows the prompt-navigation (OSC 133) buttons only when the feature is
     * enabled and the terminal is scrolled into history; hidden at the live
     * bottom so the tab strip keeps the full top-bar width.
     */
    private void updatePromptNav(boolean atBottom) {
        boolean show = settings.promptNav() && !atBottom;
        animateContextualChip(promptPrevButton, show);
        animateContextualChip(promptNextButton, show);
    }

    /** Fades/scales a contextual top-bar chip in or out instead of snapping. */
    private static void animateContextualChip(View v, boolean show) {
        Boolean desired = (Boolean) v.getTag();
        if (desired != null && desired == show) return;
        v.setTag(show);
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            v.setScaleX(0.8f);
            v.setScaleY(0.8f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
        } else {
            v.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(120)
                    .withEndAction(() -> {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                        v.setScaleX(1f);
                        v.setScaleY(1f);
                    }).start();
        }
    }

    private void applyTextMargins() {
        float density = getResources().getDisplayMetrics().density;
        int leftPx = Math.round(settings.textMarginLeft() * density);
        int rightPx = Math.round(settings.textMarginRight() * density);
        terminal.setTextMargins(leftPx, rightPx);
    }

    /** Holds the display on (or releases it) via the activity window flag. */
    private void applyKeepScreenOn(boolean enabled) {
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Hides or restores the status/navigation bars for a fullscreen terminal surface. */
    private void applyImmersiveMode(boolean enabled) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            if (enabled) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
            return;
        }

        View decor = getWindow().getDecorView();
        if (enabled) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** A session's tab label: its OSC title, or "label:position" while unnamed. */
    private static String tabTitle(TerminalSession s, int index) {
        String t = s.title();
        if (t != null && !t.isEmpty()) return t;
        // A guest terminal is already named uniquely by the machine ("hvc1"),
        // so it needs no tab position to tell it from its siblings the way a
        // row of identical shells does.
        return s.isVm() ? s.label() : s.label() + ":" + (index + 1);
    }

    private void updateTabs() {
        List<String> titles = new ArrayList<>();
        List<TabStripView.TabProgress> progress = new ArrayList<>();
        List<TerminalSession> all = sessions.sessions();
        boolean showProgress = settings.showProgress();
        for (int i = 0; i < all.size(); i++) {
            TerminalSession s = all.get(i);
            titles.add(tabTitle(s, i));
            progress.add(showProgress
                    ? new TabStripView.TabProgress(s.progressState(), s.progressValue())
                    : TabStripView.TabProgress.NONE);
        }
        tabs.update(titles, sessions.indexOf(current), progress);
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
        if (session != current) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastBellUptime < BELL_THROTTLE_MS) return;
        lastBellUptime = now;
        int mode = settings.terminalBellMode();
        if (mode == AppSettings.BELL_HAPTIC) {
            vibrateBell();
        } else if (mode == AppSettings.BELL_SCREEN_FLASH) {
            terminal.flashBell();
        } else if (mode == AppSettings.BELL_SOUND) {
            playBellSound();
        }
    }

    @Override
    public void onClipboardWrite(TerminalSession session, String sel, byte[] data) {
        if (!settings.clipboardWrite()) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        String text = new String(data, StandardCharsets.UTF_8);
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        // A program can set the clipboard in a loop; throttle the confirmation.
        long now = SystemClock.uptimeMillis();
        if (now - lastClipToastUptime >= 1500) {
            lastClipToastUptime = now;
            Toast.makeText(this, R.string.clipboard_set, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onClipboardQuery(TerminalSession session, String sel) {
        // Off by default: answering lets any program read the clipboard.
        if (!settings.clipboardRead()) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        String text = "";
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence cs = clip.getItemAt(0).coerceToText(this);
                if (cs != null) text = cs.toString();
            }
        }
        String b64 = Base64.encodeToString(
                text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        // OSC 52 reply: ESC ] 52 ; <sel> ; <base64> ST, echoing the requested
        // selection (sanitized to the protocol's target letters).
        String resp = "\u001b]52;" + sanitizeClipboardSelection(sel) + ";" + b64
                + "\u001b\\";
        session.sendClipboardResponse(resp.getBytes(StandardCharsets.US_ASCII));
    }

    /** Keeps only OSC 52 selection letters (c/p/q/s and buffers 0-7); defaults to "c". */
    private static String sanitizeClipboardSelection(String sel) {
        StringBuilder sb = new StringBuilder(sel.length());
        for (int i = 0; i < sel.length(); i++) {
            char ch = sel.charAt(i);
            if ("cpqs01234567".indexOf(ch) >= 0) sb.append(ch);
        }
        return sb.length() == 0 ? "c" : sb.toString();
    }

    @Override
    public void onProgress(TerminalSession session, int state, int value) {
        int index = sessions.indexOf(session);
        if (index < 0) return;
        if (!settings.showProgress()) {
            tabs.setProgress(index, TerminalSession.PROGRESS_NONE, 0);
            return;
        }
        tabs.setProgress(index, state, value);
    }

    private void vibrateBell() {
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createOneShot(
                BELL_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void playBellSound() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            terminal.postDelayed(tone::release, 250);
        } catch (RuntimeException ignored) {
            // Audio service unavailable; drop the bell rather than surfacing a UI error.
        }
    }

    @Override
    public void onExited(TerminalSession session, int exitCode) {
        if (sessions.indexOf(session) < 0) return;
        // A userland session that exits before the user ever typed into it never
        // really came up: the rootfs was deleted out from under us, or bash is
        // unusable and the emulator bailed at launch. If it was the last tab, closing
        // it would finish() the app — so the whole app vanishes on launch.
        // Instead, drop to a plain shell so the user keeps a working terminal.
        boolean lastTab = sessions.sessions().size() == 1;
        // Same reasoning for a guest machine as for a userland session: one that
        // ends before the user ever typed into it never came up — the images are
        // unusable, or the machine died during boot — and letting that close the
        // last tab would make the app vanish instead of showing anything.
        boolean startupFailure = (session.isUserland() || session.isVm())
                && !session.userInteracted();
        if (lastTab && startupFailure) {
            sessions.close(session);
            Toast.makeText(this, R.string.toast_userland_session_failed,
                    Toast.LENGTH_LONG).show();
            createSession(false);
            return;
        }
        closeTab(session);
    }
}
