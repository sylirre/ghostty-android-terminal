/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.function.IntConsumer;

import io.github.sylirre.terminal.R;

/**
 * A visual color picker: a saturation/value field under a hue bar (pick by
 * eye), a before/after swatch, a #RRGGBB hex field for precision, and a row of
 * chips seeded from the theme's current colors for quick reuse. Returns an
 * opaque ARGB int via {@code onPicked} when the user confirms. Built
 * programmatically so it needs no layout or third-party dependency.
 */
final class ColorPickerDialog {

    private ColorPickerDialog() {}

    static void show(Context ctx, String title, int initial, IntConsumer onPicked) {
        show(ctx, title, initial, null, onPicked);
    }

    /**
     * {@code chipColors} (optional) seeds a quick-pick chip row — the theme
     * editor passes the working theme's 19 colors so related swatches are one
     * tap away.
     */
    static void show(Context ctx, String title, int initial, int[] chipColors,
            IntConsumer onPicked) {
        final float[] hsv = new float[3];
        Color.colorToHSV(0xFF000000 | (initial & 0xFFFFFF), hsv);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Chrome.dp(ctx, R.dimen.space_5);
        root.setPaddingRelative(pad, Chrome.dp(ctx, R.dimen.space_2), pad, 0);

        // Before | after split swatch, clipped to one rounded chip.
        View before = new View(ctx);
        View after = new View(ctx);
        LinearLayout swatch = new LinearLayout(ctx);
        swatch.setOrientation(LinearLayout.HORIZONTAL);
        roundClip(swatch, Chrome.dimen(ctx, R.dimen.radius_sm));
        swatch.setBackground(Chrome.rounded(ctx, R.color.surface_1,
                Chrome.dimen(ctx, R.dimen.radius_sm), R.color.border));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        swatch.addView(before, half);
        swatch.addView(after, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        before.setBackgroundColor(0xFF000000 | (initial & 0xFFFFFF));
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Chrome.dp(ctx, R.dimen.picker_swatch_height));
        swatchLp.bottomMargin = Chrome.dp(ctx, R.dimen.space_3);
        root.addView(swatch, swatchLp);

        EditText hex = new EditText(ctx);
        hex.setInputType(InputType.TYPE_CLASS_TEXT);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6), new HexFilter()});
        hex.setHint("RRGGBB");
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setSingleLine(true);
        hex.setBackground(ctx.getDrawable(R.drawable.bg_field));
        hex.setPaddingRelative(Chrome.dp(ctx, R.dimen.space_3),
                Chrome.dp(ctx, R.dimen.space_2),
                Chrome.dp(ctx, R.dimen.space_3),
                Chrome.dp(ctx, R.dimen.space_2));
        hex.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(ctx, R.dimen.text_action));

        // A single guard prevents the field/bar and hex listeners from echoing
        // each other into an infinite update loop.
        final boolean[] updating = {false};
        final Runnable[] sync = {null};

        SvFieldView sv = new SvFieldView(ctx, hsv, () -> sync[0].run());
        HueBarView hue = new HueBarView(ctx, hsv, () -> {
            sv.onHueChanged();
            sync[0].run();
        });

        sync[0] = () -> {
            int c = Color.HSVToColor(hsv);
            after.setBackgroundColor(c);
            updating[0] = true;
            hex.setText(String.format(Locale.US, "%06X", c & 0xFFFFFF));
            updating[0] = false;
        };

        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Chrome.dp(ctx, R.dimen.picker_field_height));
        svLp.bottomMargin = Chrome.dp(ctx, R.dimen.space_3);
        root.addView(sv, svLp);
        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Chrome.dp(ctx, R.dimen.picker_bar_height));
        hueLp.bottomMargin = Chrome.dp(ctx, R.dimen.space_3);
        root.addView(hue, hueLp);

        // "#" + hex, one row.
        LinearLayout hexRow = new LinearLayout(ctx);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView hash = new TextView(ctx);
        hash.setText("#");
        hash.setTypeface(Typeface.MONOSPACE);
        hash.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(ctx, R.dimen.text_action));
        hash.setTextColor(Chrome.color(ctx, R.color.text_secondary));
        hash.setPaddingRelative(0, 0, Chrome.dp(ctx, R.dimen.space_2), 0);
        hexRow.addView(hash);
        hexRow.addView(hex, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(hexRow);

        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable e) {
                if (updating[0] || e.length() != 6) return;
                int c;
                try {
                    c = Integer.parseInt(e.toString(), 16);
                } catch (NumberFormatException ignored) {
                    return;
                }
                Color.colorToHSV(0xFF000000 | c, hsv);
                sv.onHueChanged();
                sv.invalidate();
                hue.invalidate();
                after.setBackgroundColor(0xFF000000 | c);
            }
        });

        // Quick-pick chips from the theme's current colors.
        if (chipColors != null && chipColors.length > 0) {
            LinkedHashSet<Integer> unique = new LinkedHashSet<>();
            for (int c : chipColors) unique.add(0xFF000000 | (c & 0xFFFFFF));
            LinearLayout chips = new LinearLayout(ctx);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            int chipSize = Chrome.dp(ctx, R.dimen.picker_chip);
            float chipRadius = Chrome.dimen(ctx, R.dimen.radius_sm);
            for (int c : unique) {
                View chip = new View(ctx);
                GradientDrawable d = new GradientDrawable();
                d.setColor(c);
                d.setCornerRadius(chipRadius);
                d.setStroke(Chrome.dp(ctx, R.dimen.stroke_hairline),
                        Chrome.color(ctx, R.color.border));
                chip.setBackground(d);
                chip.setClickable(true);
                final int color = c;
                chip.setOnClickListener(v -> {
                    Color.colorToHSV(color, hsv);
                    sv.onHueChanged();
                    sv.invalidate();
                    hue.invalidate();
                    sync[0].run();
                });
                LinearLayout.LayoutParams chipLp =
                        new LinearLayout.LayoutParams(chipSize, chipSize);
                chipLp.setMarginEnd(Chrome.dp(ctx, R.dimen.space_1));
                chips.addView(chip, chipLp);
            }
            HorizontalScrollView chipScroll = new HorizontalScrollView(ctx);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipScroll.addView(chips);
            LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            chipsLp.topMargin = Chrome.dp(ctx, R.dimen.space_3);
            root.addView(chipScroll, chipsLp);
        }

        sync[0].run();

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(root)
                .setPositiveButton(R.string.action_ok,
                        (d, w) -> onPicked.accept(Color.HSVToColor(hsv)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void roundClip(View v, float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** Ring-style thumb shared by the SV field and the hue bar. */
    private static void drawThumb(Canvas canvas, float cx, float cy, float r, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF000000);
        p.setStrokeWidth(r * 0.55f);
        canvas.drawCircle(cx, cy, r, p);
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(r * 0.3f);
        canvas.drawCircle(cx, cy, r, p);
    }

    /** The saturation (→) / value (↓) plane for the current hue. */
    private static final class SvFieldView extends View {
        private final float[] hsv;
        private final Runnable onChange;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float thumbRadius;
        private Shader shader;
        private float shaderHue = -1;

        SvFieldView(Context ctx, float[] hsv, Runnable onChange) {
            super(ctx);
            this.hsv = hsv;
            this.onChange = onChange;
            thumbRadius = Chrome.dp(ctx, R.dimen.space_2);
            roundClip(this, Chrome.dimen(ctx, R.dimen.radius_sm));
        }

        void onHueChanged() {
            shaderHue = -1; // rebuild the shader for the new hue on next draw
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shader = null; // gradients are sized to the view
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;
            if (shader == null || shaderHue != hsv[0]) {
                shaderHue = hsv[0];
                int hueColor = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
                Shader sat = new LinearGradient(0, 0, w, 0,
                        0xFFFFFFFF, hueColor, Shader.TileMode.CLAMP);
                Shader val = new LinearGradient(0, 0, 0, h,
                        0xFFFFFFFF, 0xFF000000, Shader.TileMode.CLAMP);
                shader = new ComposeShader(val, sat, PorterDuff.Mode.MULTIPLY);
            }
            paint.setShader(shader);
            rect.set(0, 0, w, h);
            canvas.drawRect(rect, paint);
            drawThumb(canvas, hsv[1] * w, (1f - hsv[2]) * h, thumbRadius, thumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    hsv[1] = clamp01(event.getX() / getWidth());
                    hsv[2] = 1f - clamp01(event.getY() / getHeight());
                    invalidate();
                    onChange.run();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    /** The horizontal hue rainbow bar. */
    private static final class HueBarView extends View {
        private final float[] hsv;
        private final Runnable onChange;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float thumbRadius;
        private Shader shader;

        HueBarView(Context ctx, float[] hsv, Runnable onChange) {
            super(ctx);
            this.hsv = hsv;
            this.onChange = onChange;
            thumbRadius = Chrome.dp(ctx, R.dimen.space_2);
            roundClip(this, Chrome.dimen(ctx, R.dimen.radius_pill));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shader = null; // the gradient is sized to the view
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;
            if (shader == null) {
                int[] rainbow = new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
                        0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
                shader = new LinearGradient(0, 0, w, 0, rainbow, null,
                        Shader.TileMode.CLAMP);
            }
            paint.setShader(shader);
            rect.set(0, 0, w, h);
            canvas.drawRect(rect, paint);
            drawThumb(canvas, hsv[0] / 360f * w, h / 2f, thumbRadius, thumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    hsv[0] = clamp01(event.getX() / getWidth()) * 360f;
                    invalidate();
                    onChange.run();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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
