package sh.easycli.proot.ui;

import android.content.Context;
import android.content.SharedPreferences;

import sh.easycli.proot.term.TerminalNative;

/**
 * App-wide user settings, persisted in a named SharedPreferences file so
 * they survive process death and Activity recreation (unlike the
 * activity-local {@code getPreferences()} store used for one-off prompts).
 *
 * Add a new option as a typed getter/setter pair, then declare a matching
 * {@link Setting} in {@code SettingsActivity#buildSections}; the screen renders
 * one row (title, description, control) per option.
 */
public final class AppSettings {

    private static final String FILE = "settings";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_IMMERSIVE_MODE = "immersive_mode";
    private static final String KEY_RICH_KEYBOARD = "rich_keyboard";
    private static final String KEY_EXTRA_KEYS_ENABLED = "extra_keys_enabled";
    private static final String KEY_SCROLLBACK_LINES = "scrollback_lines";
    private static final String KEY_BG_IMAGE_PATH = "bg_image_path";
    private static final String KEY_BG_IMAGE_OPACITY = "bg_image_opacity";
    private static final String KEY_BG_IMAGE_BLUR = "bg_image_blur";
    private static final String KEY_CURSOR_STYLE = "cursor_style";
    private static final String KEY_CURSOR_BLINK = "cursor_blink";
    private static final String KEY_TOUCH_KEYBOARD = "touch_keyboard";
    private static final String KEY_TEXT_MARGIN_LEFT = "text_margin_left";
    private static final String KEY_TEXT_MARGIN_RIGHT = "text_margin_right";
    private static final String KEY_HIDE_EXTRA_KEYS_WHEN_KB_HIDDEN = "hide_extra_keys_when_kb_hidden";
    private static final String KEY_EXTRA_KEYS_SWITCH = "extra_keys_switch";
    private static final String KEY_GRAPHEME_CLUSTERING = "grapheme_clustering";
    private static final String KEY_SMOOTH_SCROLL = "smooth_scroll";
    private static final String KEY_MOUSE_TRACKING = "mouse_tracking";
    private static final String KEY_BIND_EXTERNAL_STORAGE = "bind_external_storage";
    private static final String KEY_TERMINATE_PROCESSES_ON_EXIT =
            "terminate_processes_on_exit";
    private static final String KEY_TERMINAL_BELL_LEGACY = "terminal_bell";
    private static final String KEY_TERMINAL_BELL_MODE = "terminal_bell_mode";
    private static final String KEY_PROOT_LOGIN_SHELL = "proot_login_shell";
    private static final String KEY_TERMINAL_FONT_PATH = "terminal_font_path";
    private static final String KEY_TERMINAL_ITALIC_FONT_PATH = "terminal_italic_font_path";
    private static final String KEY_TERMINAL_BOLD_FONT_PATH = "terminal_bold_font_path";
    private static final String KEY_TERMINAL_BOLD_ITALIC_FONT_PATH =
            "terminal_bold_italic_font_path";

    /** Default scrollback depth used until the user changes it. */
    private static final int DEFAULT_SCROLLBACK_LINES = 10_000;

    /** Default wallpaper strength (percent) when an image is first chosen. */
    private static final int DEFAULT_BG_IMAGE_OPACITY = 35;

    public static final int BELL_OFF = 0;
    public static final int BELL_HAPTIC = 1;
    public static final int BELL_SCREEN_FLASH = 2;
    public static final int BELL_SOUND = 3;

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** When true, the display is held on while the terminal is foreground. */
    public boolean keepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    public void setKeepScreenOn(boolean enabled) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply();
    }

    /** When true, hide the status and navigation bars while the terminal is foreground. */
    public boolean immersiveMode() {
        return prefs.getBoolean(KEY_IMMERSIVE_MODE, false);
    }

    public void setImmersiveMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_IMMERSIVE_MODE, enabled).apply();
    }

    /**
     * When true, the soft keyboard runs in composing mode at a plain shell
     * prompt so suggestions, autocorrect and swipe typing work; input falls
     * back to raw key forwarding inside full-screen apps. Off by default
     * because the local edit buffer only approximates the remote line
     * (see TerminalView's rich-input handling).
     */
    public boolean richKeyboard() {
        return prefs.getBoolean(KEY_RICH_KEYBOARD, false);
    }

    public void setRichKeyboard(boolean enabled) {
        prefs.edit().putBoolean(KEY_RICH_KEYBOARD, enabled).apply();
    }

    /**
     * When true, the extra-keys toolbar is shown above the soft keyboard.
     * Disabling it only hides the toolbar — the configured key layout is kept
     * (in {@link ExtraKeysConfig}) and reappears unchanged when re-enabled.
     */
    public boolean extraKeysEnabled() {
        return prefs.getBoolean(KEY_EXTRA_KEYS_ENABLED, true);
    }

    public void setExtraKeysEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_EXTRA_KEYS_ENABLED, enabled).apply();
    }

    /**
     * Number of output lines kept in each terminal's scrollback history.
     * Read when a session is created; the underlying limit is fixed at
     * terminal creation, so a change only takes effect for new sessions.
     */
    public int scrollbackLines() {
        return prefs.getInt(KEY_SCROLLBACK_LINES, DEFAULT_SCROLLBACK_LINES);
    }

    public void setScrollbackLines(int lines) {
        prefs.edit().putInt(KEY_SCROLLBACK_LINES, lines).apply();
    }

    /**
     * Absolute path to the terminal background image (a copy kept in app
     * storage by {@link BackgroundImageStore}), or null when no wallpaper is
     * set. The image is drawn behind the default background of every session
     * by {@link TerminalView}; it is a global choice, independent of the color
     * theme.
     */
    public String backgroundImagePath() {
        return prefs.getString(KEY_BG_IMAGE_PATH, null);
    }

    /** Pass null to clear the wallpaper. */
    public void setBackgroundImagePath(String path) {
        if (path == null) {
            prefs.edit().remove(KEY_BG_IMAGE_PATH).apply();
        } else {
            prefs.edit().putString(KEY_BG_IMAGE_PATH, path).apply();
        }
    }

    /**
     * How strongly the background image shows through, 0–100 percent. Mapped to
     * a draw alpha over the solid theme background, so low values keep text
     * contrast (the theme color dominates) and high values make the image vivid.
     */
    public int backgroundImageOpacity() {
        return prefs.getInt(KEY_BG_IMAGE_OPACITY, DEFAULT_BG_IMAGE_OPACITY);
    }

    public void setBackgroundImageOpacity(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        prefs.edit().putInt(KEY_BG_IMAGE_OPACITY, clamped).apply();
    }

    /**
     * How much the background image is blurred, 0–100 percent. Unlike opacity
     * (a draw-time alpha), blur is baked into the decoded bitmap by
     * {@link BackgroundImageStore#decode}, so changing it re-decodes the image.
     * 0 leaves the photo sharp; higher values soften it so foreground text
     * stands out. Defaults to no blur.
     */
    public int backgroundImageBlur() {
        return prefs.getInt(KEY_BG_IMAGE_BLUR, 0);
    }

    public void setBackgroundImageBlur(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        prefs.edit().putInt(KEY_BG_IMAGE_BLUR, clamped).apply();
    }

    /**
     * Preferred cursor shape: one of {@link TerminalNative}.CURSOR_BLOCK,
     * CURSOR_UNDERLINE or CURSOR_BAR. Pushed as the terminal's default cursor
     * style, so a full-screen program may still override it (e.g. a bar in
     * vim's insert mode) until it resets the cursor. Defaults to a block,
     * matching the engine's built-in default. A global choice, independent of
     * the color theme.
     */
    public int cursorStyle() {
        return prefs.getInt(KEY_CURSOR_STYLE, TerminalNative.CURSOR_BLOCK);
    }

    public void setCursorStyle(int style) {
        prefs.edit().putInt(KEY_CURSOR_STYLE, style).apply();
    }

    /** When true, the cursor blinks unless a running program overrides it. */
    public boolean cursorBlink() {
        return prefs.getBoolean(KEY_CURSOR_BLINK, false);
    }

    public void setCursorBlink(boolean blink) {
        prefs.edit().putBoolean(KEY_CURSOR_BLINK, blink).apply();
    }

    /**
     * When true (default), tapping the terminal surface, resuming the app, and
     * opening a new session all raise the soft keyboard automatically. When
     * false, the keyboard only appears when the search bar is closed (search
     * always needs it) or when the user opens it manually via the system gesture.
     */
    public boolean touchKeyboard() {
        return prefs.getBoolean(KEY_TOUCH_KEYBOARD, true);
    }

    public void setTouchKeyboard(boolean enabled) {
        prefs.edit().putBoolean(KEY_TOUCH_KEYBOARD, enabled).apply();
    }

    /**
     * Left text margin in dp. A gap between the left screen edge and the
     * start of terminal text, useful on devices where the screen edge is
     * obscured by the case. Default 0.
     */
    public int textMarginLeft() {
        return prefs.getInt(KEY_TEXT_MARGIN_LEFT, 0);
    }

    public void setTextMarginLeft(int dp) {
        prefs.edit().putInt(KEY_TEXT_MARGIN_LEFT, Math.max(0, dp)).apply();
    }

    /**
     * Right text margin in dp. A gap between the end of terminal text and
     * the right screen edge. Default 0.
     */
    public int textMarginRight() {
        return prefs.getInt(KEY_TEXT_MARGIN_RIGHT, 0);
    }

    public void setTextMarginRight(int dp) {
        prefs.edit().putInt(KEY_TEXT_MARGIN_RIGHT, Math.max(0, dp)).apply();
    }

    /**
     * When true, the extra-keys toolbar is hidden whenever the soft keyboard is
     * not visible, reclaiming that vertical space while the keyboard is down.
     * Off by default so the toolbar always shows (the historical behaviour).
     */
    public boolean hideExtraKeysWhenKeyboardHidden() {
        return prefs.getBoolean(KEY_HIDE_EXTRA_KEYS_WHEN_KB_HIDDEN, false);
    }

    public void setHideExtraKeysWhenKeyboardHidden(boolean hide) {
        prefs.edit().putBoolean(KEY_HIDE_EXTRA_KEYS_WHEN_KB_HIDDEN, hide).apply();
    }

    /**
     * When true, the extra-keys toolbar shows a leading profile-switch column
     * (once more than one profile exists) that cycles the active layout on tap
     * and opens a chooser on long-press. Off by default so single-profile users
     * see no change.
     */
    public boolean showExtraKeysSwitch() {
        return prefs.getBoolean(KEY_EXTRA_KEYS_SWITCH, false);
    }

    public void setShowExtraKeysSwitch(boolean show) {
        prefs.edit().putBoolean(KEY_EXTRA_KEYS_SWITCH, show).apply();
    }

    /**
     * When true, DEC mode 2027 (grapheme-cluster mode) is force-enabled on every
     * session, so the engine merges multi-codepoint clusters — combining marks,
     * ZWJ emoji, and Indic conjuncts such as स्व — into one cell that the
     * renderer shapes as a unit. Off by default because it changes column-width
     * accounting: programs that measure strings with libc {@code wcwidth} (some
     * shells/TUIs) may then misplace the cursor. Applied per session by
     * {@code MainActivity#applyTheme}; takes effect on the next snapshot.
     */
    public boolean graphemeClustering() {
        return prefs.getBoolean(KEY_GRAPHEME_CLUSTERING, false);
    }

    public void setGraphemeClustering(boolean enabled) {
        prefs.edit().putBoolean(KEY_GRAPHEME_CLUSTERING, enabled).apply();
    }

    /**
     * When true (default), dragging and flinging the scrollback moves the
     * viewport pixel-by-pixel rather than snapping a whole line at a time.
     * Only affects the main screen — the alternate screen (vim/less/htop) has
     * no scrollback, so swipes there are still translated to arrow keys.
     * Applied to the view by {@code MainActivity}; the renderer carries a
     * sub-row pixel offset while a scroll or fling is in flight (see
     * {@link TerminalView}).
     */
    public boolean smoothScroll() {
        return prefs.getBoolean(KEY_SMOOTH_SCROLL, true);
    }

    public void setSmoothScroll(boolean enabled) {
        prefs.edit().putBoolean(KEY_SMOOTH_SCROLL, enabled).apply();
    }

    /**
     * When true (default), programs that request mouse reporting receive touch
     * gestures as mouse events: a tap is a left click and a swipe is wheel
     * scroll (vertical and horizontal, locked to the dominant axis). Only takes
     * effect while a program has enabled a mouse tracking mode; otherwise
     * gestures drive the local scrollback. Applied to the view by
     * {@code MainActivity}.
     */
    public boolean mouseTracking() {
        return prefs.getBoolean(KEY_MOUSE_TRACKING, true);
    }

    public void setMouseTracking(boolean enabled) {
        prefs.edit().putBoolean(KEY_MOUSE_TRACKING, enabled).apply();
    }

    /**
     * When true, newly opened Debian-under-PRoot sessions bind Android shared
     * storage into the guest under /mnt. Existing sessions keep the mount table
     * they were spawned with.
     */
    public boolean bindExternalStorage() {
        return prefs.getBoolean(KEY_BIND_EXTERNAL_STORAGE, false);
    }

    public void setBindExternalStorage(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIND_EXTERNAL_STORAGE, enabled).apply();
    }

    /**
     * When true, closing a Debian-under-PRoot session asks PRoot to kill every
     * traced process instead of only hanging up the top-level session.
     */
    public boolean terminateProcessesOnExit() {
        return prefs.getBoolean(KEY_TERMINATE_PROCESSES_ON_EXIT, true);
    }

    public void setTerminateProcessesOnExit(boolean enabled) {
        prefs.edit().putBoolean(KEY_TERMINATE_PROCESSES_ON_EXIT, enabled).apply();
    }

    /** BEL feedback mode for the active terminal session. Defaults to haptic feedback. */
    public int terminalBellMode() {
        if (prefs.contains(KEY_TERMINAL_BELL_MODE)) {
            return prefs.getInt(KEY_TERMINAL_BELL_MODE, BELL_HAPTIC);
        }
        return prefs.getBoolean(KEY_TERMINAL_BELL_LEGACY, true)
                ? BELL_HAPTIC : BELL_OFF;
    }

    public void setTerminalBellMode(int mode) {
        if (mode != BELL_OFF && mode != BELL_HAPTIC
                && mode != BELL_SCREEN_FLASH && mode != BELL_SOUND) {
            mode = BELL_HAPTIC;
        }
        prefs.edit().putInt(KEY_TERMINAL_BELL_MODE, mode).apply();
    }

    /**
     * Guest-absolute path of the shell PRoot execs as the login shell
     * (e.g. {@code /bin/bash}, {@code /bin/zsh}). Defaults to
     * {@code /bin/bash}. Takes effect for sessions created afterwards.
     */
    public String prootLoginShell() {
        return prefs.getString(KEY_PROOT_LOGIN_SHELL, "/bin/bash");
    }

    public void setProotLoginShell(String shell) {
        prefs.edit().putString(KEY_PROOT_LOGIN_SHELL, shell.trim()).apply();
    }

    /** Absolute path to the custom regular terminal font, or null for default monospace. */
    public String terminalFontPath() {
        return prefs.getString(KEY_TERMINAL_FONT_PATH, null);
    }

    public void setTerminalFontPath(String path) {
        setNullableString(KEY_TERMINAL_FONT_PATH, path);
    }

    /** Absolute path to the custom italic terminal font, or null to synthesize italics. */
    public String terminalItalicFontPath() {
        return prefs.getString(KEY_TERMINAL_ITALIC_FONT_PATH, null);
    }

    public void setTerminalItalicFontPath(String path) {
        setNullableString(KEY_TERMINAL_ITALIC_FONT_PATH, path);
    }

    /** Absolute path to the custom bold terminal font, or null to synthesize bold. */
    public String terminalBoldFontPath() {
        return prefs.getString(KEY_TERMINAL_BOLD_FONT_PATH, null);
    }

    public void setTerminalBoldFontPath(String path) {
        setNullableString(KEY_TERMINAL_BOLD_FONT_PATH, path);
    }

    /** Absolute path to the custom bold italic terminal font, or null for fallback styling. */
    public String terminalBoldItalicFontPath() {
        return prefs.getString(KEY_TERMINAL_BOLD_ITALIC_FONT_PATH, null);
    }

    public void setTerminalBoldItalicFontPath(String path) {
        setNullableString(KEY_TERMINAL_BOLD_ITALIC_FONT_PATH, path);
    }

    private void setNullableString(String key, String value) {
        if (value == null) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, value).apply();
        }
    }
}
