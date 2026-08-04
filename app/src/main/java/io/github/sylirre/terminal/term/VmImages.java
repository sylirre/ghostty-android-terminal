/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The two files a guest machine boots from, bundled as APK assets.
 *
 * Like the rootfs tarballs, these are optional, gitignored build inputs
 * (fetched by {@code scripts/fetch-vm-images.sh} into {@code VmImages/} at the
 * repo root) — without them the app still builds and runs, and the VM session
 * type is simply unavailable:
 *
 * <ul>
 *   <li>{@code vm_firmware.fd} — EDK2 ArmVirtQemu, the guest's UEFI.</li>
 *   <li>{@code vm_*.iso} — a bootable aarch64 image, attached read-only.</li>
 * </ul>
 *
 * They must become real files before the emulator can open them: it takes
 * paths, and an APK asset is an offset inside a zip. So they are copied to
 * {@code filesDir/vm/} once, each through a staging name renamed into place
 * only when the copy completes — so a half-written image is never mistaken for
 * an installed one (the same atomic-publish rule {@link UserlandRootfs} uses).
 */
public final class VmImages {

    /** Fixed asset name of the firmware; the ISO is found by prefix. */
    private static final String FIRMWARE_ASSET = "vm_firmware.fd";
    private static final String IMAGE_PREFIX = "vm_";
    private static final String IMAGE_SUFFIX = ".iso";

    private VmImages() {}

    /** Where the extracted images live. */
    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "vm");
    }

    public static File firmware(Context ctx) {
        return new File(dir(ctx), FIRMWARE_ASSET);
    }

    /** The installed guest image, or null when none has been extracted. */
    public static File image(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().endsWith(IMAGE_SUFFIX) && f.isFile() && f.length() > 0) {
                return f;
            }
        }
        return null;
    }

    /** Asset name of the bundled guest image, or null if this build has none. */
    public static String bundledImageAsset(Context ctx) {
        try {
            String[] names = ctx.getAssets().list("");
            if (names == null) return null;
            for (String n : names) {
                if (n.startsWith(IMAGE_PREFIX) && n.endsWith(IMAGE_SUFFIX)) return n;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** True when this build carries both a firmware and a guest image. */
    public static boolean assetsAvailable(Context ctx) {
        return bundledImageAsset(ctx) != null && assetExists(ctx, FIRMWARE_ASSET);
    }

    /** True when both images are already extracted and usable. */
    public static boolean isInstalled(Context ctx) {
        File fw = firmware(ctx);
        return fw.isFile() && fw.length() > 0 && image(ctx) != null;
    }

    /** Reports extracted bytes against the total, for a determinate bar. */
    public interface ProgressListener {
        void onProgress(long copied, long total);
    }

    /**
     * Copies both assets into {@link #dir}. Idempotent — returns immediately
     * once installed. Blocking; call from a background thread.
     */
    public static synchronized void install(Context ctx, ProgressListener progress)
            throws IOException {
        if (isInstalled(ctx)) return;
        String imageAsset = bundledImageAsset(ctx);
        if (imageAsset == null) throw new IOException("no guest image bundled");

        File dir = dir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }

        long total = assetSize(ctx, FIRMWARE_ASSET) + assetSize(ctx, imageAsset);
        long[] done = { 0 };
        copyAsset(ctx, FIRMWARE_ASSET, new File(dir, FIRMWARE_ASSET), done, total, progress);
        copyAsset(ctx, imageAsset, new File(dir, imageAsset), done, total, progress);
    }

    /** Removes the extracted images, freeing the (considerable) space they take. */
    public static synchronized void uninstall(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) f.delete();
        dir(ctx).delete();
    }

    /** Bytes the extracted images occupy, for the settings screen. */
    public static long installedSize(Context ctx) {
        long n = 0;
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) n += f.length();
        return n;
    }

    private static boolean assetExists(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Uncompressed length of an asset. Both are stored undeflated (the ISO is
     * already compressed and the firmware is mostly zeroes but small), so the
     * descriptor's length is the real one.
     */
    private static long assetSize(Context ctx, String name) {
        try (AssetFileDescriptor fd = ctx.getAssets().openFd(name)) {
            return Math.max(0, fd.getLength());
        } catch (IOException e) {
            return 0;
        }
    }

    private static void copyAsset(Context ctx, String asset, File target, long[] done,
            long total, ProgressListener progress) throws IOException {
        if (target.isFile() && target.length() > 0) {
            done[0] += target.length();
            return;
        }
        File staging = new File(target.getParentFile(), target.getName() + ".tmp");
        staging.delete();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = ctx.getAssets().open(asset);
             OutputStream out = new FileOutputStream(staging)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done[0] += n;
                if (progress != null) progress.onProgress(done[0], total);
            }
        } catch (IOException e) {
            staging.delete();
            throw e;
        }
        if (!staging.renameTo(target)) {
            staging.delete();
            throw new IOException("cannot publish " + target);
        }
    }
}
