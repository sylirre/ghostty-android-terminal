package sh.easycli.proot.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Curated, read-only built-in {@link TerminalTheme}s. {@link #DEFAULT} (the
 * first entry) is the standard xterm 16-color palette on a black background,
 * matching the terminal's historical white-on-black look; the rest are
 * popular community themes. Users build their own on top of these via
 * {@link ThemeStore}.
 */
final class ThemePresets {

    private ThemePresets() {}

    /** All presets, in display order; {@code get(0)} is {@link #DEFAULT}. */
    static final List<TerminalTheme> ALL = Collections.unmodifiableList(Arrays.asList(
        // Default: classic xterm palette, white on black.
        t("Default", 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF,
            0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
            0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
            0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
            0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF),

        t("Solarized Dark", 0xFF839496, 0xFF002B36, 0xFF93A1A1,
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
            0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
            0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3),

        t("Solarized Light", 0xFF657B83, 0xFFFDF6E3, 0xFF586E75,
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
            0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
            0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3),

        t("Dracula", 0xFFF8F8F2, 0xFF282A36, 0xFFF8F8F2,
            0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
            0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
            0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF),

        t("Nord", 0xFFD8DEE9, 0xFF2E3440, 0xFFD8DEE9,
            0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
            0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
            0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4),

        t("Gruvbox Dark", 0xFFEBDBB2, 0xFF282828, 0xFFEBDBB2,
            0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
            0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
            0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
            0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2),

        t("Catppuccin Mocha", 0xFFCDD6F4, 0xFF1E1E2E, 0xFFF5E0DC,
            0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
            0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
            0xFF585B70, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
            0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8),

        t("Tokyo Night", 0xFFC0CAF5, 0xFF1A1B26, 0xFFC0CAF5,
            0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
            0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
            0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
            0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5),

        t("One Dark", 0xFFABB2BF, 0xFF282C34, 0xFF528BFF,
            0xFF282C34, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
            0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
            0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
            0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFFFFFFF)
    ));

    /** The fallback theme used when nothing is selected or a name is unknown. */
    static final TerminalTheme DEFAULT = ALL.get(0);

    /** True if {@code name} is a built-in preset (which are immutable). */
    static boolean isPreset(String name) {
        for (TerminalTheme t : ALL) {
            if (t.name.equals(name)) return true;
        }
        return false;
    }

    private static TerminalTheme t(String name, int fg, int bg, int cursor,
            int c0, int c1, int c2, int c3, int c4, int c5, int c6, int c7,
            int c8, int c9, int c10, int c11, int c12, int c13, int c14, int c15) {
        return new TerminalTheme(name, fg, bg, cursor, new int[]{
                c0, c1, c2, c3, c4, c5, c6, c7,
                c8, c9, c10, c11, c12, c13, c14, c15});
    }
}
