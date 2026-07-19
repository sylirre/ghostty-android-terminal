/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.SparseIntArray;
import android.widget.TextView;

import io.github.sylirre.terminal.R;

/**
 * Renders symbol glyphs on buttons as bundled vector drawables instead of font
 * glyphs, so arrows and similar symbols look identical on every device (the
 * system font is no longer involved) — see docs/architecture.md.
 *
 * Buttons stay plain {@link TextView}s: {@link #apply} scans a label for known
 * symbol codepoints and swaps each for a tinted {@link ImageSpan} backed by a
 * vector, leaving every other character (letters, digits, ASCII punctuation) as
 * text. This keeps composite labels working — a combo like {@code "CTRL ◀"}
 * renders the modifier as text and the arrow as an icon — and is
 * self-falling-back: an unmapped glyph is left as the font glyph it was before.
 *
 * No PNG fallback is needed: minSdk 29 guarantees framework VectorDrawable, so
 * {@link Context#getDrawable} always returns a working vector.
 */
final class Glyphs {

    private Glyphs() {}

    /**
     * Icons render this much larger than the button text so symbols read as
     * prominent glyphs rather than at letter cap-height. It only controls glyph
     * prominence, not button height: {@link CenteredIconSpan} centers the icon
     * in the text's own line box and never lets that box shrink, so a glyph-only
     * button stays exactly as tall as a text button.
     */
    private static final float ICON_SCALE = 1.1f;

    /** Symbol codepoint → vector drawable. Both arrow styles map to one icon set. */
    private static final SparseIntArray MAP = new SparseIntArray();
    static {
        MAP.put(0x25B2, R.drawable.ic_glyph_arrow_up);     // ▲
        MAP.put(0x2191, R.drawable.ic_glyph_arrow_up);     // ↑
        MAP.put(0x25BC, R.drawable.ic_glyph_arrow_down);   // ▼
        MAP.put(0x2193, R.drawable.ic_glyph_arrow_down);   // ↓
        MAP.put(0x25C0, R.drawable.ic_glyph_arrow_left);   // ◀
        MAP.put(0x2190, R.drawable.ic_glyph_arrow_left);   // ←
        MAP.put(0x25B6, R.drawable.ic_glyph_arrow_right);  // ▶
        MAP.put(0x2192, R.drawable.ic_glyph_arrow_right);  // →
        MAP.put(0x21B5, R.drawable.ic_glyph_enter);        // ↵
        MAP.put(0x232B, R.drawable.ic_glyph_backspace);    // ⌫
        MAP.put(0x2500, R.drawable.ic_glyph_dash);         // ─ (dash key label)
        MAP.put(0x2715, R.drawable.ic_glyph_close);        // ✕
        MAP.put(0x00D7, R.drawable.ic_glyph_close);        // ×
        MAP.put(0x2630, R.drawable.ic_glyph_drag);         // ☰
        MAP.put(0x1F50D, R.drawable.ic_glyph_search);      // 🔍 (surrogate pair)
        MAP.put(0x2699, R.drawable.ic_glyph_settings);     // ⚙
    }

    /**
     * Returns {@code label} with every mapped symbol codepoint replaced by a
     * vector icon, sized to {@code sizePx} (square) and tinted to {@code color}.
     * Returns the original instance unchanged when nothing matched.
     */
    static CharSequence apply(Context ctx, CharSequence label, float sizePx, int color) {
        if (label == null || label.length() == 0) return label;
        SpannableStringBuilder out = null;  // built lazily on first match
        int size = Math.max(1, Math.round(sizePx * ICON_SCALE));
        for (int i = 0; i < label.length(); ) {
            int cp = Character.codePointAt(label, i);
            int len = Character.charCount(cp);
            int res = MAP.get(cp, 0);
            if (res != 0) {
                Drawable d = ctx.getDrawable(res);
                if (d != null) {
                    d = d.mutate();
                    d.setTint(color);
                    d.setBounds(0, 0, size, size);
                    if (out == null) out = new SpannableStringBuilder(label);
                    out.setSpan(new CenteredIconSpan(d),
                            i, i + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            i += len;
        }
        return out != null ? out : label;
    }

    /** Re-spans a {@link TextView}'s current text using its own size and color. */
    static void applyTo(TextView tv) {
        CharSequence text = tv.getText();
        CharSequence spanned = apply(tv.getContext(), text, tv.getTextSize(), tv.getCurrentTextColor());
        if (spanned != text) tv.setText(spanned);
    }

    /**
     * A centered {@link ImageSpan} that never reports a line shorter than the
     * font. Stock {@code ALIGN_CENTER} overrides the line's ascent/descent with
     * the drawable's own height; since our icons are sized just under the font's
     * line box, a glyph-only label (e.g. an arrow key or the tab ✕) measured
     * shorter than a text one, leaving its button visibly stunted. This reports
     * the union of the font box and the centered-icon box, so glyph-only buttons
     * match text buttons while a taller-than-font icon still grows the line.
     */
    private static final class CenteredIconSpan extends ImageSpan {
        CenteredIconSpan(Drawable d) {
            super(d, ALIGN_CENTER);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            Rect b = getDrawable().getBounds();
            if (fm != null) {
                Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                int center = (pfm.ascent + pfm.descent) / 2;  // text mid, vs. baseline
                int half = b.height() / 2;
                fm.ascent = Math.min(pfm.ascent, center - half);
                fm.descent = Math.max(pfm.descent, center + half);
                fm.top = Math.min(pfm.top, fm.ascent);
                fm.bottom = Math.max(pfm.bottom, fm.descent);
            }
            return b.right;
        }
    }
}
