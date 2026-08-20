/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.tukaani.xz.XZInputStream;

/**
 * Backs up and restores the userland rootfs ({@link UserlandRootfs#dir}) to and
 * from a single gzip-compressed tar file. A {@link #backup} always writes
 * gzip-tar; {@link #restore} autodetects compression and also accepts a plain
 * (uncompressed) {@code .tar}.
 *
 * The archive is a GNU-tar dialect — regular files, directories and symlinks,
 * each carrying its mode bits (including sticky/setuid/setgid), with GNU
 * {@code L}/{@code K} long-name records for paths over 100 bytes. That is
 * exactly the subset {@link UserlandRootfs#extractTar} already reads, so
 * {@link #restore} reuses that proven reader instead of a second parser; it
 * also means the file opens with a desktop {@code tar xzf}.
 *
 * <p><b>link2symlink inlining.</b> The runtime runs arm64chroot with
 * {@code --link2symlink}, which turns guest hard links — created en masse by
 * {@code apt}/{@code dpkg} — into symlinks. Each linked name becomes a symlink
 * to a same-directory backing file {@code .l2s.<ino>} that holds the real
 * content, alongside an empty {@code .l2s.<ino>.<count>} link-count marker
 * (see {@code native/arm64chroot/src/sys_file.c}). The symlink target is the
 * bare backing basename, so storing it verbatim would bake an internal name
 * into the archive and dangle on a desktop {@code tar xzf}. Instead each is
 * <em>inlined</em> — emitted as a regular file holding the backing file's
 * content and mode (the proot-distro approach) — and the raw {@code .l2s.*}
 * entries (backing + marker) are dropped. The archive is therefore
 * self-contained and portable; guest hard-link <em>sharing</em> is lost but
 * re-established lazily the next time the guest links a file after restore.
 * {@link #l2sBacking} also still recognizes the older PRoot {@code .proot.l2s.*}
 * chain layout, so a rootfs created under the previous build backs up correctly.
 *
 * <p>The live tree otherwise contains no real hard links (the installer copies
 * them) or device nodes (arm64chroot whitelists the host's at runtime), so
 * neither is emitted.
 *
 * Both directions stream in constant memory and serialize against
 * {@link UserlandRootfs#install}/{@link UserlandRootfs#replaceFromTar} by locking
 * {@code UserlandRootfs.class}. A backup is a best-effort snapshot: it does not
 * freeze a rootfs a live session may be writing, but a file that changes
 * underneath is still emitted at its declared size (zero-padded if it shrank),
 * so the archive stays structurally valid. An {@link #ensureReadable} pass
 * first grants the owner the minimum bits to read every entry, so a
 * deliberately unreadable file or directory isn't silently lost.
 */
public final class RootfsBackup {

    /**
     * Determinate progress for a backup or restore: {@code done} and
     * {@code total} share one unit so the ratio is the fraction complete. For a
     * backup that unit is uncompressed payload bytes (archived / total to
     * archive); for a restore it is compressed archive bytes (consumed / file
     * size). {@code total} is 0 when it could not be determined up front, in
     * which case only an indeterminate bar is meaningful.
     */
    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    /**
     * Opens a fresh stream at the start of the archive. {@link #restore} reads
     * the archive twice — once to probe member names for the wrapper-directory
     * strip count, once to extract — so it needs to reopen rather than rewind a
     * one-shot (e.g. SAF) stream. Each call returns an independent stream the
     * caller owns; {@code restore} closes the streams it opens.
     */
    public interface ArchiveSource {
        InputStream open() throws IOException;
    }

    private static final int BLOCK = 512;
    private static final int NAME_MAX = 100;            // ustar name/linkname width
    private static final String LONG_NAME = "././@LongLink"; // GNU sentinel name
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] ZERO = new byte[BLOCK];

    /**
     * Basename prefixes the runtime's {@code link2symlink} extension gives the
     * backing/intermediate files that stand in for guest hard links:
     * {@code .l2s.} (arm64chroot, and non-USERLAND PRoot) and {@code .proot.l2s.}
     * (the PRoot USERLAND builds previously shipped). Both are recognized so an
     * archive made under either round-trips.
     */
    private static final String[] L2S_PREFIXES = {".proot.l2s.", ".l2s."};

    private RootfsBackup() {}

    /**
     * Streams the installed rootfs to {@code out} as a gzip-compressed tar.
     * Does not close {@code out} (the caller owns it). Blocking — call off the
     * main thread; flip {@code cancelled} to abort with {@link InterruptedIOException}.
     *
     * Progress is reported as {@code archived / total} payload bytes; the total
     * comes from a quick {@link #measure} walk taken under the same lock, so the
     * denominator matches exactly what the writer counts and the fraction
     * reaches 1.0 at the end.
     */
    public static void backup(Context ctx, OutputStream out,
            ProgressListener progress, AtomicBoolean cancelled)
            throws IOException {
        File root = UserlandRootfs.dir(ctx);
        if (!root.isDirectory()) throw new IOException("Userland rootfs not installed");
        synchronized (UserlandRootfs.class) {
            ensureReadable(root);
            long total = measure(root);
            UserlandRootfs.ProgressListener tick = progress == null ? null
                    : archived -> progress.onProgress(archived, total);
            // Closed, not just finished: the gzip layer owns a Deflater whose
            // native zlib state only close() frees. The caller owns `out`, so a
            // shield sits directly on top of it and swallows the close, leaving
            // every stream this method created to be closed properly. The
            // try-with-resources also covers the cancel and failure paths,
            // where the old finish()/flush() pair was simply skipped.
            try (OutputStream gz = new GZIPOutputStream(new BufferedOutputStream(
                    new NonClosingOutputStream(out), 1 << 16))) {
                writeArchive(root, gz, tick, cancelled);
            }
        }
    }

    /**
     * Passes writes straight through and turns {@code close()} into a flush, so
     * a stream stack built on top of a caller-owned {@link OutputStream} can be
     * closed without closing that stream. {@link FilterOutputStream} would
     * forward bulk writes one byte at a time, so the array overload is
     * delegated explicitly.
     */
    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /**
     * Replaces the installed rootfs with the contents of a tar opened by
     * {@code source}. The swap is atomic and non-destructive on failure (see
     * {@link UserlandRootfs#replaceFromTar}). Blocking — call off the main thread;
     * flip {@code cancelled} to abort.
     *
     * <p>The archive need not be one of our own backups. Compression is
     * autodetected from the leading magic bytes — gzip or xz (the codec the
     * bundled rootfs ships in) are decompressed on the fly, otherwise the stream
     * is read as a plain (uncompressed) {@code .tar} — so a hand-rolled,
     * xz-recompressed, or already-decompressed tarball restores too (see
     * {@link #tarStream}). A first pass then probes the leading member names
     * ({@link UserlandRootfs#probeStripCount}) to detect how many wrapper
     * directories to strip so a foreign rootfs tarball (e.g. one nested under
     * {@code distro/}) still lands at the root, and a second pass extracts with
     * that strip applied. The probe failing for any reason just falls back to a
     * verbatim (strip 0) extraction.
     *
     * <p>Progress is reported as {@code consumed / archiveSize} <em>file</em>
     * bytes — counted on the raw stream below any decompression layer of the
     * extraction pass, since the uncompressed total is unknown until the archive
     * is fully read. Pass the archive file's byte length as {@code archiveSize}; a
     * value &le; 0 (the picker did not report a size) yields indeterminate
     * progress.
     */
    public static void restore(Context ctx, ArchiveSource source, long archiveSize,
            ProgressListener progress, AtomicBoolean cancelled)
            throws IOException {
        // Pass 1: probe the leading member names for a wrapper-directory strip
        // count. Best-effort — any failure here falls back to verbatim extract,
        // and pass 2 surfaces a genuinely broken archive.
        int strip = 0;
        try (InputStream probe = source.open();
                InputStream tar = tarStream(
                        new BufferedInputStream(probe, 1 << 16))) {
            strip = UserlandRootfs.probeStripCount(tar);
        } catch (IOException e) {
            strip = 0;
        }

        // Pass 2: extract with the detected strip applied.
        try (InputStream in = source.open()) {
            CountingInputStream counter = new CountingInputStream(in);
            InputStream tar = tarStream(new BufferedInputStream(counter, 1 << 16));
            UserlandRootfs.ProgressListener tick = progress == null ? null
                    : extracted -> progress.onProgress(counter.count(), archiveSize);
            UserlandRootfs.replaceFromTar(ctx, tar, tick, cancelled, strip);
        }
    }

    /** xz stream header magic: {@code FD 37 7A 58 5A 00} ("\xFD7zXZ\0"). */
    private static final byte[] XZ_MAGIC =
            {(byte) 0xFD, '7', 'z', 'X', 'Z', 0x00};
    /** gzip member header magic: {@code 1F 8B}. */
    private static final byte[] GZIP_MAGIC = {(byte) 0x1F, (byte) 0x8B};

    /**
     * Returns a tar stream over {@code buffered}, transparently decompressing it
     * by sniffing the leading bytes — xz ({@link #XZ_MAGIC}), gzip
     * ({@link #GZIP_MAGIC}), or read verbatim otherwise — so {@link #restore}
     * accepts our own gzip backups, an {@code .tar.xz} (the same codec the
     * bundled rootfs ships in), and a plain (uncompressed) {@code .tar}. Up to
     * six bytes are read through the buffer's {@code mark}/{@code reset} and
     * pushed back, so the chosen reader still sees the whole stream. The caller
     * supplies the {@link BufferedInputStream} so a restore can interpose a byte
     * counter beneath it. Package-private so a test can drive each branch. A
     * stream too short to match any magic is treated as plain (and the tar reader
     * rejects it if it isn't a tar).
     */
    static InputStream tarStream(BufferedInputStream buffered) throws IOException {
        byte[] head = new byte[XZ_MAGIC.length];
        buffered.mark(head.length);
        int n = 0;
        int r;
        while (n < head.length && (r = buffered.read(head, n, head.length - n)) >= 0) {
            n += r;
        }
        buffered.reset();
        if (startsWith(head, n, XZ_MAGIC)) return new XZInputStream(buffered);
        if (startsWith(head, n, GZIP_MAGIC)) return new GZIPInputStream(buffered);
        return buffered;
    }

    /** True when the first {@code len} bytes of {@code head} begin with {@code magic}. */
    private static boolean startsWith(byte[] head, int len, byte[] magic) {
        if (len < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) return false;
        }
        return true;
    }

    /**
     * Sum of the regular-file bytes the archive will carry — the backup
     * denominator. Counts only what {@link #archive} streams as file data: plain
     * regular files plus the backing file of each inlined {@code link2symlink}
     * entry (counted once, via the referring symlink — the raw
     * {@code .proot.l2s.*} siblings are skipped just like the writer skips them).
     * Directories and ordinary symlinks contribute no payload, so this equals
     * the writer's running total at completion. Package-private so a test can pin
     * the denominator against a known tree.
     */
    static long measure(File dir) {
        String rootCanon;
        try {
            rootCanon = dir.getCanonicalPath();
        } catch (IOException e) {
            rootCanon = dir.getAbsolutePath();
        }
        return measure(dir, rootCanon);
    }

    private static long measure(File dir, String rootCanon) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File child : children) {
            if (isL2sArtifact(child.getName())) continue; // inlined via its symlink
            StructStat st;
            try {
                st = Os.lstat(child.getAbsolutePath());
            } catch (ErrnoException e) {
                continue; // vanished mid-walk; the writer skips it too
            }
            if (OsConstants.S_ISDIR(st.st_mode)) {
                total += measure(child, rootCanon);
            } else if (OsConstants.S_ISLNK(st.st_mode)) {
                File backing = l2sBacking(child, rootCanon);
                if (backing != null) {
                    try {
                        total += Os.stat(backing.getAbsolutePath()).st_size;
                    } catch (ErrnoException e) {
                        // backing vanished; the writer falls back to a symlink (0 payload)
                    }
                }
            } else if (OsConstants.S_ISREG(st.st_mode)) {
                total += st.st_size;
            }
        }
        return total;
    }

    // --- link2symlink inlining & readability ---

    /**
     * True when {@code name} is a {@code link2symlink} backing/intermediate
     * file (basename carries an {@link #L2S_PREFIXES} prefix) or the
     * {@code .l2s} backing directory. Such entries are dropped from the archive;
     * their content is carried by the user-facing symlink that references them.
     */
    private static boolean isL2sArtifact(String name) {
        if (name.equals(".l2s")) return true;
        for (String p : L2S_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * If {@code symlink} is a {@code link2symlink} hard-link stand-in — its
     * target's basename carries an {@link #L2S_PREFIXES} prefix and it resolves
     * to a regular file inside the rootfs — returns the file to inline (which
     * {@link Os#stat}/{@link FileInputStream} follow to the content). Handles
     * both target styles: arm64chroot's bare same-directory basename
     * ({@code .l2s.<ino>}, resolved against the symlink's parent) and PRoot's
     * absolute-path {@code .proot.l2s.*} chain. Returns {@code null} for an
     * ordinary symlink, a broken/non-regular chain, or a target that would
     * escape {@code rootCanon} (so a crafted link can't make the backup read
     * host files).
     */
    private static File l2sBacking(File symlink, String rootCanon) {
        String target;
        try {
            target = Os.readlink(symlink.getAbsolutePath());
        } catch (ErrnoException e) {
            return null;
        }
        int slash = target.lastIndexOf('/');
        String base = slash < 0 ? target : target.substring(slash + 1);
        boolean l2s = false;
        for (String p : L2S_PREFIXES) {
            if (base.startsWith(p)) { l2s = true; break; }
        }
        if (!l2s) return null;
        File abs = target.startsWith("/") ? new File(target)
                : new File(symlink.getParentFile(), target);
        try {
            String resolved = abs.getCanonicalPath(); // follows the l2s chain
            if (!resolved.equals(rootCanon)
                    && !resolved.startsWith(rootCanon + File.separator)) {
                return null;
            }
            if (!OsConstants.S_ISREG(Os.stat(abs.getAbsolutePath()).st_mode)) {
                return null;
            }
            return abs;
        } catch (ErrnoException | IOException e) {
            return null;
        }
    }

    /**
     * Grants the owner the minimum bits to read every entry under {@code dir}
     * before it is archived, so a deliberately unreadable file or directory
     * isn't silently lost (an unreadable file would otherwise be emitted as zero
     * padding by {@link #writeFileData}; an unsearchable directory's children
     * would be dropped when {@code listFiles()} returns {@code null}). Adds only
     * the missing owner bits — a directory without owner r-x gets {@code +0500},
     * a regular file without owner r gets {@code +0400} — leaving group/other and
     * already-present bits untouched. Never follows symlinks. Mirrors
     * proot-distro's {@code _fix_permissions}; the one visible effect is that a
     * {@code chmod 000} entry round-trips as owner-readable. Best-effort: a
     * {@code chmod} that fails (e.g. on a vanished entry) is ignored, leaving the
     * pre-existing skip-on-error behavior of the writer.
     */
    private static void ensureReadable(File dir) {
        StructStat dst;
        try {
            dst = Os.lstat(dir.getAbsolutePath());
        } catch (ErrnoException e) {
            return;
        }
        int dmode = dst.st_mode & 07777;
        if ((dmode & 0500) != 0500) {
            try {
                Os.chmod(dir.getAbsolutePath(), dmode | 0500);
            } catch (ErrnoException ignored) {
            }
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            StructStat st;
            try {
                st = Os.lstat(child.getAbsolutePath());
            } catch (ErrnoException e) {
                continue;
            }
            if (OsConstants.S_ISDIR(st.st_mode)) {
                ensureReadable(child);
            } else if (OsConstants.S_ISREG(st.st_mode)) {
                int mode = st.st_mode & 07777;
                if ((mode & 0400) == 0) {
                    try {
                        Os.chmod(child.getAbsolutePath(), mode | 0400);
                    } catch (ErrnoException ignored) {
                    }
                }
            }
        }
    }

    // --- tar writer ---

    /**
     * Writes the children of {@code root} (relative paths, no leading
     * directory) to {@code out} as a tar stream terminated by the two zero
     * blocks tar uses as its end-of-archive marker. Package-private so a test
     * can round-trip it against {@link UserlandRootfs#extractTar} on a temp tree.
     */
    static void writeArchive(File root, OutputStream out,
            UserlandRootfs.ProgressListener progress, AtomicBoolean cancelled)
            throws IOException {
        byte[] buf = new byte[1 << 16];
        long[] counter = new long[2]; // [0] = bytes written, [1] = last reported
        String rootCanon;
        try {
            rootCanon = root.getCanonicalPath();
        } catch (IOException e) {
            rootCanon = root.getAbsolutePath();
        }
        archive(root, "", rootCanon, out, buf, counter, progress, cancelled);
        out.write(ZERO);
        out.write(ZERO);
    }

    /** Depth-first, parents before children, names sorted for reproducibility. */
    private static void archive(File dir, String prefix, String rootCanon,
            OutputStream out, byte[] buf, long[] counter,
            UserlandRootfs.ProgressListener progress, AtomicBoolean cancelled)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        for (File child : children) {
            if (cancelled != null && cancelled.get()) {
                throw new InterruptedIOException("backup cancelled");
            }
            // link2symlink internals reach the archive through the
            // user-facing symlink that references them (inlined below), never as
            // standalone entries.
            if (isL2sArtifact(child.getName())) continue;
            String name = prefix + child.getName();
            StructStat st;
            try {
                st = Os.lstat(child.getAbsolutePath());
            } catch (ErrnoException e) {
                continue; // vanished mid-walk; skip it
            }
            int mode = st.st_mode & 07777;
            if (OsConstants.S_ISLNK(st.st_mode)) {
                File backing = l2sBacking(child, rootCanon);
                if (backing != null) {
                    // A hard link emulated by --link2symlink: inline the backing
                    // file's bytes as a plain regular file under this path.
                    archiveBacking(out, name, backing, buf, counter, progress,
                            cancelled);
                } else {
                    String target;
                    try {
                        target = Os.readlink(child.getAbsolutePath());
                    } catch (ErrnoException e) {
                        continue;
                    }
                    writeHeader(out, name, mode, st.st_mtime, 0, '2', target);
                }
            } else if (OsConstants.S_ISDIR(st.st_mode)) {
                writeHeader(out, name + "/", mode, st.st_mtime, 0, '5', "");
                archive(child, name + "/", rootCanon, out, buf, counter, progress,
                        cancelled);
            } else if (OsConstants.S_ISREG(st.st_mode)) {
                long size = st.st_size;
                writeHeader(out, name, mode, st.st_mtime, size, '0', "");
                writeFileData(out, child, size, buf, cancelled);
                tally(size, counter, progress);
            }
            // Anything else (socket/fifo/device) has no place in a rootfs tree.
        }
    }

    /**
     * Emits {@code backing} (the file a {@code link2symlink} symlink resolves to)
     * as a regular-file entry named {@code name}, with the backing file's own
     * size/mode/mtime. {@code backing} is the intermediate symlink path;
     * {@link Os#stat} and the data copy both follow it to the content file. If it
     * vanished since {@link #l2sBacking} stat'd it, the entry is skipped (rather
     * than written truncated), keeping the archive consistent.
     */
    private static void archiveBacking(OutputStream out, String name, File backing,
            byte[] buf, long[] counter, UserlandRootfs.ProgressListener progress,
            AtomicBoolean cancelled) throws IOException {
        StructStat bst;
        try {
            bst = Os.stat(backing.getAbsolutePath()); // follows to the content file
        } catch (ErrnoException e) {
            return;
        }
        long size = bst.st_size;
        writeHeader(out, name, bst.st_mode & 07777, bst.st_mtime, size, '0', "");
        writeFileData(out, backing, size, buf, cancelled);
        tally(size, counter, progress);
    }

    /** Advances the running payload counter and reports progress every 8 MiB. */
    private static void tally(long size, long[] counter,
            UserlandRootfs.ProgressListener progress) {
        counter[0] += size;
        if (progress != null && counter[0] - counter[1] >= (8 << 20)) {
            counter[1] = counter[0];
            progress.onProgress(counter[0]);
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
     * Package-private so a test can assemble a hand-crafted archive against the
     * real reader.
     */
    static void writeHeader(OutputStream out, String name, int mode,
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

    /**
     * Tallies the bytes pulled from the underlying stream. Sits below the gzip
     * layer during a restore so {@link #count} tracks consumption of the
     * compressed archive — the restore progress numerator — rather than the
     * uncompressed total, which is unknown until the read completes. Only ever
     * read from the extraction thread that does the reads, so no synchronization.
     */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long s = in.skip(n);
            if (s > 0) count += s;
            return s;
        }
    }
}
