/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static io.github.sylirre.terminal.TestUtil.waitFor;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.term.VmImages;
import io.github.sylirre.terminal.term.VmMachine;
import io.github.sylirre.terminal.term.VmOptions;

/**
 * End-to-end guest machine smoke: {@code arm64emu_main()} booting EDK2 and a
 * real Linux kernel off the bundled ISO, its serial console reaching a tab
 * through a socketpair, and further guest terminals on virtio-consoles each
 * carrying a stream of their own.
 *
 * This exercises the whole VM session type at once — the emulator as a separate
 * shared object, the JNI spawn and its descriptor shuffle, the channel
 * bindings, the control channel, and detach semantics — so it is slow by
 * nature: booting a machine is not starting a process, and on an emulated CI
 * host it is slower still. Skipped when the build bundles no machine images,
 * which is the normal build (they are a large, optional, gitignored input; see
 * scripts/fetch-vm-images.sh).
 */
@RunWith(AndroidJUnit4.class)
public class VmSessionTest {

    /** Firmware, kernel, initramfs, modloop and OpenRC, on an emulated CPU. */
    private static final long BOOT_TIMEOUT_MS = 15 * 60_000;
    /** Once there is a shell, everything else is quick. */
    private static final long TIMEOUT_MS = 120_000;

    /** ttyAMA0 plus hvc0..hvc2, one spare terminal per test that needs one. */
    private static final int HVC_COUNT = 3;

    // One machine for the whole class. Booting is minutes, not milliseconds, so
    // a machine per test would multiply the runtime by the test count for no
    // added coverage: every test here works on an already-booted machine.
    private static VmMachine machine;
    private static TerminalSession console;

    private static final TerminalSession.Listener LISTENER = new TerminalSession.Listener() {
        @Override public void onUpdate(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onExited(TerminalSession s, int code) {}
    };

    @BeforeClass
    public static void bootMachine() throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        assumeTrue("no guest machine images bundled in this build",
                VmImages.assetsAvailable(ctx));
        VmImages.install(ctx, null);

        machine = VmMachine.start(new VmOptions(VmImages.firmware(ctx),
                VmImages.image(ctx), 512, HVC_COUNT, true));
        console = new TerminalSession(80, 24, 8, 16, 10_000, machine, 0, LISTENER);

        // The stock ISO respawns a getty on the serial console, so a login
        // prompt there is proof that firmware, kernel and userspace all came up
        // — with no guest-side setup of ours anywhere in the path.
        waitFor("guest login prompt on the console", BOOT_TIMEOUT_MS,
                () -> screen(console).contains("login:"), () -> screen(console));
        console.write("root\n");
        waitFor("root shell prompt", TIMEOUT_MS,
                () -> screen(console).contains("#"), () -> screen(console));
    }

    @AfterClass
    public static void stopMachine() {
        if (console != null) console.close();
        VmMachine.stopIfRunning();
        console = null;
        machine = null;
    }

    private static int marker;

    /**
     * Clears the screen and waits for a marker of its own to come back, so each
     * test starts from a known screen it cannot confuse with another's output —
     * and from a console just proven to be answering.
     */
    @Before
    public void clearConsole() {
        String mark = "READY" + (++marker);
        console.write("clear; echo " + mark + "\n");
        expect(console, mark);
    }

    private static String screen(TerminalSession s) {
        ScreenSnapshot snap = new ScreenSnapshot();
        s.emulator.snapshot(snap);
        return snap.text();
    }

    /** Runs a command on the console and waits for the marker it ends with. */
    private static void run(String command) {
        console.write(command + "\n");
    }

    private static void expect(TerminalSession s, String needle) {
        waitFor("\"" + needle + "\" on " + s.label(), TIMEOUT_MS,
                () -> screen(s).contains(needle), () -> screen(s));
    }

    @Test
    public void bootsARealKernelOnEmulatedHardware() {
        // A full-system guest, not a translated userland: its own kernel and its
        // own PID 1, which is the whole difference from the other session types.
        run("echo \"k=$(uname -s)/$(uname -m) init=$(cat /proc/1/comm)\"");
        expect(console, "k=Linux/aarch64 init=init");
    }

    @Test
    public void extraTerminalsAreSeparateStreams() throws IOException {
        run("ls /dev/hvc0 /dev/hvc1 /dev/hvc2 > /dev/null 2>&1; echo \"hvc=$?\"");
        expect(console, "hvc=0");

        // Put a login on hvc0 and attach a tab to it. The banner has to arrive
        // there and nowhere else: one channel per terminal is the whole point.
        TerminalSession tab = new TerminalSession(80, 24, 8, 16, 10_000,
                machine, 1, LISTENER);
        try {
            run("setsid getty -L 0 hvc0 linux &");
            expect(tab, "login:");
            assertTrue("the tab should show its own tty's name",
                    screen(tab).contains("hvc0"));
            assertTrue("the console must not have received the hvc0 banner",
                    !screen(console).contains("(/dev/hvc0)"));
        } finally {
            tab.close();
        }
    }

    @Test
    public void terminalGeometryReachesTheGuest() {
        // A socketpair carries no winsize and delivers no SIGWINCH, so the only
        // way the guest can learn how big a terminal is is the control channel:
        // the emulator turns the message into a virtio config-change interrupt
        // and Linux resizes the tty. hvc1 is this test's alone.
        machine.setWinsize(2, 100, 30);
        run("stty size < /dev/hvc1");
        expect(console, "30 100");
    }

    @Test
    public void detachingATabDoesNotHangUpTheGuest() throws IOException {
        // Attaching takes a dup of the channel, so closing a tab leaves the
        // machine's own end open. Were that not so, the emulator would see EOF
        // on the terminal and stop reading and writing it for good — and the
        // reattached tab below would stay blank forever.
        run("setsid getty -L 0 hvc2 linux &");

        TerminalSession first = new TerminalSession(80, 24, 8, 16, 10_000,
                machine, 3, LISTENER);
        try {
            expect(first, "login:");
        } finally {
            first.close();
        }

        TerminalSession again = new TerminalSession(80, 24, 8, 16, 10_000,
                machine, 3, LISTENER);
        try {
            // Poke it: a getty answers a bare newline by reprinting its banner.
            again.write("\n");
            expect(again, "login:");
            assertTrue("the machine should still be running", VmMachine.isRunning());
        } finally {
            again.close();
        }
    }
}
