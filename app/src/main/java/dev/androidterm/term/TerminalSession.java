package dev.androidterm.term;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One shell running on a PTY, wired to a {@link TerminalEmulator}.
 *
 * A reader thread pumps PTY output into the emulator and writes the
 * emulator's query responses back. Listener callbacks arrive on the main
 * thread; onUpdate is coalesced (at most one pending) so a flood of output
 * can't queue unbounded UI work.
 */
public final class TerminalSession {

    public interface Listener {
        /** Screen content changed; pull a fresh snapshot. */
        void onUpdate(TerminalSession session);
        void onTitleChanged(TerminalSession session);
        void onBell(TerminalSession session);
        /** Shell exited; code is the exit status or -signal. */
        void onExited(TerminalSession session, int exitCode);
    }

    private static final int SCROLLBACK_ROWS = 10_000;

    public final TerminalEmulator emulator;
    // Volatile, not final: the Activity that listens is recreated on config
    // changes while sessions live on in SessionManager.
    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean updatePending = new AtomicBoolean();

    private final int pid;
    private final ParcelFileDescriptor masterFd;
    private final OutputStream toPty;
    private int lastCols, lastRows;
    private volatile boolean closed;
    private volatile String title;
    private volatile Integer exitCode;

    /**
     * Spawns /system/bin/sh with PATH=/system/bin on a new PTY.
     *
     * @param homeDir HOME and initial working directory (app files dir —
     *                the only generally writable place).
     * @param tmpDir  TMPDIR (app cache dir).
     */
    public TerminalSession(int cols, int rows, String homeDir, String tmpDir,
            Listener listener) throws IOException {
        this.listener = listener;
        this.emulator = new TerminalEmulator(cols, rows, SCROLLBACK_ROWS);

        String[] env = {
                "PATH=/system/bin",
                "HOME=" + homeDir,
                "TMPDIR=" + tmpDir,
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "ANDROID_ROOT=/system",
                "ANDROID_DATA=/data",
        };
        int[] pidOut = new int[1];
        int fd = TerminalNative.ptyCreate("/system/bin/sh",
                new String[] {"sh"}, env, homeDir, cols, rows, pidOut);
        lastCols = cols;
        lastRows = rows;
        this.pid = pidOut[0];
        this.masterFd = ParcelFileDescriptor.adoptFd(fd);
        this.toPty = new FileOutputStream(masterFd.getFileDescriptor());

        Thread reader = new Thread(this::readLoop, "pty-reader-" + pid);
        reader.setDaemon(true);
        reader.start();
        Thread waiter = new Thread(this::waitLoop, "pty-waiter-" + pid);
        waiter.setDaemon(true);
        waiter.start();
    }

    private void readLoop() {
        byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(masterFd.getFileDescriptor())) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                byte[] response = emulator.feed(buf, n);
                if (response != null) writeBytes(response);
                dispatchEvents();
            }
        } catch (IOException ignored) {
            // PTY closed (shell exited or session closed); fall through.
        }
        // Only this thread feeds the emulator, so freeing here is safe;
        // concurrent UI snapshots are fenced by the emulator lock.
        emulator.close();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void waitLoop() {
        int code = TerminalNative.processWaitFor(pid);
        exitCode = code;
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onExited(this, code);
        });
    }

    private void dispatchEvents() {
        int events = emulator.events();
        if ((events & TerminalNative.EVENT_TITLE) != 0) {
            title = emulator.title();
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) l.onTitleChanged(this);
            });
        }
        if ((events & TerminalNative.EVENT_BELL) != 0) {
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) l.onBell(this);
            });
        }
        if (updatePending.compareAndSet(false, true)) {
            mainHandler.post(() -> {
                updatePending.set(false);
                Listener l = listener;
                if (l != null) l.onUpdate(this);
            });
        }
    }

    /** Title from OSC 0/2, or null. */
    public String title() {
        return title;
    }

    /** Exit status, or null while the shell is running. */
    public Integer exitCode() {
        return exitCode;
    }

    public void write(String text) {
        writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public void writeBytes(byte[] data) {
        if (closed) return;
        try {
            toPty.write(data);
        } catch (IOException ignored) {
            // Shell already gone; exit is reported via onExited.
        }
    }

    /**
     * Encodes and sends a key press. Falls back to writing utf8 raw when
     * the key has no terminal encoding. Any key snaps the viewport back
     * to the live screen, like desktop terminals.
     */
    public void sendKey(int androidKeyCode, int mods, String utf8, int unshiftedCp) {
        byte[] encoded = emulator.encodeKey(androidKeyCode, mods, utf8, unshiftedCp);
        emulator.scrollToBottom();
        if (encoded != null) {
            writeBytes(encoded);
        } else if (utf8 != null && !utf8.isEmpty()) {
            write(utf8);
        }
    }

    public void resize(int cols, int rows, int cellWidthPx, int cellHeightPx) {
        if (closed || cols <= 0 || rows <= 0) return;
        // Skip no-op resizes: a spurious SIGWINCH makes mksh wipe its
        // current prompt line without reprinting it (observed on Android's
        // /system/bin/sh), leaving the screen blank.
        if (cols == lastCols && rows == lastRows) return;
        lastCols = cols;
        lastRows = rows;
        emulator.resize(cols, rows, cellWidthPx, cellHeightPx);
        TerminalNative.ptySetSize(masterFd.getFd(), cols, rows);
    }

    /** Kills the shell and releases the PTY. Idempotent. */
    public void close() {
        if (closed) return;
        closed = true;
        TerminalNative.processKill(pid, 9);
        try {
            masterFd.close(); // unblocks the reader thread
        } catch (IOException ignored) {
        }
    }
}
