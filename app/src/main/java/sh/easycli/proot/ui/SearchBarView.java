package sh.easycli.proot.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import sh.easycli.proot.R;

/**
 * Find bar shown below the top bar: a query field, a match counter, a
 * case-sensitivity toggle, previous/next buttons, and a close button.
 *
 * It implements {@link TerminalView.SearchListener} so the terminal can push
 * the live match count back into the counter. Typing is debounced so a fresh
 * scan runs shortly after the user stops rather than on every keystroke. All
 * terminal work is delegated to a {@link Listener} (wired in MainActivity to
 * the TerminalView search methods).
 */
public class SearchBarView extends LinearLayout implements TerminalView.SearchListener {

    public interface Listener {
        void onQueryChanged(String query, boolean caseSensitive);
        void onNext();
        void onPrev();
        void onClose();
    }

    private static final int BG = 0xFF14141A;
    private static final int KEY_BG = 0xFF21212A;
    private static final int KEY_BG_ACTIVE = 0xFF3D5AFE;
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
        setBackgroundColor(BG);

        field = new EditText(context);
        field.setSingleLine(true);
        field.setHint(R.string.search_hint);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xFF777788);
        field.setTextSize(16);
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
        addView(field, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        count = new TextView(context);
        count.setTextColor(0xFF9999A6);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(8), 0, dp(8), 0);
        count.setMinWidth(dp(48));
        setCountText(0, 0);
        addView(count, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        caseToggle = addButton(context.getString(R.string.search_case_label),
                context.getString(R.string.search_case_description), this::toggleCase);
        caseToggle.setBackgroundColor(KEY_BG);
        addButton(context.getString(R.string.search_prev_label),
                context.getString(R.string.search_prev_description), () -> {
                    if (listener != null) listener.onPrev();
                });
        addButton(context.getString(R.string.search_next_label),
                context.getString(R.string.search_next_description), () -> {
                    if (listener != null) listener.onNext();
                });
        addButton(context.getString(R.string.search_close_label),
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

    /** Hides the bar, clears the query, and drops the keyboard. */
    public void hide() {
        pendingQuery = false;
        field.setText("");
        removeCallbacks(runQuery);
        setVisibility(GONE);
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
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
        caseToggle.setBackgroundColor(caseSensitive ? KEY_BG_ACTIVE : KEY_BG);
        removeCallbacks(runQuery);
        fireQuery();
    }

    private void fireQuery() {
        pendingQuery = false;
        if (listener != null) listener.onQueryChanged(field.getText().toString(), caseSensitive);
    }

    private void setCountText(int current, int total) {
        // Flag a query that found nothing (but not an empty field) in red.
        boolean noResults = total == 0 && field.getText().length() > 0;
        count.setTextColor(noResults ? 0xFFE57373 : 0xFF9999A6);
        count.setText(total == 0
                ? getContext().getString(R.string.search_no_results)
                : getContext().getString(R.string.search_count, current, total));
    }

    private TextView addButton(String label, String description, Runnable action) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        int pad = dp(14);
        b.setPadding(pad, dp(12), pad, dp(12));
        b.setClickable(true);
        b.setOnClickListener(v -> action.run());
        addView(b, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
