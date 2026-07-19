/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static io.github.sylirre.terminal.TestUtil.waitFor;

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

import io.github.sylirre.terminal.term.UserlandRootfs;
import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.TerminalSession;

/**
 * End-to-end userland: rootfs install, arm64chroot_main() in
 * the PTY child emulating the aarch64 guest, and a real bash login shell
 * asserted through the Ghostty screen.
 *
 * Skipped (assumption failure) when the build doesn't bundle the rootfs
 * asset — the tarballs live in UserlandRootfs/ at the repo root and are
 * never committed, so CI builds skip this suite.
 */
@RunWith(AndroidJUnit4.class)
public class UserlandSessionTest {

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
        assumeTrue("no userland rootfs asset bundled in this build",
                UserlandRootfs.assetAvailable(ctx));
        // One-time per device state: extracts the rootfs on the first test
        // of the run, no-ops afterwards (the rootfs directory already exists).
        UserlandRootfs.install(ctx, null);
        session = new TerminalSession(80, 24, 8, 16, 10_000,
                UserlandRootfs.command(ctx, "/bin/bash -l"), listener);
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
    public void bashRunsInsideUserlandRootfs() {
        // os-release is read with shell builtins; proves the rootfs is the
        // guest's "/" regardless of exec details.
        session.write(". /etc/os-release && echo \"ID=$ID\"\n");
        waitForOnScreen("ID=debian");
    }

    @Test
    public void guestBinariesRunEmulated() {
        // uname -m / id -u exec real guest aarch64 ELFs under the emulator:
        // "aarch64" proves emulation (true even on an x86_64 host) and
        // --fake-id fakes uid 0.
        session.write("echo \"arch=$(uname -m) uid=$(id -u)\"\n");
        waitForOnScreen("arch=aarch64 uid=0");
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
    public void exitPropagatesThroughEmulator() throws InterruptedException {
        session.write("exit 7\n");
        assertTrue("onExited delivered", exited.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(7, exitCode.get());
    }

    /**
     * A login shell missing from an installed rootfs (e.g. the user removed
     * it from the Android shell) must be detected — {@link UserlandRootfs#isUsable}
     * false, {@link UserlandRootfs#command} refusing to spawn a doomed session —
     * but the rootfs must NOT be wiped and rebuilt behind the user's back:
     * {@link UserlandRootfs#install} leaves an installed rootfs untouched.
     */
    @Test
    public void missingShellIsDetectedButRootfsKept() throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        // setUp launched a session from this rootfs; stop it before tampering.
        session.close();
        assertTrue("usable right after install", UserlandRootfs.isUsable(ctx));

        // Hide both bash and sh by renaming them aside (usrmerge: the real
        // files live under usr/bin; bin/... resolves to them). sh is now a valid
        // fallback shell, so a truly doomed rootfs must lack both. Renaming, not
        // deleting, lets us restore the shared rootfs for the other tests
        // without a reinstall.
        File root = UserlandRootfs.dir(ctx);
        File bash = new File(root, "usr/bin/bash");
        if (!bash.exists()) bash = new File(root, "bin/bash");
        File bashHidden = new File(bash.getParentFile(), "bash.hidden");
        File sh = new File(root, "usr/bin/sh");
        if (!sh.exists()) sh = new File(root, "bin/sh");
        File shHidden = new File(sh.getParentFile(), "sh.hidden");
        assertTrue("renamed bash aside", bash.renameTo(bashHidden));
        assertTrue("renamed sh aside", sh.renameTo(shHidden));
        try {
            assertFalse("missing shell is detected", UserlandRootfs.isUsable(ctx));
            try {
                UserlandRootfs.command(ctx, "/bin/bash -l");
                fail("command() must reject an incomplete rootfs");
            } catch (IOException expected) {
                // expected: spawning here would only die instantly.
            }

            // install() must short-circuit on the existing rootfs dir, not rebuild.
            UserlandRootfs.install(ctx, null);
            assertTrue("install() did not wipe the rootfs", bashHidden.exists());
            assertFalse("install() did not rebuild", UserlandRootfs.isUsable(ctx));
        } finally {
            assertTrue("restored bash", bashHidden.renameTo(bash));
            assertTrue("restored sh", shHidden.renameTo(sh));
        }
        assertTrue("usable again after restore", UserlandRootfs.isUsable(ctx));
    }
}
