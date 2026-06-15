package sh.easycli.proot.term;

import static org.junit.Assert.assertArrayEquals;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Round-trips {@link RootfsBackup}'s tar writer through {@link DebianRootfs}'s
 * tar reader on a synthetic tree, proving the backup format preserves file
 * bytes, directory structure, symlink targets, and mode bits (including the
 * sticky bit and restrictive dir modes). Lives in {@code sh.easycli.proot.term}
 * to reach the package-private {@code writeArchive}/{@code extractTar}, and
 * works in a temp directory so it never touches a real installed rootfs.
 */
@RunWith(AndroidJUnit4.class)
public class RootfsBackupTest {

    // A name and a link target both over the 100-byte ustar field, to exercise
    // the GNU L/K long-name records.
    private static final String LONG_NAME = repeat("long-file-name-segment-", 6) + ".txt";
    private static final String LONG_TARGET = "/" + repeat("very/deep/target/", 8) + "leaf";

    private File src;
    private File dst;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        File base = ctx.getCacheDir();
        src = new File(base, "bk-src-" + System.nanoTime());
        dst = new File(base, "bk-dst-" + System.nanoTime());
        assertTrue(src.mkdirs());

        writeFile(new File(src, "hello.txt"), "hi\n", 0644);
        mkdir(new File(src, "bin"), 0755);
        writeFile(new File(src, "bin/run.sh"), "#!/bin/sh\necho ok\n", 0755);
        mkdir(new File(src, "etc"), 0755);
        writeFile(new File(src, "etc/secret"), "token=42\n", 0600);
        // A read-only directory whose child must still be restorable: the reader
        // applies dir modes only after extraction, so this proves that ordering.
        // (Build the child first, then tighten the dir — 0500 has no write bit.)
        mkdir(new File(src, "locked"), 0755);
        writeFile(new File(src, "locked/inside"), "kept\n", 0644);
        Os.chmod(new File(src, "locked").getAbsolutePath(), 0500);
        // Sticky bit must survive (PRoot's /tmp relies on it).
        mkdir(new File(src, "tmp"), 01777);
        Os.symlink("hello.txt", new File(src, "rel").getAbsolutePath());
        Os.symlink("/etc/secret", new File(src, "abs").getAbsolutePath());
        Os.symlink(LONG_TARGET, new File(src, "longlink").getAbsolutePath());
        writeFile(new File(src, LONG_NAME), "long\n", 0644);
    }

    @After
    public void tearDown() {
        deleteRecursively(src);
        deleteRecursively(dst);
    }

    @Test
    public void roundTripPreservesTree() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        RootfsBackup.writeArchive(src, tar, null, null);

        assertTrue(dst.mkdirs());
        DebianRootfs.extractTar(new ByteArrayInputStream(tar.toByteArray()), dst,
                null, null);

        assertArrayEquals("hi\n".getBytes(StandardCharsets.UTF_8),
                readFile(new File(dst, "hello.txt")));
        assertEquals(0644, mode(new File(dst, "hello.txt")));
        assertEquals(0755, mode(new File(dst, "bin")));
        assertEquals(0755, mode(new File(dst, "bin/run.sh")));
        assertEquals(0600, mode(new File(dst, "etc/secret")));
        assertArrayEquals("token=42\n".getBytes(StandardCharsets.UTF_8),
                readFile(new File(dst, "etc/secret")));

        // The restrictive dir kept its mode, yet its child came through intact.
        assertEquals(0500, mode(new File(dst, "locked")));
        assertArrayEquals("kept\n".getBytes(StandardCharsets.UTF_8),
                readFile(new File(dst, "locked/inside")));

        assertEquals(01777, mode(new File(dst, "tmp")));

        assertSymlink(new File(dst, "rel"), "hello.txt");
        assertSymlink(new File(dst, "abs"), "/etc/secret");
        assertSymlink(new File(dst, "longlink"), LONG_TARGET);

        assertArrayEquals("long\n".getBytes(StandardCharsets.UTF_8),
                readFile(new File(dst, LONG_NAME)));
    }

    /**
     * The backup progress denominator counts only regular-file payload bytes —
     * not directories (whose on-disk st_size is filesystem noise) nor symlink
     * targets — so the bar reaches exactly 100% when the writer, which emits the
     * same bytes, finishes. The tree's files: hello.txt(3) + bin/run.sh(18) +
     * etc/secret(9) + locked/inside(5) + LONG_NAME(5) = 40.
     */
    @Test
    public void measureCountsOnlyRegularFilePayload() {
        assertEquals(40, RootfsBackup.measure(src));
    }

    // --- helpers ---

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private static void mkdir(File dir, int mode) throws ErrnoException {
        assertTrue(dir.mkdirs());
        Os.chmod(dir.getAbsolutePath(), mode);
    }

    private static void writeFile(File f, String content, int mode)
            throws IOException, ErrnoException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        Os.chmod(f.getAbsolutePath(), mode);
    }

    private static byte[] readFile(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static int mode(File f) throws ErrnoException {
        return Os.lstat(f.getAbsolutePath()).st_mode & 07777;
    }

    private static void assertSymlink(File link, String expectedTarget)
            throws ErrnoException {
        StructStat st = Os.lstat(link.getAbsolutePath());
        assertTrue("expected a symlink at " + link, OsConstants.S_ISLNK(st.st_mode));
        assertEquals(expectedTarget, Os.readlink(link.getAbsolutePath()));
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        StructStat st;
        try {
            st = Os.lstat(file.getAbsolutePath());
        } catch (ErrnoException e) {
            return; // doesn't exist
        }
        if (OsConstants.S_ISDIR(st.st_mode)) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        // noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
