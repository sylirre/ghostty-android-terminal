/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.sylirre.terminal.term.TerminalNative;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.VmOptions;

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
    private static final String KEY_EXTRA_KEYS_ROW_PADDING = "extra_keys_row_padding";
    private static final String KEY_GRAPHEME_CLUSTERING = "grapheme_clustering";
    private static final String KEY_SMOOTH_SCROLL = "smooth_scroll";
    private static final String KEY_MOUSE_TRACKING = "mouse_tracking";
    private static final String KEY_TAP_OPEN_LINKS = "tap_open_links";
    private static final String KEY_PROMPT_NAV = "prompt_nav";
    private static final String KEY_CLIPBOARD_WRITE = "clipboard_write";
    private static final String KEY_CLIPBOARD_READ = "clipboard_read";
    private static final String KEY_SHOW_PROGRESS = "show_progress";
    private static final String KEY_BIND_EXTERNAL_STORAGE = "bind_external_storage";
    private static final String KEY_TERMINATE_PROCESSES_ON_EXIT =
            "terminate_processes_on_exit";
    private static final String KEY_CONFIRM_SESSION_CLOSE = "confirm_session_close";
    private static final String KEY_TERMINAL_BELL_LEGACY = "terminal_bell";
    private static final String KEY_TERMINAL_BELL_MODE = "terminal_bell_mode";
    private static final String KEY_USERLAND_LOGIN_SHELL = "userland_shell";
    private static final String KEY_USERLAND_IDENTITY = "userland_identity";
    private static final String KEY_USERLAND_HOME = "userland_home";
    private static final String KEY_USERLAND_WORK_DIR = "userland_work_dir";
    private static final String KEY_USERLAND_LOCALE = "userland_locale";
    private static final String KEY_USERLAND_PATH = "userland_path";
    private static final String KEY_USERLAND_JIT = "userland_jit";
    private static final String KEY_USERLAND_JIT_MB = "userland_jit_mb";
    private static final String KEY_USERLAND_CHROOT_NG = "userland_chroot_ng";
    private static final String KEY_VM_MEMORY_MB = "vm_memory_mb";
    private static final String KEY_VM_TERMINALS = "vm_terminals";
    private static final String KEY_VM_JIT = "vm_jit";
    private static final String KEY_ONBOARDING_COMPLETED = "onboarding_completed";
    private static final String KEY_USERLAND_DISTRO_ASSET = "userland_distro_asset";
    private static final String KEY_TERMINAL_FONT_PATH = "terminal_font_path";
    private static final String KEY_TERMINAL_ITALIC_FONT_PATH = "terminal_italic_font_path";
    private static final String KEY_TERMINAL_BOLD_FONT_PATH = "terminal_bold_font_path";
    private static final String KEY_TERMINAL_BOLD_ITALIC_FONT_PATH =
            "terminal_bold_italic_font_path";

    /** Default scrollback depth used until the user changes it. */
    private static final int DEFAULT_SCROLLBACK_LINES = 10_000;

    /** Default wallpaper strength (percent) when an image is first chosen. */
    private static final int DEFAULT_BG_IMAGE_OPACITY = 35;

    /** Default extra-keys cap vertical padding (dp); matches {@code R.dimen.key_pad_v}. */
    private static final int DEFAULT_EXTRA_KEYS_ROW_PADDING = 8;

    /** JIT code-cache bounds (MiB), matching the "JIT buffer size" slider. */
    private static final int USERLAND_JIT_MB_MIN = 4;
    private static final int USERLAND_JIT_MB_MAX = 128;
    private static final int USERLAND_JIT_MB_STEP = 4;
    /** Default cache size; matches arm64chroot's own A64_JIT_MB default. */
    private static final int DEFAULT_USERLAND_JIT_MB = 32;

    /** Guest RAM bounds (MiB), matching the "Machine memory" slider. 256 is
     * about the floor an Alpine ISO boots in; the ceiling is generous for a
     * phone, since the guest never returns memory it has touched. */
    private static final int VM_MEMORY_MB_MIN = 256;
    private static final int VM_MEMORY_MB_MAX = 2048;
    private static final int VM_MEMORY_MB_STEP = 128;
    private static final int DEFAULT_VM_MEMORY_MB = VmOptions.DEFAULT_MEMORY_MB;

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
     * obscured by the case. Default 4.
     */
    public int textMarginLeft() {
        return prefs.getInt(KEY_TEXT_MARGIN_LEFT, 4);
    }

    public void setTextMarginLeft(int dp) {
        prefs.edit().putInt(KEY_TEXT_MARGIN_LEFT, Math.max(0, dp)).apply();
    }

    /**
     * Right text margin in dp. A gap between the end of terminal text and
     * the right screen edge. Default 4.
     */
    public int textMarginRight() {
        return prefs.getInt(KEY_TEXT_MARGIN_RIGHT, 4);
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
     * Vertical padding (dp) inside each extra-keys cap — the knob behind the
     * toolbar's row height. Larger values give taller rows and bigger tap
     * targets; the rows stay wrap-content so the autosized label never clips.
     * Defaults to {@link #DEFAULT_EXTRA_KEYS_ROW_PADDING}.
     */
    public int extraKeysVerticalPadding() {
        return prefs.getInt(KEY_EXTRA_KEYS_ROW_PADDING, DEFAULT_EXTRA_KEYS_ROW_PADDING);
    }

    public void setExtraKeysVerticalPadding(int dp) {
        prefs.edit().putInt(KEY_EXTRA_KEYS_ROW_PADDING, Math.max(0, dp)).apply();
    }

    /** Restore the extra-keys row height to {@link #DEFAULT_EXTRA_KEYS_ROW_PADDING}. */
    public void resetExtraKeysVerticalPadding() {
        prefs.edit().remove(KEY_EXTRA_KEYS_ROW_PADDING).apply();
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
     * When true (default), OSC 8 hyperlinks are underlined and a tap on one
     * previews its address and opens it in the system handler. Off renders
     * links like ordinary text and leaves taps to raise the keyboard. Applied
     * to the view by {@code MainActivity}.
     */
    public boolean tapToOpenLinks() {
        return prefs.getBoolean(KEY_TAP_OPEN_LINKS, true);
    }

    public void setTapToOpenLinks(boolean enabled) {
        prefs.edit().putBoolean(KEY_TAP_OPEN_LINKS, enabled).apply();
    }

    /**
     * When true, the top-bar prompt-navigation buttons appear while scrolled
     * into history and jump between primary shell-prompt lines reported via
     * OSC 133. Off by default (needs a shell that emits the sequences).
     * Applied to the view by {@code MainActivity}.
     */
    public boolean promptNav() {
        return prefs.getBoolean(KEY_PROMPT_NAV, false);
    }

    public void setPromptNav(boolean enabled) {
        prefs.edit().putBoolean(KEY_PROMPT_NAV, enabled).apply();
    }

    /**
     * When true (default), programs may set the Android clipboard via OSC 52.
     * Off ignores clipboard-set sequences.
     */
    public boolean clipboardWrite() {
        return prefs.getBoolean(KEY_CLIPBOARD_WRITE, true);
    }

    public void setClipboardWrite(boolean enabled) {
        prefs.edit().putBoolean(KEY_CLIPBOARD_WRITE, enabled).apply();
    }

    /**
     * When true, programs may <em>read</em> the clipboard via an OSC 52 query
     * ({@code ?}); the app answers with the clipboard contents. Off by default
     * because it lets any program exfiltrate the clipboard, and Android further
     * restricts background clipboard reads.
     */
    public boolean clipboardRead() {
        return prefs.getBoolean(KEY_CLIPBOARD_READ, false);
    }

    public void setClipboardRead(boolean enabled) {
        prefs.edit().putBoolean(KEY_CLIPBOARD_READ, enabled).apply();
    }

    /**
     * When true (default), OSC 9;4 progress reports drive a per-tab progress
     * indicator in the tab strip. Off hides it.
     */
    public boolean showProgress() {
        return prefs.getBoolean(KEY_SHOW_PROGRESS, true);
    }

    public void setShowProgress(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHOW_PROGRESS, enabled).apply();
    }

    /**
     * When true, newly opened userland sessions bind Android shared
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
     * When true, userland sessions run arm64chroot with {@code --jit} (native
     * block translation). Off falls back to the interpreter. Defaults to on.
     */
    public boolean userlandJitEnabled() {
        return prefs.getBoolean(KEY_USERLAND_JIT, true);
    }

    public void setUserlandJitEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_USERLAND_JIT, enabled).apply();
    }

    /**
     * When true, userland sessions run under chroot-ng — native AArch64
     * execution behind seccomp/SIGSYS path translation — instead of the
     * arm64chroot emulator. Only honored when the native library carries the
     * engine ({@link io.github.sylirre.terminal.term.TerminalNative#hasChrootNg};
     * arm64-v8a builds only). Defaults to off.
     */
    public boolean userlandChrootNgEnabled() {
        return prefs.getBoolean(KEY_USERLAND_CHROOT_NG, false);
    }

    public void setUserlandChrootNgEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_USERLAND_CHROOT_NG, enabled).apply();
    }

    /**
     * Per-thread JIT code-cache size (MiB) passed to arm64chroot as
     * {@code A64_JIT_MB}. Clamped to [{@value #USERLAND_JIT_MB_MIN},
     * {@value #USERLAND_JIT_MB_MAX}] and snapped to a multiple of
     * {@value #USERLAND_JIT_MB_STEP}, matching the slider's range and step.
     */
    public int userlandJitBufferMb() {
        return clampJitMb(prefs.getInt(KEY_USERLAND_JIT_MB, DEFAULT_USERLAND_JIT_MB));
    }

    public void setUserlandJitBufferMb(int mb) {
        prefs.edit().putInt(KEY_USERLAND_JIT_MB, clampJitMb(mb)).apply();
    }

    private static int clampJitMb(int mb) {
        int clamped = Math.max(USERLAND_JIT_MB_MIN, Math.min(USERLAND_JIT_MB_MAX, mb));
        return Math.round(clamped / (float) USERLAND_JIT_MB_STEP) * USERLAND_JIT_MB_STEP;
    }

    // --- guest machine (VM session type) ---

    /**
     * Guest RAM in MiB. This is real host memory the moment the guest touches
     * it — an Alpine boot settles around 230 MB — and it is never handed back,
     * since there is no balloon, so the value is a ceiling on what the machine
     * costs the process for its whole life. The emulator sizes the guest's
     * device tree to match.
     */
    public int vmMemoryMb() {
        return clampVmMemory(prefs.getInt(KEY_VM_MEMORY_MB, DEFAULT_VM_MEMORY_MB));
    }

    public void setVmMemoryMb(int mb) {
        prefs.edit().putInt(KEY_VM_MEMORY_MB, clampVmMemory(mb)).apply();
    }

    private static int clampVmMemory(int mb) {
        int clamped = Math.max(VM_MEMORY_MB_MIN, Math.min(VM_MEMORY_MB_MAX, mb));
        return Math.round(clamped / (float) VM_MEMORY_MB_STEP) * VM_MEMORY_MB_STEP;
    }

    /**
     * Guest terminals beyond {@code ttyAMA0}, i.e. how many tabs the machine
     * can have open at once past the first. Each is a virtio-console device
     * created at boot with a getty on it, so this is fixed for a machine's
     * lifetime and changing it takes effect on the next boot.
     */
    public int vmTerminals() {
        return Math.max(0, Math.min(VmOptions.MAX_HVC,
                prefs.getInt(KEY_VM_TERMINALS, VmOptions.DEFAULT_HVC)));
    }

    public void setVmTerminals(int n) {
        prefs.edit().putInt(KEY_VM_TERMINALS, n).apply();
    }

    /**
     * When true the guest machine translates guest code to native instead of
     * interpreting it — worth roughly an order of magnitude, and the difference
     * between a boot measured in minutes and one measured in seconds. Off is a
     * diagnostic setting. Defaults to on.
     */
    public boolean vmJitEnabled() {
        return prefs.getBoolean(KEY_VM_JIT, true);
    }

    public void setVmJitEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VM_JIT, enabled).apply();
    }

    /**
     * When true, closing a userland session SIGKILLs the emulator's whole process
     * group instead of only hanging up the top-level session.
     */
    public boolean terminateProcessesOnExit() {
        return prefs.getBoolean(KEY_TERMINATE_PROCESSES_ON_EXIT, true);
    }

    public void setTerminateProcessesOnExit(boolean enabled) {
        prefs.edit().putBoolean(KEY_TERMINATE_PROCESSES_ON_EXIT, enabled).apply();
    }

    /**
     * Whether tapping a tab's close button asks before killing the session.
     * Default on: every tab carries a close button, a stray tap while
     * scrolling the strip would otherwise end the session irrecoverably, and
     * closing the last tab also exits the app.
     */
    public boolean confirmSessionClose() {
        return prefs.getBoolean(KEY_CONFIRM_SESSION_CLOSE, true);
    }

    public void setConfirmSessionClose(boolean enabled) {
        prefs.edit().putBoolean(KEY_CONFIRM_SESSION_CLOSE, enabled).apply();
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
     * The command the emulator runs as the login shell: a guest-absolute shell
     * path with optional whitespace-separated arguments (e.g. {@code /bin/bash -l},
     * {@code /bin/zsh -l}). Populated from the configured user's {@code /etc/passwd}
     * shell when the identity is set; an empty value derives it at spawn time,
     * falling back to {@code /bin/bash -l} or {@code /bin/sh -l}. Defaults to
     * {@code /bin/bash -l}. Takes effect for sessions created afterwards.
     */
    public String userlandLoginShell() {
        return prefs.getString(KEY_USERLAND_LOGIN_SHELL, "/bin/bash -l");
    }

    public void setUserlandLoginShell(String shell) {
        prefs.edit().putString(KEY_USERLAND_LOGIN_SHELL, shell.trim()).apply();
    }

    /**
     * Raw "User identity" setting for userland sessions: {@code user},
     * {@code user:group}, {@code uid} or {@code uid:gid}. Named components are
     * resolved against the rootfs passwd/group when a session spawns (see
     * {@code UserlandIdentity}); an empty or unresolvable value falls back to
     * {@code 0:0} (root). Defaults to {@code 0:0}.
     */
    public String userlandIdentity() {
        return prefs.getString(KEY_USERLAND_IDENTITY, "0:0");
    }

    public void setUserlandIdentity(String identity) {
        prefs.edit().putString(KEY_USERLAND_IDENTITY, identity.trim()).apply();
    }

    /**
     * Guest-absolute HOME for userland sessions (passed as {@code -E HOME=}).
     * Populated from the configured user's {@code /etc/passwd} home when the
     * identity is set; an empty value derives it at spawn time (falling back to
     * {@code /}). Defaults to {@code /root}.
     */
    public String userlandHome() {
        return prefs.getString(KEY_USERLAND_HOME, "/root");
    }

    public void setUserlandHome(String home) {
        prefs.edit().putString(KEY_USERLAND_HOME, home.trim()).apply();
    }

    /**
     * Guest-absolute working directory for userland sessions (passed as
     * {@code --work-dir}). An empty value derives the configured user's home
     * (falling back to {@code /}) at spawn time. Unlike {@link #userlandHome},
     * it is not repopulated when the identity changes. Defaults to empty.
     */
    public String userlandWorkDir() {
        return prefs.getString(KEY_USERLAND_WORK_DIR, "");
    }

    public void setUserlandWorkDir(String workDir) {
        prefs.edit().putString(KEY_USERLAND_WORK_DIR, workDir.trim()).apply();
    }

    /**
     * Guest locale for userland sessions, passed as {@code -E LANG=}. A blank
     * value falls back to {@code C.UTF-8} at spawn time. Defaults to
     * {@code C.UTF-8}. Takes effect only for new sessions.
     */
    public String userlandLocale() {
        return prefs.getString(KEY_USERLAND_LOCALE, "C.UTF-8");
    }

    public void setUserlandLocale(String locale) {
        prefs.edit().putString(KEY_USERLAND_LOCALE, locale.trim()).apply();
    }

    /**
     * Guest {@code PATH} for userland sessions, passed as {@code -E PATH=}. A
     * blank value falls back to {@link UserlandOptions#DEFAULT_PATH} at spawn
     * time. Defaults to that same search path. Takes effect only for new
     * sessions.
     */
    public String userlandPath() {
        return prefs.getString(KEY_USERLAND_PATH, UserlandOptions.DEFAULT_PATH);
    }

    public void setUserlandPath(String path) {
        prefs.edit().putString(KEY_USERLAND_PATH, path.trim()).apply();
    }

    /**
     * True once the first-run onboarding flow finished (a distro installed or
     * "Android shell only" explicitly chosen). Also set when a rootfs is
     * already installed — pre-onboarding installs never see the intro. While
     * false, {@code MainActivity} shows {@code OnboardingActivity} before the
     * first session.
     */
    public boolean onboardingCompleted() {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false);
    }

    public void setOnboardingCompleted(boolean completed) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply();
    }

    /**
     * Asset file name of the distribution the user chose in onboarding (see
     * {@code UserlandDistro}), or null when none was ever installed from an
     * asset. Informational — the installed rootfs itself is the source of
     * truth for sessions.
     */
    public String userlandDistroAsset() {
        return prefs.getString(KEY_USERLAND_DISTRO_ASSET, null);
    }

    public void setUserlandDistroAsset(String assetName) {
        setNullableString(KEY_USERLAND_DISTRO_ASSET, assetName);
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
