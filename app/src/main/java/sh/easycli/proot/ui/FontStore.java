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
 * File lifecycle for the terminal's custom default/italic font files.
 *
 * The system file picker hands back a transient {@code content://} URI whose
 * read permission does not survive the process, so {@link #importFrom} copies
 * the picked bytes into a fixed file per slot in app-internal storage; that
 * path is what {@link AppSettings#customFontDefaultPath}/
 * {@link AppSettings#customFontItalicPath} persist. Unlike an image (which
 * {@link android.graphics.BitmapFactory} incidentally validates on decode), a
 * font file has no other validation step before {@link TerminalView} tries to
 * use it for live rendering, so the import is rejected up front if it doesn't
 * parse as a font.
 */
final class FontStore {

    static final String SLOT_DEFAULT = "terminal_font_default";
    static final String SLOT_ITALIC = "terminal_font_italic";

    private FontStore() {}

    static File file(Context context, String slot) {
        return new File(context.getFilesDir(), slot);
    }

    /**
     * Copies the picked font into app storage under {@code slot}, replacing
     * any previous file there, then validates it actually parses as a font.
     * Returns the stored absolute path on success; on any failure (unreadable
     * source, invalid font) the file is deleted and null is returned.
     */
    static String importFrom(Context context, Uri src, String slot) throws IOException {
        File dst = file(context, slot);
        try (InputStream in = context.getContentResolver().openInputStream(src)) {
            if (in == null) return null;
            try (OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        if (!isValidFont(dst)) {
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
            return null;
        }
        return dst.getPath();
    }

    /** Removes the stored font for this slot, if any. */
    static void clear(Context context, String slot) {
        // noinspection ResultOfMethodCallIgnored
        file(context, slot).delete();
    }

    private static boolean isValidFont(File f) {
        try {
            Typeface.createFromFile(f);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
