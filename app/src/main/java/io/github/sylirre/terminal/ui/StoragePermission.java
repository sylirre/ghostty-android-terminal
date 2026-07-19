/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

/**
 * Whether the app may bind Android shared storage into userland sessions. The
 * check is shared by {@link MainActivity} (before opening a session) and
 * {@link SettingsActivity} (which hosts the toggle and its permission request),
 * so it lives here rather than being duplicated.
 */
final class StoragePermission {

    private StoragePermission() {}

    static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }
}
