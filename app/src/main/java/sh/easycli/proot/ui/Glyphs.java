package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.SparseIntArray;
import android.widget.TextView;

import sh.easycli.proot.R;

/**
 * Renders symbol glyphs on buttons as bundled vector drawables instead of font
 * glyphs, so arrows and similar symbols look identical on every device (the
 * system font is no longer involved) — see docs/architecture.md.
 *
 * Buttons stay plain {@link TextView}s: {@link #apply} scans a label for known
 * symbol codepoints and swaps each for a tinted {@link ImageSpan} backed by a
 * vector, leaving every other character (letters, digits, ASCII punctuation) as
 * text. This keeps composite labels working — a combo like {@code "^◀"} renders
 * the caret as text and the arrow as an icon — and is self-falling-back: an
 * unmapped glyph is left as the font glyph it was before.
 *
 * No PNG fallback is needed: minSdk 29 guarantees framework VectorDrawable, so
 * {@link Context#getDrawable} always returns a working vector.
 */
final class Glyphs {

    private Glyphs() {}

    /**
     * Icons render this much taller than the button text so symbols read as
     * prominent glyphs rather than at letter cap-height. ImageSpan.ALIGN_CENTER
     * grows the line to fit, so the only effect is marginally taller buttons.
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
        MAP.put(0x2325, R.drawable.ic_glyph_alt);          // ⌥
        MAP.put(0x21E7, R.drawable.ic_glyph_shift);        // ⇧
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
                    out.setSpan(new ImageSpan(d, ImageSpan.ALIGN_CENTER),
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
}
