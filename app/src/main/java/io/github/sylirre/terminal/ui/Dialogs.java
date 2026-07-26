/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.Function;

import io.github.sylirre.terminal.R;

/**
 * Shared dialog vocabulary so every screen prompts the same way: a styled
 * input field ({@code bg_field}, monospace for path-like values), inline
 * validation that keeps the dialog open instead of a post-dismissal toast,
 * an optional "Use default" action for blank-means-default settings, and a
 * danger-tinted confirm for destructive actions. Window chrome (rounded
 * surface, accent buttons) comes from {@code Theme.Terminal.Dialog}.
 */
final class Dialogs {

    private Dialogs() {}

    /**
     * A styled single-purpose text field. {@code monospacePath} switches to a
     * monospace face and soft-wraps instead of scrolling, so long
     * colon-separated PATHs and shell command lines stay fully readable.
     */
    static EditText field(Context context, String initial, String hint, boolean monospacePath) {
        EditText input = new EditText(context);
        input.setBackground(context.getDrawable(R.drawable.bg_field));
        int padH = Chrome.dp(context, R.dimen.space_3);
        int padV = Chrome.dp(context, R.dimen.space_2);
        input.setPaddingRelative(padH, padV, padH, padV);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(context, R.dimen.text_action));
        input.setHint(hint);
        // URI variation: no autocorrect/suggestions mangling paths and commands.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (monospacePath) {
            input.setTypeface(Typeface.MONOSPACE);
            input.setHorizontallyScrolling(false);
            input.setMaxLines(4);
        } else {
            input.setSingleLine(true);
        }
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        return input;
    }

    /**
     * A text prompt with inline validation. {@code validate} maps the trimmed
     * input to an error message or null; on error the message appears under
     * the field and the dialog stays open (the typed value is never lost).
     * A non-null {@code onUseDefault} adds a neutral "Use default" button.
     */
    static void prompt(Context context, int titleRes, String initial, String hint,
            boolean monospacePath, Function<String, String> validate,
            Consumer<String> onOk, Runnable onUseDefault) {
        prompt(context, context.getText(titleRes), initial, hint, monospacePath,
                validate, onOk, onUseDefault);
    }

    static void prompt(Context context, CharSequence title, String initial, String hint,
            boolean monospacePath, Function<String, String> validate,
            Consumer<String> onOk, Runnable onUseDefault) {
        EditText input = field(context, initial, hint, monospacePath);

        TextView error = new TextView(context);
        error.setTextColor(Chrome.color(context, R.color.danger));
        error.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(context, R.dimen.text_caption));
        error.setPaddingRelative(Chrome.dp(context, R.dimen.space_1),
                Chrome.dp(context, R.dimen.space_1), 0, 0);
        error.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = Chrome.dp(context, R.dimen.space_5);
        box.setPaddingRelative(padH, Chrome.dp(context, R.dimen.space_2), padH, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(error);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_ok, null) // listener installed after show()
                .setNegativeButton(R.string.action_cancel, null);
        if (onUseDefault != null) {
            builder.setNeutralButton(R.string.action_use_default, (d, w) -> onUseDefault.run());
        }
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();

        // Validate on OK without dismissing; dismiss only when the value passes.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            String message = validate != null ? validate.apply(value) : null;
            if (message != null) {
                error.setText(message);
                error.setVisibility(View.VISIBLE);
                return;
            }
            onOk.accept(value);
            dialog.dismiss();
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                error.setVisibility(View.GONE);
            }
        });
    }

    /** A confirm dialog for a destructive action; the confirm button is danger-tinted. */
    static void confirmDanger(Context context, CharSequence title, CharSequence message,
            int confirmRes, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(confirmRes, (d, w) -> onConfirm.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(Chrome.color(context, R.color.danger));
    }
}
