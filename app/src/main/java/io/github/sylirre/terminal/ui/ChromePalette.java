/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;

import io.github.sylirre.terminal.R;

/**
 * The main screen's chrome colors, derived from the active terminal theme so
 * the top bar, tab strip, search bar and extra-keys toolbar frame the terminal
 * instead of clashing with it.
 *
 * Dark terminal backgrounds keep the stock token chrome verbatim — the app's
 * indigo-on-near-black identity, and the default theme renders exactly as
 * before. A light background (luminance ≥ 0.5, e.g. Solarized Light) derives
 * a light chrome: surfaces step the terminal color slightly toward black,
 * inks flip to near-black neutrals, and the caller flips the status-bar icon
 * appearance. The accent stays the fixed indigo brand color either way.
 *
 * Also carries drawable factories mirroring {@link Chrome}'s, resolved
 * against this palette instead of resource ids — views that follow the theme
 * route every fill/ripple through these.
 */
public final class ChromePalette {

    public final boolean isLight;
    public final int surfaceBase;
    public final int surface1;
    public final int surface2;
    public final int surface3;
    public final int surface4;
    public final int divider;
    public final int border;
    public final int textPrimary;
    public final int textSecondary;
    public final int textTertiary;
    public final int accent;
    public final int accentPressed;
    public final int accentDeep;
    public final int accentTranslucent;
    public final int onAccent;

    private final int hairlinePx;

    private ChromePalette(boolean isLight, int hairlinePx,
            int surfaceBase, int surface1, int surface2, int surface3, int surface4,
            int divider, int border,
            int textPrimary, int textSecondary, int textTertiary,
            int accent, int accentPressed, int accentDeep, int accentTranslucent,
            int onAccent) {
        this.isLight = isLight;
        this.hairlinePx = hairlinePx;
        this.surfaceBase = surfaceBase;
        this.surface1 = surface1;
        this.surface2 = surface2;
        this.surface3 = surface3;
        this.surface4 = surface4;
        this.divider = divider;
        this.border = border;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.textTertiary = textTertiary;
        this.accent = accent;
        this.accentPressed = accentPressed;
        this.accentDeep = accentDeep;
        this.accentTranslucent = accentTranslucent;
        this.onAccent = onAccent;
    }

    /** The chrome palette for a terminal with the given background color. */
    public static ChromePalette from(Context c, int terminalBg) {
        int bg = terminalBg | 0xFF000000; // theme colors are opaque; make sure
        int hairline = Chrome.dp(c, R.dimen.stroke_hairline);
        if (Color.luminance(bg) < 0.5f) {
            return new ChromePalette(false, hairline,
                    Chrome.color(c, R.color.surface_base),
                    Chrome.color(c, R.color.surface_1),
                    Chrome.color(c, R.color.surface_2),
                    Chrome.color(c, R.color.surface_3),
                    Chrome.color(c, R.color.surface_4),
                    Chrome.color(c, R.color.divider),
                    Chrome.color(c, R.color.border),
                    Chrome.color(c, R.color.text_primary),
                    Chrome.color(c, R.color.text_secondary),
                    Chrome.color(c, R.color.text_tertiary),
                    Chrome.color(c, R.color.accent),
                    Chrome.color(c, R.color.accent_pressed),
                    Chrome.color(c, R.color.accent_deep),
                    Chrome.color(c, R.color.accent_translucent),
                    Chrome.color(c, R.color.on_accent));
        }
        return new ChromePalette(true, hairline,
                bg,
                mix(bg, Color.BLACK, 0.045f),
                mix(bg, Color.BLACK, 0.08f),
                mix(bg, Color.BLACK, 0.13f),
                mix(bg, Color.BLACK, 0.18f),
                0x14000000,
                0x1F000000,
                0xFF1B1B22,
                0xFF565660,
                0xFF8B8B96,
                Chrome.color(c, R.color.accent),
                Chrome.color(c, R.color.accent_pressed),
                Chrome.color(c, R.color.accent_deep),
                Chrome.color(c, R.color.accent_translucent),
                Chrome.color(c, R.color.on_accent));
    }

    /** Per-channel lerp of {@code color} toward {@code toward} by {@code f}. */
    private static int mix(int color, int toward, float f) {
        int r = Math.round(Color.red(color) + (Color.red(toward) - Color.red(color)) * f);
        int g = Math.round(Color.green(color) + (Color.green(toward) - Color.green(color)) * f);
        int b = Math.round(Color.blue(color) + (Color.blue(toward) - Color.blue(color)) * f);
        return Color.rgb(r, g, b);
    }

    // --- Drawable factories (palette-resolved twins of Chrome's) ---

    /** A rounded solid rectangle. {@code stroke == 0} draws no border. */
    public GradientDrawable rounded(int fill, float radiusPx, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (stroke != 0) d.setStroke(hairlinePx, stroke);
        return d;
    }

    /** A rounded fill with an accent press ripple clipped to the corners. */
    public RippleDrawable ripple(int fill, float radiusPx, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(accentTranslucent),
                rounded(fill, radiusPx, stroke), mask(radiusPx));
    }

    /** A background-less rounded ripple (ghost buttons). */
    public RippleDrawable rippleTransparent(float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(accentTranslucent),
                null, mask(radiusPx));
    }

    /**
     * A pressed/normal fill swap with an accent ripple layered on top — the
     * keycap press language (immediate fill feedback) plus the ripple every
     * other tappable surface has.
     */
    public RippleDrawable pressRipple(int fill, int pressedFill, float radiusPx, int stroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(pressedFill, radiusPx, stroke));
        states.addState(new int[]{}, rounded(fill, radiusPx, stroke));
        return new RippleDrawable(ColorStateList.valueOf(accentTranslucent),
                states, mask(radiusPx));
    }

    /**
     * A bar surface: {@link #surface1} with a hairline divider along one edge
     * ({@code edgeAtBottom} picks which). The top bar and search overlay use
     * the bottom edge; the extra-keys toolbar uses the top edge.
     */
    public LayerDrawable barSurface(boolean edgeAtBottom) {
        LayerDrawable d = new LayerDrawable(new ColorDrawable[]{
                new ColorDrawable(surface1), new ColorDrawable(divider)});
        d.setLayerHeight(1, hairlinePx);
        d.setLayerGravity(1, edgeAtBottom ? Gravity.BOTTOM : Gravity.TOP);
        return d;
    }

    private GradientDrawable mask(float radiusPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return mask;
    }
}
