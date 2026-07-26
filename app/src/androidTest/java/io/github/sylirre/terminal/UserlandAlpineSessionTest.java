/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static io.github.sylirre.terminal.TestUtil.waitFor;

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

import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * End-to-end Alpine userland smoke: rootfs install from the bundled asset,
 * {@code arm64chroot_main()} emulating the aarch64 guest, and a BusyBox ash
 * login shell asserted through the Ghostty screen. The CI counterpart of
 * {@link UserlandSessionTest}: the Alpine asset is small enough (~3 MB) for
 * the workflow to build on every push, so this is the suite that actually
 * boots the userland in CI (docs/testing.md).
 *
 * Gated to CI-shaped builds so it never competes with the Debian suite for
 * the one shared rootfs dir: skipped when no Alpine asset is bundled, when a
 * Debian asset is bundled too and nothing is installed yet (local runs —
 * the dir is left for {@link UserlandSessionTest}'s Debian install), or when
 * the installed rootfs is not Alpine.
 */
@RunWith(AndroidJUnit4.class)
public class UserlandAlpineSessionTest {

    private static final long TIMEOUT_MS = 30_000;

    private TerminalSession session;

    private final TerminalSession.Listener listener = new TerminalSession.Listener() {
        @Override public void onUpdate(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onExited(TerminalSession s, int code) {}
    };

    @Before
    public void setUp() throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        String alpine = assetById(ctx, "alpine");
        assumeTrue("no Alpine rootfs asset bundled in this build", alpine != null);
        if (!UserlandRootfs.isInstalled(ctx)) {
            assumeTrue("Debian asset bundled too; leaving the rootfs dir to "
                    + "UserlandSessionTest", assetById(ctx, "debian") == null);
            UserlandRootfs.install(ctx, alpine, null);
        }
        assumeTrue("installed rootfs is not Alpine",
                new File(UserlandRootfs.dir(ctx), "etc/alpine-release").exists());
        session = new TerminalSession(80, 24, 8, 16, 10_000,
                UserlandRootfs.command(ctx, "/bin/sh -l"), listener);
        waitForOnScreen("~#"); // ash login prompt: "localhost:~#"
    }

    @After
    public void tearDown() {
        if (session != null) session.close();
    }

    /** The bundled rootfs asset for {@code id}, or null when absent. */
    private static String assetById(Context ctx, String id) {
        for (UserlandDistro d : UserlandDistro.bundled(ctx)) {
            if (id.equals(d.id)) return d.assetName;
        }
        return null;
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
    public void shellRunsInsideAlpineRootfs() {
        // os-release is read with shell builtins; proves the rootfs is the
        // guest's "/" regardless of exec details.
        session.write(". /etc/os-release && echo \"ID=$ID\"\n");
        waitForOnScreen("ID=alpine");
    }

    @Test
    public void guestBinariesRunEmulated() {
        // uname/id exec the real guest aarch64 busybox under the emulator:
        // "aarch64" proves emulation (true even on an x86_64 host) and
        // --fake-id fakes uid 0.
        session.write("echo \"arch=$(uname -m) uid=$(id -u)\"\n");
        waitForOnScreen("arch=aarch64 uid=0");
    }

    @Test
    public void apkIsFunctional() {
        // `apk info` reads the installed-package database without network.
        session.write("apk info > /dev/null 2>&1; echo \"apk=$?\"\n");
        waitForOnScreen("apk=0");
    }
}
