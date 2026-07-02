package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import sh.easycli.proot.term.TerminalNative;

/**
 * A non-interactive sample of a {@link TerminalTheme}, drawn the way
 * {@link TerminalView} draws real output (monospace glyphs on the theme
 * background) so the editor shows what a theme actually looks like. It draws a
 * mock shell prompt, a colored file listing, error/warning lines, a cursor in
 * the cursor color (shape and blink mirror the global cursor setting), and a
 * strip of all 16 ANSI swatches.
 *
 * Purely self-contained — it needs no terminal engine. Call {@link #setTheme}
 * after every edit to repaint.
 */
public final class ThemePreviewView extends View {

    /** Blink half-period (ms); the cursor toggles on/off at this cadence. */
    private static final long BLINK_MS = 530;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final Paint bgImagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bgSrc = new Rect();
    private final Rect bgDst = new Rect();
    private TerminalTheme theme;
    private Bitmap backgroundImage;
    private int backgroundImageAlpha = 255;

    // Custom default/bold/italic/bold-italic fonts (mirrors
    // TerminalView.setFontFiles); FontStyle resolves the fallback chain for
    // whichever roles are null the same way TerminalView does.
    private Typeface defaultTypeface = Typeface.MONOSPACE;
    private Typeface boldTypeface;
    private Typeface italicTypeface;
    private Typeface boldItalicTypeface;

    private int cursorStyle = TerminalNative.CURSOR_BLOCK;
    private boolean cursorBlink;
    // Current blink phase; always true (cursor shown) when blink is off.
    private boolean blinkOn = true;
    private final Runnable blinkTick = new Runnable() {
        @Override
        public void run() {
            blinkOn = !blinkOn;
            invalidate();
            postDelayed(this, BLINK_MS);
        }
    };

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
     * Sets the fonts to preview, mirroring {@link TerminalView#setFontFiles}.
     * {@code defaultTf} is used for regular text; {@code boldTf}/{@code
     * italicTf}/{@code boldItalicTf} may be null, meaning that role falls
     * back through {@link FontStyle}'s faux-style chain, matching the
     * terminal's own fallback.
     */
    public void setFont(Typeface defaultTf, Typeface boldTf, Typeface italicTf,
            Typeface boldItalicTf) {
        this.defaultTypeface = defaultTf != null ? defaultTf : Typeface.MONOSPACE;
        this.boldTypeface = boldTf;
        this.italicTypeface = italicTf;
        this.boldItalicTypeface = boldItalicTf;
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

    /**
     * Sets the cursor shape ({@link TerminalNative}.CURSOR_*) and whether it
     * blinks, so the preview matches the global cursor setting. Blinking is
     * animated for live feedback while this view is attached.
     */
    public void setCursor(int style, boolean blink) {
        this.cursorStyle = style;
        this.cursorBlink = blink;
        this.blinkOn = true;
        updateBlinkAnimation();
        invalidate();
    }

    /** Starts the blink loop when blinking and attached; stops it otherwise. */
    private void updateBlinkAnimation() {
        removeCallbacks(blinkTick);
        if (cursorBlink && isAttachedToWindow()) {
            postDelayed(blinkTick, BLINK_MS);
        } else {
            blinkOn = true; // a steady cursor is always shown
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateBlinkAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(blinkTick);
    }

    /** One colored, optionally bold/italic run of text on a preview line. */
    private static final class Seg {
        final String text;
        final int color;
        final boolean bold;
        final boolean italic;
        Seg(String text, int color, boolean bold, boolean italic) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
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
            { new Seg("user", a[2], true, false), new Seg("@", fg, false, false),
              new Seg("debian", a[2], true, false), new Seg(":", fg, false, false),
              new Seg("~/src", a[4], true, false), new Seg("$ ", fg, false, false),
              new Seg("ls --color", fg, false, false) },
            { new Seg("Desktop  ", a[4], true, false), new Seg("photo.jpg  ", a[5], false, false),
              new Seg("build.sh  ", a[2], false, false), new Seg("README.md", fg, false, false) },
            { new Seg("error: ", a[1], true, false), new Seg("file not found", fg, false, false) },
            { new Seg("warning: ", a[3], false, true), new Seg("deprecated call", fg, false, true) },
            { new Seg("note: ", a[6], true, true), new Seg("bold italic sample", fg, true, true) },
        };

        textPaint.setTypeface(defaultTypeface);
        textPaint.setTextSkewX(0);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent) * 1.15f;
        float pad = dp(10);
        float charW = textPaint.measureText("M");
        float y = pad - fm.ascent;

        for (Seg[] line : lines) {
            float x = pad;
            for (Seg s : line) {
                textPaint.setColor(s.color);
                FontStyle fs = FontStyle.select(defaultTypeface, boldTypeface, italicTypeface,
                        boldItalicTypeface, s.bold, s.italic);
                textPaint.setTypeface(fs.typeface);
                textPaint.setFakeBoldText(fs.fakeBold);
                textPaint.setTextSkewX(fs.fakeItalic ? -0.25f : 0);
                canvas.drawText(s.text, x, y, textPaint);
                x += textPaint.measureText(s.text);
            }
            // A cursor trailing the prompt line, in the cursor color; its shape
            // and blink mirror the global cursor setting.
            if (line == lines[0]) {
                drawCursor(canvas, x, y + fm.ascent, y + fm.descent, charW);
            }
            y += lineHeight;
        }

        // ANSI palette strip: 16 swatches across the width. Reset the text
        // state the loop above may have left on an italic run.
        textPaint.setFakeBoldText(false);
        textPaint.setTypeface(defaultTypeface);
        textPaint.setTextSkewX(0);
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

    /**
     * Draws the cursor in one cell (left..left+width, top..bottom). Bar and
     * underline use the same proportions as {@link TerminalView}; while
     * blinking, nothing is drawn on the "off" phase.
     */
    private void drawCursor(Canvas canvas, float left, float top, float bottom, float width) {
        if (cursorBlink && !blinkOn) return;
        fillPaint.setColor(theme.cursor);
        switch (cursorStyle) {
            case TerminalNative.CURSOR_BAR:
                canvas.drawRect(left, top, left + Math.max(1f, width / 4f), bottom, fillPaint);
                break;
            case TerminalNative.CURSOR_UNDERLINE:
                canvas.drawRect(left, bottom - Math.max(1f, (bottom - top) / 8f),
                        left + width, bottom, fillPaint);
                break;
            default: // block
                canvas.drawRect(left, top, left + width, bottom, fillPaint);
                break;
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
