package sh.easycli.proot.ui;

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

    private static final String DEFAULT_FILE = "terminal_font_default";
    private static final String ITALIC_FILE = "terminal_font_italic";

    private TerminalFontStore() {}

    static File file(Context context, int kind) {
        return new File(context.getFilesDir(), kind == ITALIC ? ITALIC_FILE : DEFAULT_FILE);
    }

    /**
     * Copies and validates a picked font. Returns the stored absolute path, or
     * null if Android cannot load the bytes as a Typeface.
     */
    static String importFrom(Context context, Uri src, int kind) throws IOException {
        File dst = file(context, kind);
        File tmp = new File(context.getFilesDir(), dst.getName() + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(src)) {
            if (in == null) return null;
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }

        if (!canLoad(tmp.getPath())) {
            // noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
        // noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (!tmp.renameTo(dst)) {
            throw new IOException("Could not store font");
        }
        return dst.getPath();
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
