package dev.androidterm.ui;

import android.content.Context;
import android.view.View;
import android.widget.Switch;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One entry in the settings dialog: a title, a one-line description, and an
 * optional trailing control. The control type lives in a subclass so the
 * dialog ({@link SettingsDialog}) stays type-agnostic — it lays out the
 * shared title/summary row and drops in whatever {@link #createControl}
 * returns.
 *
 * Only {@link Toggle} (a Switch) exists today. Add further types — a slider,
 * a choice list, a tappable action — as sibling subclasses; nothing in the
 * dialog or {@code MainActivity} needs to change to host them.
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
}
