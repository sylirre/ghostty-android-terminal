/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One running guest machine under arm64emu, and the host channels into it.
 *
 * The other two session types are one process per tab. This one is not: a VM is
 * a whole machine — a kernel, an init, a set of getty processes — that outlives
 * any particular tab and that several tabs attach to at once. So it is a process
 * singleton like {@link SessionManager}, started once and stopped explicitly,
 * and a tab is only a view onto one of its terminals.
 *
 * Each guest terminal has a socketpair of its own. Tab 0 is {@code ttyAMA0}, the
 * serial console: it carries the firmware and kernel output, and the stock Alpine
 * ISO already respawns a getty there, so it needs no guest-side setup. The rest
 * are virtio-consoles, {@code hvc0} upward, one guest tty each.
 *
 * A tab attaching takes a {@code dup} of its channel rather than the channel
 * itself, so closing the tab hangs up nothing: the guest's shell keeps running
 * and reattaching finds it where it was. What does not survive is the local
 * scrollback, since the Ghostty terminal behind the tab goes with it.
 */
public final class VmMachine {
    private static final String TAG = "VmMachine";
    private static final int SIGKILL = 9;

    /** Control-channel opcode: set a terminal's window size. */
    private static final int OP_WINSIZE = 1;
    /** Control-channel device byte naming the PL011 (see the emulator's console.h). */
    private static final int DEV_UART = 0xff;
    private static final int CTRL_MSG_SIZE = 8;

    private static VmMachine instance;

    /** The running machine, or null if none. */
    public static synchronized VmMachine get() {
        return instance;
    }

    public static synchronized boolean isRunning() {
        return instance != null && instance.running;
    }

    /**
     * Boots a machine. Fails if one is already running — the tabs of a second
     * machine would be indistinguishable from the first's, and two full-system
     * emulators is more than a phone should be asked for.
     */
    public static synchronized VmMachine start(VmOptions options) throws IOException {
        if (instance != null && instance.running) {
            throw new IOException("a guest machine is already running");
        }
        instance = new VmMachine(options);
        return instance;
    }

    public static synchronized void stopIfRunning() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    private final VmOptions options;
    private final ParcelFileDescriptor[] channels;   // [0] ttyAMA0, [1..] hvc0..
    private final ParcelFileDescriptor control;
    private final ParcelFileDescriptor diagnostics;
    private final OutputStream toControl;
    private final int pid;
    private volatile boolean running = true;
    private volatile Integer exitCode;
    private volatile Listener listener;

    /** Notified when the machine goes away, on the main thread's behalf. */
    public interface Listener {
        void onVmExited(int exitCode);
    }

    private VmMachine(VmOptions options) throws IOException {
        this.options = options;

        int nTerminals = 1 + options.hvcCount;
        int[] fds = new int[nTerminals + 2];
        int[] pidOut = new int[1];
        TerminalNative.vmStart(argv(options), env(), null, options.hvcCount, fds,
                pidOut);

        this.pid = pidOut[0];
        this.channels = new ParcelFileDescriptor[nTerminals];
        for (int i = 0; i < nTerminals; i++) {
            channels[i] = ParcelFileDescriptor.adoptFd(fds[i]);
        }
        this.control = ParcelFileDescriptor.adoptFd(fds[nTerminals]);
        this.diagnostics = ParcelFileDescriptor.adoptFd(fds[nTerminals + 1]);
        this.toControl = new FileOutputStream(control.getFileDescriptor());

        Thread logs = new Thread(this::pumpDiagnostics, "vm-log-" + pid);
        logs.setDaemon(true);
        logs.start();
        Thread waiter = new Thread(this::waitLoop, "vm-waiter-" + pid);
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * The emulator's command line, minus the channel bindings — those name
     * descriptor numbers the native side assigns, so it appends them itself.
     *
     * The guest console deliberately stays on {@code ttyAMA0} rather than moving
     * to {@code hvc0}: that would need {@code console=hvc0} on the kernel command
     * line, which a GRUB boot off the stock ISO does not let us set. The extra
     * virtio-consoles ride alongside it instead, which needs nothing of the
     * bootloader.
     */
    private static String[] argv(VmOptions o) {
        return new String[] {
                "arm64emu",
                o.jit ? "--jit" : "--no-pd",
                "--bios", o.firmware.getAbsolutePath(),
                "--drive", o.image.getAbsolutePath() + ",ro",
                "--memory", Integer.toString(o.memoryMb),
                "--console", "pl011,count=" + o.hvcCount,
        };
    }

    private static String[] env() {
        // The emulator reads only its own AE* variables, none of which are set
        // here; TMPDIR is passed so anything falling back to it stays in the app.
        return new String[] { "TMPDIR=/data/local/tmp" };
    }

    /** Guest terminals, including {@code ttyAMA0}. Always at least one. */
    public int terminalCount() {
        return channels.length;
    }

    /** Guest-side name of terminal {@code i}, for tab labels. */
    public String terminalName(int i) {
        return i == 0 ? "ttyAMA0" : "hvc" + (i - 1);
    }

    /**
     * A private view of terminal {@code i}'s channel for a session to read and
     * write. The caller owns it and should close it when its tab goes away;
     * the machine keeps its own, so the guest side never sees a hangup.
     */
    public ParcelFileDescriptor attach(int i) throws IOException {
        if (i < 0 || i >= channels.length) {
            throw new IOException("no such guest terminal: " + i);
        }
        if (!running) throw new IOException("the guest machine is not running");
        return channels[i].dup();
    }

    /**
     * Tells the guest how big terminal {@code i} now is.
     *
     * A socketpair has no {@code TIOCGWINSZ} and delivers no {@code SIGWINCH},
     * so geometry only reaches the guest by being said out loud on the control
     * channel; the emulator turns it into a virtio config-change interrupt and
     * Linux resizes the tty. {@code ttyAMA0} is the exception: a serial line has
     * no window size at all — real PL011 hardware has no such register — so a
     * program there keeps its default 80x24 until something inside the guest
     * (an {@code stty}) says otherwise.
     */
    public void setWinsize(int i, int cols, int rows) {
        if (!running || i < 0 || i >= channels.length) return;
        byte[] msg = new byte[CTRL_MSG_SIZE];
        msg[0] = OP_WINSIZE;
        msg[1] = (byte) (i == 0 ? DEV_UART : i - 1);
        msg[2] = (byte) (cols & 0xff);
        msg[3] = (byte) ((cols >> 8) & 0xff);
        msg[4] = (byte) (rows & 0xff);
        msg[5] = (byte) ((rows >> 8) & 0xff);
        try {
            toControl.write(msg);
            toControl.flush();
        } catch (IOException e) {
            // The machine is going away; its exit is reported by waitLoop.
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public VmOptions options() {
        return options;
    }

    /** Exit status of the machine, or null while it runs. */
    public Integer exitCode() {
        return exitCode;
    }

    /**
     * Stops the machine and releases every channel. Idempotent.
     *
     * The emulator is its own session leader (it {@code setsid}s before booting),
     * so signalling the group reaches it and nothing of ours. Guest state does
     * not survive: an ISO boot is diskless by construction, so there is nothing
     * to flush and no reason to ask the guest to shut down politely first.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        TerminalNative.processKill(-pid, SIGKILL);
        for (ParcelFileDescriptor c : channels) closeQuietly(c);
        closeQuietly(control);
        closeQuietly(diagnostics);
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Forwards the emulator's own diagnostics — device wiring, JIT fallbacks,
     * console warnings — to logcat. They would otherwise go nowhere: an app
     * process has no terminal behind stderr.
     */
    private void pumpDiagnostics() {
        byte[] buf = new byte[4096];
        StringBuilder line = new StringBuilder();
        try (InputStream in = new FileInputStream(diagnostics.getFileDescriptor())) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    char c = (char) (buf[i] & 0xff);
                    if (c == '\n') {
                        Log.i(TAG, line.toString());
                        line.setLength(0);
                    } else if (c != '\r' && line.length() < 512) {
                        line.append(c);
                    }
                }
            }
        } catch (IOException ignored) {
            // Machine gone; the pipe's write end closed with it.
        }
        if (line.length() > 0) Log.i(TAG, line.toString());
    }

    private void waitLoop() {
        int code = TerminalNative.processWaitFor(pid);
        exitCode = code;
        running = false;
        Listener l = listener;
        if (l != null) l.onVmExited(code);
    }
}
