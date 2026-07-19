/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;

import io.github.sylirre.terminal.R;

/**
 * Shared styling vocabulary for the views that are built entirely in code
 * (tab strip, extra-keys toolbar, search bar, color picker). It is the code
 * counterpart to the XML design tokens: every color and metric resolves from
 * {@code res/values} so layouts and Java draw the same palette. Prefer these
 * factories over hand-rolled {@link GradientDrawable}/hex constants so the
 * chrome stays consistent and re-themable from one place.
 */
final class Chrome {

    private Chrome() {}

    /** Resolves a color token. */
    static int color(Context c, int colorRes) {
        return c.getColor(colorRes);
    }

    /** Resolves a dimension token to whole pixels. */
    static int dp(Context c, int dimenRes) {
        return c.getResources().getDimensionPixelSize(dimenRes);
    }

    /** Resolves a dimension token to pixels (fractional). */
    static float dimen(Context c, int dimenRes) {
        return c.getResources().getDimension(dimenRes);
    }

    /**
     * A rounded solid rectangle. {@code strokeRes == 0} draws no border.
     */
    static GradientDrawable rounded(Context c, int fillRes, float radiusPx, int strokeRes) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color(c, fillRes));
        d.setCornerRadius(radiusPx);
        if (strokeRes != 0) {
            d.setStroke(dp(c, R.dimen.stroke_hairline), color(c, strokeRes));
        }
        return d;
    }

    /**
     * A rounded fill with an accent-tinted press ripple clipped to the same
     * corners. {@code strokeRes == 0} draws no border.
     */
    static RippleDrawable ripple(Context c, int fillRes, float radiusPx, int strokeRes) {
        return new RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.accent_translucent)),
                rounded(c, fillRes, radiusPx, strokeRes),
                roundedMask(radiusPx));
    }

    /**
     * A transparent (background-less) rounded ripple, for ghost buttons and
     * inactive tabs that only reveal an accent tint on touch.
     */
    static RippleDrawable rippleTransparent(Context c, float radiusPx) {
        return new RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.accent_translucent)),
                null, roundedMask(radiusPx));
    }

    /**
     * A pressed/normal state list of rounded fills, used where the caller also
     * swaps the whole background to signal state (e.g. active/locked modifier
     * keys) rather than relying on a ripple. {@code strokeRes == 0} → no border.
     */
    static StateListDrawable stateful(Context c, int fillRes, int pressedFillRes,
            float radiusPx, int strokeRes) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed},
                rounded(c, pressedFillRes, radiusPx, strokeRes));
        s.addState(new int[]{}, rounded(c, fillRes, radiusPx, strokeRes));
        return s;
    }

    private static GradientDrawable roundedMask(float radiusPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return mask;
    }
}
