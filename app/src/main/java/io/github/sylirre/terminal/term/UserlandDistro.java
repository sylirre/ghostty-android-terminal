/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A Linux distribution rootfs bundled in the APK, discovered by asset file
 * name. The build scripts (scripts/build-*-rootfs.sh) drop tarballs named
 * {@code <id>_<version>_aarch64_rootfs.tar.xz} into {@code UserlandRootfs/}
 * at the repo root; whatever is present at build time rides along as assets,
 * and this class is the single place that maps those file names back to
 * choosable distributions (the onboarding chooser, install entry points).
 *
 * Only aarch64 rootfs are ever packaged — arm64chroot emulates an AArch64
 * guest on every host ABI — so the name pattern hard-codes the arch and
 * anything else is ignored.
 */
public final class UserlandDistro {

    /** Asset name suffix every bundled rootfs tarball must carry. */
    private static final String SUFFIX = "_aarch64_rootfs.tar.xz";

    /** Chooser/display order for the ids we ship; unknown ids sort after, alphabetically. */
    private static final List<String> KNOWN_ORDER = Arrays.asList("alpine", "debian");

    /** Distribution id — the file-name token before the first underscore, e.g. "alpine". */
    public final String id;

    /** Version/release token(s) from the file name, e.g. "3.22.2" or "trixie"; may be "". */
    public final String version;

    /** Full asset file name, the handle {@link UserlandRootfs#install} takes. */
    public final String assetName;

    /** Compressed asset size in bytes, or 0 when it could not be determined. */
    public final long sizeBytes;

    private UserlandDistro(String id, String version, String assetName, long sizeBytes) {
        this.id = id;
        this.version = version;
        this.assetName = assetName;
        this.sizeBytes = sizeBytes;
    }

    /**
     * Parses a rootfs asset file name, or returns null when it doesn't match
     * the {@code <id>_<version>_aarch64_rootfs.tar.xz} pattern (a bare
     * {@code <id>} with no version is accepted too). Package-private for tests.
     */
    static UserlandDistro fromAssetName(String assetName, long sizeBytes) {
        if (assetName == null || !assetName.endsWith(SUFFIX)) return null;
        String stem = assetName.substring(0, assetName.length() - SUFFIX.length());
        if (stem.isEmpty()) return null;
        int underscore = stem.indexOf('_');
        String id = underscore < 0 ? stem : stem.substring(0, underscore);
        String version = underscore < 0 ? "" : stem.substring(underscore + 1);
        if (id.isEmpty()) return null;
        return new UserlandDistro(id, version, assetName, sizeBytes);
    }

    /**
     * All rootfs tarballs bundled in this build, in chooser order
     * ({@link #KNOWN_ORDER} first, then any others alphabetically). Empty when
     * the build was made without tarballs in {@code UserlandRootfs/} (the CI
     * case) — the app then offers only the plain Android shell.
     */
    public static List<UserlandDistro> bundled(Context ctx) {
        List<UserlandDistro> distros = new ArrayList<>();
        String[] assets;
        try {
            assets = ctx.getAssets().list("");
        } catch (IOException e) {
            return distros;
        }
        if (assets == null) return distros;
        for (String name : assets) {
            UserlandDistro d = fromAssetName(name, assetSize(ctx, name));
            if (d != null) distros.add(d);
        }
        distros.sort(Comparator
                .comparingInt((UserlandDistro d) -> {
                    int known = KNOWN_ORDER.indexOf(d.id);
                    return known < 0 ? KNOWN_ORDER.size() : known;
                })
                .thenComparing(d -> d.id)
                .thenComparing(d -> d.version));
        return distros;
    }

    /** The bundled distro whose asset is {@code assetName}, or null. */
    public static UserlandDistro bundledByAsset(Context ctx, String assetName) {
        if (assetName == null) return null;
        for (UserlandDistro d : bundled(ctx)) {
            if (d.assetName.equals(assetName)) return d;
        }
        return null;
    }

    /**
     * Compressed size of an asset. Works because rootfs tarballs are stored
     * uncompressed in the APK ({@code noCompress 'xz'} — they are already xz);
     * a compressed asset has no seekable fd and reports 0 instead.
     */
    private static long assetSize(Context ctx, String name) {
        try (AssetFileDescriptor fd = ctx.getAssets().openFd(name)) {
            return Math.max(0, fd.getLength());
        } catch (IOException e) {
            return 0;
        }
    }
}
