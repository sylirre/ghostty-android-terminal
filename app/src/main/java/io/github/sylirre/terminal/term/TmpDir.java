/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import java.io.File;

/**
 * The app's own temporary directory, {@code files/tmp}: TMPDIR for everything
 * a session spawns — the Android shell, both userland engines (arm64chroot,
 * chroot-ng), and the guest machine's emulator.
 *
 * Mode 0700 is the point. Android creates {@code files/} group-writable (0771),
 * and chroot-ng will not keep private state in a directory anyone but its owner
 * can write to without the sticky bit ({@code shm_parent_safe} in
 * native/chroot-ng/src/path/path.c). With {@code files/} itself as TMPDIR, its
 * guest got no {@code /dev/shm} stand-in, so {@code shm_open}/{@code sem_open}
 * had nowhere to go.
 *
 * Under {@code files/} rather than the cache dir, because what lives here must
 * outlast storage pressure while a session runs: the {@code --shared-proc}
 * registry every tab of a rootfs joins, the guest's {@code /dev/shm} objects,
 * arm64chroot's emulated tmpfs mounts. The system clears cache dirs when space
 * runs low, whether the app is running or not.
 */
final class TmpDir {
    private static final String TAG = "TmpDir";

    private TmpDir() {}

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "tmp");
    }

    /**
     * Makes {@link #dir} a directory with mode 0700 and returns its absolute
     * path. Called on every spawn, so a TMPDIR removed from a shell comes back
     * with the next session.
     *
     * Best effort: a failure is logged and the path returned anyway. Every
     * consumer checks the directory itself (both engines fall back to their
     * next candidate), and the plain shell has to open even on a full disk —
     * freeing space may be what it was opened for.
     */
    static String ensure(Context ctx) {
        String path = dir(ctx).getAbsolutePath();
        try {
            StructStat st = lstat(path);
            if (st != null && !OsConstants.S_ISDIR(st.st_mode)) {
                Os.remove(path); // a file or symlink in its place
                st = null;
            }
            if (st == null) Os.mkdir(path, 0700);
            // An existing directory may carry any mode; set it outright.
            Os.chmod(path, 0700);
        } catch (ErrnoException e) {
            Log.w(TAG, "cannot prepare " + path, e);
        }
        return path;
    }

    private static StructStat lstat(String path) throws ErrnoException {
        try {
            return Os.lstat(path);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return null;
            throw e;
        }
    }
}
