package sh.easycli.proot.ui;

import java.util.Arrays;
import java.util.Locale;

/**
 * An immutable terminal color theme: a name plus 19 opaque ARGB colors —
 * foreground, background, cursor, and the 16 ANSI palette entries (0–15).
 *
 * The remaining palette indices (16–255) are not stored: {@link #toPalette256}
 * generates the standard xterm 6×6×6 color cube and grayscale ramp for them,
 * which is what {@link sh.easycli.proot.term.TerminalEmulator#setColors} feeds
 * to the native engine. Presets live in {@link ThemePresets}; user themes are
 * persisted by {@link ThemeStore} via {@link #toCsv}/{@link #fromCsv}.
 */
public final class TerminalTheme {

    /** Number of editable colors: fg, bg, cursor, then ANSI 0–15. */
    public static final int ANSI_COUNT = 16;

    public final String name;
    public final int foreground;
    public final int background;
    public final int cursor;
    /** ANSI palette entries 0–15 as ARGB; always length {@link #ANSI_COUNT}. */
    public final int[] ansi;

    public TerminalTheme(String name, int foreground, int background, int cursor,
            int[] ansi) {
        if (ansi.length != ANSI_COUNT) {
            throw new IllegalArgumentException("ansi must have " + ANSI_COUNT
                    + " entries, got " + ansi.length);
        }
        this.name = name;
        this.foreground = foreground;
        this.background = background;
        this.cursor = cursor;
        this.ansi = ansi.clone();
    }

    /** A copy with a different name; colors are shared (immutable). */
    public TerminalTheme withName(String newName) {
        return new TerminalTheme(newName, foreground, background, cursor, ansi);
    }

    /**
     * The full 256-entry ARGB palette: 0–15 from {@link #ansi}, 16–231 the
     * 6×6×6 color cube, 232–255 the 24-step grayscale ramp. Matches Ghostty's
     * (and xterm's) default generation for the upper range, so only the named
     * ANSI colors are themeable while 256-color apps still render correctly.
     */
    public int[] toPalette256() {
        int[] pal = new int[256];
        System.arraycopy(ansi, 0, pal, 0, ANSI_COUNT);
        int[] levels = {0, 95, 135, 175, 215, 255};
        for (int i = 0; i < 216; i++) {
            int r = levels[(i / 36) % 6];
            int g = levels[(i / 6) % 6];
            int b = levels[i % 6];
            pal[16 + i] = argb(r, g, b);
        }
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            pal[232 + i] = argb(v, v, v);
        }
        return pal;
    }

    /** True when {@code other} has identical colors (the name is ignored). */
    public boolean sameColors(TerminalTheme other) {
        return other != null
                && foreground == other.foreground
                && background == other.background
                && cursor == other.cursor
                && Arrays.equals(ansi, other.ansi);
    }

    /**
     * The 19 colors as comma-separated 6-digit hex (fg, bg, cursor, ansi0..15),
     * without the name or alpha. {@link #fromCsv} is the inverse.
     */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append(hex(foreground)).append(',')
          .append(hex(background)).append(',')
          .append(hex(cursor));
        for (int c : ansi) sb.append(',').append(hex(c));
        return sb.toString();
    }

    /** Rebuilds a theme from {@link #toCsv} output; throws on a malformed string. */
    public static TerminalTheme fromCsv(String name, String csv) {
        String[] parts = csv.split(",");
        int expected = 3 + ANSI_COUNT;
        if (parts.length != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " colors, got " + parts.length);
        }
        int fg = parseHex(parts[0]);
        int bg = parseHex(parts[1]);
        int cur = parseHex(parts[2]);
        int[] ansi = new int[ANSI_COUNT];
        for (int i = 0; i < ANSI_COUNT; i++) ansi[i] = parseHex(parts[3 + i]);
        return new TerminalTheme(name, fg, bg, cur, ansi);
    }

    /** Packs RGB components into an opaque ARGB int. */
    static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String hex(int color) {
        return String.format(Locale.US, "%06X", color & 0xFFFFFF);
    }

    private static int parseHex(String s) {
        return 0xFF000000 | (Integer.parseInt(s.trim(), 16) & 0xFFFFFF);
    }
}
