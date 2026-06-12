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

    // --- PTY / process ---

    /**
     * Opens a PTY and spawns cmd on it. Returns the master fd; the child
     * pid is written to pidOut[0].
     */
    public static native int ptyCreate(String cmd, String[] args, String[] env,
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
     * Encodes a key press per current terminal modes. utf8 is the text the
     * key produces (null for pure control keys). Returns bytes for the PTY
     * or null if the key encodes to nothing.
     */
    public static native byte[] terminalEncodeKey(long handle, int androidKeyCode,
            int mods, String utf8, int unshiftedCodepoint);
}
