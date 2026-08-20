/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The font importer's staging discipline. Lives in
 * {@code io.github.sylirre.terminal.ui} to reach the package-private store.
 */
@RunWith(AndroidJUnit4.class)
public class TerminalFontStoreTest {

    /**
     * An import that fails after it has already copied the picked file must not
     * leave that copy in filesDir. Nothing ever sweeps that directory, so a
     * staged copy left behind is a font-sized file stranded there for good.
     */
    @Test
    public void failedImportLeavesNothingStaged() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        File dst = TerminalFontStore.file(ctx, TerminalFontStore.DEFAULT);
        // A custom font is the user's, not the test's.
        assumeFalse("a custom terminal font is installed; leaving it alone",
                dst.exists());
        File tmp = new File(ctx.getFilesDir(), dst.getName() + ".tmp");
        File source = new File(ctx.getCacheDir(), "font-src-" + System.nanoTime());
        File blocker = new File(dst, "occupied");
        try (OutputStream out = new FileOutputStream(source)) {
            out.write("picked file contents".getBytes(StandardCharsets.UTF_8));
        }
        try {
            // Block the destination with a non-empty directory: delete() can't
            // remove it and renameTo() can't replace it, so the import fails at
            // the last step — with its copy already staged.
            assertTrue(blocker.mkdirs());
            try {
                TerminalFontStore.importFrom(ctx, Uri.fromFile(source),
                        TerminalFontStore.DEFAULT);
                fail("the import should not have been able to store the font");
            } catch (IOException expected) {
                // could not publish the staged copy over the blocked destination
            }
            assertFalse("the staged copy was left in filesDir", tmp.exists());
        } finally {
            // noinspection ResultOfMethodCallIgnored
            blocker.delete();
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
            // noinspection ResultOfMethodCallIgnored
            tmp.delete();
            // noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }
}
