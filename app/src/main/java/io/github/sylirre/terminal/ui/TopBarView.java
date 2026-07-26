/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.sylirre.terminal.R;

/**
 * Shared screen top bar: a back arrow, a title, and a trailing slot for
 * text actions (Done, Reset). One component so every secondary screen —
 * Settings, theme editor, extra-keys editor — gets identical geometry,
 * background (surface with a hairline bottom edge) and elevation.
 *
 * The status-bar inset is applied as extra top padding by
 * {@link EdgeInsets#apply}, so the bar's surface runs underneath the
 * status bar instead of leaving a differently-colored band above it.
 */
public final class TopBarView extends LinearLayout {

    private final ImageButton back;
    private final TextView title;
    private final LinearLayout actions;

    public TopBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Chrome.dp(context, R.dimen.top_bar_height));
        setBackground(context.getDrawable(R.drawable.bg_top_bar));
        int padH = Chrome.dp(context, R.dimen.space_2);
        setPaddingRelative(padH, 0, padH, 0);
        // A real shadow over the scrolling content, so the bar reads as a layer.
        setElevation(Chrome.dimen(context, R.dimen.elevation_bar));
        setOutlineProvider(ViewOutlineProvider.BOUNDS);

        back = new ImageButton(context);
        back.setImageResource(R.drawable.ic_glyph_back);
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setImageTintList(ColorStateList.valueOf(
                Chrome.color(context, R.color.text_secondary)));
        back.setBackground(context.getDrawable(R.drawable.bg_toolbar_icon));
        back.setContentDescription(context.getString(R.string.top_bar_back_description));
        int size = Chrome.dp(context, R.dimen.icon_button);
        addView(back, new LayoutParams(size, size));

        title = new TextView(context, null, 0, R.style.TopBarTitle);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(Chrome.dp(context, R.dimen.space_2));
        addView(title, titleLp);

        actions = new LinearLayout(context);
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addView(actions, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setTitle(CharSequence text) {
        title.setText(text);
    }

    public void setOnBack(Runnable action) {
        back.setOnClickListener(v -> action.run());
    }

    /**
     * Appends a text action to the trailing slot and returns it for id/click
     * wiring. {@code prominent} actions (Done) render bold accent; others
     * (Reset) render as secondary text. Both get a contained rounded ripple
     * and a {@code touch_min} hit target.
     */
    public TextView addTextAction(int labelRes, boolean prominent) {
        Context context = getContext();
        TextView action = new TextView(context);
        action.setText(labelRes);
        action.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(context, R.dimen.text_action));
        action.setTextColor(Chrome.color(context,
                prominent ? R.color.accent : R.color.text_secondary));
        if (prominent) action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(Chrome.dp(context, R.dimen.touch_min));
        action.setMinWidth(Chrome.dp(context, R.dimen.touch_min));
        action.setPaddingRelative(Chrome.dp(context, R.dimen.space_3), 0,
                Chrome.dp(context, R.dimen.space_3), 0);
        action.setBackground(Chrome.rippleTransparent(context,
                Chrome.dimen(context, R.dimen.radius_md)));
        action.setClickable(true);
        action.setFocusable(true);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Chrome.dp(context, R.dimen.space_1));
        actions.addView(action, lp);
        return action;
    }
}
