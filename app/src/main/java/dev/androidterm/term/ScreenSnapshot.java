package dev.androidterm.term;

/**
 * Reusable flat-array copy of the terminal viewport for rendering.
 *
 * Cell (x, y) lives at index y * cols + x. Colors are final ARGB values
 * (defaults/inverse/faint already resolved natively); attrs hold
 * {@link TerminalNative}.ATTR_* bits. Wide glyphs occupy two cells: the
 * head has ATTR_WIDE, the tail has codepoint 0.
 */
public final class ScreenSnapshot {
    public int cols;
    public int rows;
    public int[] codepoints = new int[0];
    public int[] fg = new int[0];
    public int[] bg = new int[0];
    public byte[] attrs = new byte[0];
    /** See terminal_jni.c terminalSnapshot for the layout. */
    public final int[] meta = new int[9];

    public boolean cursorInViewport() { return meta[0] != 0; }
    public int cursorX() { return meta[1]; }
    public int cursorY() { return meta[2]; }
    public int cursorStyle() { return meta[3]; }
    public boolean cursorVisible() { return meta[4] != 0; }
    public boolean cursorBlinking() { return meta[5] != 0; }
    public int defaultBg() { return meta[7]; }
    public int defaultFg() { return meta[8]; }

    void ensureCapacity(int cells) {
        if (codepoints.length >= cells) return;
        codepoints = new int[cells];
        fg = new int[cells];
        bg = new int[cells];
        attrs = new byte[cells];
    }

    /** Row text with trailing blanks trimmed; empty cells become spaces. */
    public String rowText(int y) {
        StringBuilder sb = new StringBuilder(cols);
        for (int x = 0; x < cols; x++) {
            int cp = codepoints[y * cols + x];
            sb.appendCodePoint(cp == 0 ? ' ' : cp);
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /** All viewport rows joined with newlines; for tests and debugging. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            if (y > 0) sb.append('\n');
            sb.append(rowText(y));
        }
        return sb.toString();
    }
}
