package dev.androidterm.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App-wide user settings, persisted in a named SharedPreferences file so
 * they survive process death and Activity recreation (unlike the
 * activity-local {@code getPreferences()} store used for one-off prompts).
 *
 * Add a new option as a typed getter/setter pair, then declare a matching
 * {@link Setting} in {@code MainActivity#showSettings}; {@link SettingsDialog}
 * renders one row (title, description, control) per option.
 */
public final class AppSettings {

    private static final String FILE = "settings";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_RICH_KEYBOARD = "rich_keyboard";
    private static final String KEY_SCROLLBACK_LINES = "scrollback_lines";

    /** Default scrollback depth used until the user changes it. */
    private static final int DEFAULT_SCROLLBACK_LINES = 10_000;

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
}
