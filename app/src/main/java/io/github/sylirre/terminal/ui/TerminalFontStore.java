/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Imports terminal font files into app-private storage so document-provider
 * permissions do not need to survive process death.
 */
final class TerminalFontStore {

    static final int DEFAULT = 0;
    static final int ITALIC = 1;
    static final int BOLD = 2;
    static final int BOLD_ITALIC = 3;

    private static final String DEFAULT_FILE = "terminal_font_default";
    private static final String ITALIC_FILE = "terminal_font_italic";
    private static final String BOLD_FILE = "terminal_font_bold";
    private static final String BOLD_ITALIC_FILE = "terminal_font_bold_italic";

    private TerminalFontStore() {}

    static File file(Context context, int kind) {
        String name;
        switch (kind) {
            case ITALIC:
                name = ITALIC_FILE;
                break;
            case BOLD:
                name = BOLD_FILE;
                break;
            case BOLD_ITALIC:
                name = BOLD_ITALIC_FILE;
                break;
            default:
                name = DEFAULT_FILE;
                break;
        }
        return new File(context.getFilesDir(), name);
    }

    /**
     * Copies and validates a picked font. Returns the stored absolute path, or
     * null if Android cannot load the bytes as a Typeface.
     */
    static String importFrom(Context context, Uri src, int kind) throws IOException {
        File dst = file(context, kind);
        File tmp = new File(context.getFilesDir(), dst.getName() + ".tmp");
        boolean stored = false;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(src)) {
                if (in == null) return null;
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }

            if (!canLoad(tmp.getPath())) return null;
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
            if (!tmp.renameTo(dst)) {
                throw new IOException("Could not store font");
            }
            stored = true;
            return dst.getPath();
        } finally {
            // Anything short of the rename leaves the staged copy behind: a
            // read or write that failed mid-copy, a file no Typeface will load,
            // or a rename that didn't take. Nothing else ever sweeps filesDir,
            // so a rejected import used to strand a font-sized file there for
            // good. Only the rename hands `tmp` over; every other exit deletes it.
            // noinspection ResultOfMethodCallIgnored
            if (!stored) tmp.delete();
        }
    }

    static Typeface load(String path) {
        if (path == null) return null;
        try {
            return Typeface.createFromFile(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void clear(Context context, int kind) {
        File f = file(context, kind);
        // noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean canLoad(String path) {
        return load(path) != null;
    }
}
