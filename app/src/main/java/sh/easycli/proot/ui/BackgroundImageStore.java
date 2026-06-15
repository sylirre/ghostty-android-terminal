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

    // Blur tuning. The blur is run on a copy no larger than BLUR_MAX_DIM on its
    // longest side (it is upscaled when drawn, and blur discards detail anyway),
    // which keeps the pixel passes and their temporary buffers cheap. At 100%
    // the radius is BLUR_MAX_FRACTION of the working bitmap's shorter side;
    // BLUR_PASSES box-blur iterations approximate a Gaussian.
    private static final int BLUR_MAX_DIM = 1080;
    private static final float BLUR_MAX_FRACTION = 0.05f;
    private static final int BLUR_PASSES = 2;

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
     * then blurs it by {@code blurPercent} (0–100; 0 leaves it sharp). Returns
     * null if the file is missing or undecodable.
     */
    static Bitmap decode(String path, int reqW, int reqH, int blurPercent) {
        if (path == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
                Math.max(1, reqW), Math.max(1, reqH));
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null || blurPercent <= 0) return bmp;
        return blur(bmp, reqW, reqH, blurPercent);
    }

    private static int sampleSize(int w, int h, int reqW, int reqH) {
        int sample = 1;
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Returns a blurred copy of {@code src} (recycling {@code src} once it is no
     * longer needed) sized for at most {@code reqW}×{@code reqH}. The blur runs
     * as repeated box blurs over the pixel buffer, which is portable across all
     * supported API levels (no RenderScript/RenderEffect version split).
     */
    private static Bitmap blur(Bitmap src, int reqW, int reqH, int percent) {
        int cap = Math.min(Math.max(reqW, reqH), BLUR_MAX_DIM);
        Bitmap work = scaleDown(src, cap); // recycles src if it shrinks it
        int w = work.getWidth(), h = work.getHeight();
        int radius = Math.round(percent / 100f * BLUR_MAX_FRACTION * Math.min(w, h));
        if (radius < 1) return work;

        int[] a = new int[w * h];
        int[] b = new int[w * h];
        work.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < BLUR_PASSES; pass++) {
            boxBlur(a, b, w, h, radius, true);  // horizontal: a -> b
            boxBlur(b, a, w, h, radius, false); // vertical:   b -> a
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        work.recycle();
        return out;
    }

    /** Scales {@code src} so its longest side is at most {@code cap}, recycling
     *  the original; returns it unchanged when already within {@code cap}. */
    private static Bitmap scaleDown(Bitmap src, int cap) {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= cap) return src;
        float s = cap / (float) longest;
        Bitmap scaled = Bitmap.createScaledBitmap(
                src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    /**
     * One box-blur pass over ARGB pixels with a sliding window of width
     * {@code 2*radius+1}, edges extended by clamping. Reads {@code in}, writes
     * {@code out}; {@code horizontal} selects the axis.
     */
    private static void boxBlur(int[] in, int[] out, int w, int h, int radius,
            boolean horizontal) {
        int div = 2 * radius + 1;
        int lines = horizontal ? h : w;
        int span = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int d = -radius; d <= radius; d++) {
                int p = in[base + clamp(d, span) * step];
                sa += p >>> 24; sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int i = 0; i < span; i++) {
                out[base + i * step] = ((sa / div) << 24) | ((sr / div) << 16)
                        | ((sg / div) << 8) | (sb / div);
                int pa = in[base + clamp(i + radius + 1, span) * step];
                int ps = in[base + clamp(i - radius, span) * step];
                sa += (pa >>> 24) - (ps >>> 24);
                sr += ((pa >> 16) & 0xFF) - ((ps >> 16) & 0xFF);
                sg += ((pa >> 8) & 0xFF) - ((ps >> 8) & 0xFF);
                sb += (pa & 0xFF) - (ps & 0xFF);
            }
        }
    }

    private static int clamp(int i, int span) {
        return i < 0 ? 0 : (i >= span ? span - 1 : i);
    }
}
