/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.system.Os;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Login-shell detection against synthetic rootfs layouts. The regression that
 * motivates this: shell paths must resolve symlinks <em>inside the guest</em> —
 * Alpine's {@code /bin/sh} is an <b>absolute</b> symlink to
 * {@code /bin/busybox}, which a host-side {@code File.exists()} chases against
 * the host {@code /} and misses, making a perfectly good rootfs look unusable.
 */
@RunWith(AndroidJUnit4.class)
public class UserlandRootfsTest {

    private File root;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        root = new File(ctx.getCacheDir(), "fake-rootfs-" + System.nanoTime());
        assertTrue(root.mkdirs());
    }

    @After
    public void tearDown() {
        deleteTree(root);
    }

    @Test
    public void alpineAbsoluteBusyboxLinksResolve() throws Exception {
        // Alpine minirootfs layout: every shell is an absolute link to busybox.
        File bin = new File(root, "bin");
        assertTrue(bin.mkdirs());
        writeFile(new File(bin, "busybox"), "stub");
        Os.symlink("/bin/busybox", new File(bin, "sh").getAbsolutePath());
        Os.symlink("/bin/busybox", new File(bin, "ash").getAbsolutePath());
        File etc = new File(root, "etc");
        assertTrue(etc.mkdirs());
        writeFile(new File(etc, "passwd"), "root:x:0:0:root:/root:/bin/ash\n");

        assertEquals("/bin/ash -l", UserlandRootfs.deriveLoginShell(root, "0:0"));
    }

    @Test
    public void usrmergeRelativeLinksResolve() throws Exception {
        // Debian usrmerge layout: /bin is a relative symlink to usr/bin.
        File usrBin = new File(root, "usr/bin");
        assertTrue(usrBin.mkdirs());
        writeFile(new File(usrBin, "bash"), "stub");
        Os.symlink("usr/bin", new File(root, "bin").getAbsolutePath());

        assertEquals("/bin/bash -l", UserlandRootfs.deriveLoginShell(root, "0:0"));
    }

    @Test
    public void danglingLinksAndEscapesDoNotResolve() throws Exception {
        File bin = new File(root, "bin");
        assertTrue(bin.mkdirs());
        // Dangling absolute target inside the guest.
        Os.symlink("/bin/busybox", new File(bin, "sh").getAbsolutePath());
        // A target that climbs out of the rootfs must not count either, even
        // if the host path it would land on exists.
        Os.symlink("../../../../system/bin/sh",
                new File(bin, "bash").getAbsolutePath());

        assertNull(UserlandRootfs.deriveLoginShell(root, "0:0"));
    }

    private static void writeFile(File file, String content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
