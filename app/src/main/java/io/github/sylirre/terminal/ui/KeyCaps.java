/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;

import io.github.sylirre.terminal.R;

/**
 * The one place a keycap is styled. Both the live toolbar
 * ({@link ExtraKeysView}) and the editor grid ({@link ExtraKeysActivity})
 * build caps through this factory, so the editor's WYSIWYG claim can't drift
 * from reality again (it had: 3dp vs 6dp padding, ripple vs state-swap).
 */
final class KeyCaps {

    private KeyCaps() {}

    /**
     * One keycap: monospace bold label (symbol glyphs vectorized), token
     * padding, and the pressed-fill-plus-ripple background. {@code
     * verticalPadPx} is the row-height knob; {@code secondaryHint} (nullable)
     * is the swipe-up alternate drawn in the corner.
     */
    static KeyCapView make(Context context, ChromePalette palette, CharSequence label,
            CharSequence secondaryHint, int verticalPadPx) {
        KeyCapView view = new KeyCapView(context);
        view.setText(label);
        // setMaxLines (not setSingleLine, which enables horizontal scrolling
        // and defeats auto-size) so KeyCapView can shrink a long label to fit;
        // the ellipsis is the last-resort guarantee that a label that doesn't
        // fit even at the minimum size clips on one line instead of wrapping.
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextColor(palette.textPrimary);
        view.setGravity(Gravity.CENTER);
        int padH = Chrome.dp(context, R.dimen.key_pad_h);
        view.setPadding(padH, verticalPadPx, padH, verticalPadPx);
        view.setBackground(palette.pressRipple(palette.surface2, palette.surface4,
                Chrome.dimen(context, R.dimen.key_radius), palette.border));
        view.setClickable(true);
        // Render arrows and other symbol glyphs as vectors, not font glyphs,
        // so they look the same on every device (combo labels keep their
        // ASCII prefix as text and only the glyph parts become icons).
        Glyphs.applyTo(view);
        if (secondaryHint != null) view.setSecondaryHint(secondaryHint);
        return view;
    }

    /**
     * Applies one shared label size to every cap in a row: the size at which
     * the row's most cramped label still fits its cap. Per-cap autosizing
     * otherwise mixes label sizes within a row. Non-cap children (add cells,
     * drag gaps) are skipped. Call after the row has real widths; glyph caps
     * measure by their original characters, which tracks the vector spans
     * closely enough.
     */
    static void uniformize(LinearLayout row) {
        float maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                KeyCapView.TEXT_SP_MAX, row.getResources().getDisplayMetrics());
        float uniformSp = KeyCapView.TEXT_SP_MAX;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (!(row.getChildAt(i) instanceof KeyCapView)) continue;
            KeyCapView cap = (KeyCapView) row.getChildAt(i);
            int avail = cap.getWidth() - cap.getPaddingLeft() - cap.getPaddingRight();
            CharSequence text = cap.getText();
            if (avail <= 0 || text.length() == 0) continue;
            TextPaint paint = new TextPaint(cap.getPaint());
            paint.setTextSize(maxPx);
            float width = paint.measureText(text.toString());
            if (width > avail) {
                uniformSp = Math.min(uniformSp, Math.max(
                        KeyCapView.TEXT_SP_MIN, KeyCapView.TEXT_SP_MAX * avail / width));
            }
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            if (!(row.getChildAt(i) instanceof KeyCapView)) continue;
            ((KeyCapView) row.getChildAt(i)).setUniformTextSizeSp(uniformSp);
        }
    }
}
