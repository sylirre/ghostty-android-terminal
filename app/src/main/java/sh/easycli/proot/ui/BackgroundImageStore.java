package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * File lifecycle for the terminal background image.
 *
 * The system image picker hands back a transient {@code content://} URI whose
 * read permission does not survive the process, so {@link #importFrom} copies
 * the picked bytes into a fixed file in app-internal storage; that path is what
 * {@link AppSettings#backgroundImagePath} persists. {@link #decode} reads the
 * copy back as a downsampled {@link Bitmap} sized for the screen — a full-
 * resolution photo would dwarf the viewport and risk {@code OutOfMemoryError}.
 */
final class BackgroundImageStore {

    private static final String FILE = "terminal_background";

    private BackgroundImageStore() {}

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    /**
     * Copies the picked image into app storage, replacing any previous one.
     * Returns the stored absolute path on success, or null if the source could
     * not be read.
     */
    static String importFrom(Context context, Uri src) throws IOException {
        File dst = file(context);
        try (InputStream in = context.getContentResolver().openInputStream(src)) {
            if (in == null) return null;
            try (OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return dst.getPath();
    }

    /** Removes the stored wallpaper, if any. */
    static void clear(Context context) {
        // noinspection ResultOfMethodCallIgnored
        file(context).delete();
    }

    /**
     * Decodes the stored image downsampled so its dimensions stay at or above
     * {@code reqW}×{@code reqH} (a power-of-two subsample, the cheapest path),
     * or null if the file is missing or undecodable.
     */
    static Bitmap decode(String path, int reqW, int reqH) {
        if (path == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
                Math.max(1, reqW), Math.max(1, reqH));
        return BitmapFactory.decodeFile(path, opts);
    }

    private static int sampleSize(int w, int h, int reqW, int reqH) {
        int sample = 1;
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) {
            sample *= 2;
        }
        return sample;
    }
}
