package dev.androidterm;

import static dev.androidterm.TestUtil.waitFor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.androidterm.term.DebianRootfs;
import dev.androidterm.term.ScreenSnapshot;
import dev.androidterm.term.TerminalSession;

/**
 * End-to-end Debian-under-PRoot: rootfs install, proot_main() in the PTY
 * child, the loader exec'd from nativeLibraryDir, and a real bash login
 * shell asserted through the Ghostty screen.
 *
 * Skipped (assumption failure) when the build doesn't bundle a rootfs
 * asset for this ABI — the tarballs live in DebianRootfs/ at the repo root
 * and are never committed, so CI builds skip this suite.
 */
@RunWith(AndroidJUnit4.class)
public class DebianSessionTest {

    private static final long TIMEOUT_MS = 30_000;

    private TerminalSession session;
    private final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
    private final CountDownLatch exited = new CountDownLatch(1);

    private final TerminalSession.Listener listener = new TerminalSession.Listener() {
        @Override public void onUpdate(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onExited(TerminalSession s, int code) {
            exitCode.set(code);
            exited.countDown();
        }
    };

    @Before
    public void setUp() throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        assumeTrue("no Debian rootfs asset bundled for this ABI",
                DebianRootfs.assetAvailable(ctx));
        // One-time per device state: extracts the rootfs on the first test
        // of the run, no-ops afterwards (marker file).
        DebianRootfs.install(ctx, null);
        session = new TerminalSession(80, 24, 8, 16, 10_000,
                DebianRootfs.command(ctx), listener);
        waitForOnScreen("~#"); // root login prompt: "root@host:~#"
    }

    @After
    public void tearDown() {
        if (session != null) session.close();
    }

    private String screen() {
        ScreenSnapshot snap = new ScreenSnapshot();
        session.emulator.snapshot(snap);
        return snap.text();
    }

    private void waitForOnScreen(String needle) {
        waitFor("\"" + needle + "\" on screen", TIMEOUT_MS,
                () -> screen().contains(needle), this::screen);
    }

    @Test
    public void bashRunsInsideDebianRootfs() {
        // os-release is read with shell builtins; proves the rootfs is the
        // guest's "/" regardless of exec details.
        session.write(". /etc/os-release && echo \"ID=$ID\"\n");
        waitForOnScreen("ID=debian");
    }

    @Test
    public void guestBinariesExecThroughLoader() {
        // $(id -u) execs a real guest ELF, i.e. PRoot's loader was execve'd
        // from nativeLibraryDir and mapped /usr/bin/id. -0 fakes uid 0.
        session.write("echo \"uid=$(id -u)\"\n");
        waitForOnScreen("uid=0");
    }

    @Test
    public void hardLinksWorkViaLink2symlink() {
        // dpkg/apt rely on ln; apps can't link(2), so --link2symlink must
        // translate it.
        session.write("cd && touch a.txt && ln a.txt b.txt && echo \"ln=ok-$?\"\n");
        waitForOnScreen("ln=ok-0");
        session.write("rm -f a.txt b.txt\n");
    }

    @Test
    public void aptIsFunctional() {
        // `apt list` exercises dpkg's database without the network.
        session.write("apt list --installed 2>/dev/null | head -n 3; echo \"apt=$?\"\n");
        waitForOnScreen("apt=0");
    }

    @Test
    public void exitPropagatesThroughProot() throws InterruptedException {
        session.write("exit 7\n");
        assertTrue("onExited delivered", exited.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(7, exitCode.get());
    }
}
