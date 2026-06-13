package dev.androidterm.term;

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

    /**
     * Kitty graphics placement record layout from {@link #terminalGraphics}:
     * GFX_STRIDE ints per visible placement, mirrored in terminal_jni.c. Col,
     * row and z are signed; col/row are viewport cell coordinates that may be
     * negative when a placement is partly scrolled off the top/left.
     */
    public static final int GFX_STRIDE = 12;
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

    // --- PTY / process ---

    /**
     * Opens a PTY and spawns cmd on it. Returns the master fd; the child
     * pid is written to pidOut[0].
     */
    public static native int ptyCreate(String cmd, String[] args, String[] env,
            String cwd, int cols, int rows, int[] pidOut) throws java.io.IOException;

    /**
     * Opens a PTY and forks a child that enters PRoot's main() in-process
     * (libterm.so links PRoot; nothing is exec'd except, later, the tracee's
     * loader — see native/proot/ANDROID.md). args is the full proot argv
     * including argv[0]; env must carry PROOT_LOADER and PROOT_TMP_DIR.
     */
    public static native int ptyCreateProot(String[] args, String[] env,
            String cwd, int cols, int rows, int[] pidOut) throws java.io.IOException;

    public static native void ptySetSize(int fd, int cols, int rows);

    /** Blocks until pid exits; returns exit code or -signal. */
    public static native int processWaitFor(int pid);

    public static native void processKill(int pid, int signal);

    // --- Ghostty terminal ---

    /** Returns a terminal handle, or 0 on allocation failure. */
    public static native long terminalNew(int cols, int rows, int scrollbackRows);

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

    /** mode: 0 = top, 1 = bottom, 2 = by delta rows (negative is up). */
    public static native void terminalScroll(long handle, int mode, int delta);

    /** out: [0] total rows, [1] viewport offset, [2] viewport length. */
    public static native void terminalScrollbar(long handle, int[] out);

    /**
     * Copies the viewport into the given arrays; see terminal_jni.c for the
     * meta layout. Returns (cols << 16) | rows; if the arrays are smaller
     * than cols*rows, only meta is filled and the caller must retry with
     * bigger arrays.
     */
    public static native int terminalSnapshot(long handle, int[] codepoints,
            int[] fg, int[] bg, byte[] attrs, int[] meta);

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

    /**
     * Encodes paste text for the PTY: strips unsafe control bytes and
     * applies bracketed-paste markers or newline→CR per terminal modes.
     */
    public static native byte[] terminalEncodePaste(long handle, byte[] utf8);
}
