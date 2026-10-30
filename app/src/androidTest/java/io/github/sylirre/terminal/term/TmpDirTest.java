/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The app-owned TMPDIR. Mode 0700 is what chroot-ng requires before it keeps
 * its guest's /dev/shm stand-in there, so every path into {@link TmpDir#ensure}
 * must end at a real directory with exactly that mode.
 */
@RunWith(AndroidJUnit4.class)
public class TmpDirTest {

    private Context ctx;
    private File dir;
    private File outside;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        dir = TmpDir.dir(ctx);
        outside = new File(ctx.getCacheDir(), "tmpdir-target-" + System.nanoTime());
        deleteTree(dir);
    }

    @After
    public void tearDown() {
        deleteTree(outside);
        TmpDir.ensure(ctx); // leave the app's TMPDIR as the app expects it
    }

    @Test
    public void createsPrivateDirectory() throws Exception {
        assertEquals(dir.getAbsolutePath(), TmpDir.ensure(ctx));
        assertPrivateDirectory();
    }

    @Test
    public void tightensLooseModeAndKeepsContents() throws Exception {
        TmpDir.ensure(ctx);
        File kept = new File(dir, "kept");
        assertTrue(kept.createNewFile());
        Os.chmod(dir.getAbsolutePath(), 0771); // what files/ itself carries

        TmpDir.ensure(ctx);
        assertPrivateDirectory();
        assertTrue(kept.isFile());
    }

    @Test
    public void replacesRegularFile() throws Exception {
        try (FileOutputStream out = new FileOutputStream(dir)) {
            out.write('x');
        }
        TmpDir.ensure(ctx);
        assertPrivateDirectory();
    }

    @Test
    public void replacesSymlinkWithoutTouchingTarget() throws Exception {
        assertTrue(outside.mkdirs());
        Os.chmod(outside.getAbsolutePath(), 0771);
        Os.symlink(outside.getAbsolutePath(), dir.getAbsolutePath());

        TmpDir.ensure(ctx);
        assertPrivateDirectory();
        assertEquals(0771, Os.stat(outside.getAbsolutePath()).st_mode & 07777);
    }

    private void assertPrivateDirectory() throws Exception {
        StructStat st = Os.lstat(dir.getAbsolutePath());
        assertTrue("not a directory", OsConstants.S_ISDIR(st.st_mode));
        assertEquals(0700, st.st_mode & 07777);
    }

    private static void deleteTree(File f) {
        int mode;
        try {
            mode = Os.lstat(f.getAbsolutePath()).st_mode;
        } catch (ErrnoException e) {
            return; // nothing there
        }
        if (OsConstants.S_ISDIR(mode)) { // lstat: never follow a link out
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k);
            }
        }
        f.delete();
    }
}
