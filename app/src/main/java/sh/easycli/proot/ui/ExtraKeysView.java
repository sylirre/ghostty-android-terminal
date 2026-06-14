package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import sh.easycli.proot.R;

/**
 * Special-key toolbar shown above the soft keyboard.
 *
 * CTRL and ALT are sticky: they highlight and apply to the next key or
 * typed character (via {@link TerminalView.StickyModifiers}). Everything
 * else sends immediately through the VT key encoder.
 */
public class ExtraKeysView extends HorizontalScrollView {

    private TerminalView terminal;
    private final TerminalView.StickyModifiers sticky = new TerminalView.StickyModifiers();
    private TextView ctrlButton;
    private TextView altButton;

    private static final int BG = 0xFF21212A;
    private static final int BG_ACTIVE = 0xFF3D5AFE;

    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(BG);
        setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        addKey(row, context.getString(R.string.key_esc),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_ESCAPE));
        ctrlButton = addKey(row, context.getString(R.string.key_ctrl), () -> {
            sticky.ctrl = !sticky.ctrl;
            updateToggles();
        });
        altButton = addKey(row, context.getString(R.string.key_alt), () -> {
            sticky.alt = !sticky.alt;
            updateToggles();
        });
        addKey(row, context.getString(R.string.key_tab),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_TAB));
        addKey(row, context.getString(R.string.key_up),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_DPAD_UP));
        addKey(row, context.getString(R.string.key_down),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN));
        addKey(row, context.getString(R.string.key_left),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT));
        addKey(row, context.getString(R.string.key_right),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        addKey(row, context.getString(R.string.key_home),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_MOVE_HOME));
        addKey(row, context.getString(R.string.key_end),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_MOVE_END));
        addKey(row, context.getString(R.string.key_pgup),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_PAGE_UP));
        addKey(row, context.getString(R.string.key_pgdn),
                () -> terminal.dispatchKey(KeyEvent.KEYCODE_PAGE_DOWN));
        addKey(row, context.getString(R.string.key_dash),
                () -> terminal.dispatchText("-"));
        addKey(row, context.getString(R.string.key_slash),
                () -> terminal.dispatchText("/"));
        addKey(row, context.getString(R.string.key_pipe),
                () -> terminal.dispatchText("|"));

        sticky.onChanged = this::updateToggles;
    }

    /** Must be called before the toolbar is used. */
    public void attachTerminal(TerminalView view) {
        terminal = view;
        view.setStickyModifiers(sticky);
    }

    private TextView addKey(LinearLayout row, String label, Runnable action) {
        TextView key = new TextView(getContext());
        key.setText(label);
        key.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        key.setTextColor(Color.WHITE);
        key.setGravity(Gravity.CENTER);
        int pad = dp(14);
        key.setPadding(pad, dp(12), pad, dp(12));
        key.setClickable(true);
        key.setOnClickListener(v -> {
            if (terminal != null) action.run();
        });
        row.addView(key, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return key;
    }

    private void updateToggles() {
        ctrlButton.setBackgroundColor(sticky.ctrl ? BG_ACTIVE : BG);
        altButton.setBackgroundColor(sticky.alt ? BG_ACTIVE : BG);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
