/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

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
    private static final int SIGKILL = 9;

    // OSC 9;4 (ConEmu) progress states, as reported by a running program.
    /** No progress / cleared. */
    public static final int PROGRESS_NONE = 0;
    /** Determinate progress; {@code progressValue()} is the percentage. */
    public static final int PROGRESS_NORMAL = 1;
    /** An error occurred; the percentage (if any) is the point it failed at. */
    public static final int PROGRESS_ERROR = 2;
    /** Busy with an unknown completion time. */
    public static final int PROGRESS_INDETERMINATE = 3;
    /** Paused / waiting on the user. */
    public static final int PROGRESS_PAUSED = 4;

    public interface Listener {
        /** Screen content changed; pull a fresh snapshot. */
        void onUpdate(TerminalSession session);
        void onTitleChanged(TerminalSession session);
        void onBell(TerminalSession session);
        /** Shell exited; code is the exit status or -signal. */
        void onExited(TerminalSession session, int exitCode);

        /**
         * A program set the clipboard via OSC 52. {@code data} is the raw
         * decoded bytes; {@code sel} names the target selection(s) (e.g. "c").
         */
        default void onClipboardWrite(TerminalSession session, String sel, byte[] data) {}

        /**
         * A program requested the clipboard via OSC 52 ({@code ?}). The app may
         * answer with {@link #sendClipboardResponse}, subject to user consent.
         */
        default void onClipboardQuery(TerminalSession session, String sel) {}

        /**
         * OSC 9;4 progress changed. {@code state} is one of the
         * {@code PROGRESS_*} constants; {@code value} is 0..100.
         */
        default void onProgress(TerminalSession session, int state, int value) {}
    }

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
    private volatile boolean terminateProcessesOnExit;
    private volatile int progressState = PROGRESS_NONE;
    private volatile int progressValue;

    /**
     * Taps the raw PTY stream for OSC 52 / OSC 9;4 in parallel with the VT
     * engine. Its callbacks fire on the reader thread; this session hops them to
     * the main thread and out to the {@link Listener}, like the VT effects.
     */
    private final OscSideScanner oscScanner = new OscSideScanner(new OscSideScanner.OscSink() {
        @Override
        public void onClipboardWrite(String sel, byte[] data) {
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) l.onClipboardWrite(TerminalSession.this, sel, data);
            });
        }

        @Override
        public void onClipboardQuery(String sel) {
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) l.onClipboardQuery(TerminalSession.this, sel);
            });
        }

        @Override
        public void onProgress(int state, int value) {
            progressState = state;
            progressValue = value;
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) l.onProgress(TerminalSession.this, state, value);
            });
        }
    });

    private final String label;
    private final boolean userland;
    // Set when this session is a view onto a guest machine's terminal rather
    // than a process on a PTY. Then there is no child of ours to wait on or
    // signal: the machine owns the process, and this session owns only a dup of
    // one channel into it.
    private final VmMachine vm;
    private final int vmTerminal;
    // Set once the user actually sends something to the shell. Distinguishes a
    // session the user used from one that died before they could touch it.
    private volatile boolean userInteracted;

    /** Spawns /system/bin/sh; see {@link SessionCommand#androidShell}. */
    public TerminalSession(int cols, int rows, int cellWidthPx, int cellHeightPx,
            int scrollbackLines, String homeDir, String tmpDir, Listener listener)
            throws IOException {
        this(cols, rows, cellWidthPx, cellHeightPx, scrollbackLines,
                SessionCommand.androidShell(homeDir, tmpDir), listener);
    }

    /**
     * Spawns the given command (Android shell or userland). The cell
     * pixel size seeds the PTY winsize so the spawn size is final — including
     * the pixel fields some programs (e.g. Kitty's icat) read to size images.
     * {@code scrollbackLines} fixes the emulator's history depth for this
     * session's lifetime.
     */
    public TerminalSession(int cols, int rows, int cellWidthPx, int cellHeightPx,
            int scrollbackLines, SessionCommand command, Listener listener)
            throws IOException {
        this(cols, rows, cellWidthPx, cellHeightPx, scrollbackLines, command,
                false, listener);
    }

    public TerminalSession(int cols, int rows, int cellWidthPx, int cellHeightPx,
            int scrollbackLines, SessionCommand command,
            boolean terminateProcessesOnExit, Listener listener)
            throws IOException {
        this.listener = listener;
        this.label = command.label;
        this.userland = command.userland;
        this.vm = null;
        this.vmTerminal = -1;
        this.terminateProcessesOnExit = terminateProcessesOnExit;
        this.emulator = new TerminalEmulator(cols, rows, scrollbackLines);

        int[] pidOut = new int[1];
        int fd = command.cmd != null
                ? TerminalNative.ptyCreate(command.cmd, command.argv,
                        command.env, command.cwd, cols, rows, cellWidthPx,
                        cellHeightPx, pidOut)
                : TerminalNative.ptyCreateEmulator(command.argv, command.env,
                        command.cwd, cols, rows, cellWidthPx, cellHeightPx,
                        pidOut);
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

    /**
     * Attaches to one terminal of a running guest machine.
     *
     * Nothing is spawned: the guest's own {@code getty} is already sitting on
     * that tty, and this session is a view onto the channel reaching it. So
     * there is no child process to wait on, and closing the tab must not hang
     * anything up — {@link VmMachine#attach} hands out a {@code dup}, so
     * releasing it leaves the guest's shell exactly where it was.
     */
    public TerminalSession(int cols, int rows, int cellWidthPx, int cellHeightPx,
            int scrollbackLines, VmMachine machine, int terminal,
            Listener listener) throws IOException {
        this.listener = listener;
        this.label = machine.terminalName(terminal);
        this.userland = false;
        this.vm = machine;
        this.vmTerminal = terminal;
        this.terminateProcessesOnExit = false;
        this.emulator = new TerminalEmulator(cols, rows, scrollbackLines);

        this.pid = 0;                       // not ours; the machine owns it
        this.masterFd = machine.attach(terminal);
        this.toPty = new FileOutputStream(masterFd.getFileDescriptor());
        lastCols = cols;
        lastRows = rows;
        machine.setWinsize(terminal, cols, rows);

        Thread reader = new Thread(this::readLoop, "vm-reader-" + label);
        reader.setDaemon(true);
        reader.start();
        // No waiter: the machine reports its own exit, and this session ends
        // when its channel does.
    }

    private void readLoop() {
        byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(masterFd.getFileDescriptor())) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                // Passive tap for OSC 52 / OSC 9;4 before the engine sees the
                // bytes; it reads, never mutates, so ordering doesn't matter.
                oscScanner.scan(buf, n);
                byte[] response = emulator.feed(buf, n);
                if (response != null) writeRaw(response); // protocol reply, not user input
                dispatchEvents();
            }
        } catch (IOException ignored) {
            // PTY closed (shell exited or session closed); fall through.
        }
        // Only this thread feeds the emulator, so freeing here is safe;
        // concurrent UI snapshots are fenced by the emulator lock.
        emulator.close();
        // A VM session has no child to wait on, so nothing else would ever
        // report its end: it ends when its channel does. Reaching here without
        // close() having run means the machine went away underneath the tab,
        // which the listener has to hear about — a user-closed tab is already
        // being torn down and must not be reported twice.
        if (vm != null && !closed) {
            exitCode = 0;
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) l.onExited(this, 0);
            });
        }
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

    /** Static tab-title prefix for sessions that never set a title. */
    public String label() {
        return label;
    }

    /** True for a userland session (vs. the plain Android shell). */
    public boolean isUserland() {
        return userland;
    }

    /**
     * True once the user has sent any input (a key, text, or paste) to this
     * shell. A session that exits while this is still false never came up far
     * enough for the user to use it — e.g. the emulator or bash failed at launch.
     * Terminal query replies written back by the reader thread don't count.
     */
    public boolean userInteracted() {
        return userInteracted;
    }

    /** Exit status, or null while the shell is running. */
    public Integer exitCode() {
        return exitCode;
    }

    /** Latest OSC 9;4 progress state ({@code PROGRESS_*}); {@code PROGRESS_NONE} if unset. */
    public int progressState() {
        return progressState;
    }

    /** Latest OSC 9;4 progress percentage (0..100), meaningful for the determinate states. */
    public int progressValue() {
        return progressValue;
    }

    /**
     * Writes an OSC 52 clipboard-query answer back to the PTY. Like the VT
     * engine's own query replies, this is a protocol response, so it does not
     * count as user interaction.
     */
    public void sendClipboardResponse(byte[] data) {
        writeRaw(data);
    }

    /**
     * When true, closing a userland tab SIGKILLs the emulator's whole process
     * group (foreground and same-group guest processes die with the tab).
     * When false, tab close only hangs up the top-level session.
     */
    public void setTerminateProcessesOnExit(boolean terminate) {
        terminateProcessesOnExit = terminate;
    }

    public void write(String text) {
        userInteracted = true;
        writeRaw(text.getBytes(StandardCharsets.UTF_8));
    }

    public void writeBytes(byte[] data) {
        userInteracted = true;
        writeRaw(data);
    }

    /** Writes to the PTY without marking user interaction (protocol replies). */
    private void writeRaw(byte[] data) {
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
        userInteracted = true;
        byte[] encoded = emulator.encodeKey(androidKeyCode, mods, utf8, unshiftedCp);
        emulator.scrollToBottom();
        if (encoded != null) {
            writeRaw(encoded);
        } else if (utf8 != null && !utf8.isEmpty()) {
            writeRaw(utf8.getBytes(StandardCharsets.UTF_8));
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
        if (vm != null) {
            // A socketpair carries no winsize, so the guest is told over the
            // machine's control channel instead of by ioctl on this end.
            vm.setWinsize(vmTerminal, cols, rows);
            return;
        }
        TerminalNative.ptySetSize(masterFd.getFd(), cols, rows, cellWidthPx,
                cellHeightPx);
    }

    /** True for a session attached to a guest machine's terminal. */
    public boolean isVm() {
        return vm != null;
    }

    /**
     * Which of the machine's terminals this session is attached to, or -1 when
     * it is not a VM session. Lets the tab layer tell which terminals are
     * already open without keeping a second list in step with this one.
     */
    public int vmTerminal() {
        return vmTerminal;
    }

    /** Hangs up or terminates the session and releases the PTY. Idempotent. */
    public void close() {
        if (closed) return;
        closed = true;
        if (vm != null) {
            // Detach only. The guest's getty and whatever it is running belong
            // to the machine, not to this tab; closing our dup of the channel
            // leaves the machine's own copy open, so the guest sees no hangup
            // and a later tab finds the same shell. Local scrollback goes,
            // because the Ghostty terminal behind this session goes with it.
            try {
                masterFd.close();           // unblocks the reader thread
            } catch (IOException ignored) {
            }
            return;
        }
        if (userland && terminateProcessesOnExit) {
            // arm64chroot has no tracee supervisor: every guest process is a
            // real host process in the emulator's session (the fork()ed child
            // called setsid(), so its pid is the process-group leader). SIGKILL
            // the whole group so foreground and same-group children die with the
            // tab. A guest daemon that setsid()s into its own group escapes —
            // the inherent limit of an unprivileged sandbox with no supervisor.
            TerminalNative.processKill(-pid, SIGKILL);
        } else {
            TerminalNative.ptyHangupForeground(masterFd.getFd(), pid);
        }
        try {
            masterFd.close(); // unblocks the reader thread
        } catch (IOException ignored) {
        }
    }
}
