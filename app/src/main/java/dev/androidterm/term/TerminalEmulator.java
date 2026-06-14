package dev.androidterm.term;

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

    /** Fills out with the current viewport; returns false after close(). */
    public synchronized boolean snapshot(ScreenSnapshot out) {
        if (handle == 0) return false;
        int dims = TerminalNative.terminalSnapshot(
                handle, out.codepoints, out.fg, out.bg, out.attrs, out.meta);
        int cols = dims >>> 16, rows = dims & 0xFFFF;
        if (cols * rows > out.codepoints.length) {
            out.ensureCapacity(cols * rows);
            TerminalNative.terminalSnapshot(
                    handle, out.codepoints, out.fg, out.bg, out.attrs, out.meta);
        }
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

    // --- Selection. The terminal owns it (tracked refs), so it follows its
    // text across scrolling, new output, and reflow; the snapshot reports
    // highlighted cells and endpoint positions for the UI. ---

    /** Selects the word (or blank cell) at viewport (x, y); false if out of range. */
    public synchronized boolean selectWord(int x, int y) {
        return handle != 0 && TerminalNative.terminalSelectWord(handle, x, y);
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
