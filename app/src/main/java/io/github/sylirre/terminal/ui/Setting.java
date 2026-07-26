/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import io.github.sylirre.terminal.R;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One entry in the settings screen: a title, a one-line description, and an
 * optional trailing control. The control type lives in a subclass so the
 * screen ({@link SettingsActivity}) stays type-agnostic — it lays out the
 * shared title/summary row and drops in whatever {@link #createControl}
 * returns.
 *
 * {@link Toggle} (a Switch) and {@link Choice} (a value picked from a fixed
 * list) exist today. Add further types — a slider, a tappable action — as
 * sibling subclasses; nothing in the dialog or {@code MainActivity} needs to
 * change to host them.
 */
abstract class Setting {

    final String title;
    final String summary;

    /**
     * Gates whether this row is interactive. When it returns false the screen
     * greys the row out and ignores taps (see {@link SettingsActivity}). Null
     * means always enabled. Re-evaluated whenever any row reports a change, so
     * one toggle can disable another live (e.g. hiding the extra-keys toolbar
     * greys out its editor row).
     */
    private BooleanSupplier enabledWhen;

    /** Whether tapping this row opens another screen (drawn with a chevron). */
    private boolean navigates;

    /**
     * Set by the dialog; a row calls it after mutating a value so the dialog
     * can re-apply enabled state across all rows. Null until hosted.
     */
    Runnable onChanged;

    Setting(String title, String summary) {
        this.title = title;
        this.summary = summary;
    }

    /** Restricts this row to being interactive only while {@code gate} is true. */
    Setting enabledWhen(BooleanSupplier gate) {
        this.enabledWhen = gate;
        return this;
    }

    /** Marks this row as opening another screen; it gains a disclosure chevron. */
    Setting navigates() {
        this.navigates = true;
        return this;
    }

    /** Whether the row opens another screen (see {@link #navigates()}). */
    boolean isNavigation() {
        return navigates;
    }

    /** Whether the row is currently interactive (ungated rows always are). */
    boolean isEnabled() {
        return enabledWhen == null || enabledWhen.getAsBoolean();
    }

    /** Subclasses call this after changing a value so dependents can refresh. */
    void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    /**
     * Builds this row's trailing control (e.g. a Switch), or returns null
     * for a row whose body is itself the only tap target.
     */
    abstract View createControl(Context context);

    /**
     * Invoked when the row body — not the control — is tapped, so the whole
     * row acts as a hit target. {@code control} is whatever
     * {@link #createControl} returned (possibly null). Default: no-op.
     */
    void onRowClick(View control) {}

    /** A boolean setting, rendered as a Switch and toggled by row taps too. */
    static final class Toggle extends Setting {

        private final BooleanSupplier value;
        private final Consumer<Boolean> onChange;

        Toggle(String title, String summary,
               BooleanSupplier value, Consumer<Boolean> onChange) {
            super(title, summary);
            this.value = value;
            this.onChange = onChange;
        }

        @Override
        View createControl(Context context) {
            Switch sw = new Switch(context);
            // Seed state before wiring the listener so this is silent.
            sw.setChecked(value.getAsBoolean());
            sw.setOnCheckedChangeListener((btn, checked) -> {
                onChange.accept(checked);
                notifyChanged(); // a dependent row may need to grey out/in
            });
            return sw;
        }

        @Override
        void onRowClick(View control) {
            control.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            ((Switch) control).toggle(); // fires the listener, which persists + applies
        }
    }

    /**
     * A boolean setting whose change can be refused. Used for toggles that
     * must launch an external permission flow before the value can become true.
     */
    static final class RequestToggle extends Setting {

        private final BooleanSupplier value;
        private final Predicate<Boolean> onChange;
        private boolean syncing;

        RequestToggle(String title, String summary,
                BooleanSupplier value, Predicate<Boolean> onChange) {
            super(title, summary);
            this.value = value;
            this.onChange = onChange;
        }

        @Override
        View createControl(Context context) {
            Switch sw = new Switch(context);
            sw.setChecked(value.getAsBoolean());
            sw.setOnCheckedChangeListener((btn, checked) -> {
                if (syncing) return;
                boolean accepted = onChange.test(checked);
                sync(sw);
                if (accepted) notifyChanged();
            });
            sw.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                private final ViewTreeObserver.OnWindowFocusChangeListener onFocus =
                        hasFocus -> {
                            if (hasFocus) sync(sw);
                        };

                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver().addOnWindowFocusChangeListener(onFocus);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver().removeOnWindowFocusChangeListener(onFocus);
                }
            });
            return sw;
        }

        @Override
        void onRowClick(View control) {
            control.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            ((Switch) control).toggle();
        }

        private void sync(Switch sw) {
            syncing = true;
            sw.setChecked(value.getAsBoolean());
            syncing = false;
        }
    }

    /**
     * An integer setting picked from a fixed list of options. The trailing
     * control is a label showing the current value; tapping the row opens a
     * single-choice dialog and writes the chosen value back through
     * {@code onChange}. {@code values} and {@code labels} are parallel arrays.
     */
    static final class Choice extends Setting {

        private final int[] values;
        private final String[] labels;
        private final IntSupplier value;
        private final IntConsumer onChange;

        Choice(String title, String summary, int[] values, String[] labels,
               IntSupplier value, IntConsumer onChange) {
            super(title, summary);
            this.values = values;
            this.labels = labels;
            this.value = value;
            this.onChange = onChange;
        }

        @Override
        View createControl(Context context) {
            TextView label = new TextView(context);
            label.setText(labelFor(value.getAsInt()));
            return label;
        }

        @Override
        void onRowClick(View control) {
            TextView label = (TextView) control;
            int current = value.getAsInt();
            int checked = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) {
                    checked = i;
                    break;
                }
            }
            new AlertDialog.Builder(control.getContext())
                    .setTitle(title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        onChange.accept(values[which]);
                        label.setText(labels[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        }

        /** The label for the current value, or the raw number if unlisted. */
        private String labelFor(int v) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == v) return labels[i];
            }
            return String.valueOf(v);
        }
    }

    /**
     * A row that opens something else (a screen or sub-dialog) when tapped.
     * The trailing control is a label showing the current value (e.g. the
     * active theme name). The opened screen (theme editor, extra-keys editor)
     * can change that value while this dialog stays up underneath, so the
     * label re-reads the value whenever the dialog regains window focus on
     * return — otherwise it would keep the snapshot taken when it was built.
     */
    static final class Action extends Setting {

        private final Supplier<String> value;
        private final Runnable onClick;

        Action(String title, String summary, Supplier<String> value, Runnable onClick) {
            super(title, summary);
            this.value = value;
            this.onClick = onClick;
        }

        @Override
        View createControl(Context context) {
            TextView label = new TextView(context);
            label.setText(value.get());
            // Refresh on return from the screen this row opens: when that
            // screen finishes, the dialog window regains focus and we re-read
            // the (possibly changed) value rather than showing a stale name.
            label.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                private final ViewTreeObserver.OnWindowFocusChangeListener onFocus =
                        hasFocus -> {
                            if (hasFocus) label.setText(value.get());
                        };

                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver().addOnWindowFocusChangeListener(onFocus);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver().removeOnWindowFocusChangeListener(onFocus);
                }
            });
            return label;
        }

        @Override
        void onRowClick(View control) {
            onClick.run();
        }
    }

    /**
     * An integer setting adjusted with a horizontal slider. Rendered into the
     * row's full-width control slot: a flexible {@link SeekBar} plus a live
     * read-out label (e.g. "32 MB"). {@code min}, {@code max} and {@code step}
     * bound the value; the SeekBar works in whole steps, so the value emitted
     * through {@code onChange} is always {@code min + k*step}. The row body
     * itself is inert — the bar owns its touch, so there is no
     * {@code onRowClick} and the screen draws no row ripple.
     */
    static final class Slider extends Setting {

        private final int min;
        private final int max;
        private final int step;
        private final IntSupplier value;
        private final IntConsumer onChange;
        private final IntFunction<String> valueLabel;

        Slider(String title, String summary, int min, int max, int step,
               IntSupplier value, IntConsumer onChange,
               IntFunction<String> valueLabel) {
            super(title, summary);
            this.min = min;
            this.max = max;
            this.step = step;
            this.value = value;
            this.onChange = onChange;
            this.valueLabel = valueLabel;
        }

        @Override
        View createControl(Context context) {
            int current = value.getAsInt();

            TextView readout = new TextView(context);
            readout.setText(valueLabel.apply(current));
            readout.setTextColor(Chrome.color(context, R.color.text_primary));
            readout.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Chrome.dimen(context, R.dimen.text_row_value));
            readout.setGravity(Gravity.END);
            // Reserve digits' width so the bar doesn't jiggle while dragging.
            readout.setMinWidth(Chrome.dp(context, R.dimen.slider_readout_min));

            SeekBar bar = new SeekBar(context);
            // Seed progress before wiring the listener so this is silent.
            bar.setMin(min / step);
            bar.setMax(max / step);
            bar.setProgress(current / step);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int v = progress * step;
                    readout.setText(valueLabel.apply(v));
                    onChange.accept(v);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });

            LinearLayout box = new LinearLayout(context) {
                @Override
                public void setEnabled(boolean enabled) {
                    // Row.applyEnabled() disables the container it gets back;
                    // forward that to the bar so a greyed row is non-draggable.
                    super.setEnabled(enabled);
                    bar.setEnabled(enabled);
                }
            };
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            // Full-width slot: the bar flexes, the read-out keeps its reserve.
            box.addView(bar, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(readout);
            return box;
        }
    }
}
