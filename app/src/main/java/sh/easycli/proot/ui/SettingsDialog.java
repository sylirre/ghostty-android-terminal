package sh.easycli.proot.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import sh.easycli.proot.R;

/**
 * Shows a list of {@link Setting}s as a scrollable dialog, one row each.
 *
 * The dialog is deliberately ignorant of which options exist or how they
 * behave: it inflates the shared title/summary row and inserts whatever
 * control the Setting builds, so adding a new setting type touches only
 * {@link Setting}, not this class.
 */
final class SettingsDialog {

    private SettingsDialog() {}

    static void show(Context context, List<Setting> settings) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        // Built rows, so a change in one (e.g. a toggle) can re-grey the rest.
        List<Row> rows = new ArrayList<>(settings.size());
        Runnable refresh = () -> { for (Row r : rows) r.applyEnabled(); };

        LayoutInflater inflater = LayoutInflater.from(context);
        for (Setting setting : settings) {
            View row = inflater.inflate(R.layout.settings_row, list, false);
            ((TextView) row.findViewById(R.id.setting_title)).setText(setting.title);
            ((TextView) row.findViewById(R.id.setting_summary)).setText(setting.summary);

            View control = setting.createControl(context);
            if (control != null) {
                FrameLayout slot = row.findViewById(R.id.setting_control);
                slot.addView(control);
            }
            // Tapping anywhere on the row drives the control; the control
            // still handles direct touches itself. A disabled row ignores taps.
            row.setOnClickListener(v -> {
                if (setting.isEnabled()) setting.onRowClick(control);
            });
            // Let a change in this row re-evaluate every row's enabled state.
            setting.onChanged = refresh;
            rows.add(new Row(setting, row, control));
            list.addView(row);
        }
        refresh.run(); // seed the initial greyed/active state

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);

        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.settings_dialog_close, null)
                .show();
    }

    /** A rendered row paired with its setting, so enabled state can be re-applied. */
    private static final class Row {
        final Setting setting;
        final View view;
        final View control;

        Row(Setting setting, View view, View control) {
            this.setting = setting;
            this.view = view;
            this.control = control;
        }

        /** Greys out and freezes the row when its setting is gated off. */
        void applyEnabled() {
            boolean on = setting.isEnabled();
            view.setEnabled(on);
            view.setClickable(on);
            view.setAlpha(on ? 1f : 0.4f);
            if (control != null) control.setEnabled(on);
        }
    }
}
