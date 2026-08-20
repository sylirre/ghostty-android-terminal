/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

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
import java.util.zip.GZIPInputStream;

/**
 * Round-trips {@link RootfsBackup}'s tar writer through {@link UserlandRootfs}'s
 * tar reader on a synthetic tree, proving the backup format preserves file
 * bytes, directory structure, symlink targets, and mode bits (including the
 * sticky bit and restrictive dir modes). Lives in {@code io.github.sylirre.terminal.term}
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
        // Sticky bit must survive.
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
        UserlandRootfs.extractTar(new ByteArrayInputStream(tar.toByteArray()), dst,
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

    /**
     * The backup denominator counts an inlined link2symlink's backing bytes
     * exactly once (via the referring symlink), ignoring the {@code .l2s.*}
     * siblings — so the bar still reaches 100% and doesn't double-count shared
     * content. The setUp tree is 40 bytes; the chain's backing file adds 5.
     */
    @Test
    public void measureCountsInlinedBacking() throws Exception {
        buildL2sChain();
        assertEquals(40 + 5, RootfsBackup.measure(src));
    }

    /**
     * arm64chroot's {@code --link2symlink} hard link — a symlink whose target is
     * the <em>relative</em> bare basename of a same-directory {@code .l2s.<ino>}
     * backing file, next to an empty {@code .l2s.<ino>.<count>} marker — inlines
     * exactly like the PRoot chain: the archive carries the backing content as a
     * plain regular file under the user-facing path, with the backing file's
     * mode, and none of the {@code .l2s.*} internals (backing or marker) survive.
     * Exercises the relative-target branch of {@link RootfsBackup#l2sBacking}.
     */
    @Test
    public void inlinesArm64chrootHardLink() throws Exception {
        buildArm64chrootLink();

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        RootfsBackup.writeArchive(src, tar, null, null);
        assertTrue(dst.mkdirs());
        UserlandRootfs.extractTar(new ByteArrayInputStream(tar.toByteArray()), dst,
                null, null);

        File tool = new File(dst, "usr/bin/tool");
        StructStat st = Os.lstat(tool.getAbsolutePath());
        assertTrue("expected a regular file, not a symlink",
                OsConstants.S_ISREG(st.st_mode));
        assertEquals(0755, st.st_mode & 07777);
        assertArrayEquals("EXEC\n".getBytes(StandardCharsets.UTF_8), readFile(tool));

        // The .l2s backing + marker were dropped — usr/bin holds only the file.
        File[] kids = new File(dst, "usr/bin").listFiles();
        assertEquals(1, kids.length);
        assertEquals("tool", kids[0].getName());
    }

    /** The inlined backing is counted once via its symlink; the {@code .l2s.*}
     *  backing and marker siblings don't double-count (setUp tree 40 + 5). */
    @Test
    public void measureCountsInlinedArm64chrootBacking() throws Exception {
        buildArm64chrootLink();
        assertEquals(40 + 5, RootfsBackup.measure(src));
    }

    /**
     * A hostile archive that plants a symlink escaping the extraction root and
     * then writes a file "through" it must not land outside the root. The guard
     * resolves each entry's parent and skips the write; the escape directory
     * stays empty.
     */
    @Test
    public void restoreRejectsTraversalEscape() throws Exception {
        File escape = new File(src.getParentFile(), "bk-escape-" + System.nanoTime());
        assertTrue(escape.mkdirs());
        try {
            ByteArrayOutputStream tar = new ByteArrayOutputStream();
            // evil -> <escape>  (a symlink out of the extraction root)
            RootfsBackup.writeHeader(tar, "evil", 0777, 0, 0, '2',
                    escape.getAbsolutePath());
            // evil/x  (a file the reader would write *through* the symlink)
            byte[] payload = "PWNED\n".getBytes(StandardCharsets.UTF_8);
            RootfsBackup.writeHeader(tar, "evil/x", 0644, 0, payload.length, '0', "");
            tar.write(payload);
            tar.write(new byte[512 - payload.length]); // pad the data block
            tar.write(new byte[512]);                  // end-of-archive marker
            tar.write(new byte[512]);

            assertTrue(dst.mkdirs());
            UserlandRootfs.extractTar(new ByteArrayInputStream(tar.toByteArray()), dst,
                    null, null);

            // The symlink itself is fine (it lives inside the root)...
            assertSymlink(new File(dst, "evil"), escape.getAbsolutePath());
            // ...but nothing was written through it into the escape directory.
            assertFalse("write escaped the extraction root",
                    new File(escape, "x").exists());
        } finally {
            deleteRecursively(escape);
        }
    }

    /**
     * A custom archive that nests the whole rootfs under a wrapper directory is
     * detected and stripped so its contents land at the root — letting restore
     * accept tarballs that weren't produced by this app's backup.
     */
    @Test
    public void detectsAndStripsWrapperDirectory() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        tarDir(tar, "mydistro/");
        tarDir(tar, "mydistro/etc/");
        tarFile(tar, "mydistro/etc/hostname", "host\n");
        tarDir(tar, "mydistro/usr/");
        tarDir(tar, "mydistro/usr/bin/");
        tarDir(tar, "mydistro/bin/");
        tar.write(new byte[512]); // end-of-archive marker
        tar.write(new byte[512]);
        byte[] bytes = tar.toByteArray();

        // The probe pass sees the wrapper at depth 0 and picks strip 1.
        assertEquals(1, UserlandRootfs.probeStripCount(new ByteArrayInputStream(bytes)));

        // Extracting with that strip lands the rootfs dirs at the root, with no
        // wrapper directory left behind.
        assertTrue(dst.mkdirs());
        UserlandRootfs.extractTar(new ByteArrayInputStream(bytes), dst, null, null,
                true, 1);
        assertArrayEquals("host\n".getBytes(StandardCharsets.UTF_8),
                readFile(new File(dst, "etc/hostname")));
        assertTrue(new File(dst, "bin").isDirectory());
        assertTrue(new File(dst, "usr/bin").isDirectory());
        assertFalse(new File(dst, "mydistro").exists());
    }

    /**
     * The strip score is highest at the depth where rootfs dirs appear: 0 for an
     * already-rooted archive, the wrapper depth for a nested one, and 0 (verbatim)
     * when nothing looks like a rootfs.
     */
    @Test
    public void detectStripCountScoresRootfsDepth() {
        assertEquals(0, UserlandRootfs.detectStripCount(Arrays.asList(
                "etc/", "etc/hostname", "usr/", "bin/", "var/log/")));
        assertEquals(1, UserlandRootfs.detectStripCount(Arrays.asList(
                "x/etc/", "x/usr/", "x/bin/", "x/var/")));
        assertEquals(0, UserlandRootfs.detectStripCount(Arrays.asList(
                "notes.txt", "photos/a.jpg")));
    }

    /**
     * Restore autodetects compression from the leading magic bytes: the same tar
     * content is read whether it is plain, gzip-wrapped, or xz-wrapped (the codec
     * the bundled rootfs ships in), so a foreign {@code .tar}/{@code .tar.gz}/
     * {@code .tar.xz} restores like one of our own backups. Drives each branch of
     * {@code tarStream} and confirms the member survives the round trip.
     */
    @Test
    public void tarStreamAutodetectsCompression() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        tarFile(raw, "etc/hostname", "host\n");
        raw.write(new byte[512]); // end-of-archive marker
        raw.write(new byte[512]);
        byte[] plain = raw.toByteArray();

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz =
                new java.util.zip.GZIPOutputStream(gzipped)) {
            gz.write(plain);
        }

        ByteArrayOutputStream xzipped = new ByteArrayOutputStream();
        try (org.tukaani.xz.XZOutputStream xz = new org.tukaani.xz.XZOutputStream(
                xzipped, new org.tukaani.xz.LZMA2Options())) {
            xz.write(plain);
        }

        // Plain bytes start with the tar name field, not a compression magic.
        assertEquals("host\n", firstMemberContent(plain));
        // Gzip bytes start with 0x1f 0x8b and are transparently inflated.
        assertEquals("host\n", firstMemberContent(gzipped.toByteArray()));
        // xz bytes start with FD 37 7A 58 5A 00 and are transparently decoded.
        assertEquals("host\n", firstMemberContent(xzipped.toByteArray()));
    }

    /**
     * Feeds {@code archive} through {@code RootfsBackup.tarStream} (autodetecting
     * gzip vs. plain) into the tar reader and returns the first regular file's
     * contents — a tiny stand-in for what {@code restore} extracts.
     */
    private String firstMemberContent(byte[] archive) throws Exception {
        try (java.io.BufferedInputStream buf = new java.io.BufferedInputStream(
                new ByteArrayInputStream(archive));
                InputStream tar = RootfsBackup.tarStream(buf)) {
            assertTrue(dst.mkdirs());
            UserlandRootfs.extractTar(tar, dst, null, null);
            return new String(readFile(new File(dst, "etc/hostname")),
                    StandardCharsets.UTF_8);
        } finally {
            deleteRecursively(dst);
        }
    }

    // --- helpers ---

    /**
     * Reproduces a link2symlink emulated hard link in {@code src}:
     * {@code usr/bin/tool} is a symlink to a {@code .l2s.*} intermediate,
     * which symlinks to the regular backing file holding the content (mode 0755,
     * 5 bytes). All targets are absolute, exactly as the extension writes them.
     */
    private void buildL2sChain() throws Exception {
        mkdir(new File(src, "usr"), 0755);
        mkdir(new File(src, "usr/bin"), 0755);
        File content = new File(src, "usr/bin/.l2s.tool0001.0002");
        writeFile(content, "EXEC\n", 0755);
        File intermediate = new File(src, "usr/bin/.l2s.tool0001");
        Os.symlink(content.getAbsolutePath(), intermediate.getAbsolutePath());
        Os.symlink(intermediate.getAbsolutePath(),
                new File(src, "usr/bin/tool").getAbsolutePath());
    }

    /**
     * Reproduces an arm64chroot {@code --link2symlink} hard link in {@code src}:
     * {@code usr/bin/tool} is a symlink whose target is the <em>relative</em>
     * bare basename {@code .l2s.<ino>} of a same-directory regular backing file
     * (mode 0755, 5 bytes), next to an empty {@code .l2s.<ino>.<count>} marker —
     * exactly as {@code native/arm64chroot/src/sys_file.c} writes them.
     */
    private void buildArm64chrootLink() throws Exception {
        mkdir(new File(src, "usr"), 0755);
        mkdir(new File(src, "usr/bin"), 0755);
        String data = ".l2s.4831521";
        writeFile(new File(src, "usr/bin/" + data), "EXEC\n", 0755);
        writeFile(new File(src, "usr/bin/.l2s.4831521.0001"), "", 0600); // link-count marker
        // Relative, same-directory target: the shape l2sBacking must resolve
        // against the symlink's parent (not an absolute host path).
        Os.symlink(data, new File(src, "usr/bin/tool").getAbsolutePath());
    }

    /**
     * A PAX extended header's record lengths count bytes, not characters. An
     * archive whose overriding path holds non-ASCII — the very thing PAX
     * headers exist to carry — must still extract under that name, and the
     * records after it must still parse.
     */
    @Test
    public void restoreHonorsPaxPathWithNonAscii() throws Exception {
        String name = "usr/share/dokumentaci\u00f3n/na\u00efve.txt";
        byte[] pax = paxBlock("path=" + name, "comment=trailing-record");

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        RootfsBackup.writeHeader(tar, "PaxHeaders/0", 0644, 0, pax.length, 'x', "");
        tar.write(pax);
        tar.write(new byte[(512 - pax.length % 512) % 512]);
        // The ustar header the PAX block overrides carries a placeholder name.
        tarFile(tar, "placeholder", "HELLO\n");
        tar.write(new byte[512]);
        tar.write(new byte[512]);

        assertTrue(dst.mkdirs());
        UserlandRootfs.extractTar(new ByteArrayInputStream(tar.toByteArray()), dst,
                null, null);

        assertArrayEquals("HELLO\n".getBytes(StandardCharsets.UTF_8),
                readFile(new File(dst, name)));
        assertFalse("the PAX path override was ignored",
                new File(dst, "placeholder").exists());
    }

    /**
     * Assembles PAX records as {@code "<len> <key>=<value>\n"}, where
     * {@code len} counts the whole record including its own digits.
     */
    private static byte[] paxBlock(String... records) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String record : records) {
            byte[] body = (" " + record + "\n").getBytes(StandardCharsets.UTF_8);
            int len = body.length + 1;
            while (Integer.toString(len).length() + body.length != len) {
                len = Integer.toString(len).length() + body.length;
            }
            out.write(Integer.toString(len).getBytes(StandardCharsets.US_ASCII));
            out.write(body);
        }
        return out.toByteArray();
    }

    /**
     * The public entry point end to end, over the stream stack it actually
     * builds. Two things it must get right and a test can see: the caller's
     * stream is left open (the caller owns it), and the gzip layer is carried
     * all the way to a complete stream — decompressing to EOF checks the
     * trailer's CRC and length, which only a finished stream has.
     */
    @Test
    public void backupLeavesTheCallerStreamOpenAndWritesAWholeGzip() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        // This has to write a rootfs to back up, so never run where one is
        // installed — that tree is the user's data, not the test's.
        assumeFalse("a userland rootfs is installed; leaving it alone",
                UserlandRootfs.isInstalled(ctx));
        File rootfs = UserlandRootfs.dir(ctx);
        try {
            mkdir(rootfs, 0755);
            mkdir(new File(rootfs, "etc"), 0755);
            writeFile(new File(rootfs, "etc/hostname"), "guest\n", 0644);

            CloseRecordingStream sink = new CloseRecordingStream();
            RootfsBackup.backup(ctx, sink, null, null);
            assertFalse("backup closed the caller's stream", sink.closed);

            byte[] archive = sink.toByteArray();
            assertTrue("not a gzip stream", archive.length > 2
                    && (archive[0] & 0xFF) == 0x1F && (archive[1] & 0xFF) == 0x8B);

            // Drain the whole thing rather than stopping at the tar's
            // end-of-archive marker: the trailer sits past it, so only a full
            // read reaches the CRC check.
            byte[] tar;
            try (InputStream gz = new GZIPInputStream(
                    new ByteArrayInputStream(archive))) {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = gz.read(buf)) > 0) raw.write(buf, 0, n);
                tar = raw.toByteArray();
            }

            assertTrue(dst.mkdirs());
            UserlandRootfs.extractTar(new ByteArrayInputStream(tar), dst, null, null);
            assertArrayEquals("guest\n".getBytes(StandardCharsets.UTF_8),
                    readFile(new File(dst, "etc/hostname")));
        } finally {
            deleteRecursively(rootfs);
        }
    }

    /** A sink that records whether anything closed it. */
    private static final class CloseRecordingStream extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** Writes a directory entry (name should end with '/') into a raw tar. */
    private static void tarDir(OutputStream out, String name) throws IOException {
        RootfsBackup.writeHeader(out, name, 0755, 0, 0, '5', "");
    }

    /** Writes a regular-file entry plus its 512-block padding into a raw tar. */
    private static void tarFile(OutputStream out, String name, String content)
            throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        RootfsBackup.writeHeader(out, name, 0644, 0, data.length, '0', "");
        out.write(data);
        int pad = (512 - data.length % 512) % 512;
        out.write(new byte[pad]);
    }

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
