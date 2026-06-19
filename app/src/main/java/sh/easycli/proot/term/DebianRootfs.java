package sh.easycli.proot.term;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Debian userland: rootfs install state, the tar.xz installer, and the
 * PRoot command line for {@link TerminalSession}.
 *
 * The rootfs rides in the APK as an optional asset
 * (debian_trixie_&lt;arch&gt;_rootfs.tar.xz, bundled from DebianRootfs/ when
 * present at build time — never committed) and is extracted once into
 * filesDir/debian. The tar reader is deliberately minimal: the rootfs
 * tarballs contain only regular files, directories, symlinks and
 * (potentially) hard links; device nodes etc. are skipped because PRoot
 * binds the host /dev anyway.
 */
public final class DebianRootfs {

    /** Cumulative uncompressed bytes; called from the install thread. */
    public interface ProgressListener {
        void onProgress(long bytesExtracted);
    }

    private DebianRootfs() {}

    /**
     * Rootfs root, bound as guest "/" — filesDir/debian. Its existence is the
     * install marker: {@link #install} builds the tree in {@link #stagingDir}
     * and only renames it into place once complete, so this directory appears
     * atomically and never represents a half-written rootfs.
     */
    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "debian");
    }

    /** Where {@link #install} extracts before the atomic rename onto {@link #dir}. */
    private static File stagingDir(Context ctx) {
        return new File(ctx.getFilesDir(), "debian.tmp");
    }

    /** The rootfs directory is present, i.e. an install completed here. */
    public static boolean isInstalled(Context ctx) {
        return dir(ctx).isDirectory();
    }

    /**
     * The rootfs is present and actually runnable: the directory exists
     * <em>and</em> the bash binary the login command execs is still there.
     *
     * The directory alone is not enough — it lives under filesDir, which a
     * session's own {@code rm -rf} (or another app) can gut from under us,
     * leaving the directory but no shell. Spawning PRoot against a gutted
     * rootfs only dies instantly, so callers gate the Debian session type on
     * this instead of {@link #isInstalled}.
     */
    public static boolean isUsable(Context ctx) {
        return isInstalled(ctx) && hasShell(dir(ctx));
    }

    /** True when the login shell ({@code /bin/bash}) resolves inside root. */
    private static boolean hasShell(File root) {
        // exists() follows the usrmerge bin -> usr/bin symlink; check both
        // layouts so a non-usrmerge rootfs is recognized too.
        return new File(root, "bin/bash").exists()
                || new File(root, "usr/bin/bash").exists();
    }

    /** Asset file name for this device's primary ABI, or null. */
    public static String assetName() {
        switch (Build.SUPPORTED_ABIS[0]) {
            case "arm64-v8a": return "debian_trixie_aarch64_rootfs.tar.xz";
            case "x86_64": return "debian_trixie_x86_64_rootfs.tar.xz";
            default: return null;
        }
    }

    /** True when this build bundles a rootfs for this device's ABI. */
    public static boolean assetAvailable(Context ctx) {
        String name = assetName();
        if (name == null) return false;
        try (InputStream in = ctx.getAssets().open(name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extracts the bundled rootfs into {@link #dir}. Idempotent: returns
     * immediately when already installed. Extraction happens in a staging
     * directory that is renamed onto {@link #dir} only once complete, so the
     * rootfs directory — which is the install marker — appears atomically; a
     * crash mid-extract leaves only the staging dir, which the next attempt
     * discards. Deliberately does <em>not</em> rebuild an installed rootfs that
     * has become unusable (e.g. its {@code /bin/bash} was deleted) — that
     * rootfs may hold the user's data, so it is never wiped behind their back;
     * callers fall back to the Android shell instead. Blocking — call from a
     * background thread.
     */
    public static synchronized void install(Context ctx, ProgressListener progress)
            throws IOException {
        if (isInstalled(ctx)) return;
        String asset = assetName();
        if (asset == null) throw new IOException("no Debian rootfs for ABI "
                + Build.SUPPORTED_ABIS[0]);

        File staging = stagingDir(ctx);
        deleteRecursively(staging); // drop any aborted previous attempt
        if (!staging.mkdirs()) throw new IOException("cannot create " + staging);

        try (InputStream raw = ctx.getAssets().open(asset);
                XZInputStream xz = new XZInputStream(new BufferedInputStream(raw, 1 << 16))) {
            // The bundled asset is trusted (we built it) and unwrapped, so skip
            // both the per-entry path-escape guard and the strip heuristic that
            // restore applies to arbitrary picked files.
            extractTar(xz, staging, progress, null, false, 0);
        }
        writeGuestDefaults(staging);
        publish(ctx, staging);
    }

    /**
     * Replaces the installed rootfs with the contents of an <em>uncompressed</em>
     * tar stream (the caller handles any decompression — see
     * {@link RootfsBackup#restore}). Extraction lands in {@link #stagingDir}
     * first and only the final {@link #publish} rename touches {@link #dir}, so
     * a failure or cancel mid-extract leaves the existing rootfs untouched.
     *
     * Unlike {@link #install} this deliberately skips {@link #writeGuestDefaults}:
     * a restore reproduces the backed-up tree verbatim — its resolv.conf, /etc,
     * and the bind-mount point directories all ride in the archive, and
     * rewriting them would clobber the user's data. Synchronized against
     * {@link #install} (both lock this class). Blocking — call off the main
     * thread; pass {@code cancelled} to abort a long restore.
     *
     * <p>{@code strip} drops that many leading path components from every member
     * (a {@code tar --strip-components} equivalent) so a custom archive that
     * nests the rootfs under a wrapper directory still lands at the root; pass 0
     * for a verbatim extraction. The caller derives it from
     * {@link #probeStripCount}.
     *
     * <p>Only a structurally valid tar is accepted — a corrupt or non-tar file
     * fails extraction and leaves the existing rootfs untouched (extraction
     * stages in {@link #stagingDir} and only {@link #publish} touches
     * {@link #dir}). The published tree is <em>not</em> required to be a
     * recognizable Debian rootfs: a custom or non-Debian archive is installed as
     * given and the caller warns if its login shell is missing (so the install
     * isn't silently blocked).
     */
    static synchronized void replaceFromTar(Context ctx, InputStream tar,
            ProgressListener progress, AtomicBoolean cancelled, int strip)
            throws IOException {
        File staging = stagingDir(ctx);
        deleteRecursively(staging); // drop any aborted previous attempt
        if (!staging.mkdirs()) throw new IOException("cannot create " + staging);
        try {
            extractTar(tar, staging, progress, cancelled, true, strip);
        } catch (IOException e) {
            deleteRecursively(staging); // never leave a half-written tree behind
            throw e;
        }
        publish(ctx, staging);
    }

    /**
     * Atomically swaps {@code staging} onto {@link #dir} with a rename(2) within
     * filesDir. The destination is removed first because renameTo won't replace
     * a non-empty directory; for a fresh {@link #install} that delete is a
     * defensive no-op. Shared by {@link #install} and {@link #replaceFromTar} so
     * {@link #dir} (the install marker) only ever names a finished rootfs.
     */
    private static void publish(Context ctx, File staging) throws IOException {
        File root = dir(ctx);
        deleteRecursively(root);
        if (!staging.renameTo(root)) {
            deleteRecursively(staging);
            throw new IOException("cannot publish rootfs to " + root);
        }
    }

    /**
     * PRoot command for a Debian login shell. Requires an installed rootfs
     * and the loader executable that APK packaging extracted into
     * nativeLibraryDir (its only exec-allowed location under W^X).
     *
     * @param shell guest-absolute path to the login shell (e.g. {@code /bin/bash})
     */
    public static SessionCommand command(Context ctx, String shell) throws IOException {
        if (!isInstalled(ctx)) throw new IOException("Debian rootfs not installed");
        if (!hasShell(dir(ctx))) {
            throw new IOException("Debian rootfs is incomplete: /bin/bash is "
                    + "missing (was it deleted outside the app?)");
        }
        String shellRel = shell.startsWith("/") ? shell.substring(1) : shell;
        if (!new File(dir(ctx), shellRel).exists()) {
            throw new IOException("Login shell " + shell + " not found in Debian rootfs");
        }
        File loader = new File(ctx.getApplicationInfo().nativeLibraryDir,
                "libproot-loader.so");
        if (!loader.canExecute()) {
            throw new IOException("PRoot loader not executable: " + loader
                    + " (jniLibs must use legacy packaging)");
        }
        File tmp = new File(ctx.getFilesDir(), "proot-tmp");
        tmp.mkdirs();

        String[] argv = {
                "proot",
                "--kill-on-exit",  // no orphaned tracees after bash exits
                "--link2symlink",  // apps can't hard-link; dpkg needs ln to work
                "-0",              // fake uid/gid 0: apt/dpkg insist on root
                "-r", dir(ctx).getAbsolutePath(),
                "-w", "/root",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                // env -i: the host environment (incl. PROOT_*) stops here;
                // the guest gets a clean Debian login environment.
                "/usr/bin/env", "-i",
                "HOME=/root",
                "USER=root",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                shell, "--login",
        };
        String[] env = {
                "PROOT_LOADER=" + loader.getAbsolutePath(),
                "PROOT_TMP_DIR=" + tmp.getAbsolutePath(),
                "PATH=/system/bin",
                "HOME=" + ctx.getFilesDir().getAbsolutePath(),
        };
        return new SessionCommand(null, argv, env,
                ctx.getFilesDir().getAbsolutePath(), "deb", true);
    }

    // --- tar extraction ---

    private static final int BLOCK = 512;

    /**
     * Top-level directory names that mark a filesystem root. Used by
     * {@link #detectStripCount} to recognize where the rootfs actually begins
     * inside a custom archive. Mirrors proot-distro's {@code _ROOTFS_DIRS}.
     */
    private static final Set<String> ROOTFS_DIRS = new HashSet<>(Arrays.asList(
            "bin", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32",
            "media", "mnt", "opt", "proc", "root", "run", "sbin", "srv",
            "sys", "tmp", "usr", "var"));

    /** Member-name sample size and max leading components considered for stripping. */
    private static final int STRIP_SAMPLE = 500;
    private static final int STRIP_MAX = 4;

    /**
     * Extracts {@code in} into {@code root} with the path-escape guard on: this
     * is the entry point for restoring an arbitrary, possibly hostile, picked
     * archive (see {@link #extractTar(InputStream, File, ProgressListener,
     * AtomicBoolean, boolean)}).
     */
    static void extractTar(InputStream in, File root, ProgressListener progress,
            AtomicBoolean cancelled) throws IOException {
        extractTar(in, root, progress, cancelled, true, 0);
    }

    /**
     * Core tar reader. When {@code guard} is set, every entry is confined to
     * {@code root}: an entry whose parent (or, for a directory, itself) resolves
     * through a symlink to outside {@code root} is skipped, and any symlink
     * already present at a regular-file/hard-link target is removed before the
     * write so a planted {@code evil -> /} (followed by {@code evil/x}) can't
     * redirect onto the host. Legitimate usrmerge symlinks resolve <em>within</em>
     * {@code root} and are unaffected. {@code guard} is off only for the trusted
     * bundled-asset install, where the per-entry canonicalization is needless
     * cost.
     *
     * <p>{@code strip} drops that many leading path components from each member
     * (and from a hard link's source), so a custom archive nesting the rootfs
     * under a wrapper directory extracts at the root; a member at or above the
     * strip depth is dropped. Package-private so a test can drive the strip
     * directly.
     */
    static void extractTar(InputStream in, File root, ProgressListener progress,
            AtomicBoolean cancelled, boolean guard, int strip) throws IOException {
        final String rootCanon;
        if (guard) {
            String p;
            try {
                p = root.getCanonicalPath();
            } catch (IOException e) {
                p = root.getAbsolutePath();
            }
            rootCanon = p;
        } else {
            rootCanon = null;
        }
        byte[] header = new byte[BLOCK];
        byte[] buf = new byte[1 << 16];
        // Directory modes are applied after extraction: a read-only dir
        // applied up front would block creating its children (umask makes
        // mkdir yield 0700 during the loop, so writes always succeed).
        List<File> dirFiles = new ArrayList<>();
        List<Integer> dirModes = new ArrayList<>();
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        long extracted = 0;
        long lastReport = 0;

        while (readBlock(in, header)) {
            if (cancelled != null && cancelled.get()) {
                throw new InterruptedIOException("restore cancelled");
            }
            if (isZeroBlock(header)) break; // end-of-archive marker

            String name = field(header, 0, 100);
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            int mode = (int) octal(header, 100, 8);
            long size = octal(header, 124, 12);
            byte type = header[156];
            String linkName = field(header, 157, 100);

            switch (type) {
                case 'L': // GNU long name: data holds the next entry's name
                    longName = readString(in, size);
                    continue;
                case 'K': // GNU long link target
                    longLink = readString(in, size);
                    continue;
                case 'x': { // PAX per-file records
                    String[] overrides = parsePax(readString(in, size));
                    paxPath = overrides[0];
                    paxLink = overrides[1];
                    continue;
                }
                case 'g': // PAX global records — nothing we honor
                    skip(in, padded(size));
                    continue;
                default:
                    break;
            }

            if (longName != null) name = longName;
            if (paxPath != null) name = paxPath;
            if (longLink != null) linkName = longLink;
            if (paxLink != null) linkName = paxLink;
            longName = longLink = paxPath = paxLink = null;

            if (strip > 0) {
                name = stripComponents(name, strip);
                if (name == null) { // the wrapper dir itself or shallower
                    skip(in, padded(size));
                    continue;
                }
                if (type == '1') { // a hard link's source is a member path too
                    linkName = stripComponents(linkName, strip);
                    if (linkName == null) {
                        skip(in, padded(size));
                        continue;
                    }
                }
            }

            File target = resolve(root, name);
            if (target == null) { // unsafe path — skip entry and its data
                skip(in, padded(size));
                continue;
            }

            switch (type) {
                case '0':
                case 0:
                case '7': { // regular file
                    File parent = target.getParentFile();
                    if (parent != null) {
                        if (guard && !withinRoot(rootCanon, parent)) {
                            skip(in, padded(size));
                            break;
                        }
                        parent.mkdirs();
                    }
                    if (guard) deleteIfSymlink(target); // don't write through a planted link
                    try (OutputStream out = new FileOutputStream(target)) {
                        long left = size;
                        while (left > 0) {
                            if (cancelled != null && cancelled.get()) {
                                throw new InterruptedIOException("restore cancelled");
                            }
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("truncated tar entry " + name);
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skip(in, padded(size) - size);
                    chmod(target, mode);
                    break;
                }
                case '5': // directory
                    if (guard && !withinRoot(rootCanon, target)) {
                        skip(in, padded(size));
                        break;
                    }
                    target.mkdirs();
                    dirFiles.add(target);
                    dirModes.add(mode);
                    skip(in, padded(size));
                    break;
                case '2': { // symlink
                    File parent = target.getParentFile();
                    if (parent != null) {
                        if (guard && !withinRoot(rootCanon, parent)) {
                            skip(in, padded(size));
                            break;
                        }
                        parent.mkdirs();
                    }
                    target.delete();
                    try {
                        Os.symlink(linkName, target.getAbsolutePath());
                    } catch (ErrnoException e) {
                        throw new IOException("symlink " + name + " -> " + linkName, e);
                    }
                    skip(in, padded(size));
                    break;
                }
                case '1': { // hard link: apps can't link(); copy the target
                    File source = resolve(root, linkName);
                    if (source == null || (guard && !withinRoot(rootCanon, source))
                            || !source.isFile()) {
                        throw new IOException("hard link source missing: " + linkName);
                    }
                    File parent = target.getParentFile();
                    if (parent != null) {
                        if (guard && !withinRoot(rootCanon, parent)) {
                            skip(in, padded(size));
                            break;
                        }
                        parent.mkdirs();
                    }
                    if (guard) deleteIfSymlink(target);
                    try (InputStream src = new FileInputStream(source);
                            OutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    chmod(target, mode);
                    skip(in, padded(size));
                    break;
                }
                default: // device nodes, fifos, sparse files: not needed
                    skip(in, padded(size));
                    break;
            }

            extracted += size;
            if (progress != null && extracted - lastReport >= (8 << 20)) {
                lastReport = extracted;
                progress.onProgress(extracted);
            }
        }

        for (int i = 0; i < dirFiles.size(); i++) {
            chmod(dirFiles.get(i), dirModes.get(i));
        }
    }

    // --- wrapper-directory strip heuristic (custom archives) ---

    /**
     * Reads the leading member names of an (already-decompressed) tar stream and
     * returns how many leading path components {@link #extractTar} should strip
     * so the rootfs lands at the root. Best-effort: a parse error mid-probe just
     * scores the names gathered so far. The stream is consumed and must be
     * reopened for the actual extraction. Package-private for tests.
     */
    static int probeStripCount(InputStream tar) throws IOException {
        byte[] header = new byte[BLOCK];
        List<String> names = new ArrayList<>();
        try {
            while (names.size() < STRIP_SAMPLE && readBlock(tar, header)) {
                if (isZeroBlock(header)) break;
                String name = field(header, 0, 100);
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                names.add(name);
                skip(tar, padded(size));
            }
        } catch (IOException e) {
            // Truncated/odd archive: decide from whatever names we have. The
            // extraction pass will surface any real corruption.
        }
        return detectStripCount(names);
    }

    /**
     * Scores strip counts 0..{@link #STRIP_MAX} by how many sampled names carry a
     * {@link #ROOTFS_DIRS} entry at that depth, and returns the best. 0 means the
     * archive is already rooted (the common case). Mirrors proot-distro's
     * {@code detect_strip_count}. Package-private for tests.
     */
    static int detectStripCount(List<String> names) {
        int bestStrip = 0;
        int bestScore = -1;
        for (int strip = 0; strip <= STRIP_MAX; strip++) {
            int score = 0;
            for (String name : names) {
                String[] parts = splitMember(name);
                if (parts.length > strip && ROOTFS_DIRS.contains(parts[strip])) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestStrip = strip;
            }
        }
        return bestStrip;
    }

    /** Trims leading/trailing slashes then splits on '/'; {@code []} when empty. */
    private static String[] splitMember(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && name.charAt(start) == '/') start++;
        while (end > start && name.charAt(end - 1) == '/') end--;
        if (start >= end) return new String[0];
        return name.substring(start, end).split("/");
    }

    /**
     * Drops the first {@code strip} path components from {@code name}; returns
     * {@code null} when the name has no more than {@code strip} components (the
     * wrapper directory itself or shallower), so the caller skips it.
     */
    private static String stripComponents(String name, int strip) {
        String[] parts = splitMember(name);
        if (parts.length <= strip) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = strip; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** Reads one block; false on clean EOF, throws if truncated mid-block. */
    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int off = 0;
        while (off < BLOCK) {
            int n = in.read(block, off, BLOCK - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new IOException("truncated tar header");
            }
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    /** NUL/space-padded text field. */
    private static String field(byte[] block, int off, int len) {
        int end = off;
        while (end < off + len && block[end] != 0) end++;
        return new String(block, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] block, int off, int len) throws IOException {
        if ((block[off] & 0x80) != 0) { // GNU base-256; rootfs never needs it
            throw new IOException("base-256 tar field unsupported");
        }
        long value = 0;
        for (int i = off; i < off + len; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') {
                if (value != 0) break;
                continue;
            }
            if (b < '0' || b > '7') throw new IOException("bad octal tar field");
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static String readString(InputStream in, long size) throws IOException {
        if (size > (1 << 20)) throw new IOException("oversized tar meta entry");
        byte[] data = new byte[(int) size];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) throw new IOException("truncated tar meta entry");
            off += n;
        }
        skip(in, padded(size) - size);
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) end--;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    /** PAX "len key=value\n" records; returns {path, linkpath} (nullable). */
    private static String[] parsePax(String data) {
        String path = null;
        String link = null;
        int pos = 0;
        while (pos < data.length()) {
            int sp = data.indexOf(' ', pos);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(data.substring(pos, sp));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length()) break;
            String record = data.substring(sp + 1, pos + len - 1); // strip \n
            int eq = record.indexOf('=');
            if (eq > 0) {
                String key = record.substring(0, eq);
                String value = record.substring(eq + 1);
                if (key.equals("path")) path = value;
                else if (key.equals("linkpath")) link = value;
            }
            pos += len;
        }
        return new String[] {path, link};
    }

    private static void skip(InputStream in, long count) throws IOException {
        while (count > 0) {
            long n = in.skip(count);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("truncated tar data");
                n = 1;
            }
            count -= n;
        }
    }

    /**
     * True when {@code path}'s real location (all symlink components resolved) is
     * {@code rootCanon} itself or lies beneath it. Used by the guarded
     * {@link #extractTar} path to reject an entry that a symlink planted by an
     * earlier member would redirect outside the staging tree, while still
     * allowing the rootfs's own usrmerge symlinks (whose parent {@code bin}
     * resolves to {@code usr/bin}, still inside {@code root}). A path that can't
     * be canonicalized is treated as unsafe.
     */
    private static boolean withinRoot(String rootCanon, File path) {
        String p;
        try {
            p = path.getCanonicalPath();
        } catch (IOException e) {
            return false;
        }
        return p.equals(rootCanon) || p.startsWith(rootCanon + File.separator);
    }

    /** Removes {@code file} only if it is itself a symlink (the link, not its target). */
    private static void deleteIfSymlink(File file) {
        try {
            if (OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode)) {
                file.delete();
            }
        } catch (ErrnoException ignored) {
            // doesn't exist (or can't stat) — nothing to remove
        }
    }

    /** Joins a tar path under root, rejecting absolute/".." escapes. */
    private static File resolve(File root, String name) {
        StringBuilder clean = new StringBuilder();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        if (clean.length() == 0) return root;
        return new File(root, clean.toString());
    }

    /** Best-effort: PRoot fakes root, so lost mode bits aren't fatal. */
    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode & 07777);
        } catch (ErrnoException ignored) {
        }
    }

    /** Bind-mount points plus network config the minimal rootfs lacks. */
    private static void writeGuestDefaults(File root) throws IOException {
        for (String dir : new String[] {"dev", "proc", "sys", "tmp", "root", "etc"}) {
            new File(root, dir).mkdirs();
        }
        chmod(new File(root, "tmp"), 01777);

        // Android has no /etc/resolv.conf to inherit; without one the guest
        // has no DNS. Replace whatever the rootfs shipped (often a dangling
        // systemd-resolved symlink).
        File resolv = new File(root, "etc/resolv.conf");
        resolv.delete();
        try (OutputStream out = new FileOutputStream(resolv)) {
            out.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
                    .getBytes(StandardCharsets.UTF_8));
        }

        File hosts = new File(root, "etc/hosts");
        if (!hosts.exists()) {
            try (OutputStream out = new FileOutputStream(hosts)) {
                out.write("127.0.0.1 localhost\n::1 localhost\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                // listFiles doesn't follow symlinks for listing, but delete
                // the link itself, never its target's contents.
                if (child.isDirectory() && !isSymlink(child)) {
                    deleteRecursively(child);
                } else {
                    child.delete();
                }
            }
        }
        file.delete();
    }

    private static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            return true; // can't tell — don't recurse into it
        }
    }
}
