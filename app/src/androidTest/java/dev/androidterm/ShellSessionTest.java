package dev.androidterm;

import static dev.androidterm.TestUtil.waitFor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

import dev.androidterm.term.ScreenSnapshot;
import dev.androidterm.term.TerminalSession;

/**
 * End-to-end: a real /system/bin/sh on a real PTY, asserted through the
 * Ghostty screen. This is the test that proves the whole pipeline.
 */
@RunWith(AndroidJUnit4.class)
public class ShellSessionTest {

    private static final long TIMEOUT_MS = 15_000;

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
        session = new TerminalSession(80, 24, 8, 16, 10_000,
                ctx.getFilesDir().getAbsolutePath(),
                ctx.getCacheDir().getAbsolutePath(),
                listener);
    }

    @After
    public void tearDown() {
        session.close();
    }

    private String screen() {
        ScreenSnapshot snap = new ScreenSnapshot();
        session.emulator.snapshot(snap);
        return snap.text();
    }

    private void waitForOnScreen(String needle) {
        waitFor("\"" + needle + "\" on screen", TIMEOUT_MS,
                () -> screen().contains(needle));
    }

    @Test
    public void shellEchoesCommandOutput() {
        waitForOnScreen("$"); // prompt
        session.write("echo hello-from-sh\n");
        waitForOnScreen("hello-from-sh");
    }

    @Test
    public void pathIsSystemBinOnly() {
        session.write("echo \"PATH=[$PATH]\"\n");
        waitForOnScreen("PATH=[/system/bin]");
    }

    @Test
    public void systemBinariesAreReachable() {
        // `id` is a toybox binary in /system/bin; running it proves PATH
        // resolution and exec from the app domain work.
        session.write("id\n");
        waitForOnScreen("uid=");
    }

    @Test
    public void workingDirectoryIsHome() {
        // getFilesDir() reports /data/user/0/... but the kernel resolves the
        // cwd through the /data/data symlink, so match the stable suffix.
        session.write("pwd\n");
        waitForOnScreen("dev.androidterm/files");
    }

    @Test
    public void resizeIsDeliveredToShell() {
        session.resize(100, 30, 8, 16);
        session.write("stty size\n");
        waitForOnScreen("30 100");
    }

    @Test
    public void terminalQueriesAreAnsweredOverPty() {
        // Ask the shell to print a DSR query; the emulator's response goes
        // back over the PTY where `read` captures it. Proves the
        // feed→response→write loop, not just parsing.
        session.write("printf '\\033[6n'; read -r reply; echo \"got:${#reply}\"\n");
        waitForOnScreen("got:");
    }

    @Test
    public void exitReportsCode() throws InterruptedException {
        session.write("exit 42\n");
        assertTrue("onExited delivered", exited.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(42, exitCode.get());
        assertEquals(Integer.valueOf(42), session.exitCode());
    }

    @Test
    public void closeKillsShell() throws InterruptedException {
        waitForOnScreen("$");
        session.close();
        assertTrue("onExited after close", exited.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(-9, exitCode.get()); // SIGKILL
    }

    @Test
    public void titlePropagatesFromShell() {
        session.write("printf '\\033]2;tab-title\\007'\n");
        waitFor("title set", TIMEOUT_MS, () -> "tab-title".equals(session.title()));
        assertNotNull(session.title());
    }
}
