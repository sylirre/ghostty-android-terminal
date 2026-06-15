package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * A non-interactive sample of a {@link TerminalTheme}, drawn the way
 * {@link TerminalView} draws real output (monospace glyphs on the theme
 * background) so the editor shows what a theme actually looks like. It draws a
 * mock shell prompt, a colored file listing, error/warning lines, a block
 * cursor in the cursor color, and a strip of all 16 ANSI swatches.
 *
 * Purely self-contained — it needs no terminal engine. Call {@link #setTheme}
 * after every edit to repaint.
 */
public final class ThemePreviewView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final Paint bgImagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bgSrc = new Rect();
    private final Rect bgDst = new Rect();
    private TerminalTheme theme;
    private Bitmap backgroundImage;
    private int backgroundImageAlpha = 255;

    public ThemePreviewView(Context context) {
        this(context, null);
    }

    public ThemePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        theme = ThemePresets.DEFAULT;
    }

    public void setTheme(TerminalTheme theme) {
        this.theme = theme;
        invalidate();
    }

    /**
     * Mirrors the terminal wallpaper in the preview so the opacity slider has
     * live feedback. The bitmap is owned by the caller ({@link ThemeActivity});
     * this view only references it. Pass null to show no wallpaper.
     */
    public void setBackgroundImage(Bitmap bmp, int alpha) {
        this.backgroundImage = bmp;
        this.backgroundImageAlpha = alpha;
        invalidate();
    }

    /** One colored, optionally-bold run of text on a preview line. */
    private static final class Seg {
        final String text;
        final int color;
        final boolean bold;
        Seg(String text, int color, boolean bold) {
            this.text = text;
            this.color = color;
            this.bold = bold;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (theme == null) return;
        canvas.drawColor(theme.background);
        drawBackgroundImage(canvas);

        int fg = theme.foreground;
        int[] a = theme.ansi;
        // ANSI indices: 1 red, 2 green, 3 yellow, 4 blue, 5 magenta, 6 cyan.
        Seg[][] lines = {
            { new Seg("user", a[2], true), new Seg("@", fg, false),
              new Seg("debian", a[2], true), new Seg(":", fg, false),
              new Seg("~/src", a[4], true), new Seg("$ ", fg, false),
              new Seg("ls --color", fg, false) },
            { new Seg("Desktop  ", a[4], true), new Seg("photo.jpg  ", a[5], false),
              new Seg("build.sh  ", a[2], false), new Seg("README.md", fg, false) },
            { new Seg("error: ", a[1], true), new Seg("file not found", fg, false) },
            { new Seg("warning: ", a[3], false), new Seg("deprecated call", fg, false) },
        };

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent) * 1.15f;
        float pad = dp(10);
        float charW = textPaint.measureText("M");
        float y = pad - fm.ascent;

        for (Seg[] line : lines) {
            float x = pad;
            for (Seg s : line) {
                textPaint.setColor(s.color);
                textPaint.setFakeBoldText(s.bold);
                canvas.drawText(s.text, x, y, textPaint);
                x += textPaint.measureText(s.text);
            }
            // A block cursor trailing the prompt line, in the cursor color.
            if (line == lines[0]) {
                fillPaint.setColor(theme.cursor);
                canvas.drawRect(x, y + fm.ascent, x + charW, y + fm.descent, fillPaint);
            }
            y += lineHeight;
        }

        // ANSI palette strip: 16 swatches across the width.
        textPaint.setFakeBoldText(false);
        y += dp(6);
        float stripH = dp(18);
        float available = getWidth() - 2 * pad;
        float sw = available / 16f;
        for (int i = 0; i < 16; i++) {
            fillPaint.setColor(a[i]);
            float left = pad + i * sw;
            canvas.drawRect(left, y, left + sw - dp(1), y + stripH, fillPaint);
        }
    }

    /** Center-cropped wallpaper fill, matching {@link TerminalView}. */
    private void drawBackgroundImage(Canvas canvas) {
        Bitmap bmp = backgroundImage;
        int vw = getWidth(), vh = getHeight();
        if (bmp == null || vw <= 0 || vh <= 0) return;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) return;
        if ((long) bw * vh > (long) vw * bh) {
            int cropW = Math.round(bh * (vw / (float) vh));
            int x = (bw - cropW) / 2;
            bgSrc.set(x, 0, x + cropW, bh);
        } else {
            int cropH = Math.round(bw * (vh / (float) vw));
            int y = (bh - cropH) / 2;
            bgSrc.set(0, y, bw, y + cropH);
        }
        bgDst.set(0, 0, vw, vh);
        bgImagePaint.setAlpha(backgroundImageAlpha);
        canvas.drawBitmap(bmp, bgSrc, bgDst, bgImagePaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
