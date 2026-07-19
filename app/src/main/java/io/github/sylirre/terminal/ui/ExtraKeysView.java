/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.TerminalNative;

/**
 * Special-key toolbar shown above the soft keyboard — a full-width, stacked
 * flex-grid "keyboard".
 *
 * The set, order, grouping into rows, per-key widths and secondaries are driven
 * by an {@link ExtraKeysConfig} (edited in {@link ExtraKeysActivity}); call
 * {@link #setConfig} once and {@link #reload} whenever the config may have
 * changed.
 *
 * The toolbar is a vertical stack of 1–{@link ExtraKeysConfig#MAX_ROWS} rows.
 * Each row is a horizontal {@link LinearLayout} that fills the width: every
 * keycap has {@code width=0} and {@code layout_weight = key.width} (1.0 / 1.5 /
 * 2.0), so the caps flex to fill the row and never scroll off-edge. Rows stack
 * and read as one keyboard.
 *
 * Gestures:
 * <ul>
 *   <li>tap → the primary key;</li>
 *   <li>swipe up → the cap's secondary key, if it defines one (hinted in the
 *       top-right corner by {@link KeyCapView});</li>
 *   <li>long-press → auto-repeat (KEY/TEXT) or lock (sticky CTRL/ALT).</li>
 * </ul>
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

    // When true (and more than one profile exists), a leading switch column
    // lets the user cycle / choose the active layout profile.
    private boolean showSwitch = false;

    // Sticky-modifier buttons currently on screen, with the bit each toggles, so
    // updateToggles() can recolor them without knowing the layout in advance.
    private final List<ModButton> modButtons = new ArrayList<>();

    private static final int REPEAT_INTERVAL_MS = 80;

    // Upward travel (px) that turns a press into a swipe-up secondary emit.
    private final int swipeThresholdPx;

    // Vertical padding (px) inside each keycap; the toolbar's row-height knob.
    // Defaults to key_pad_v and is overridden by setKeyVerticalPaddingDp().
    private int keyPaddingV;

    private static final class ModButton {
        final KeyCapView view;
        final int modifier;
        ModButton(KeyCapView view, int modifier) {
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
        swipeThresholdPx = Math.max(ViewConfiguration.get(context).getScaledTouchSlop() * 2,
                Chrome.dp(context, R.dimen.space_5));
        keyPaddingV = Chrome.dp(context, R.dimen.key_pad_v);
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
     * Shows or hides the leading profile-switch column (only ever visible when
     * more than one profile exists). Rebuilds so the change applies immediately.
     */
    public void setShowSwitch(boolean show) {
        if (showSwitch == show) return;
        showSwitch = show;
        reload();
    }

    /**
     * Sets the vertical padding inside each keycap — the toolbar's row-height
     * knob. Rows stay wrap-content, so a larger value grows the row and tap
     * target while the autosized label never clips. Rebuilds on change.
     */
    public void setKeyVerticalPaddingDp(int dp) {
        int px = Math.round(dp * getResources().getDisplayMetrics().density);
        if (keyPaddingV == px) return;
        keyPaddingV = px;
        reload();
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

        // With the switch column the toolbar becomes [switch | stacked rows];
        // without it the rows stack directly in this vertical container.
        LinearLayout rowsContainer;
        if (showSwitch && hasKeys && config.profileCount() > 1) {
            setOrientation(HORIZONTAL);
            LayoutParams swLp = new LayoutParams(
                    Chrome.dp(getContext(), R.dimen.key_switch_width), LayoutParams.MATCH_PARENT);
            int m = Chrome.dp(getContext(), R.dimen.key_gap) / 2;
            swLp.setMargins(m, m, m, m);
            addView(buildSwitchColumn(), swLp);
            rowsContainer = new LinearLayout(getContext());
            rowsContainer.setOrientation(VERTICAL);
            addView(rowsContainer, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        } else {
            setOrientation(VERTICAL);
            rowsContainer = this;
        }

        for (List<ExtraKey> row : rows) {
            LinearLayout rowView = new LinearLayout(getContext());
            rowView.setOrientation(HORIZONTAL);
            for (ExtraKey key : row) addKey(key, rowView);
            rowsContainer.addView(rowView,
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        applyVisibility();
        updateToggles();
    }

    /** The leading profile-switch cap: tap cycles the active profile, long-press chooses. */
    private View buildSwitchColumn() {
        ImageView v = new ImageView(getContext());
        v.setImageResource(R.drawable.ic_glyph_layers);
        v.setImageTintList(ColorStateList.valueOf(Chrome.color(getContext(), R.color.accent)));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Chrome.dp(getContext(), R.dimen.key_pad_h);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(keyBg());
        v.setContentDescription(getContext().getString(R.string.extra_keys_switch_chooser_title));
        v.setClickable(true);
        v.setOnClickListener(x -> {
            int n = config.profileCount();
            if (n > 1) {
                config.setActiveIndex((config.activeIndex() + 1) % n);
                reload();
            }
        });
        v.setOnLongClickListener(x -> { showProfileChooser(); return true; });
        return v;
    }

    /** A single-choice dialog of profile names; picking one switches to it. */
    private void showProfileChooser() {
        List<ExtraKeysConfig.Profile> profiles = config.profiles();
        int n = profiles.size();
        if (n <= 1) return;
        CharSequence[] names = new CharSequence[n];
        for (int i = 0; i < n; i++) names[i] = profiles.get(i).name;
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.extra_keys_switch_chooser_title)
                .setSingleChoiceItems(names, config.activeIndex(), (d, which) -> {
                    config.setActiveIndex(which);
                    reload();
                    d.dismiss();
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    private void applyVisibility() {
        boolean show = enabledRow && hasKeys && (!hideWhenKeyboardHidden || keyboardVisible);
        setVisibility(show ? VISIBLE : GONE);
    }

    private void addKey(ExtraKey key, LinearLayout row) {
        KeyCapView view = new KeyCapView(getContext());
        view.setText(key.label);
        // setMaxLines (not setSingleLine, which enables horizontal scrolling and
        // defeats auto-size) so KeyCapView can shrink a long label to fit.
        view.setMaxLines(1);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Chrome.dp(getContext(), R.dimen.key_pad_h),
                keyPaddingV,
                Chrome.dp(getContext(), R.dimen.key_pad_h),
                keyPaddingV);
        view.setBackground(keyBg());
        view.setClickable(true);
        // Render arrows and other symbol glyphs as vectors, not font glyphs, so
        // they look the same on every device (combo labels keep their ASCII
        // prefix as text and only the glyph parts become icons).
        Glyphs.applyTo(view);
        if (key.hasSecondary()) {
            ExtraKey sec = ExtraKeysConfig.resolve(getContext(), key.secondaryId);
            if (sec != null) view.setSecondaryHint(sec.label);
        }
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
                wireRepeat(view,
                        () -> { if (terminal != null) terminal.dispatchKey(key.keyCode, key.mods); },
                        secondaryEmitter(key));
                break;
            case TEXT:
                wireRepeat(view,
                        () -> { if (terminal != null) terminal.dispatchText(key.text, key.mods); },
                        secondaryEmitter(key));
                break;
        }
        // Caps flex to fill the row: width 0 + weight = the key's width multiplier.
        // MATCH_PARENT height so every cap stretches to the row's tallest cap;
        // otherwise a label autosized to a smaller text size measures shorter and
        // top-aligns, leaving its keycap ragged against its neighbours.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, key.width);
        int m = Chrome.dp(getContext(), R.dimen.key_gap) / 2;
        lp.setMargins(m, m, m, m);
        row.addView(view, lp);
    }

    /** A runnable that emits {@code key}'s secondary, or null when it has none. */
    private Runnable secondaryEmitter(ExtraKey key) {
        if (!key.hasSecondary()) return null;
        ExtraKey sec = ExtraKeysConfig.resolve(getContext(), key.secondaryId);
        if (sec == null) return null;
        return () -> {
            if (terminal == null) return;
            if (sec.kind == ExtraKey.Kind.KEY) terminal.dispatchKey(sec.keyCode, sec.mods);
            else if (sec.kind == ExtraKey.Kind.TEXT) terminal.dispatchText(sec.text, sec.mods);
        };
    }

    /**
     * Wires tap/long-press-repeat and (when {@code emitSecondary != null}) a
     * swipe-up that emits the secondary instead of the primary. The three
     * gestures are disjoint: an upward drag past {@link #swipeThresholdPx}
     * consumes the touch so neither the tap nor the repeat fires.
     */
    private void wireRepeat(KeyCapView view, Runnable emit, Runnable emitSecondary) {
        final long longPressMs = ViewConfiguration.getLongPressTimeout();
        final boolean[] longPressed = {false};
        final boolean[] consumed = {false};   // swipe-up already emitted the secondary
        final float[] downY = {0f};
        final Runnable[] longPressRun = {null};
        final Runnable[] repeatRun = {null};
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    longPressed[0] = false;
                    consumed[0] = false;
                    downY[0] = event.getY();
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
                case MotionEvent.ACTION_MOVE:
                    if (!consumed[0] && !longPressed[0] && emitSecondary != null
                            && (downY[0] - event.getY()) > swipeThresholdPx) {
                        consumed[0] = true;
                        view.setPressed(false);
                        cancelRepeat(view, longPressRun, repeatRun);
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        emitSecondary.run();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    cancelRepeat(view, longPressRun, repeatRun);
                    if (!longPressed[0] && !consumed[0]) emit.run();
                    longPressed[0] = false;
                    consumed[0] = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    cancelRepeat(view, longPressRun, repeatRun);
                    longPressed[0] = false;
                    consumed[0] = false;
                    break;
            }
            return true;
        });
    }

    private static void cancelRepeat(KeyCapView view, Runnable[] longPressRun, Runnable[] repeatRun) {
        if (longPressRun[0] != null) view.removeCallbacks(longPressRun[0]);
        if (repeatRun[0] != null) {
            view.removeCallbacks(repeatRun[0]);
            repeatRun[0] = null;
        }
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
}
