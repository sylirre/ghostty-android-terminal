/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.TerminalSession;

/**
 * Session tab bar: a scrolling row of rounded pills — every pill carries a
 * close (×), the active one an accent border ring — plus a pinned + button
 * that never scrolls out of reach. OSC 9;4 progress renders as a small ring
 * around the reporting tab's close glyph (browser-download style), so the
 * two signals live on different geometry and can never collide.
 *
 * {@link #update} reconciles the existing pills in place (retitle, restyle,
 * add/remove at the tail) instead of rebuilding, so per-command OSC title
 * updates don't cancel ripples or twitch the strip; a {@link LayoutTransition}
 * animates tabs opening and closing. The strip follows the terminal theme via
 * {@link #applyPalette}; fading edges hint at tabs scrolled out of view.
 */
public class TabStripView extends LinearLayout {

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

    private final HorizontalScrollView scroller;
    private final LinearLayout row;
    private final ImageView addButton;
    private final List<Holder> holders = new ArrayList<>();
    private Listener listener;
    private ChromePalette palette;
    /** Bumped by applyPalette so holders styled for an old palette restyle. */
    private int paletteGen;
    private final int progressErrorColor;
    private final int progressPausedColor;
    private boolean transitionsArmed;

    /** One pill and its bound state, reused across updates. */
    private final class Holder {
        final LinearLayout pill;
        final TextView label;
        final TextView close;
        final TabRing ring;
        int index;
        boolean styledActive;
        int styledGen = -1;

        Holder(LinearLayout pill, TextView label, TextView close, TabRing ring) {
            this.pill = pill;
            this.label = label;
            this.close = close;
            this.ring = ring;
        }
    }

    public TabStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        palette = ChromePalette.from(context, Color.BLACK);
        progressErrorColor = Chrome.color(context, R.color.danger);
        progressPausedColor = Chrome.color(context, R.color.warning);

        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        // The only hint that more tabs exist off-screen.
        scroller.setHorizontalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength(dp(R.dimen.space_6));
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        addView(scroller, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        // Pinned outside the scroller so it never drifts out of reach.
        addButton = new ImageView(context);
        addButton.setImageResource(R.drawable.ic_glyph_plus);
        addButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        addButton.setContentDescription(context.getString(R.string.tab_new_description));
        addButton.setClickable(true);
        addButton.setFocusable(true);
        addButton.setOnClickListener(v -> listener.onNewTab());
        addButton.setOnLongClickListener(v -> {
            listener.onNewTabLongPress();
            return true;
        });
        styleAddButton();
        int size = dp(R.dimen.icon_button);
        LayoutParams addLp = new LayoutParams(size, size);
        addLp.setMarginStart(dp(R.dimen.space_1));
        addView(addButton, addLp);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Re-derives all pill/button styling from a new chrome palette. */
    public void applyPalette(ChromePalette p) {
        palette = p;
        paletteGen++;
        styleAddButton();
        for (int i = 0; i < holders.size(); i++) {
            Holder h = holders.get(i);
            style(h, h.styledActive);
        }
    }

    public void update(List<String> titles, int activeIndex, List<TabProgress> progress) {
        while (holders.size() > titles.size()) {
            Holder h = holders.remove(holders.size() - 1);
            h.ring.set(TerminalSession.PROGRESS_NONE, 0); // stop a live spinner
            row.removeView(h.pill);
        }
        while (holders.size() < titles.size()) {
            holders.add(makeTab());
        }
        View activeTab = null;
        for (int i = 0; i < holders.size(); i++) {
            Holder h = holders.get(i);
            boolean active = i == activeIndex;
            TabProgress p = progress != null && i < progress.size()
                    ? progress.get(i) : TabProgress.NONE;
            h.index = i;
            if (!TextUtils.equals(h.label.getText(), titles.get(i))) {
                h.label.setText(titles.get(i));
            }
            if (h.styledActive != active || h.styledGen != paletteGen) {
                style(h, active);
            }
            h.ring.set(p.state, p.value);
            if (active) activeTab = h.pill;
        }
        // Arm add/remove animations only after the initial population, so a
        // restored strip doesn't animate into place.
        if (!transitionsArmed) {
            transitionsArmed = true;
            post(() -> row.setLayoutTransition(new LayoutTransition()));
        }
        if (activeTab != null) {
            final View tabToShow = activeTab;
            final boolean last = activeIndex == titles.size() - 1;
            post(() -> {
                if (last) {
                    scroller.fullScroll(View.FOCUS_RIGHT);
                    return;
                }
                row.requestRectangleOnScreen(new Rect(
                        tabToShow.getLeft(), 0, tabToShow.getRight(), row.getHeight()), false);
            });
        }
    }

    /**
     * Updates the progress indicator on a single tab in place, without
     * touching the strip — cheap enough to call on every OSC 9;4 tick.
     */
    public void setProgress(int index, int state, int value) {
        if (index < 0 || index >= holders.size()) return;
        holders.get(index).ring.set(state, value);
    }

    /** Builds one pill (label + close + line overlay); bound/styled by update(). */
    private Holder makeTab() {
        Context context = getContext();
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClickable(true);
        pill.setFocusable(true);
        int padH = dp(R.dimen.tab_pad_h);
        int padV = dp(R.dimen.tab_pad_v);
        pill.setPaddingRelative(padH, padV, dp(R.dimen.space_1), padV);

        TextView label = new TextView(context);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(R.dimen.tab_max_width));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(context, R.dimen.text_tab));
        label.setGravity(Gravity.CENTER_VERTICAL);
        pill.addView(label);

        TextView close = new TextView(context);
        close.setText("×");
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(context.getString(R.string.tab_close_description));
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(Chrome.rippleTransparent(context, dp(R.dimen.radius_pill)));
        close.setPaddingRelative(dp(R.dimen.space_2), dp(R.dimen.space_2),
                dp(R.dimen.space_2), dp(R.dimen.space_2));
        pill.addView(close);

        // The OSC 9;4 progress ring wraps the close glyph as the view's
        // foreground: never part of layout or hit-testing, so closing keeps
        // working through it and the pill stays a stable-width tap target.
        TabRing ring = new TabRing(dp(R.dimen.tab_indicator));
        close.setForeground(ring);

        Holder h = new Holder(pill, label, close, ring);
        pill.setOnClickListener(v -> listener.onTabSelected(h.index));
        close.setOnClickListener(v -> listener.onTabClosed(h.index));
        row.addView(pill, tabLayout());
        return h;
    }

    private void style(Holder h, boolean active) {
        h.styledActive = active;
        h.styledGen = paletteGen;
        float r = Chrome.dimen(getContext(), R.dimen.radius_md);
        // The active tab is marked by an accent border ring; inactive pills
        // keep the neutral hairline. Strokes draw inside the bounds, so pill
        // widths stay stable across selection.
        h.pill.setBackground(active
                ? palette.ripple(palette.surface3, r, palette.accent,
                        dp(R.dimen.tab_active_border))
                : palette.ripple(palette.surface2, r, palette.border));
        h.label.setTextColor(active ? palette.textPrimary : palette.textSecondary);
        h.label.setTypeface(active
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        h.close.setTextColor(palette.textSecondary);
        Glyphs.applyTo(h.close);  // × → vector icon, re-tinted to the palette
    }

    private void styleAddButton() {
        addButton.setImageTintList(ColorStateList.valueOf(palette.accent));
        addButton.setBackground(palette.ripple(palette.surface2,
                Chrome.dimen(getContext(), R.dimen.radius_md), palette.border));
    }

    /**
     * The OSC 9;4 progress ring drawn around a tab's close glyph: a faint
     * full-circle track with an arc scaled to the percentage — accent for
     * normal progress, danger for error, warning for paused, and a spinning
     * arc for indeterminate (driven by a ValueAnimator, so the global
     * animator scale — off on test devices — governs it). Draws nothing
     * while the session reports no progress.
     */
    private final class TabRing extends Drawable {
        private static final float INDETERMINATE_SWEEP = 100f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private final int strokePx;
        private int state = TerminalSession.PROGRESS_NONE;
        private int value;
        private float spin;
        private ValueAnimator spinner;

        TabRing(int strokePx) {
            this.strokePx = strokePx;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(int state, int value) {
            if (this.state == state && this.value == value) return;
            this.state = state;
            this.value = Math.max(0, Math.min(100, value));
            if (state == TerminalSession.PROGRESS_INDETERMINATE) {
                startSpin();
            } else {
                stopSpin();
            }
            invalidateSelf();
        }

        private void startSpin() {
            if (spinner != null) return;
            spinner = ValueAnimator.ofFloat(0f, 360f);
            spinner.setDuration(1000);
            spinner.setRepeatCount(ValueAnimator.INFINITE);
            spinner.setInterpolator(new LinearInterpolator());
            spinner.addUpdateListener(a -> {
                spin = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            spinner.start();
        }

        private void stopSpin() {
            if (spinner == null) return;
            spinner.cancel();
            spinner = null;
            spin = 0f;
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            // Don't animate detached/hidden pills; resume if still reporting.
            if (!visible) {
                stopSpin();
            } else if (state == TerminalSession.PROGRESS_INDETERMINATE) {
                startSpin();
            }
            return super.setVisible(visible, restart);
        }

        @Override
        public void draw(Canvas canvas) {
            if (state == TerminalSession.PROGRESS_NONE) return;
            Rect b = getBounds();
            float radius = Math.min(b.width(), b.height()) / 2f - strokePx;
            if (radius <= 0) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
            int color;
            float sweep;
            switch (state) {
                case TerminalSession.PROGRESS_ERROR:
                    color = progressErrorColor;
                    sweep = value > 0 ? value * 3.6f : 360f;
                    break;
                case TerminalSession.PROGRESS_PAUSED:
                    color = progressPausedColor;
                    sweep = value > 0 ? value * 3.6f : 360f;
                    break;
                case TerminalSession.PROGRESS_INDETERMINATE:
                    color = palette.accent;
                    sweep = INDETERMINATE_SWEEP;
                    break;
                default:
                    color = palette.accent;
                    sweep = value * 3.6f;
                    break;
            }
            paint.setColor(palette.border);
            canvas.drawArc(arc, 0f, 360f, false, paint);
            paint.setColor(color);
            canvas.drawArc(arc, -90f + spin, sweep, false, paint);
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

    private LayoutParams tabLayout() {
        LayoutParams lp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(R.dimen.space_1));
        return lp;
    }

    private int dp(int dimenRes) {
        return Chrome.dp(getContext(), dimenRes);
    }
}
