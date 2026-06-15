package sh.easycli.proot.term;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Backs up and restores the Debian rootfs ({@link DebianRootfs#dir}) to and
 * from a single gzip-compressed tar file.
 *
 * The archive is a GNU-tar dialect — regular files, directories and symlinks,
 * each carrying its mode bits (including sticky/setuid/setgid), with GNU
 * {@code L}/{@code K} long-name records for paths over 100 bytes. That is
 * exactly the subset {@link DebianRootfs#extractTar} already reads, so
 * {@link #restore} reuses that proven reader instead of a second parser; it
 * also means the file opens with a desktop {@code tar xzf}. The live tree never
 * contains hard links (the installer copies them) or device nodes (PRoot binds
 * the host's at runtime), so neither is emitted.
 *
 * Both directions stream in constant memory and serialize against
 * {@link DebianRootfs#install}/{@link DebianRootfs#replaceFromTar} by locking
 * {@code DebianRootfs.class}. A backup is a best-effort snapshot: it does not
 * freeze a rootfs a live session may be writing, but a file that changes
 * underneath is still emitted at its declared size (zero-padded if it shrank),
 * so the archive stays structurally valid.
 */
public final class RootfsBackup {

    private static final int BLOCK = 512;
    private static final int NAME_MAX = 100;            // ustar name/linkname width
    private static final String LONG_NAME = "././@LongLink"; // GNU sentinel name
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] ZERO = new byte[BLOCK];

    private RootfsBackup() {}

    /**
     * Streams the installed rootfs to {@code out} as a gzip-compressed tar.
     * Does not close {@code out} (the caller owns it). Blocking — call off the
     * main thread; flip {@code cancelled} to abort with {@link InterruptedIOException}.
     */
    public static void backup(Context ctx, OutputStream out,
            DebianRootfs.ProgressListener progress, AtomicBoolean cancelled)
            throws IOException {
        File root = DebianRootfs.dir(ctx);
        if (!root.isDirectory()) throw new IOException("Debian rootfs not installed");
        synchronized (DebianRootfs.class) {
            GZIPOutputStream gz = new GZIPOutputStream(
                    new BufferedOutputStream(out, 1 << 16));
            writeArchive(root, gz, progress, cancelled);
            gz.finish(); // write the gzip trailer without closing the caller's stream
            gz.flush();
        }
    }

    /**
     * Replaces the installed rootfs with the contents of gzip-tar {@code in}.
     * Does not close {@code in} (the caller owns it). The swap is atomic and
     * non-destructive on failure (see {@link DebianRootfs#replaceFromTar}).
     * Blocking — call off the main thread; flip {@code cancelled} to abort.
     */
    public static void restore(Context ctx, InputStream in,
            DebianRootfs.ProgressListener progress, AtomicBoolean cancelled)
            throws IOException {
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(in, 1 << 16));
        DebianRootfs.replaceFromTar(ctx, gz, progress, cancelled);
    }

    // --- tar writer ---

    /**
     * Writes the children of {@code root} (relative paths, no leading
     * directory) to {@code out} as a tar stream terminated by the two zero
     * blocks tar uses as its end-of-archive marker. Package-private so a test
     * can round-trip it against {@link DebianRootfs#extractTar} on a temp tree.
     */
    static void writeArchive(File root, OutputStream out,
            DebianRootfs.ProgressListener progress, AtomicBoolean cancelled)
            throws IOException {
        byte[] buf = new byte[1 << 16];
        long[] counter = new long[2]; // [0] = bytes written, [1] = last reported
        archive(root, "", out, buf, counter, progress, cancelled);
        out.write(ZERO);
        out.write(ZERO);
    }

    /** Depth-first, parents before children, names sorted for reproducibility. */
    private static void archive(File dir, String prefix, OutputStream out,
            byte[] buf, long[] counter, DebianRootfs.ProgressListener progress,
            AtomicBoolean cancelled) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        for (File child : children) {
            if (cancelled != null && cancelled.get()) {
                throw new InterruptedIOException("backup cancelled");
            }
            String name = prefix + child.getName();
            StructStat st;
            try {
                st = Os.lstat(child.getAbsolutePath());
            } catch (ErrnoException e) {
                continue; // vanished mid-walk; skip it
            }
            int mode = st.st_mode & 07777;
            if (OsConstants.S_ISLNK(st.st_mode)) {
                String target;
                try {
                    target = Os.readlink(child.getAbsolutePath());
                } catch (ErrnoException e) {
                    continue;
                }
                writeHeader(out, name, mode, st.st_mtime, 0, '2', target);
            } else if (OsConstants.S_ISDIR(st.st_mode)) {
                writeHeader(out, name + "/", mode, st.st_mtime, 0, '5', "");
                archive(child, name + "/", out, buf, counter, progress, cancelled);
            } else if (OsConstants.S_ISREG(st.st_mode)) {
                long size = st.st_size;
                writeHeader(out, name, mode, st.st_mtime, size, '0', "");
                writeFileData(out, child, size, buf, cancelled);
                counter[0] += size;
                if (progress != null && counter[0] - counter[1] >= (8 << 20)) {
                    counter[1] = counter[0];
                    progress.onProgress(counter[0]);
                }
            }
            // Anything else (socket/fifo/device) has no place in a rootfs tree.
        }
    }

    /**
     * Copies exactly {@code size} bytes (the size declared in the header) of
     * {@code file} into the data region, then pads to the next 512-block. If the
     * file shrank or vanished since it was stat'd, the shortfall is zero-filled
     * so the region length always matches the header — the archive can't desync.
     */
    private static void writeFileData(OutputStream out, File file, long size,
            byte[] buf, AtomicBoolean cancelled) throws IOException {
        long remaining = size;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
        } catch (IOException e) {
            in = null; // gone since the walk; the whole entry becomes zero padding
        }
        if (in != null) {
            try {
                while (remaining > 0) {
                    if (cancelled != null && cancelled.get()) {
                        throw new InterruptedIOException("backup cancelled");
                    }
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break; // shrank; zero-fill the rest below
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            } finally {
                in.close();
            }
        }
        writeZeros(out, remaining + (padded(size) - size));
    }

    /**
     * Emits one entry's 512-byte header, preceded by GNU {@code L}/{@code K}
     * long-name records when the path or link target exceeds 100 bytes.
     */
    private static void writeHeader(OutputStream out, String name, int mode,
            long mtime, long size, char type, String linkName) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > NAME_MAX) writeLongEntry(out, 'L', nameBytes);
        byte[] linkBytes = linkName.getBytes(StandardCharsets.UTF_8);
        if (linkBytes.length > NAME_MAX) writeLongEntry(out, 'K', linkBytes);
        out.write(buildHeader(truncate(nameBytes), mode, mtime, size, type,
                truncate(linkBytes)));
    }

    /**
     * A GNU long-name ({@code L}) or long-link ({@code K}) record: a header
     * under the {@code ././@LongLink} sentinel whose NUL-terminated data carries
     * the full string, consumed by the reader before the following real header.
     */
    private static void writeLongEntry(OutputStream out, char type, byte[] data)
            throws IOException {
        long size = data.length + 1L; // GNU counts the trailing NUL
        out.write(buildHeader(LONG_NAME.getBytes(StandardCharsets.UTF_8),
                0, 0, size, type, EMPTY));
        out.write(data);
        out.write(0);
        writeZeros(out, padded(size) - size);
    }

    private static byte[] buildHeader(byte[] name, int mode, long mtime, long size,
            char type, byte[] linkName) {
        byte[] h = new byte[BLOCK];
        putBytes(h, 0, name, NAME_MAX);
        putOctal(h, 100, 8, mode);
        putOctal(h, 108, 8, 0);   // uid: archive as root, like the Debian tarball
        putOctal(h, 116, 8, 0);   // gid
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, mtime);
        h[156] = (byte) type;
        putBytes(h, 157, linkName, NAME_MAX);
        // GNU magic "ustar  \0" across magic[6] + version[2], the format whose
        // L/K records this writer uses.
        h[257] = 'u'; h[258] = 's'; h[259] = 't'; h[260] = 'a'; h[261] = 'r';
        h[262] = ' '; h[263] = ' '; h[264] = 0;
        putChecksum(h);
        return h;
    }

    private static void putBytes(byte[] h, int off, byte[] src, int max) {
        System.arraycopy(src, 0, h, off, Math.min(src.length, max));
    }

    /** Zero-padded octal in {@code [off, off+len-1)}, then a NUL terminator. */
    private static void putOctal(byte[] h, int off, int len, long value) {
        h[off + len - 1] = 0;
        for (int i = off + len - 2; i >= off; i--) {
            h[i] = (byte) ('0' + (int) (value & 7));
            value >>>= 3;
        }
    }

    /** Sum of all header bytes with the checksum field read as spaces. */
    private static void putChecksum(byte[] h) {
        int sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xFF);
        }
        // Conventional layout: six octal digits, NUL, space.
        h[148 + 6] = 0;
        h[148 + 7] = ' ';
        for (int i = 148 + 5; i >= 148; i--) {
            h[i] = (byte) ('0' + (sum & 7));
            sum >>>= 3;
        }
    }

    private static byte[] truncate(byte[] src) {
        return src.length <= NAME_MAX ? src : Arrays.copyOf(src, NAME_MAX);
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static void writeZeros(OutputStream out, long count) throws IOException {
        while (count > 0) {
            int n = (int) Math.min(ZERO.length, count);
            out.write(ZERO, 0, n);
            count -= n;
        }
    }
}
