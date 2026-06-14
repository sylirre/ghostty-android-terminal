package sh.easycli.proot.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
            // still handles direct touches itself.
            row.setOnClickListener(v -> setting.onRowClick(control));
            list.addView(row);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);

        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.settings_dialog_close, null)
                .show();
    }
}
