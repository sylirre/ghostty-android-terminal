/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

import io.github.sylirre.terminal.R;

/**
 * The theme chooser: every theme shown with its name and a mini palette strip
 * (background, foreground, and the six primary ANSI colors), sectioned into
 * built-in presets and the user's saved themes — a picker that shows what it
 * is picking, replacing the old name-only single-choice list.
 */
final class ThemePickerDialog {

    private ThemePickerDialog() {}

    static void show(Context ctx, ThemeStore store, String selectedName,
            Consumer<TerminalTheme> onPick) {
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int padH = Chrome.dp(ctx, R.dimen.space_2);
        list.setPaddingRelative(padH, 0, padH, Chrome.dp(ctx, R.dimen.space_2));

        final AlertDialog[] dialog = new AlertDialog[1];
        addSection(ctx, list, ctx.getString(R.string.theme_picker_presets),
                store.presets(), selectedName, dialog, onPick);
        List<TerminalTheme> user = store.userThemes();
        if (!user.isEmpty()) {
            addSection(ctx, list, ctx.getString(R.string.theme_picker_user),
                    user, selectedName, dialog, onPick);
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(list);
        dialog[0] = new AlertDialog.Builder(ctx)
                .setTitle(R.string.theme_picker_label)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void addSection(Context ctx, LinearLayout list, String title,
            List<TerminalTheme> themes, String selectedName,
            AlertDialog[] dialog, Consumer<TerminalTheme> onPick) {
        TextView header = new TextView(ctx, null, 0, R.style.SectionHeader);
        header.setText(title);
        list.addView(header);
        float radius = Chrome.dimen(ctx, R.dimen.radius_md);
        for (TerminalTheme theme : themes) {
            boolean selected = theme.name.equals(selectedName);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Chrome.dp(ctx, R.dimen.touch_min));
            row.setPaddingRelative(Chrome.dp(ctx, R.dimen.space_3),
                    Chrome.dp(ctx, R.dimen.space_2),
                    Chrome.dp(ctx, R.dimen.space_3),
                    Chrome.dp(ctx, R.dimen.space_2));
            row.setBackground(selected
                    ? Chrome.ripple(ctx, R.color.accent_soft, radius, 0)
                    : Chrome.rippleTransparent(ctx, radius));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                onPick.accept(theme);
            });

            TextView name = new TextView(ctx);
            name.setText(theme.name);
            name.setSingleLine(true);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Chrome.dimen(ctx, R.dimen.text_action));
            name.setTextColor(Chrome.color(ctx,
                    selected ? R.color.accent : R.color.text_primary));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, nameLp);

            MiniPaletteView strip = new MiniPaletteView(ctx, new int[]{
                    theme.background, theme.foreground,
                    theme.ansi[1], theme.ansi[2], theme.ansi[3],
                    theme.ansi[4], theme.ansi[5], theme.ansi[6]});
            LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            stripLp.setMarginStart(Chrome.dp(ctx, R.dimen.space_3));
            row.addView(strip, stripLp);

            list.addView(row);
        }
    }

    /** A row of small rounded color cells — a theme's palette at a glance. */
    private static final class MiniPaletteView extends View {
        private final int[] colors;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cell = new RectF();
        private final int cellPx;
        private final int gapPx;
        private final float cornerPx;

        MiniPaletteView(Context ctx, int[] colors) {
            super(ctx);
            this.colors = colors;
            cellPx = Chrome.dp(ctx, R.dimen.space_3);
            gapPx = Chrome.dp(ctx, R.dimen.space_1) / 2;
            cornerPx = cellPx / 4f;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Chrome.dp(ctx, R.dimen.stroke_hairline));
            edge.setColor(Chrome.color(ctx, R.color.border));
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(
                    colors.length * cellPx + (colors.length - 1) * gapPx, cellPx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float x = 0;
            for (int color : colors) {
                cell.set(x, 0, x + cellPx, cellPx);
                fill.setColor(color);
                canvas.drawRoundRect(cell, cornerPx, cornerPx, fill);
                canvas.drawRoundRect(cell, cornerPx, cornerPx, edge);
                x += cellPx + gapPx;
            }
        }
    }
}
