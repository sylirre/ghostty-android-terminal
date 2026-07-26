/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

/**
 * Java owner of one native Ghostty terminal.
 *
 * All methods are synchronized because libghostty-vt is not thread-safe;
 * the PTY reader thread feeds bytes while the UI thread snapshots and
 * encodes keys. After {@link #close} every method becomes a no-op, so a
 * racing UI call cannot touch a freed handle.
 */
public final class TerminalEmulator implements AutoCloseable {
    private long handle;

    public TerminalEmulator(int cols, int rows, int scrollbackLines) {
        handle = TerminalNative.terminalNew(cols, rows, scrollbackLines);
        if (handle == 0) throw new OutOfMemoryError("ghostty_terminal_new failed");
    }

    /** Feeds shell output; returns query-response bytes for the PTY, or null. */
    public synchronized byte[] feed(byte[] data, int len) {
        return handle == 0 ? null : TerminalNative.terminalFeed(handle, data, len);
    }

    /** Returns and clears pending EVENT_* bits. */
    public synchronized int events() {
        return handle == 0 ? 0 : TerminalNative.terminalEvents(handle);
    }

    public synchronized String title() {
        return handle == 0 ? null : TerminalNative.terminalTitle(handle);
    }

    public synchronized void resize(int cols, int rows, int cellWidthPx, int cellHeightPx) {
        if (handle != 0) {
            TerminalNative.terminalResize(handle, cols, rows, cellWidthPx, cellHeightPx);
        }
    }

    /**
     * Sets the default fg/bg/cursor colors (ARGB ints) and the full 256-entry
     * palette (ARGB ints). Safe to call at any time; takes effect on the next
     * snapshot. No-op after {@link #close}.
     */
    public synchronized void setColors(int fg, int bg, int cursor, int[] palette256) {
        if (handle != 0) {
            TerminalNative.terminalSetColors(handle, fg, bg, cursor, palette256);
        }
    }

    /**
     * Sets the default cursor style ({@link TerminalNative}.CURSOR_*) and whether
     * it blinks. Safe to call at any time; takes effect on the next snapshot.
     * A program's DECSCUSR override still wins until it resets the cursor.
     * No-op after {@link #close}.
     */
    public synchronized void setCursorStyle(int style, boolean blink) {
        if (handle != 0) {
            TerminalNative.terminalSetCursorStyle(handle, style, blink);
        }
    }

    /**
     * Force-enables or disables DEC mode 2027 (grapheme-cluster mode). With it
     * on, the engine combines multi-codepoint grapheme clusters — combining
     * marks, ZWJ emoji, and Indic conjuncts like स्व — into one (possibly wide)
     * cell, which the renderer shapes as a unit. Off by default: it changes
     * column-width accounting, which wcwidth-based programs may not match.
     * No-op after {@link #close}.
     */
    public synchronized void setGraphemeClustering(boolean enable) {
        if (handle != 0) TerminalNative.terminalSetGraphemeClustering(handle, enable);
    }

    public synchronized void scrollToBottom() {
        if (handle != 0) TerminalNative.terminalScroll(handle, 1, 0);
    }

    /** Scrolls the viewport by delta rows; negative is up (into history). */
    public synchronized void scrollBy(int deltaRows) {
        if (handle != 0) TerminalNative.terminalScroll(handle, 2, deltaRows);
    }

    /** out: [0] total rows, [1] viewport offset, [2] viewport length. */
    public synchronized void scrollbar(int[] out) {
        if (handle != 0) TerminalNative.terminalScrollbar(handle, out);
    }

    /**
     * Scrolls to the previous ({@code dir < 0}) or next ({@code dir > 0})
     * primary shell-prompt line (OSC 133), landing it at the top of the
     * viewport. Returns true if it moved, false when there is no prompt in that
     * direction (or after {@link #close}).
     */
    public synchronized boolean promptNav(int dir) {
        return handle != 0 && TerminalNative.terminalPromptNav(handle, dir) != 0;
    }

    /** Fills out with the current viewport; returns false after close(). */
    public synchronized boolean snapshot(ScreenSnapshot out) {
        if (handle == 0) return false;
        return fillSnapshot(out);
    }

    /** Reusable scrollbar scratch for {@link #snapshotSmooth}; lock-guarded. */
    private final int[] sbScratch = new int[3];

    /**
     * Snapshots the current viewport into {@code out} plus, for smooth (sub-row)
     * scrolling, the single row immediately above the viewport into {@code above}
     * as its row 0. Both snapshots are taken under one lock so the reader thread
     * cannot shift the viewport between them (the row-above is revealed by a
     * transient one-row scroll that is restored before returning).
     *
     * Returns 0 when closed (nothing filled), 1 when only {@code out} was filled
     * because the viewport is already at the top of history (no row above), and 2
     * when both {@code out} and {@code above} were filled.
     */
    public synchronized int snapshotSmooth(ScreenSnapshot out, ScreenSnapshot above) {
        if (handle == 0) return 0;
        if (!fillSnapshot(out)) return 0;
        TerminalNative.terminalScrollbar(handle, sbScratch);
        if (sbScratch[1] <= 0) return 1; // at the top: no row above to reveal
        TerminalNative.terminalScroll(handle, 2, -1); // reveal one row of history
        fillSnapshot(above);
        TerminalNative.terminalScroll(handle, 2, 1);  // restore the viewport
        return 2;
    }

    /** Snapshot body; caller must hold the lock and have a live handle. */
    private boolean fillSnapshot(ScreenSnapshot out) {
        int dims = TerminalNative.terminalSnapshot(handle, out.codepoints,
                out.fg, out.bg, out.attrs, out.meta, out.graphemes);
        int cols = dims >>> 16, rows = dims & 0xFFFF;
        if (cols * rows > out.codepoints.length) {
            // Cell arrays were too small: only meta was written, and grapheme
            // collection was skipped. Grow and refill before judging graphemes.
            out.ensureCapacity(cols * rows);
            TerminalNative.terminalSnapshot(handle, out.codepoints, out.fg,
                    out.bg, out.attrs, out.meta, out.graphemes);
        }
        if (out.graphemesOverflowed()) {
            // Cells now fit; the grapheme buffer asked for more room. Grow it
            // and refill once more (records fit on the retry, same contract).
            out.ensureGraphemeCapacity(out.graphemes[0]);
            TerminalNative.terminalSnapshot(handle, out.codepoints, out.fg,
                    out.bg, out.attrs, out.meta, out.graphemes);
        }
        out.indexGraphemes();
        out.cols = cols;
        out.rows = rows;
        return true;
    }

    // --- Kitty graphics. Images and placements live in the terminal; the
    // renderer reads geometry every frame and pulls pixels on cache misses. ---

    /**
     * Packs visible image placements into out (TerminalNative.GFX_STRIDE ints
     * each) and returns the count, 0 after close(). If out is too small only
     * the first that fit are written; grow it and retry on overflow.
     */
    public synchronized int graphics(int[] out) {
        return handle == 0 ? 0 : TerminalNative.terminalGraphics(handle, out);
    }

    /** RGBA8888 pixels for a stored image (wh[0]=width, wh[1]=height), or null. */
    public synchronized byte[] imagePixels(int imageId, int[] wh) {
        return handle == 0 ? null : TerminalNative.terminalImage(handle, imageId, wh);
    }

    /** Encodes a key press per current terminal modes; null if it encodes to nothing. */
    public synchronized byte[] encodeKey(int androidKeyCode, int mods,
            String utf8, int unshiftedCodepoint) {
        if (handle == 0) return null;
        return TerminalNative.terminalEncodeKey(
                handle, androidKeyCode, mods, utf8, unshiftedCodepoint);
    }

    /**
     * Encodes a mouse event for the PTY per the terminal's active tracking
     * mode; null if it encodes to nothing. See
     * {@link TerminalNative#terminalEncodeMouse}.
     */
    public synchronized byte[] encodeMouse(int action, int button, float x,
            float y, boolean buttonHeld) {
        if (handle == 0) return null;
        return TerminalNative.terminalEncodeMouse(
                handle, action, button, x, y, buttonHeld);
    }

    // --- Selection. The terminal owns it (tracked refs), so it follows its
    // text across scrolling, new output, and reflow; the snapshot reports
    // highlighted cells and endpoint positions for the UI. ---

    /** Selects the word (or blank cell) at viewport (x, y); false if out of range. */
    public synchronized boolean selectWord(int x, int y) {
        return handle != 0 && TerminalNative.terminalSelectWord(handle, x, y);
    }

    /** Selects the whole line (or blank cell) at viewport (x, y); false if out of range. */
    public synchronized boolean selectLine(int x, int y) {
        return handle != 0 && TerminalNative.terminalSelectLine(handle, x, y);
    }

    /** Selects all content (scrollback + screen); false when the terminal is empty. */
    public synchronized boolean selectAll() {
        return handle != 0 && TerminalNative.terminalSelectAll(handle);
    }

    /** Pins the endpoint opposite the grabbed handle (0 = top-left, 1 = bottom-right). */
    public synchronized void selectionAnchor(int which) {
        if (handle != 0) TerminalNative.terminalSelectionAnchor(handle, which);
    }

    /** Drags the grabbed selection endpoint to viewport (x, y). */
    public synchronized void selectionDrag(int x, int y) {
        if (handle != 0) TerminalNative.terminalSelectionDrag(handle, x, y);
    }

    public synchronized void selectionClear() {
        if (handle != 0) TerminalNative.terminalSelectionClear(handle);
    }

    /** Selected text (unwrapped, trimmed), or null when nothing is selected. */
    public synchronized String selectionText() {
        if (handle == 0) return null;
        byte[] utf8 = TerminalNative.terminalSelectionText(handle);
        return utf8 == null ? null
                : new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * OSC 8 hyperlink URI at viewport cell (x, y), or null when the cell has
     * no hyperlink (or after {@link #close}). Cheap enough to call per tap.
     */
    public synchronized String hyperlinkAt(int x, int y) {
        if (handle == 0) return null;
        byte[] utf8 = TerminalNative.terminalHyperlinkAt(handle, x, y);
        return utf8 == null ? null
                : new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- Text search. The search state (query, matches, current index) lives
    // in the native context; set/step scan and highlight in a single locked
    // call, so they can't race with the reader thread, and the current match is
    // installed as the selection — which is why search reuses the selection
    // slot. Each call fills out: out[0] = current position (1-based, 0 if none),
    // out[1] = navigable match count. ---

    /**
     * Sets a new query, scans, and highlights the match nearest the viewport.
     * Returns the total hit count (0 after close); see the section comment for
     * {@code out}. An empty query clears the search.
     */
    public synchronized int searchSet(String query, boolean caseSensitive, int[] out) {
        if (handle == 0) {
            out[0] = 0;
            out[1] = 0;
            return 0;
        }
        return TerminalNative.terminalSearchSet(handle,
                query.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                caseSensitive, out);
    }

    /** Steps to the next (dir &gt; 0) or previous (dir &lt; 0) match; returns the total. */
    public synchronized int searchStep(int dir, int[] out) {
        if (handle == 0) {
            out[0] = 0;
            out[1] = 0;
            return 0;
        }
        return TerminalNative.terminalSearchStep(handle, dir, out);
    }

    public synchronized void searchClear() {
        if (handle != 0) TerminalNative.terminalSearchClear(handle);
    }

    /** Encodes paste text per terminal modes (bracketed paste etc.), or null. */
    public synchronized byte[] encodePaste(String text) {
        if (handle == 0) return null;
        return TerminalNative.terminalEncodePaste(
                handle, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            TerminalNative.terminalFree(handle);
            handle = 0;
        }
    }
}
