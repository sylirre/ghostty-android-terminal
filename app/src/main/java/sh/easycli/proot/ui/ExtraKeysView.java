package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import sh.easycli.proot.R;
import sh.easycli.proot.term.TerminalNative;

/**
 * Special-key toolbar shown above the soft keyboard.
 *
 * The set, order, grouping into rows and presence of keys is driven by an
 * {@link ExtraKeysConfig} (edited in {@link ExtraKeysActivity}); call
 * {@link #setConfig} once and {@link #reload} whenever the config may have
 * changed.
 *
 * The toolbar is a vertical stack of 1–3 rows. Each row is a {@link FillRow}:
 * its keys stretch to fill the width evenly when they fit, and it falls back to
 * horizontal scrolling only when they don't — so a stable keyboard-grid layout
 * is the norm and a crowded row degrades gracefully instead of hiding keys.
 *
 * CTRL and ALT are sticky: they highlight and apply to the next key or typed
 * character (via {@link TerminalView.StickyModifiers}). Everything else sends
 * immediately — non-printable keys through the VT key encoder, literal text
 * straight to the PTY.
 */
public class ExtraKeysView extends LinearLayout {

    private TerminalView terminal;
    private final TerminalView.StickyModifiers sticky = new TerminalView.StickyModifiers();
    private ExtraKeysConfig config;

    // When false the toolbar is hidden regardless of the configured keys; the
    // keys themselves are untouched, so flipping this back shows them as before.
    private boolean enabledRow = true;

    // When true, the toolbar additionally hides while the soft keyboard is down.
    private boolean hideWhenKeyboardHidden = false;
    // Tracks the last known IME visibility so we can react without a full reload.
    private boolean keyboardVisible = true;
    // Set by reload(); needed so applyVisibility() can check emptiness without
    // re-querying the config.
    private boolean hasKeys = false;

    // Sticky-modifier buttons currently on screen, with the bit each toggles, so
    // updateToggles() can recolor them without knowing the layout in advance.
    private final List<ModButton> modButtons = new ArrayList<>();

    private static final int REPEAT_INTERVAL_MS = 80;

    private static final class ModButton {
        final TextView view;
        final int modifier;
        ModButton(TextView view, int modifier) {
            this.view = view;
            this.modifier = modifier;
        }
    }

    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setBackground(context.getDrawable(R.drawable.bg_toolbar_top));
        int pad = Chrome.dp(context, R.dimen.key_gap);
        setPadding(pad, pad, pad, pad);
        sticky.onChanged = this::updateToggles;
    }

    /** Must be called before the toolbar is used. */
    public void attachTerminal(TerminalView view) {
        terminal = view;
        view.setStickyModifiers(sticky);
    }

    /** Binds the config and builds the keys; call once after construction. */
    public void setConfig(ExtraKeysConfig config) {
        this.config = config;
        reload();
    }

    /**
     * Shows or hides the whole toolbar without touching the configured keys.
     * Rebuilds so the change applies immediately.
     */
    public void setRowEnabled(boolean enabled) {
        enabledRow = enabled;
        reload();
    }

    /**
     * When {@code hide} is true, the toolbar additionally hides while the soft
     * keyboard is not visible (as reported by {@link #setKeyboardVisible}).
     */
    public void setHideWhenKeyboardHidden(boolean hide) {
        hideWhenKeyboardHidden = hide;
        applyVisibility();
    }

    /**
     * Called by the activity whenever the IME appears or disappears so the
     * toolbar can react when {@link #setHideWhenKeyboardHidden} is on.
     */
    public void setKeyboardVisible(boolean visible) {
        if (keyboardVisible == visible) return;
        keyboardVisible = visible;
        applyVisibility();
    }

    /** Rebuilds the key rows from the current config (call after edits). */
    public void reload() {
        removeAllViews();
        modButtons.clear();
        if (config == null) return;
        List<List<ExtraKey>> rows = config.enabledRows(getContext());
        hasKeys = !rows.isEmpty();
        for (List<ExtraKey> row : rows) {
            FillRow rowView = new FillRow(getContext());
            for (ExtraKey key : row) addKey(key, rowView.content());
            addView(rowView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        applyVisibility();
        updateToggles();
    }

    private void applyVisibility() {
        boolean show = enabledRow && hasKeys && (!hideWhenKeyboardHidden || keyboardVisible);
        setVisibility(show ? VISIBLE : GONE);
    }

    private void addKey(ExtraKey key, LinearLayout row) {
        TextView view = new TextView(getContext());
        view.setText(key.label);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Chrome.dp(getContext(), R.dimen.key_pad_h),
                Chrome.dp(getContext(), R.dimen.key_pad_v),
                Chrome.dp(getContext(), R.dimen.key_pad_h),
                Chrome.dp(getContext(), R.dimen.key_pad_v));
        view.setMinWidth(Chrome.dp(getContext(), R.dimen.key_min_width));
        view.setBackground(keyBg());
        view.setClickable(true);
        // Render arrows and other symbol glyphs as vectors, not font glyphs, so
        // they look the same on every device (combo labels keep their ASCII
        // prefix as text and only the glyph parts become icons).
        Glyphs.applyTo(view);
        switch (key.kind) {
            case MODIFIER:
                modButtons.add(new ModButton(view, key.modifier));
                view.setOnClickListener(v -> {
                    setModifier(key.modifier, !modifierActive(key.modifier), false);
                    updateToggles();
                });
                view.setOnLongClickListener(v -> {
                    if (modifierActive(key.modifier)) {
                        setModifier(key.modifier, false, false);
                    } else {
                        setModifier(key.modifier, true, true);
                    }
                    updateToggles();
                    return true;
                });
                break;
            case KEY:
                wireRepeat(view, () -> { if (terminal != null) terminal.dispatchKey(key.keyCode, key.mods); });
                break;
            case TEXT:
                wireRepeat(view, () -> { if (terminal != null) terminal.dispatchText(key.text, key.mods); });
                break;
        }
        // Width/weight are managed per measure by FillRow (fill vs scroll); start
        // from wrap-content so the row can find its natural width. The small
        // margins (a gap between keycaps) are preserved through the fill re-measure
        // since FillRow only rewrites width/weight, not margins.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = Chrome.dp(getContext(), R.dimen.key_gap) / 2;
        lp.setMargins(m, m, m, m);
        row.addView(view, lp);
    }

    private void wireRepeat(TextView view, Runnable emit) {
        final long longPressMs = ViewConfiguration.getLongPressTimeout();
        final boolean[] longPressed = {false};
        final Runnable[] longPressRun = {null};
        final Runnable[] repeatRun = {null};
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    longPressed[0] = false;
                    longPressRun[0] = () -> {
                        longPressed[0] = true;
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        emit.run();
                        repeatRun[0] = new Runnable() {
                            @Override
                            public void run() {
                                emit.run();
                                view.postDelayed(this, REPEAT_INTERVAL_MS);
                            }
                        };
                        view.postDelayed(repeatRun[0], REPEAT_INTERVAL_MS);
                    };
                    view.postDelayed(longPressRun[0], longPressMs);
                    break;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    view.removeCallbacks(longPressRun[0]);
                    if (repeatRun[0] != null) {
                        view.removeCallbacks(repeatRun[0]);
                        repeatRun[0] = null;
                    }
                    if (!longPressed[0]) emit.run();
                    longPressed[0] = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    view.removeCallbacks(longPressRun[0]);
                    if (repeatRun[0] != null) {
                        view.removeCallbacks(repeatRun[0]);
                        repeatRun[0] = null;
                    }
                    longPressed[0] = false;
                    break;
            }
            return true;
        });
    }

    private void setModifier(int modifier, boolean active, boolean locked) {
        if (modifier == TerminalNative.MOD_CTRL) { sticky.ctrl = active; sticky.ctrlLocked = locked; }
        else if (modifier == TerminalNative.MOD_ALT) { sticky.alt = active; sticky.altLocked = locked; }
    }

    private boolean modifierActive(int modifier) {
        if (modifier == TerminalNative.MOD_CTRL) return sticky.ctrl;
        if (modifier == TerminalNative.MOD_ALT) return sticky.alt;
        return false;
    }

    private boolean modifierLocked(int modifier) {
        if (modifier == TerminalNative.MOD_CTRL) return sticky.ctrlLocked;
        if (modifier == TerminalNative.MOD_ALT) return sticky.altLocked;
        return false;
    }

    private void updateToggles() {
        for (ModButton b : modButtons) {
            boolean active = modifierActive(b.modifier);
            boolean locked = modifierLocked(b.modifier);
            b.view.setBackground(!active ? keyBg() : locked ? keyBgLocked() : keyBgActive());
        }
    }

    // Rounded keycaps drawn from the design tokens. The idle cap carries a
    // hairline border; the accent (active) and deep-accent (locked) states drop
    // it so the fill reads as the whole key.
    private Drawable keyBg() {
        return Chrome.stateful(getContext(), R.color.surface_2, R.color.surface_4,
                keyRadius(), R.color.border);
    }

    private Drawable keyBgActive() {
        return Chrome.stateful(getContext(), R.color.accent, R.color.accent_pressed,
                keyRadius(), 0);
    }

    private Drawable keyBgLocked() {
        return Chrome.stateful(getContext(), R.color.accent_deep, R.color.accent,
                keyRadius(), 0);
    }

    private float keyRadius() {
        return Chrome.dimen(getContext(), R.dimen.key_radius);
    }

    /**
     * One toolbar row. Wraps a horizontal {@link LinearLayout} of key buttons in
     * a {@link HorizontalScrollView} and decides, each measure, between two
     * modes: when the keys' natural width fits the viewport they stretch to fill
     * it as equal columns (no scrolling, stable positions); when they don't, the
     * keys keep their natural width and the row scrolls sideways like the old
     * single-row toolbar. Picking the mode from a real measurement means it
     * adapts to label widths, screen width and rotation automatically.
     */
    private static final class FillRow extends HorizontalScrollView {
        private final LinearLayout content;

        FillRow(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            content = new LinearLayout(c);
            content.setOrientation(LinearLayout.HORIZONTAL);
            addView(content, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        LinearLayout content() { return content; }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // Natural pass: a HorizontalScrollView measures its child with an
            // unbounded width, so this yields the row's intrinsic (wrap) width.
            setChildLayout(LinearLayout.LayoutParams.WRAP_CONTENT, 0f);
            super.onMeasure(widthSpec, heightSpec);

            int avail = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            boolean fill = content.getChildCount() > 0 && content.getMeasuredWidth() <= avail;
            if (!fill) return;  // scroll mode: the natural pass already fits

            // Fill mode: equal columns. Give every key width=0/weight=1 and
            // re-measure the row exactly at the viewport width so LinearLayout's
            // weight pass splits it evenly. The scroll view's own measured size
            // (full width, content height) stays as the natural pass set it.
            setChildLayout(0, 1f);
            content.measure(
                    MeasureSpec.makeMeasureSpec(avail, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(content.getMeasuredHeight(), MeasureSpec.EXACTLY));
        }

        private void setChildLayout(int width, float weight) {
            for (int i = 0; i < content.getChildCount(); i++) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) content.getChildAt(i).getLayoutParams();
                lp.width = width;
                lp.weight = weight;
            }
        }
    }
}
