package dev.androidterm.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * One entry in the settings dialog: a title, a one-line description, and an
 * optional trailing control. The control type lives in a subclass so the
 * dialog ({@link SettingsDialog}) stays type-agnostic — it lays out the
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

    Setting(String title, String summary) {
        this.title = title;
        this.summary = summary;
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
            sw.setOnCheckedChangeListener((btn, checked) -> onChange.accept(checked));
            return sw;
        }

        @Override
        void onRowClick(View control) {
            ((Switch) control).toggle(); // fires the listener, which persists + applies
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
}
