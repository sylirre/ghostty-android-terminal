package sh.easycli.proot;

import static sh.easycli.proot.TestUtil.waitFor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import sh.easycli.proot.term.DebianRootfs;
import sh.easycli.proot.term.ScreenSnapshot;
import sh.easycli.proot.term.TerminalSession;

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
        // of the run, no-ops afterwards (the rootfs directory already exists).
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

    /**
     * A login shell missing from an installed rootfs (e.g. the user removed
     * it from the Android shell) must be detected — {@link DebianRootfs#isUsable}
     * false, {@link DebianRootfs#command} refusing to spawn a doomed PRoot —
     * but the rootfs must NOT be wiped and rebuilt behind the user's back:
     * {@link DebianRootfs#install} leaves an installed rootfs untouched.
     */
    @Test
    public void missingShellIsDetectedButRootfsKept() throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        // setUp launched a session from this rootfs; stop it before tampering.
        session.close();
        assertTrue("usable right after install", DebianRootfs.isUsable(ctx));

        // Hide bash by renaming it aside (usrmerge: the real file is under
        // usr/bin; bin/bash resolves to it). Renaming, not deleting, lets us
        // restore the shared rootfs for the other tests without a reinstall.
        File root = DebianRootfs.dir(ctx);
        File bash = new File(root, "usr/bin/bash");
        if (!bash.exists()) bash = new File(root, "bin/bash");
        File hidden = new File(bash.getParentFile(), "bash.hidden");
        assertTrue("renamed bash aside", bash.renameTo(hidden));
        try {
            assertFalse("missing /bin/bash is detected", DebianRootfs.isUsable(ctx));
            try {
                DebianRootfs.command(ctx);
                fail("command() must reject an incomplete rootfs");
            } catch (IOException expected) {
                // expected: spawning here would only die instantly.
            }

            // install() must short-circuit on the existing rootfs dir, not rebuild.
            DebianRootfs.install(ctx, null);
            assertTrue("install() did not wipe the rootfs", hidden.exists());
            assertFalse("install() did not rebuild", DebianRootfs.isUsable(ctx));
        } finally {
            assertTrue("restored bash", hidden.renameTo(bash));
        }
        assertTrue("usable again after restore", DebianRootfs.isUsable(ctx));
    }
}
