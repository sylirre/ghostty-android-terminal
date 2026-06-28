package sh.easycli.proot.term;

import java.util.HashMap;

/**
 * Reusable flat-array copy of the terminal viewport for rendering.
 *
 * Cell (x, y) lives at index y * cols + x. Colors are final ARGB values
 * (defaults/inverse/faint already resolved natively); attrs hold
 * {@link TerminalNative}.ATTR_* bits. Wide glyphs occupy two cells: the
 * head has ATTR_WIDE, the tail has codepoint 0.
 *
 * The {@link #codepoints} array holds one base codepoint per cell. Cells whose
 * text is a multi-codepoint grapheme cluster (base + combining/ZWJ marks) keep
 * that base codepoint here and carry the full cluster separately in
 * {@link #graphemes}; {@link #graphemeAt} resolves it for the renderer.
 */
public final class ScreenSnapshot {
    public int cols;
    public int rows;
    public int[] codepoints = new int[0];
    public int[] fg = new int[0];
    public int[] bg = new int[0];
    public int[] attrs = new int[0];
    /** See terminal_jni.c terminalSnapshot for the layout. */
    public final int[] meta = new int[16];

    /**
     * Grapheme-cluster overflow buffer (see terminal_jni.c): slot 0 is the
     * number of record ints written, followed by {@code [cellIndex, count,
     * cp0, ...]} records. Decoded into {@link #graphemeMap} by
     * {@link #indexGraphemes} after each snapshot fill.
     */
    public int[] graphemes = new int[1];

    /** cellIndex → cluster text; empty (the common case) when no clusters. */
    private final HashMap<Integer, String> graphemeMap = new HashMap<>();

    public boolean cursorInViewport() { return meta[0] != 0; }
    public int cursorX() { return meta[1]; }
    public int cursorY() { return meta[2]; }
    public int cursorStyle() { return meta[3]; }
    public boolean cursorVisible() { return meta[4] != 0; }
    public boolean cursorBlinking() { return meta[5] != 0; }
    public int defaultBg() { return meta[7]; }
    public int defaultFg() { return meta[8]; }

    /**
     * Effective cursor color (ARGB), or 0 when unset — callers fall back to
     * {@link #defaultFg()}. Set by the theme and by program OSC 12 overrides.
     */
    public int cursorColor() { return meta[15]; }

    /**
     * Selection endpoints are viewport cells ordered top-left to
     * bottom-right (both inclusive); each coordinate pair is only
     * meaningful while its visibility flag is set — an endpoint scrolled
     * out of the viewport keeps the selection alive but has no position.
     */
    public boolean hasSelection() {
        return (meta[9] & TerminalNative.SEL_ACTIVE) != 0;
    }
    public boolean selectionStartVisible() {
        return (meta[9] & TerminalNative.SEL_START_VISIBLE) != 0;
    }
    public boolean selectionEndVisible() {
        return (meta[9] & TerminalNative.SEL_END_VISIBLE) != 0;
    }
    public int selectionStartX() { return meta[10]; }
    public int selectionStartY() { return meta[11]; }
    public int selectionEndX() { return meta[12]; }
    public int selectionEndY() { return meta[13]; }

    /**
     * True when the terminal is running something that consumes raw keys —
     * the alternate screen (vim/less/tmux) or application-cursor-keys mode.
     * Rich keyboard input (suggestions/swipe) must disable itself here, since
     * a local edit buffer can't mirror a full-screen or modal program.
     */
    public boolean rawKeyInput() {
        return (meta[14] & (TerminalNative.INPUT_MODE_ALT_SCREEN
                | TerminalNative.INPUT_MODE_APP_CURSOR)) != 0;
    }

    /**
     * True on the alternate screen (nano/vim/less/htop), which has no
     * scrollback. A vertical swipe there can't scroll history, so the view
     * translates it into arrow-key presses instead.
     */
    public boolean altScreen() {
        return (meta[14] & TerminalNative.INPUT_MODE_ALT_SCREEN) != 0;
    }

    /**
     * True when the running program has enabled a mouse tracking mode
     * (X10/normal/button/any-event). The view then reports touch gestures as
     * mouse events — swipes as wheel scroll, taps as a left click — instead of
     * driving the local scrollback or raising the keyboard.
     */
    public boolean mouseTracking() {
        return (meta[14] & TerminalNative.INPUT_MODE_MOUSE) != 0;
    }

    void ensureCapacity(int cells) {
        if (codepoints.length >= cells) return;
        codepoints = new int[cells];
        fg = new int[cells];
        bg = new int[cells];
        attrs = new int[cells];
    }

    /** Grows the grapheme buffer to hold {@code ints} record ints plus slot 0. */
    void ensureGraphemeCapacity(int ints) {
        if (graphemes.length >= ints + 1) return;
        graphemes = new int[ints + 1];
    }

    /** True when the last fill overflowed the grapheme buffer; grow and retry. */
    boolean graphemesOverflowed() {
        return graphemes.length > 0 && graphemes[0] > graphemes.length - 1;
    }

    /**
     * Rebuilds the cell-index → cluster lookup from {@link #graphemes}. Called
     * by {@link TerminalEmulator} after each snapshot fill. Cheap and
     * allocation-free in the common no-cluster case (slot 0 == 0).
     */
    void indexGraphemes() {
        graphemeMap.clear();
        int needed = graphemes.length > 0 ? graphemes[0] : 0;
        if (needed <= 0) return;
        int limit = Math.min(1 + needed, graphemes.length);
        int p = 1;
        while (p + 1 < limit) {
            int cellIdx = graphemes[p++];
            int count = graphemes[p++];
            if (count <= 0 || p + count > limit) break;
            StringBuilder sb = new StringBuilder(count);
            for (int k = 0; k < count; k++) sb.appendCodePoint(graphemes[p + k]);
            p += count;
            graphemeMap.put(cellIdx, sb.toString());
        }
    }

    /**
     * Full grapheme-cluster text for the cell at index {@code i}, or null for
     * an ordinary single-codepoint cell. The renderer draws this string in
     * place of the bare base codepoint so combining marks and ZWJ sequences
     * shape correctly.
     */
    public String graphemeAt(int i) {
        return graphemeMap.isEmpty() ? null : graphemeMap.get(i);
    }

    /** Row text with trailing blanks trimmed; empty cells become spaces. */
    public String rowText(int y) {
        StringBuilder sb = new StringBuilder(cols);
        for (int x = 0; x < cols; x++) {
            int i = y * cols + x;
            String cluster = graphemeAt(i);
            if (cluster != null) {
                sb.append(cluster);
                continue;
            }
            int cp = codepoints[i];
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
