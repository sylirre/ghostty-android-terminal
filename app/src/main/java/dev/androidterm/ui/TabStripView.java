package dev.androidterm.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

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
        for (int i = 0; i < titles.size(); i++) {
            final int index = i;
            boolean active = i == activeIndex;
            TextView tab = makeButton(titles.get(i), active);
            tab.setOnClickListener(v -> listener.onTabSelected(index));
            row.addView(tab);
            if (active) {
                TextView close = makeButton("×", true);
                close.setContentDescription("close tab");
                close.setOnClickListener(v -> listener.onTabClosed(index));
                row.addView(close);
            }
        }
        TextView add = makeButton("+", false);
        add.setContentDescription("new tab");
        add.setOnClickListener(v -> listener.onNewTab());
        add.setOnLongClickListener(v -> {
            listener.onNewTabLongPress();
            return true;
        });
        row.addView(add);
    }

    private TextView makeButton(String label, boolean active) {
        TextView v = new TextView(getContext());
        v.setText(label);
        v.setTextColor(active ? Color.WHITE : 0xFF9999A6);
        v.setBackgroundColor(active ? BG_ACTIVE : BG);
        v.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        v.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        v.setClickable(true);
        return v;
    }
}
