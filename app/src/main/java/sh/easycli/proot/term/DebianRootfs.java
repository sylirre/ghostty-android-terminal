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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    /** Rootfs root, bound as guest "/" — filesDir/debian. */
    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "debian");
    }

    /** Marker lives next to the rootfs so the guest never sees it. */
    private static File marker(Context ctx) {
        return new File(ctx.getFilesDir(), "debian.installed");
    }

    public static boolean isInstalled(Context ctx) {
        return marker(ctx).exists();
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
     * immediately when already installed; a partial install (no marker) is
     * wiped and redone. Blocking — call from a background thread.
     */
    public static synchronized void install(Context ctx, ProgressListener progress)
            throws IOException {
        if (isInstalled(ctx)) return;
        String asset = assetName();
        if (asset == null) throw new IOException("no Debian rootfs for ABI "
                + Build.SUPPORTED_ABIS[0]);

        File root = dir(ctx);
        deleteRecursively(root);
        if (!root.mkdirs()) throw new IOException("cannot create " + root);

        try (InputStream raw = ctx.getAssets().open(asset);
                XZInputStream xz = new XZInputStream(new BufferedInputStream(raw, 1 << 16))) {
            extractTar(xz, root, progress);
        }

        writeGuestDefaults(root);
        if (!marker(ctx).createNewFile()) throw new IOException("cannot create marker");
    }

    /**
     * PRoot command for a Debian login shell. Requires an installed rootfs
     * and the loader executable that APK packaging extracted into
     * nativeLibraryDir (its only exec-allowed location under W^X).
     */
    public static SessionCommand command(Context ctx) throws IOException {
        if (!isInstalled(ctx)) throw new IOException("Debian rootfs not installed");
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
                "/bin/bash", "--login",
        };
        String[] env = {
                "PROOT_LOADER=" + loader.getAbsolutePath(),
                "PROOT_TMP_DIR=" + tmp.getAbsolutePath(),
                "PATH=/system/bin",
                "HOME=" + ctx.getFilesDir().getAbsolutePath(),
        };
        return new SessionCommand(null, argv, env,
                ctx.getFilesDir().getAbsolutePath(), "deb");
    }

    // --- tar extraction ---

    private static final int BLOCK = 512;

    private static void extractTar(InputStream in, File root, ProgressListener progress)
            throws IOException {
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
                    if (parent != null) parent.mkdirs();
                    try (OutputStream out = new FileOutputStream(target)) {
                        long left = size;
                        while (left > 0) {
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
                    target.mkdirs();
                    dirFiles.add(target);
                    dirModes.add(mode);
                    skip(in, padded(size));
                    break;
                case '2': { // symlink
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
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
                    if (source == null || !source.isFile()) {
                        throw new IOException("hard link source missing: " + linkName);
                    }
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
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
