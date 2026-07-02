package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import sh.easycli.proot.R;

/**
 * Session tab bar: one tab per shell, a close (×) on the active tab and a
 * trailing + button. Rebuilt wholesale on every change — the tab count is
 * tiny, so diffing isn't worth the code.
 */
public class TabStripView extends HorizontalScrollView {

    public interface Listener {
        void onTabSelected(int index);
        void onTabClosed(int index);
        void onNewTab();
        /** Long-press on +: a tab of the non-default session type. */
        void onNewTabLongPress();
    }

    private static final int BG = 0xFF14141A;
    private static final int BG_ACTIVE = 0xFF2B2B36;

    private final LinearLayout row;
    private Listener listener;

    // Set by update() whenever the active tab changes; consumed (and
    // cleared) in onLayout, once the row's children have real coordinates.
    private View pendingScrollStart;
    private View pendingScrollEnd;

    public TabStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(BG);
        setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void update(List<String> titles, int activeIndex) {
        row.removeAllViews();
        View activeStart = null;
        View activeEnd = null;
        boolean activeIsLast = activeIndex == titles.size() - 1;
        for (int i = 0; i < titles.size(); i++) {
            final int index = i;
            boolean active = i == activeIndex;
            TextView tab = makeButton(titles.get(i), active);
            tab.setOnClickListener(v -> listener.onTabSelected(index));
            row.addView(tab);
            if (active) {
                activeStart = tab;
                activeEnd = tab;
                TextView close = makeButton("×", true);
                Glyphs.applyTo(close);  // × → vector icon, not a font glyph
                close.setContentDescription(getContext().getString(R.string.tab_close_description));
                close.setOnClickListener(v -> listener.onTabClosed(index));
                row.addView(close);
                activeEnd = close;
            }
        }
        TextView add = makeButton("+", false);
        add.setContentDescription(getContext().getString(R.string.tab_new_description));
        add.setOnClickListener(v -> listener.onNewTab());
        add.setOnLongClickListener(v -> {
            listener.onNewTabLongPress();
            return true;
        });
        row.addView(add);
        // The active tab is the newest/last one (the common "just created a
        // session" case): keep the + reachable too, since it's right next
        // to its close button with no other tab in between.
        if (activeIsLast) activeEnd = add;

        // Defer to onLayout: the new/moved children have no valid
        // getLeft()/getRight() until this pass' layout has run.
        pendingScrollStart = activeStart;
        pendingScrollEnd = activeEnd;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingScrollStart != null) {
            scrollToShow(pendingScrollStart, pendingScrollEnd);
            pendingScrollStart = null;
            pendingScrollEnd = null;
        }
    }

    /**
     * Scrolls just enough to bring [start, end] fully into view. When the
     * range is wider than the viewport, the tail (close/+ controls) wins
     * over the tab label — those are what the user needs to reach.
     */
    private void scrollToShow(View start, View end) {
        int viewportWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        if (viewportWidth <= 0) return;
        int left = start.getLeft();
        int right = end.getRight();
        int scrollX = getScrollX();
        if (right - left >= viewportWidth || right > scrollX + viewportWidth) {
            smoothScrollTo(right - viewportWidth, 0);
        } else if (left < scrollX) {
            smoothScrollTo(left, 0);
        }
    }

    private TextView makeButton(String label, boolean active) {
        TextView v = new TextView(getContext());
        v.setText(label);
        v.setTextColor(active ? Color.WHITE : 0xFF9999A6);
        v.setBackground(tabBg(active ? BG_ACTIVE : BG, !active));
        v.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        v.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        v.setClickable(true);
        return v;
    }

    private GradientDrawable tabBg(int fill, boolean border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (border) {
            float density = getResources().getDisplayMetrics().density;
            d.setStroke((int) (density + 0.5f), 0xFF2A2A35);
        }
        return d;
    }
}
