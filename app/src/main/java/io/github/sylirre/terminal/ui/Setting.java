/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
                    .setNegativeButton("Cancel", null)
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
}
