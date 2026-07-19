/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import io.github.sylirre.terminal.R;

/**
 * Session tab bar: one rounded pill per shell, a close (×) on the active tab
 * and a trailing + button. Rebuilt wholesale on every change — the tab count is
 * tiny, so diffing isn't worth the code. Styling comes from the design tokens
 * via {@link Chrome}.
 */
public class TabStripView extends HorizontalScrollView {

    public interface Listener {
        void onTabSelected(int index);
        void onTabClosed(int index);
        void onNewTab();
        /** Long-press on +: a tab of the non-default session type. */
        void onNewTabLongPress();
    }

    private final LinearLayout row;
    private Listener listener;

    public TabStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void update(List<String> titles, int activeIndex) {
        row.removeAllViews();
        View activeTab = null;
        for (int i = 0; i < titles.size(); i++) {
            final int index = i;
            boolean active = i == activeIndex;
            View tab = makeTab(titles.get(i), active, index);
            row.addView(tab, tabLayout());
            if (active) activeTab = tab;
        }
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                dp(R.dimen.icon_button), dp(R.dimen.icon_button));
        addLp.setMarginEnd(dp(R.dimen.space_1));
        row.addView(makeAddButton(), addLp);
        if (activeTab != null) {
            final View tabToShow = activeTab;
            post(() -> {
                if (activeIndex == titles.size() - 1) {
                    fullScroll(View.FOCUS_RIGHT);
                    return;
                }
                row.requestRectangleOnScreen(new Rect(
                        tabToShow.getLeft(), 0, tabToShow.getRight(), row.getHeight()), false);
            });
        }
    }

    /** One tab pill: a label plus, when active, a close affordance. */
    private View makeTab(String title, boolean active, int index) {
        LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setClickable(true);
        chip.setFocusable(true);
        float r = Chrome.dimen(getContext(), R.dimen.radius_md);
        chip.setBackground(active
                ? Chrome.rounded(getContext(), R.color.surface_3, r, R.color.border)
                : Chrome.ripple(getContext(), R.color.surface_2, r, R.color.border));
        int padH = dp(R.dimen.tab_pad_h);
        int padV = dp(R.dimen.tab_pad_v);
        chip.setPadding(padH, padV, active ? dp(R.dimen.space_1) : padH, padV);
        chip.setOnClickListener(v -> listener.onTabSelected(index));

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(R.dimen.tab_max_width));
        label.setTextSize(14);
        label.setTextColor(Chrome.color(getContext(),
                active ? R.color.text_primary : R.color.text_secondary));
        if (active) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER_VERTICAL);
        chip.addView(label);

        if (active) {
            TextView close = new TextView(getContext());
            close.setText("×");
            Glyphs.applyTo(close);  // × → vector icon, not a font glyph
            close.setTextColor(Chrome.color(getContext(), R.color.text_secondary));
            close.setGravity(Gravity.CENTER);
            close.setContentDescription(getContext().getString(R.string.tab_close_description));
            close.setClickable(true);
            close.setFocusable(true);
            close.setBackground(Chrome.rippleTransparent(getContext(), dp(R.dimen.radius_pill)));
            close.setPadding(dp(R.dimen.space_1), dp(R.dimen.space_1),
                    dp(R.dimen.space_2), dp(R.dimen.space_1));
            close.setOnClickListener(v -> listener.onTabClosed(index));
            chip.addView(close);
        }
        return chip;
    }

    /** Trailing new-tab (+) ghost button. */
    private View makeAddButton() {
        TextView add = new TextView(getContext());
        add.setText("+");
        add.setTextColor(Chrome.color(getContext(), R.color.accent));
        add.setTextSize(22);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setGravity(Gravity.CENTER);
        add.setBackground(Chrome.ripple(getContext(), R.color.surface_2,
                Chrome.dimen(getContext(), R.dimen.radius_md), R.color.border));
        add.setContentDescription(getContext().getString(R.string.tab_new_description));
        add.setClickable(true);
        add.setFocusable(true);
        add.setOnClickListener(v -> listener.onNewTab());
        add.setOnLongClickListener(v -> {
            listener.onNewTabLongPress();
            return true;
        });
        return add;
    }

    private LinearLayout.LayoutParams tabLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(R.dimen.space_1));
        return lp;
    }

    private int dp(int dimenRes) {
        return Chrome.dp(getContext(), dimenRes);
    }
}
