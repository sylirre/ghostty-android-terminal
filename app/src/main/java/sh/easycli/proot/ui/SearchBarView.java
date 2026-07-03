package sh.easycli.proot.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import sh.easycli.proot.R;

/**
 * Find bar shown below the top bar: a rounded query field with a leading search
 * glyph, a match counter, a case-sensitivity toggle, previous/next buttons, and
 * a close button.
 *
 * It implements {@link TerminalView.SearchListener} so the terminal can push
 * the live match count back into the counter. Typing is debounced so a fresh
 * scan runs shortly after the user stops rather than on every keystroke. All
 * terminal work is delegated to a {@link Listener} (wired in MainActivity to
 * the TerminalView search methods). Styling comes from the design tokens via
 * {@link Chrome}.
 */
public class SearchBarView extends LinearLayout implements TerminalView.SearchListener {

    public interface Listener {
        void onQueryChanged(String query, boolean caseSensitive);
        void onNext();
        void onPrev();
        void onClose();
    }

    private static final long DEBOUNCE_MS = 150;

    private final EditText field;
    private final TextView count;
    private final TextView caseToggle;
    private Listener listener;
    private boolean caseSensitive;
    private boolean pendingQuery;
    private final Runnable runQuery = this::fireQuery;

    public SearchBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(Chrome.color(context, R.color.surface_1));
        setPadding(dp(8), dp(6), dp(8), dp(6));

        field = new EditText(context);
        field.setSingleLine(true);
        field.setHint(R.string.search_hint);
        field.setTextColor(Chrome.color(context, R.color.text_primary));
        field.setHintTextColor(Chrome.color(context, R.color.text_tertiary));
        field.setTextSize(15);
        field.setBackground(Chrome.rounded(context, R.color.surface_2,
                Chrome.dimen(context, R.dimen.radius_md), R.color.border));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setMinHeight(dp(40));
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_glyph_search, 0, 0, 0);
        field.setCompoundDrawablePadding(dp(8));
        field.setCompoundDrawableTintList(
                ColorStateList.valueOf(Chrome.color(context, R.color.text_secondary)));
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
        fieldLp.setMarginEnd(dp(4));
        addView(field, fieldLp);

        count = new TextView(context);
        count.setTextColor(Chrome.color(context, R.color.text_secondary));
        count.setTextSize(13);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(6), 0, dp(6), 0);
        count.setMinWidth(dp(44));
        setCountText(0, 0);
        addView(count, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        caseToggle = new TextView(context);
        caseToggle.setText(R.string.search_case_label);
        caseToggle.setTypeface(Typeface.DEFAULT_BOLD);
        caseToggle.setGravity(Gravity.CENTER);
        caseToggle.setTextSize(14);
        caseToggle.setContentDescription(context.getString(R.string.search_case_description));
        caseToggle.setMinWidth(dp(40));
        caseToggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        caseToggle.setClickable(true);
        caseToggle.setFocusable(true);
        caseToggle.setOnClickListener(v -> toggleCase());
        applyCaseToggle();
        LayoutParams caseLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        caseLp.setMarginEnd(dp(2));
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
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Reveals the bar, focuses the field, and raises the keyboard. */
    public void show() {
        setVisibility(VISIBLE);
        field.requestFocus();
        post(() -> {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Hides the bar and clears the query. Keyboard management is the caller's responsibility. */
    public void hide() {
        pendingQuery = false;
        field.setText("");
        removeCallbacks(runQuery);
        setVisibility(GONE);
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
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
        float r = Chrome.dimen(getContext(), R.dimen.radius_sm);
        caseToggle.setBackground(caseSensitive
                ? Chrome.rounded(getContext(), R.color.accent, r, 0)
                : Chrome.ripple(getContext(), R.color.surface_2, r, R.color.border));
        caseToggle.setTextColor(Chrome.color(getContext(),
                caseSensitive ? R.color.on_accent : R.color.text_secondary));
    }

    private void fireQuery() {
        pendingQuery = false;
        if (listener != null) listener.onQueryChanged(field.getText().toString(), caseSensitive);
    }

    private void setCountText(int current, int total) {
        // Flag a query that found nothing (but not an empty field) in red.
        boolean noResults = total == 0 && field.getText().length() > 0;
        count.setTextColor(Chrome.color(getContext(),
                noResults ? R.color.danger : R.color.text_secondary));
        count.setText(total == 0
                ? getContext().getString(R.string.search_no_results)
                : getContext().getString(R.string.search_count, current, total));
    }

    private void addIconButton(String label, String description, Runnable action) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(Chrome.color(getContext(), R.color.text_secondary));
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setMinWidth(dp(40));
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        b.setBackground(getContext().getDrawable(R.drawable.bg_toolbar_icon));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        Glyphs.applyTo(b);  // ▲ ▼ ✕ → vector icons
        addView(b, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
