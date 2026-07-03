package sh.easycli.proot.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.IntConsumer;

import sh.easycli.proot.R;

/**
 * A self-contained RGB color picker: a live swatch, three 0–255 channel
 * sliders, and a hex field, kept in sync. Returns an opaque ARGB int via
 * {@code onPicked} when the user confirms. Built programmatically so it needs
 * no layout or third-party dependency.
 */
final class ColorPickerDialog {

    private ColorPickerDialog() {}

    static void show(Context ctx, String title, int initial, IntConsumer onPicked) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);

        View swatch = new View(ctx);
        LinearLayout.LayoutParams swatchLp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 56));
        swatchLp.bottomMargin = dp(ctx, 16);
        root.addView(swatch, swatchLp);

        SeekBar[] bars = new SeekBar[3];
        TextView[] vals = new TextView[3];
        String[] names = {"R", "G", "B"};
        int[] start = {(initial >> 16) & 0xFF, (initial >> 8) & 0xFF, initial & 0xFF};

        EditText hex = new EditText(ctx);
        hex.setInputType(InputType.TYPE_CLASS_TEXT);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6),
                new HexFilter()});
        hex.setHint("RRGGBB");
        hex.setBackground(Chrome.rounded(ctx, R.color.surface_2,
                Chrome.dimen(ctx, R.dimen.radius_sm), R.color.border));
        hex.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        hex.setTextColor(Chrome.color(ctx, R.color.text_primary));
        hex.setHintTextColor(Chrome.color(ctx, R.color.text_tertiary));

        // A single guard prevents the slider and hex listeners from echoing
        // each other into an infinite update loop.
        final boolean[] updating = {false};

        Runnable refreshFromBars = () -> {
            int c = colorFrom(bars);
            paintSwatch(swatch, c);
            for (int i = 0; i < 3; i++) vals[i].setText(String.valueOf(bars[i].getProgress()));
            updating[0] = true;
            hex.setText(String.format(Locale.US, "%06X", c & 0xFFFFFF));
            updating[0] = false;
        };

        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(ctx);
            label.setText(names[i]);
            label.setTextColor(Chrome.color(ctx, R.color.text_secondary));
            label.setWidth(dp(ctx, 24));

            SeekBar bar = new SeekBar(ctx);
            bar.setMax(255);
            bar.setProgress(start[i]);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

            TextView val = new TextView(ctx);
            val.setText(String.valueOf(start[i]));
            val.setTextColor(Chrome.color(ctx, R.color.text_secondary));
            val.setWidth(dp(ctx, 36));
            val.setGravity(Gravity.END);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (updating[0]) return; // programmatic change from the hex field
                    refreshFromBars.run();
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });

            row.addView(label);
            row.addView(bar, barLp);
            row.addView(val);
            root.addView(row);

            bars[i] = bar;
            vals[i] = val;
        }

        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexLp.topMargin = dp(ctx, 12);
        root.addView(hex, hexLp);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable e) {
                if (updating[0]) return;
                if (e.length() != 6) return;
                int c;
                try {
                    c = Integer.parseInt(e.toString(), 16);
                } catch (NumberFormatException ignored) {
                    return;
                }
                updating[0] = true;
                bars[0].setProgress((c >> 16) & 0xFF);
                bars[1].setProgress((c >> 8) & 0xFF);
                bars[2].setProgress(c & 0xFF);
                updating[0] = false;
                paintSwatch(swatch, 0xFF000000 | c);
                for (int i = 0; i < 3; i++) {
                    vals[i].setText(String.valueOf(bars[i].getProgress()));
                }
            }
        });

        paintSwatch(swatch, 0xFF000000 | (initial & 0xFFFFFF));
        hex.setText(String.format(Locale.US, "%06X", initial & 0xFFFFFF));

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(root)
                .setPositiveButton(R.string.theme_color_ok,
                        (d, w) -> onPicked.accept(colorFrom(bars)))
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    private static int colorFrom(SeekBar[] bars) {
        return 0xFF000000
                | (bars[0].getProgress() << 16)
                | (bars[1].getProgress() << 8)
                | bars[2].getProgress();
    }

    /** Paints the live preview as a rounded chip with a hairline edge. */
    private static void paintSwatch(View v, int color) {
        Context ctx = v.getContext();
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Chrome.dimen(ctx, R.dimen.radius_md));
        d.setStroke(Chrome.dp(ctx, R.dimen.stroke_hairline),
                Chrome.color(ctx, R.color.border));
        v.setBackground(d);
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /** Keeps the hex field to uppercase hex digits. */
    private static final class HexFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                android.text.Spanned dest, int dstart, int dend) {
            StringBuilder out = new StringBuilder();
            for (int i = start; i < end; i++) {
                char ch = Character.toUpperCase(source.charAt(i));
                if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F')) out.append(ch);
            }
            return out.toString();
        }
    }
}
