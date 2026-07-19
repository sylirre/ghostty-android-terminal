/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.widget.TextView;

import io.github.sylirre.terminal.R;

/**
 * One keycap. Deliberately a {@link TextView} subclass rather than a bespoke
 * {@link android.view.View}: the primary label stays real text, so
 * {@link Glyphs#applyTo} spans it and Espresso {@code withText("ESC")} still
 * matches it, while {@link #onDraw} layers an optional secondary-key hint in the
 * top-right corner (the swipe-up / long-press alternate). Shared by the live
 * toolbar ({@link ExtraKeysView}) and the editor grid ({@link ExtraKeysActivity}).
 */
final class KeyCapView extends TextView {

    /** Corner hint size relative to the cap's own text size. */
    private static final float HINT_SCALE = 0.62f;

    private Layout hint;  // pre-laid-out corner hint, or null

    KeyCapView(Context context) {
        super(context);
        // Auto-shrink long labels (CTRL, HOME, combo prefixes) so they fit a
        // narrow flex cap instead of clipping; short/glyph caps stay at max size.
        setAutoSizeTextTypeUniformWithConfiguration(8, 15, 1, TypedValue.COMPLEX_UNIT_SP);
    }

    /**
     * Sets (or clears, when {@code label} is null/empty) the corner hint drawn
     * for a swipe-up secondary. The label is run through {@link Glyphs} so a
     * symbol secondary (an arrow, ↵, …) renders as its vector, matching the
     * primary caps.
     */
    void setSecondaryHint(CharSequence label) {
        if (label == null || label.length() == 0) {
            hint = null;
            invalidate();
            return;
        }
        float size = getTextSize() * HINT_SCALE;
        int color = Chrome.color(getContext(), R.color.text_secondary);
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.MONOSPACE);
        CharSequence spanned = Glyphs.apply(getContext(), label, size, color);
        int w = (int) Math.ceil(Layout.getDesiredWidth(spanned, p));
        hint = StaticLayout.Builder.obtain(spanned, 0, spanned.length(), p, Math.max(1, w)).build();
        invalidate();
    }

    boolean hasSecondaryHint() {
        return hint != null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (hint == null) return;
        int inset = Chrome.dp(getContext(), R.dimen.key_hint_inset);
        float x = getWidth() - hint.getWidth() - inset;
        float y = inset;
        canvas.save();
        canvas.translate(x, y);
        hint.draw(canvas);
        canvas.restore();
    }
}
