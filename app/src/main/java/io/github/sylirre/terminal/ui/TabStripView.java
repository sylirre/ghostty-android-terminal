/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.TerminalSession;

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

    /** OSC 9;4 progress for one tab: a {@code TerminalSession.PROGRESS_*} state and 0..100. */
    public static final class TabProgress {
        public static final TabProgress NONE =
                new TabProgress(TerminalSession.PROGRESS_NONE, 0);
        public final int state;
        public final int value;

        public TabProgress(int state, int value) {
            this.state = state;
            this.value = value;
        }
    }

    private static final int PROGRESS_ERROR_COLOR = 0xFFE5534B;
    private static final int PROGRESS_PAUSED_COLOR = 0xFFD9A441;

    private final LinearLayout row;
    // Per-tab progress overlays, aligned with the tab index; rebuilt by update().
    private final List<ProgressLine> progressLines = new ArrayList<>();
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

    public void update(List<String> titles, int activeIndex, List<TabProgress> progress) {
        row.removeAllViews();
        progressLines.clear();
        View activeTab = null;
        for (int i = 0; i < titles.size(); i++) {
            final int index = i;
            boolean active = i == activeIndex;
            TabProgress p = progress != null && i < progress.size()
                    ? progress.get(i) : TabProgress.NONE;
            View tab = makeTab(titles.get(i), active, index, p);
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

    /**
     * Updates the progress indicator on a single tab in place, without
     * rebuilding the strip — cheap enough to call on every OSC 9;4 tick.
     */
    public void setProgress(int index, int state, int value) {
        if (index < 0 || index >= progressLines.size()) return;
        progressLines.get(index).set(state, value);
    }

    /**
     * A thin progress line drawn as a tab pill's foreground, along its bottom
     * edge: an accent fill scaled to the percentage for normal progress, red
     * for error, amber for paused, and a full accent bar for indeterminate.
     * A foreground drawable never affects the pill's measurement, clickability
     * or visibility, so the tab stays a clean tap target.
     */
    private static final class ProgressLine extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int inset;
        private final int thickness;
        private final int accent;
        private int state = TerminalSession.PROGRESS_NONE;
        private int value;

        ProgressLine(int inset, int thickness, int accent) {
            this.inset = inset;
            this.thickness = thickness;
            this.accent = accent;
        }

        void set(int state, int value) {
            this.state = state;
            this.value = Math.max(0, Math.min(100, value));
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (state == TerminalSession.PROGRESS_NONE) return;
            int color;
            float frac;
            switch (state) {
                case TerminalSession.PROGRESS_ERROR:
                    color = PROGRESS_ERROR_COLOR;
                    frac = value > 0 ? value / 100f : 1f;
                    break;
                case TerminalSession.PROGRESS_PAUSED:
                    color = PROGRESS_PAUSED_COLOR;
                    frac = value > 0 ? value / 100f : 1f;
                    break;
                case TerminalSession.PROGRESS_INDETERMINATE:
                    color = accent;
                    frac = 1f;
                    break;
                default:
                    color = accent;
                    frac = value / 100f;
                    break;
            }
            Rect b = getBounds();
            float left = b.left + inset;
            float right = b.right - inset;
            float top = b.bottom - thickness;
            paint.setColor(color);
            canvas.drawRect(left, top, left + (right - left) * frac, b.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** One tab pill: a label plus, when active, a close affordance; progress line as foreground. */
    private View makeTab(String title, boolean active, int index, TabProgress progress) {
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

        // A thin progress line rides as the pill's foreground, inset from the
        // rounded corners. Foreground drawing leaves layout and hit-testing
        // untouched, so the tab stays a clean tap target.
        int thickness = Math.round(3 * getResources().getDisplayMetrics().density);
        ProgressLine line = new ProgressLine(padH, thickness,
                Chrome.color(getContext(), R.color.accent));
        line.set(progress.state, progress.value);
        chip.setForeground(line);
        progressLines.add(line);
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
