package sh.easycli.proot.term;

/**
 * JNI surface of libterm.so (PTY syscalls + libghostty-vt bindings).
 *
 * Terminal handles are raw native pointers; callers must serialize access
 * per handle and never use one after {@link #terminalFree}. That discipline
 * lives in {@link TerminalEmulator} — use it instead of calling these
 * directly.
 */
public final class TerminalNative {
    static {
        System.loadLibrary("term");
    }

    private TerminalNative() {}

    /** Event bits returned by {@link #terminalEvents}. */
    public static final int EVENT_BELL = 1;
    public static final int EVENT_TITLE = 2;

    /** Attribute bits in the snapshot attrs array. */
    public static final int ATTR_BOLD = 1;
    public static final int ATTR_ITALIC = 2;
    public static final int ATTR_UNDERLINE = 4;
    public static final int ATTR_STRIKE = 8;
    public static final int ATTR_WIDE = 16;

    /**
     * Underline shape: a 3-bit field in the attrs byte (bits 5-7) holding one
     * of the {@code UNDERLINE_*} values. {@link #ATTR_UNDERLINE} is set
     * whenever this field is non-zero. Mask with {@link #ATTR_UL_MASK} before
     * shifting right by {@link #ATTR_UL_SHIFT} — bit 7 is the byte's sign bit,
     * so a bare shift would sign-extend.
     */
    public static final int ATTR_UL_SHIFT = 5;
    public static final int ATTR_UL_MASK = 7 << ATTR_UL_SHIFT;
    public static final int UNDERLINE_NONE = 0;
    public static final int UNDERLINE_SINGLE = 1;
    public static final int UNDERLINE_DOUBLE = 2;
    public static final int UNDERLINE_CURLY = 3;
    public static final int UNDERLINE_DOTTED = 4;
    public static final int UNDERLINE_DASHED = 5;

    /** Modifier bits for {@link #terminalEncodeKey} (GHOSTTY_MODS_*). */
    public static final int MOD_SHIFT = 1;
    public static final int MOD_CTRL = 1 << 1;
    public static final int MOD_ALT = 1 << 2;

    /** Cursor style enum values in snapshot meta[3]. */
    public static final int CURSOR_BAR = 0;
    public static final int CURSOR_BLOCK = 1;
    public static final int CURSOR_UNDERLINE = 2;
    public static final int CURSOR_BLOCK_HOLLOW = 3;

    /** Selection flag bits in snapshot meta[9]. */
    public static final int SEL_ACTIVE = 1;
    public static final int SEL_START_VISIBLE = 2;
    public static final int SEL_END_VISIBLE = 4;

    /** Input-mode flag bits in snapshot meta[14] (mirrored in terminal_jni.c). */
    public static final int INPUT_MODE_ALT_SCREEN = 1;
    public static final int INPUT_MODE_APP_CURSOR = 2;
    public static final int INPUT_MODE_BRACKETED_PASTE = 4;
    public static final int INPUT_MODE_MOUSE = 8;

    /** Mouse event actions for {@link #terminalEncodeMouse} (GhosttyMouseAction). */
    public static final int MOUSE_PRESS = 0;
    public static final int MOUSE_RELEASE = 1;
    public static final int MOUSE_MOTION = 2;

    /**
     * Mouse buttons for {@link #terminalEncodeMouse} (GhosttyMouseButton).
     * Wheel scroll is reported as a press of buttons 4–7 (X11 convention):
     * 4/5 are wheel up/down, 6/7 are wheel left/right.
     */
    public static final int MOUSE_BUTTON_LEFT = 1;
    public static final int MOUSE_BUTTON_RIGHT = 2;
    public static final int MOUSE_BUTTON_MIDDLE = 3;
    public static final int MOUSE_WHEEL_UP = 4;
    public static final int MOUSE_WHEEL_DOWN = 5;
    public static final int MOUSE_WHEEL_LEFT = 6;
    public static final int MOUSE_WHEEL_RIGHT = 7;

    /**
     * Kitty graphics placement record layout from {@link #terminalGraphics}:
     * GFX_STRIDE ints per visible placement, mirrored in terminal_jni.c. Col,
     * row and z are signed; col/row are viewport cell coordinates that may be
     * negative when a placement is partly scrolled off the top/left.
     */
    public static final int GFX_STRIDE = 14;
    public static final int GFX_IMAGE_ID = 0;
    public static final int GFX_IMAGE_W = 1;   // source image width, px
    public static final int GFX_IMAGE_H = 2;   // source image height, px
    public static final int GFX_COL = 3;       // viewport column (cells)
    public static final int GFX_ROW = 4;       // viewport row (cells)
    public static final int GFX_PIXEL_W = 5;   // rendered width, px
    public static final int GFX_PIXEL_H = 6;   // rendered height, px
    public static final int GFX_SRC_X = 7;     // source rect origin x, px
    public static final int GFX_SRC_Y = 8;     // source rect origin y, px
    public static final int GFX_SRC_W = 9;     // source rect width, px
    public static final int GFX_SRC_H = 10;    // source rect height, px
    public static final int GFX_Z = 11;        // z-index (<0 draws below text)
    public static final int GFX_OFF_X = 12;    // sub-cell pixel offset x
    public static final int GFX_OFF_Y = 13;    // sub-cell pixel offset y

    // --- PTY / process ---

    /**
     * Opens a PTY and spawns cmd on it. Returns the master fd; the child
     * pid is written to pidOut[0].
     */
    public static native int ptyCreate(String cmd, String[] args, String[] env,
            String cwd, int cols, int rows, int cellWidthPx, int cellHeightPx,
            int[] pidOut) throws java.io.IOException;

    /**
     * Opens a PTY and forks a child that enters PRoot's main() in-process
     * (libterm.so links PRoot; nothing is exec'd except, later, the tracee's
     * loader — see native/proot/ANDROID.md). args is the full proot argv
     * including argv[0]; env must carry PROOT_LOADER and PROOT_TMP_DIR.
     */
    public static native int ptyCreateProot(String[] args, String[] env,
            String cwd, int cols, int rows, int cellWidthPx, int cellHeightPx,
            int[] pidOut) throws java.io.IOException;

    public static native void ptySetSize(int fd, int cols, int rows,
            int cellWidthPx, int cellHeightPx);

    /** Blocks until pid exits; returns exit code or -signal. */
    public static native int processWaitFor(int pid);

    public static native void processKill(int pid, int signal);

    // --- Ghostty terminal ---

    /**
     * Returns a terminal handle, or 0 on allocation failure. {@code
     * scrollbackLines} is a line count; the native side converts it to the
     * byte budget Ghostty's max_scrollback actually expects (see terminal_jni).
     */
    public static native long terminalNew(int cols, int rows, int scrollbackLines);

    public static native void terminalFree(long handle);

    /**
     * Feeds PTY output to the VT parser. Returns response bytes that must
     * be written back to the PTY (terminal query replies), or null.
     */
    public static native byte[] terminalFeed(long handle, byte[] data, int len);

    /** Returns and clears pending EVENT_* bits. */
    public static native int terminalEvents(long handle);

    /** Current title from OSC 0/2, or null if unset. */
    public static native String terminalTitle(long handle);

    public static native void terminalResize(long handle, int cols, int rows,
            int cellWidthPx, int cellHeightPx);

    /**
     * Sets the default color theme. {@code fg}, {@code bg} and {@code cursor}
     * are ARGB ints (alpha ignored); {@code palette256} is the full 256-entry
     * palette as ARGB ints. These are defaults — programs may still override
     * them via OSC. Effective colors show up in {@link #terminalSnapshot}
     * (defaults in meta[7]/[8], cursor in meta[15], per-cell fg/bg resolved).
     */
    public static native void terminalSetColors(long handle, int fg, int bg,
            int cursor, int[] palette256);

    /**
     * Sets the default cursor style ({@code CURSOR_*}) and whether it blinks.
     * These are the values the cursor resets to on DECSCUSR (CSI 0 q); the
     * engine also pushes them to the live cursor immediately when no program
     * override is active. Effective style/blink appear in
     * {@link #terminalSnapshot} (meta[3]/meta[5]).
     */
    public static native void terminalSetCursorStyle(long handle, int style,
            boolean blink);

    /**
     * Force-enables or disables DEC mode 2027 (grapheme-cluster mode). When on,
     * the engine groups multi-codepoint grapheme clusters (combining marks, ZWJ
     * emoji, Indic conjuncts) into a single cell; the native side re-asserts it
     * after each feed so it survives a program's RIS reset. Off by default.
     */
    public static native void terminalSetGraphemeClustering(long handle,
            boolean enable);

    /** mode: 0 = top, 1 = bottom, 2 = by delta rows (negative is up). */
    public static native void terminalScroll(long handle, int mode, int delta);

    /** out: [0] total rows, [1] viewport offset, [2] viewport length. */
    public static native void terminalScrollbar(long handle, int[] out);

    /**
     * Copies the viewport into the given arrays; see terminal_jni.c for the
     * meta layout. Returns (cols << 16) | rows; if the cell arrays are smaller
     * than cols*rows, only meta is filled and the caller must retry with
     * bigger arrays.
     *
     * Multi-codepoint grapheme clusters ride in {@code graphemes}, a
     * self-describing overflow buffer: slot 0 is the number of record ints
     * required (excluding slot 0), followed by {@code [cellIndex, count,
     * cp0, cp1, ...]} records. If slot 0 exceeds {@code graphemes.length - 1}
     * the records didn't fit; grow the buffer and retry, same as the cells.
     */
    public static native int terminalSnapshot(long handle, int[] codepoints,
            int[] fg, int[] bg, byte[] attrs, int[] meta, int[] graphemes);

    /**
     * Packs visible Kitty graphics placements into out (GFX_STRIDE ints each)
     * and returns the placement count. If out is too small, only those that
     * fit are written and the caller must retry with a bigger array — same
     * contract as {@link #terminalSnapshot}.
     */
    public static native int terminalGraphics(long handle, int[] out);

    /**
     * Pixels of a stored Kitty image as RGBA8888 (width*height*4 bytes, R,G,B,A
     * order), with wh[0]=width and wh[1]=height. Null if the image is gone or
     * can't be marshalled.
     */
    public static native byte[] terminalImage(long handle, int imageId, int[] wh);

    /**
     * Encodes a key press per current terminal modes. utf8 is the text the
     * key produces (null for pure control keys). Returns bytes for the PTY
     * or null if the key encodes to nothing.
     */
    public static native byte[] terminalEncodeKey(long handle, int androidKeyCode,
            int mods, String utf8, int unshiftedCodepoint);

    /**
     * Encodes a single mouse event per the terminal's active tracking mode and
     * output format. {@code action} is a {@code MOUSE_PRESS/RELEASE/MOTION}
     * value, {@code button} a {@code MOUSE_BUTTON_*}/{@code MOUSE_WHEEL_*}
     * value, and {@code x}/{@code y} are surface pixels relative to the cell
     * grid origin. Returns bytes for the PTY, or null when the event encodes to
     * nothing (e.g. no tracking mode is active).
     */
    public static native byte[] terminalEncodeMouse(long handle, int action,
            int button, float x, float y);

    // --- Selection (state lives in the terminal; survives scroll/reflow) ---

    /**
     * Selects the word under viewport cell (x, y) — or just that cell when
     * it holds no word — and makes it the active selection. Returns false
     * if the coordinates don't resolve to a cell.
     */
    public static native boolean terminalSelectWord(long handle, int x, int y);

    /**
     * Reorders the active selection so the grabbed visual endpoint
     * (0 = top-left, 1 = bottom-right) is the one {@link #terminalSelectionDrag}
     * moves; the other endpoint stays anchored for the drag.
     */
    public static native void terminalSelectionAnchor(long handle, int which);

    /** Moves the dragged selection endpoint to viewport cell (x, y). */
    public static native void terminalSelectionDrag(long handle, int x, int y);

    public static native void terminalSelectionClear(long handle);

    /** Selected text as UTF-8 (unwrapped, trimmed), or null if no selection. */
    public static native byte[] terminalSelectionText(long handle);

    // --- Text search (state lives in the terminal; matches are screen ranges) ---

    /**
     * Sets a new search {@code query} (UTF-8), scans the whole screen
     * (scrollback + active area), and highlights and reveals the match nearest
     * the viewport — all in one call, so it can't race with PTY output. Returns
     * the <em>total</em> hit count (which may exceed the navigable window) and
     * writes two ints to {@code out}: {@code out[0]} = the current match
     * position (1-based, 0 if none), {@code out[1]} = the navigable match count
     * (the most-recent hits, capped to bound memory). An empty query clears the
     * search.
     */
    public static native int terminalSearchSet(long handle, byte[] query,
            boolean caseSensitive, int[] out);

    /**
     * Moves to the next ({@code dir > 0}) or previous ({@code dir < 0}) match,
     * wrapping, and highlights/reveals it. Re-scans first only if the buffer
     * changed since the last scan, so idle navigation is cheap. Return value and
     * {@code out} are as in {@link #terminalSearchSet}.
     */
    public static native int terminalSearchStep(long handle, int dir, int[] out);

    /** Frees the search state and clears the selection. */
    public static native void terminalSearchClear(long handle);

    /**
     * Encodes paste text for the PTY: strips unsafe control bytes and
     * applies bracketed-paste markers or newline→CR per terminal modes.
     */
    public static native byte[] terminalEncodePaste(long handle, byte[] utf8);
}
