/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.sylirre.terminal.R;

/**
 * Find bar: a rounded query field with a leading search glyph, a match
 * counter, a case-sensitivity toggle, previous/next buttons, and a close
 * button.
 *
 * It overlays the terminal (a FrameLayout child in activity_main, aligned
 * just under the top bar) rather than sitting in the layout column, so
 * opening it never resizes the terminal — no SIGWINCH to the shell — and it
 * slides/fades in and out. It implements {@link TerminalView.SearchListener}
 * so the terminal can push the live match count back into the counter.
 * Typing is debounced so a fresh scan runs shortly after the user stops
 * rather than on every keystroke. All terminal work is delegated to a
 * {@link Listener} (wired in MainActivity to the TerminalView search
 * methods). Colors follow the terminal theme via {@link #applyPalette}.
 */
public class SearchBarView extends LinearLayout implements TerminalView.SearchListener {

    public interface Listener {
        void onQueryChanged(String query, boolean caseSensitive);
        void onNext();
        void onPrev();
        void onClose();
    }

    private static final long DEBOUNCE_MS = 150;
    private static final long ANIM_MS = 200;

    private final EditText field;
    private final TextView count;
    private final TextView caseToggle;
    private final List<TextView> iconButtons = new ArrayList<>();
    private Listener listener;
    private ChromePalette palette;
    private boolean caseSensitive;
    private boolean pendingQuery;
    private boolean lastCountNoResults;
    /** Desired open state; visibility lags it while the hide animation runs. */
    private boolean shown;
    private final Runnable runQuery = this::fireQuery;

    public SearchBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        palette = ChromePalette.from(context, Color.BLACK);
        // Same height and side padding as the top bar it hangs under, so the
        // trailing buttons line up in the same columns.
        setMinimumHeight(Chrome.dp(context, R.dimen.top_bar_height));
        int padH = Chrome.dp(context, R.dimen.space_1);
        int padV = (Chrome.dp(context, R.dimen.top_bar_height)
                - Chrome.dp(context, R.dimen.icon_button)) / 2;
        setPaddingRelative(padH, padV, padH, padV);
        // Overlay chrome: elevated above the terminal with its own bottom edge.
        setElevation(Chrome.dimen(context, R.dimen.elevation_bar)
                + Chrome.dp(context, R.dimen.stroke_hairline));
        setOutlineProvider(ViewOutlineProvider.BOUNDS);

        field = new EditText(context);
        field.setSingleLine(true);
        field.setHint(R.string.search_hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(context, R.dimen.text_action));
        field.setPaddingRelative(Chrome.dp(context, R.dimen.space_3),
                Chrome.dp(context, R.dimen.space_2),
                Chrome.dp(context, R.dimen.space_3),
                Chrome.dp(context, R.dimen.space_2));
        field.setMinHeight(Chrome.dp(context, R.dimen.icon_button));
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_glyph_search, 0, 0, 0);
        field.setCompoundDrawablePadding(Chrome.dp(context, R.dimen.space_2));
        // A filter field: no autocorrect/suggestions getting in the way of a
        // literal search query; Enter acts as the search/next action.
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_FILTER
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                pendingQuery = true;
                removeCallbacks(runQuery);
                postDelayed(runQuery, DEBOUNCE_MS);
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            // Flush a still-pending query immediately; otherwise advance.
            if (pendingQuery) {
                removeCallbacks(runQuery);
                fireQuery();
            } else if (listener != null) {
                listener.onNext();
            }
            return true;
        });
        LayoutParams fieldLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(field, fieldLp);

        count = new TextView(context);
        count.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Chrome.dimen(context, R.dimen.text_row_summary));
        count.setGravity(Gravity.CENTER);
        count.setPaddingRelative(Chrome.dp(context, R.dimen.space_1), 0,
                Chrome.dp(context, R.dimen.space_1), 0);
        count.setMinWidth(Chrome.dp(context, R.dimen.icon_button));
        LayoutParams countLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        countLp.setMarginStart(Chrome.dp(context, R.dimen.space_1));
        addView(count, countLp);

        caseToggle = new TextView(context);
        caseToggle.setText(R.string.search_case_label);
        caseToggle.setTypeface(Typeface.DEFAULT_BOLD);
        caseToggle.setGravity(Gravity.CENTER);
        caseToggle.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Chrome.dimen(context, R.dimen.text_tab));
        caseToggle.setContentDescription(context.getString(R.string.search_case_description));
        caseToggle.setMinWidth(Chrome.dp(context, R.dimen.icon_button));
        caseToggle.setPaddingRelative(Chrome.dp(context, R.dimen.space_2), 0,
                Chrome.dp(context, R.dimen.space_2), 0);
        caseToggle.setClickable(true);
        caseToggle.setFocusable(true);
        caseToggle.setOnClickListener(v -> toggleCase());
        LayoutParams caseLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, Chrome.dp(context, R.dimen.icon_button));
        caseLp.setMarginStart(Chrome.dp(context, R.dimen.space_1));
        addView(caseToggle, caseLp);

        addIconButton(context.getString(R.string.search_prev_label),
                context.getString(R.string.search_prev_description), () -> {
                    if (listener != null) listener.onPrev();
                });
        addIconButton(context.getString(R.string.search_next_label),
                context.getString(R.string.search_next_description), () -> {
                    if (listener != null) listener.onNext();
                });
        addIconButton(context.getString(R.string.search_close_label),
                context.getString(R.string.search_close_description), () -> {
                    if (listener != null) listener.onClose();
                });
        restyle();
        setCountText(0, 0);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Re-derives all colors from a new chrome palette. */
    public void applyPalette(ChromePalette p) {
        palette = p;
        restyle();
    }

    private void restyle() {
        setBackground(palette.barSurface(true));
        field.setTextColor(palette.textPrimary);
        // Secondary (not tertiary) hint ink: tertiary fails contrast on the field.
        field.setHintTextColor(palette.textSecondary);
        field.setBackground(palette.rounded(palette.surface2,
                Chrome.dimen(getContext(), R.dimen.radius_md), palette.border));
        field.setCompoundDrawableTintList(ColorStateList.valueOf(palette.textSecondary));
        count.setTextColor(lastCountNoResults
                ? Chrome.color(getContext(), R.color.danger) : palette.textSecondary);
        applyCaseToggle();
        for (TextView b : iconButtons) {
            b.setTextColor(palette.textSecondary);
            b.setBackground(palette.ripple(palette.surface2,
                    Chrome.dimen(getContext(), R.dimen.radius_md), palette.border));
            Glyphs.applyTo(b);  // re-tint the vector glyph from the new ink
        }
    }

    /** Reveals the bar (slide + fade), focuses the field, raises the keyboard. */
    public void show() {
        if (shown) return;
        shown = true;
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        post(() -> {
            if (!shown) return; // hidden again before the first layout
            setTranslationY(-getHeight());
            animate().translationY(0f).alpha(1f).setDuration(ANIM_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
            field.requestFocus();
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Hides the bar and clears the query. Keyboard management is the caller's responsibility. */
    public void hide() {
        // Order matters: clearing the field is itself a text change, so the
        // watcher re-arms the debounce. Cancel and clear the flag *after* it,
        // or the bar closes claiming a query is queued when none is — and the
        // next Enter on the reopened bar re-runs the (empty) query instead of
        // advancing to the next match.
        field.setText("");
        removeCallbacks(runQuery);
        pendingQuery = false;
        if (!shown) return;
        shown = false;
        animate().cancel();
        animate().translationY(-getHeight()).alpha(0f).setDuration(ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    setVisibility(GONE);
                    setTranslationY(0f);
                    setAlpha(1f);
                }).start();
    }

    public boolean isOpen() {
        return shown;
    }

    @Override
    public void onSearchUpdated(int current, int total) {
        setCountText(current, total);
    }

    private void toggleCase() {
        caseSensitive = !caseSensitive;
        applyCaseToggle();
        removeCallbacks(runQuery);
        fireQuery();
    }

    private void applyCaseToggle() {
        float r = Chrome.dimen(getContext(), R.dimen.radius_md);
        caseToggle.setBackground(caseSensitive
                ? palette.ripple(palette.accent, r, 0)
                : palette.ripple(palette.surface2, r, palette.border));
        caseToggle.setTextColor(caseSensitive ? palette.onAccent : palette.textSecondary);
    }

    private void fireQuery() {
        pendingQuery = false;
        if (listener != null) listener.onQueryChanged(field.getText().toString(), caseSensitive);
    }

    private void setCountText(int current, int total) {
        // Flag a query that found nothing (but not an empty field) in red.
        lastCountNoResults = total == 0 && field.getText().length() > 0;
        count.setTextColor(lastCountNoResults
                ? Chrome.color(getContext(), R.color.danger) : palette.textSecondary);
        count.setText(total == 0
                ? getContext().getString(R.string.search_no_results)
                : getContext().getString(R.string.search_count, current, total));
    }

    private void addIconButton(String label, String description, Runnable action) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Chrome.dimen(getContext(), R.dimen.text_icon_glyph));
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        // Same fixed square as the top-bar icon buttons so the two bars match.
        int size = Chrome.dp(getContext(), R.dimen.icon_button);
        LayoutParams lp = new LayoutParams(size, size);
        lp.setMarginStart(Chrome.dp(getContext(), R.dimen.space_1));
        addView(b, lp);
        iconButtons.add(b);
    }
}
