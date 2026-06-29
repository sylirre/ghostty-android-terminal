package sh.easycli.proot.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
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
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import sh.easycli.proot.R;
import sh.easycli.proot.term.DebianRootfs;
import sh.easycli.proot.term.RootfsBackup;
import sh.easycli.proot.term.SessionManager;
import sh.easycli.proot.term.SessionService;
import sh.easycli.proot.term.TerminalSession;

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
    public static final String EXTRA_FORCE_SHELL = "sh.easycli.proot.FORCE_SHELL";

    private static final int REQ_POST_NOTIFICATIONS = 1;
    private static final int REQ_STORAGE_PERMISSION = 2;
    private static final int REQ_BACKUP = 100;
    private static final int REQ_RESTORE = 101;
    private static final String PREF_ASKED_BATTERY_OPT = "asked_ignore_battery_opt";
    private static final long BELL_VIBRATION_MS = 300;
    private static final long BELL_THROTTLE_MS = BELL_VIBRATION_MS;

    private final SessionManager sessions = SessionManager.get();
    private TerminalView terminal;
    private TabStripView tabs;
    private SearchBarView searchBar;
    private ExtraKeysView extraKeys;
    private TextView installStatus;
    private TerminalSession current;
    private AppSettings settings;
    private ThemeStore themeStore;
    private ExtraKeysConfig extraKeysConfig;
    private boolean forceShell;
    private boolean pendingEnableStorageBinding;
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
        installStatus = findViewById(R.id.install_status);
        forceShell = getIntent().getBooleanExtra(EXTRA_FORCE_SHELL, false);
        extraKeys = findViewById(R.id.extra_keys);
        extraKeys.attachTerminal(terminal);

        settings = new AppSettings(this);
        themeStore = new ThemeStore(this);
        extraKeysConfig = new ExtraKeysConfig(this);
        extraKeys.setConfig(extraKeysConfig);
        extraKeys.setRowEnabled(settings.extraKeysEnabled());
        extraKeys.setHideWhenKeyboardHidden(settings.hideExtraKeysWhenKeyboardHidden());
        applyKeepScreenOn(settings.keepScreenOn());
        terminal.setRichKeyboard(settings.richKeyboard());
        terminal.setTouchKeyboardEnabled(settings.touchKeyboard());
        terminal.setSmoothScroll(settings.smoothScroll());
        terminal.setMouseTracking(settings.mouseTracking());
        applyTextMargins();
        findViewById(R.id.settings_button).setOnClickListener(this::showSettings);
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
        findViewById(R.id.search_button).setOnClickListener(v -> {
            if (searchBar.isOpen()) hideSearch();
            else searchBar.show();
        });
        Glyphs.applyTo(findViewById(R.id.search_button));

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean imeVisible;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
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
                // Only ever extract when nothing is installed yet; an installed
                // but broken rootfs is left alone (createSession falls back to
                // a shell) rather than wiped and rebuilt behind the user's back.
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

        IntentFilter filter = new IntentFilter(SessionService.ACTION_EXITED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(exitReceiver, filter);
        }
        // Needed (33+) for the foreground-service notification to be visible;
        // the service still runs if denied. Skipped under the test seam so
        // the system dialog never lands on top of Espresso.
        if (!forceShell && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pick up theme changes made in ThemeActivity (and seed the first paint).
        applyTheme();
        // Pick up a wallpaper chosen/removed in ThemeActivity.
        applyBackgroundImage();
        // Pick up font choices made in ThemeActivity.
        applyTerminalFonts();
        // Pick up extra-keys edits made in ExtraKeysActivity, and re-apply
        // the show/hide toggle.
        extraKeys.setRowEnabled(settings.extraKeysEnabled());
        extraKeys.setHideWhenKeyboardHidden(settings.hideExtraKeysWhenKeyboardHidden());
        terminal.setTouchKeyboardEnabled(settings.touchKeyboard());
        disableStorageBindingIfPermissionRevoked();
        completePendingStorageBindingIfGranted();
        if (current != null && settings.touchKeyboard()) showKeyboard();
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
        terminal.invalidate();
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

    private boolean debianByDefault() {
        return !forceShell && DebianRootfs.isUsable(this);
    }

    /** First tab: Debian when usable, install flow when bundled but never installed. */
    private void createFirstSession() {
        if (debianByDefault()) {
            createSession(true);
        } else if (!forceShell && !DebianRootfs.isInstalled(this)
                && DebianRootfs.assetAvailable(this)) {
            installDebianThenCreateSession();
        } else {
            // No asset, or an installed-but-unusable rootfs we won't wipe:
            // a plain Android shell. createSession also falls back here if a
            // Debian spawn fails.
            createSession(false);
        }
    }

    private void createSession(boolean debian) {
        try {
            boolean bindExternalStorage = storageBindingEnabledForNewSession();
            TerminalSession s = sessions.create(this,
                    terminal.gridCols(), terminal.gridRows(),
                    terminal.cellWidthPx(), terminal.cellHeightPx(),
                    settings.scrollbackLines(), debian, settings.prootLoginShell(),
                    bindExternalStorage, this);
            switchTo(s);
            applyTheme(); // color the new session before any output arrives
            if (settings.touchKeyboard()) showKeyboard();
            // Promote the process to foreground priority so the shell
            // survives backgrounding; refresh updates the session count.
            SessionService.refresh(this);
            maybePromptBatteryOptimization();
        } catch (IOException e) {
            if (debian) {
                // The rootfs went missing or is incomplete (deleted from a
                // shell, half-installed). Don't strand the user: fall back to
                // a plain Android shell rather than failing with nothing.
                Toast.makeText(this, getString(R.string.toast_debian_unavailable,
                        e.getMessage()), Toast.LENGTH_LONG).show();
                createSession(false);
            } else {
                Toast.makeText(this, getString(R.string.toast_shell_start_failed,
                        e.getMessage()), Toast.LENGTH_LONG).show();
                if (sessions.isEmpty()) finishAndRemoveTask();
            }
        }
    }

    /**
     * One-time rootfs extraction on a background thread; the overlay shows
     * progress. Install is idempotent/synchronized, so a racing second
     * activity instance at worst waits and then finds it installed.
     */
    private void installDebianThenCreateSession() {
        installStatus.setText(R.string.installing_debian);
        installStatus.setVisibility(View.VISIBLE);
        new Thread(() -> {
            IOException failure = null;
            try {
                DebianRootfs.install(getApplicationContext(), bytes ->
                        runOnUiThread(() -> installStatus.setText(
                                getString(R.string.installing_debian_progress, bytes >> 20))));
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
                    Toast.makeText(this, getString(R.string.toast_debian_install_failed,
                            error.getMessage()), Toast.LENGTH_LONG).show();
                    if (sessions.isEmpty()) createSession(false);
                }
            });
        }, "debian-install").start();
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

    /** Collapses the find bar and clears its match highlight. */
    private void hideSearch() {
        boolean wasOpen = searchBar.isOpen();
        searchBar.hide();
        terminal.searchClose();
        // Always restore the keyboard when search was actually open; search
        // requires it and the user expects it back when dismissing the bar.
        // When called from switchTo() with a closed bar, skip it — the caller
        // handles keyboard visibility based on the touch-keyboard setting.
        if (wasOpen) showKeyboard();
    }

    @Override
    public void onBackPressed() {
        if (searchBar != null && searchBar.isOpen()) {
            hideSearch();
            return;
        }
        super.onBackPressed();
    }

    private void closeTab(TerminalSession s) {
        sessions.close(s);
        if (sessions.isEmpty()) {
            SessionService.stop(this);
            finishAndRemoveTask();
            return;
        }
        SessionService.refresh(this); // reflect the new count in the notification
        if (s == current) {
            switchTo(sessions.sessions().get(sessions.sessions().size() - 1));
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

    /** Opens the settings dialog from the gear button in the top bar. */
    private void showSettings(View ignored) {
        List<Setting> items = new ArrayList<>();
        items.add(new Setting.Action(
                getString(R.string.setting_theme_title),
                getString(R.string.setting_theme_summary),
                () -> themeStore.current().name,
                () -> startActivity(new Intent(this, ThemeActivity.class))));
        items.add(new Setting.Toggle(
                getString(R.string.setting_keep_screen_on_title),
                getString(R.string.setting_keep_screen_on_summary),
                settings::keepScreenOn,
                enabled -> {
                    settings.setKeepScreenOn(enabled);
                    applyKeepScreenOn(enabled);
                }));
        items.add(new Setting.Toggle(
                getString(R.string.setting_touch_keyboard_title),
                getString(R.string.setting_touch_keyboard_summary),
                settings::touchKeyboard,
                enabled -> {
                    settings.setTouchKeyboard(enabled);
                    terminal.setTouchKeyboardEnabled(enabled);
                }));
        items.add(new Setting.Toggle(
                getString(R.string.setting_rich_keyboard_title),
                getString(R.string.setting_rich_keyboard_summary),
                settings::richKeyboard,
                enabled -> {
                    settings.setRichKeyboard(enabled);
                    terminal.setRichKeyboard(enabled);
                })
                .enabledWhen(settings::touchKeyboard));
        items.add(new Setting.Toggle(
                getString(R.string.setting_grapheme_title),
                getString(R.string.setting_grapheme_summary),
                settings::graphemeClustering,
                enabled -> {
                    settings.setGraphemeClustering(enabled);
                    applyTheme(); // re-pushes the mode to every open session
                }));
        items.add(new Setting.Toggle(
                getString(R.string.setting_smooth_scroll_title),
                getString(R.string.setting_smooth_scroll_summary),
                settings::smoothScroll,
                enabled -> {
                    settings.setSmoothScroll(enabled);
                    terminal.setSmoothScroll(enabled);
                }));
        items.add(new Setting.Toggle(
                getString(R.string.setting_mouse_tracking_title),
                getString(R.string.setting_mouse_tracking_summary),
                settings::mouseTracking,
                enabled -> {
                    settings.setMouseTracking(enabled);
                    terminal.setMouseTracking(enabled);
                }));
        items.add(new Setting.RequestToggle(
                getString(R.string.setting_bind_storage_title),
                getString(R.string.setting_bind_storage_summary),
                settings::bindExternalStorage,
                this::setBindExternalStorageRequested));
        items.add(new Setting.Choice(
                getString(R.string.setting_terminal_bell_title),
                getString(R.string.setting_terminal_bell_summary),
                getResources().getIntArray(R.array.terminal_bell_mode_values),
                getResources().getStringArray(R.array.terminal_bell_mode_labels),
                settings::terminalBellMode,
                settings::setTerminalBellMode));
        items.add(new Setting.Toggle(
                getString(R.string.setting_extra_keys_enabled_title),
                getString(R.string.setting_extra_keys_enabled_summary),
                settings::extraKeysEnabled,
                enabled -> {
                    settings.setExtraKeysEnabled(enabled);
                    extraKeys.setRowEnabled(enabled);
                }));
        items.add(new Setting.Toggle(
                getString(R.string.setting_extra_keys_hide_when_kb_hidden_title),
                getString(R.string.setting_extra_keys_hide_when_kb_hidden_summary),
                settings::hideExtraKeysWhenKeyboardHidden,
                hide -> {
                    settings.setHideExtraKeysWhenKeyboardHidden(hide);
                    extraKeys.setHideWhenKeyboardHidden(hide);
                })
                .enabledWhen(settings::extraKeysEnabled));
        // Greyed out while the toolbar is off — its keys are kept, just hidden,
        // so there is nothing to edit until it is shown again.
        items.add(new Setting.Action(
                getString(R.string.setting_extra_keys_title),
                getString(R.string.setting_extra_keys_summary),
                () -> getResources().getQuantityString(R.plurals.extra_keys_count,
                        extraKeysConfig.order().size(), extraKeysConfig.order().size()),
                () -> startActivity(new Intent(this, ExtraKeysActivity.class)))
                .enabledWhen(settings::extraKeysEnabled));
        items.add(new Setting.Choice(
                getString(R.string.setting_scrollback_title),
                getString(R.string.setting_scrollback_summary),
                getResources().getIntArray(R.array.scrollback_option_values),
                getResources().getStringArray(R.array.scrollback_option_labels),
                settings::scrollbackLines,
                settings::setScrollbackLines));
        items.add(new Setting.Choice(
                getString(R.string.setting_text_margin_left_title),
                getString(R.string.setting_text_margin_left_summary),
                getResources().getIntArray(R.array.text_margin_option_values),
                getResources().getStringArray(R.array.text_margin_option_labels),
                settings::textMarginLeft,
                dp -> {
                    settings.setTextMarginLeft(dp);
                    applyTextMargins();
                }));
        items.add(new Setting.Choice(
                getString(R.string.setting_text_margin_right_title),
                getString(R.string.setting_text_margin_right_summary),
                getResources().getIntArray(R.array.text_margin_option_values),
                getResources().getStringArray(R.array.text_margin_option_labels),
                settings::textMarginRight,
                dp -> {
                    settings.setTextMarginRight(dp);
                    applyTextMargins();
                }));
        // Debian-specific settings: only meaningful on an ABI that can run it.
        if (DebianRootfs.assetName() != null) {
            items.add(new Setting.Action(
                    getString(R.string.setting_proot_shell_title),
                    getString(R.string.setting_proot_shell_summary),
                    settings::prootLoginShell,
                    this::showLoginShellDialog));
            items.add(new Setting.Action(
                    getString(R.string.setting_backup_title),
                    getString(R.string.setting_backup_summary),
                    () -> "",
                    this::startBackup)
                    .enabledWhen(() -> DebianRootfs.isInstalled(this)));
            items.add(new Setting.Action(
                    getString(R.string.setting_restore_title),
                    getString(R.string.setting_restore_summary),
                    () -> "",
                    this::confirmRestore));
        }
        SettingsDialog.show(this, items);
    }

    private boolean setBindExternalStorageRequested(boolean enabled) {
        if (!enabled) {
            pendingEnableStorageBinding = false;
            settings.setBindExternalStorage(false);
            return true;
        }
        if (hasStorageBindingPermission()) {
            settings.setBindExternalStorage(true);
            return true;
        }
        pendingEnableStorageBinding = true;
        requestStorageBindingPermission();
        return false;
    }

    private boolean storageBindingEnabledForNewSession() {
        if (!settings.bindExternalStorage()) return false;
        if (hasStorageBindingPermission()) return true;
        settings.setBindExternalStorage(false);
        return false;
    }

    private void disableStorageBindingIfPermissionRevoked() {
        if (settings.bindExternalStorage() && !hasStorageBindingPermission()) {
            settings.setBindExternalStorage(false);
        }
    }

    private boolean hasStorageBindingPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageBindingPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent appSettings = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(appSettings);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException ignored) {
                    pendingEnableStorageBinding = false;
                    Toast.makeText(this, R.string.storage_permission_settings_unavailable,
                            Toast.LENGTH_LONG).show();
                }
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE_PERMISSION);
        }
    }

    private void completePendingStorageBindingIfGranted() {
        if (!pendingEnableStorageBinding || Build.VERSION.SDK_INT < 30) return;
        pendingEnableStorageBinding = false;
        if (hasStorageBindingPermission()) {
            settings.setBindExternalStorage(true);
            Toast.makeText(this, R.string.storage_binding_enabled, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE_PERMISSION) return;
        pendingEnableStorageBinding = false;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            settings.setBindExternalStorage(true);
            Toast.makeText(this, R.string.storage_binding_enabled, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    // --- Debian rootfs settings ---

    private void showLoginShellDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint(R.string.setting_proot_shell_hint);
        input.setText(settings.prootLoginShell());

        LinearLayout container = new LinearLayout(this);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(p, p / 2, p, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.setting_proot_shell_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String shell = input.getText().toString().trim();
                    if (!shell.isEmpty()) settings.setProotLoginShell(shell);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- Debian rootfs backup & restore ---

    /** Lets the user pick a destination, then streams the rootfs into it. */
    private void startBackup() {
        String name = "debian-rootfs-"
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
        // Tear down every session before the rootfs is swapped: a live PRoot
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
                    // a launchable Debian rootfs (no /bin/bash — could be a custom
                    // or non-Debian image), keep it but warn and fall back to the
                    // Android shell rather than failing to open a session.
                    boolean usable = DebianRootfs.isUsable(this);
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
                    createSession(DebianRootfs.isUsable(this));
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
        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        box.addView(status);
        box.addView(bar);

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
        // A Debian session that exits before the user ever typed into it never
        // really came up: the rootfs was deleted out from under us, or bash is
        // unusable and PRoot bailed at launch. If it was the last tab, closing
        // it would finish() the app — so the whole app vanishes on launch.
        // Instead, drop to a plain shell so the user keeps a working terminal.
        boolean lastTab = sessions.sessions().size() == 1;
        boolean startupFailure = session.isDebian() && !session.userInteracted();
        if (lastTab && startupFailure) {
            sessions.close(session);
            Toast.makeText(this, R.string.toast_debian_session_failed,
                    Toast.LENGTH_LONG).show();
            createSession(false);
            return;
        }
        closeTab(session);
    }
}
