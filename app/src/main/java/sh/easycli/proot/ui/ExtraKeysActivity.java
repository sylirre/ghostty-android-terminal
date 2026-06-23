package sh.easycli.proot.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import sh.easycli.proot.R;
import sh.easycli.proot.term.TerminalNative;

/**
 * Full-screen editor for the extra-keys toolbar, reached from Settings. Lets the
 * user choose which keys appear, group them into rows, reorder them (and the row
 * dividers between them) by dragging a handle, remove keys, and add custom text
 * keys.
 *
 * Model mirrors {@link ThemeActivity}: a mutable working list ({@code items}) is
 * loaded from {@link ExtraKeysConfig} and persisted immediately on every change,
 * so {@link MainActivity} reflects edits when it reloads the toolbar in
 * {@code onResume}. There is no Save/dirty step.
 */
public final class ExtraKeysActivity extends Activity {

    private ExtraKeysConfig config;

    /**
     * The working layout as a flat list, where {@link #ROW_BREAK} marks a
     * boundary between toolbar rows and every other entry is a key id. This lets
     * the existing single-list {@link DragController} compose rows: keys and the
     * dividers between them are dragged in one list. Split at the breaks on save.
     */
    private final List<String> items = new ArrayList<>();

    /**
     * Sentinel item marking a row boundary. A NUL can never be a real key id
     * (catalog ids, {@code lit:…} and {@code combo:…} are all printable).
     */
    private static final String ROW_BREAK = "\u0000";

    private ScrollView pageScroll;
    private LinearLayout previewRows;
    private LinearLayout enabledList;
    private TextView enabledEmpty;
    private GridLayout availableGrid;
    private View addRowButton;

    private final DragController drag = new DragController();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extra_keys);

        // Edge-to-edge like MainActivity/ThemeActivity: pad content past the bars.
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return WindowInsets.CONSUMED;
        });

        config = new ExtraKeysConfig(this);
        pageScroll = findViewById(R.id.page_scroll);
        previewRows = findViewById(R.id.preview_rows);
        enabledList = findViewById(R.id.enabled_list);
        enabledEmpty = findViewById(R.id.enabled_empty);
        availableGrid = findViewById(R.id.available_grid);
        addRowButton = findViewById(R.id.extra_keys_add_row);

        findViewById(R.id.extra_keys_done).setOnClickListener(v -> finish());
        findViewById(R.id.extra_keys_reset).setOnClickListener(v -> resetToDefaults());
        findViewById(R.id.extra_keys_add_custom).setOnClickListener(v -> promptCustom());
        findViewById(R.id.extra_keys_add_combo).setOnClickListener(v -> promptCombo());
        addRowButton.setOnClickListener(v -> addRow());

        loadItems();
        render();
    }

    /** Loads the saved rows into the flat working list, dropping unresolved ids and empty rows. */
    private void loadItems() {
        items.clear();
        for (List<String> row : config.rows()) {
            List<String> resolved = new ArrayList<>();
            for (String id : row) {
                if (ExtraKeysConfig.resolve(this, id) != null) resolved.add(id);
            }
            if (resolved.isEmpty()) continue;
            if (!items.isEmpty()) items.add(ROW_BREAK);
            items.addAll(resolved);
        }
    }

    private void persistAndRender() {
        normalizeItems();
        config.setRows(toRows());
        render();
    }

    private void resetToDefaults() {
        config.reset();
        loadItems();
        render();
    }

    /** Splits the working list into rows at each {@link #ROW_BREAK}, dropping empty rows. */
    private List<List<String>> toRows() {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String item : items) {
            if (ROW_BREAK.equals(item)) {
                if (!cur.isEmpty()) { rows.add(cur); cur = new ArrayList<>(); }
            } else {
                cur.add(item);
            }
        }
        if (!cur.isEmpty()) rows.add(cur);
        return rows;
    }

    /**
     * Tidies the working list after an edit or drag: drops leading breaks and
     * collapses runs of breaks, so no empty rows form in the middle. A single
     * trailing break is kept — that's an intentional empty new row the user can
     * drag keys into (it just isn't persisted until it has a key).
     */
    private void normalizeItems() {
        List<String> out = new ArrayList<>(items.size());
        boolean prevBreak = true;  // treat the start as a break to drop leading ones
        for (String item : items) {
            if (ROW_BREAK.equals(item)) {
                if (prevBreak) continue;
                out.add(item);
                prevBreak = true;
            } else {
                out.add(item);
                prevBreak = false;
            }
        }
        items.clear();
        items.addAll(out);
    }

    /** Rows currently in the editor, counting a pending empty trailing row toward the cap. */
    private int editorRowCount() {
        int rows = toRows().size();
        if (!items.isEmpty() && ROW_BREAK.equals(items.get(items.size() - 1))) rows++;
        return rows;
    }

    // --- Mutations ---

    private void addId(String id) {
        if (items.contains(id)) return;
        items.add(id);
        persistAndRender();
    }

    private void addRow() {
        if (items.isEmpty() || editorRowCount() >= ExtraKeysConfig.MAX_ROWS) return;
        if (ROW_BREAK.equals(items.get(items.size() - 1))) return;  // already a pending row
        items.add(ROW_BREAK);
        persistAndRender();
    }

    private void removeAt(int index) {
        if (index < 0 || index >= items.size()) return;
        items.remove(index);
        persistAndRender();
    }

    private void promptCustom() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setHint(R.string.extra_keys_add_custom_hint);

        LinearLayout container = new LinearLayout(this);
        int p = dp(20);
        container.setPadding(p, p / 2, p, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.extra_keys_add_custom_title)
                .setView(container)
                .setPositiveButton(R.string.theme_color_ok, (d, w) -> {
                    String text = expandEscapes(input.getText().toString());
                    if (text.isEmpty()) {
                        toast(R.string.extra_keys_custom_empty);
                        return;
                    }
                    String id = ExtraKeysConfig.literalId(text);
                    if (items.contains(id)) {
                        toast(R.string.extra_keys_custom_exists);
                        return;
                    }
                    addId(id);
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    /**
     * Builds a single-tap modifier combo (Ctrl-C, Ctrl-→, …): CTRL/ALT/SHIFT
     * toggles plus a base picker — either a typed character or a special key
     * from the spinner. Produces a {@code combo:} id via
     * {@link ExtraKeysConfig#comboId}.
     */
    private void promptCombo() {
        CheckBox ctrl = comboToggle(R.string.key_ctrl);
        CheckBox alt = comboToggle(R.string.key_alt);
        CheckBox shift = comboToggle(R.string.key_shift);
        LinearLayout modsRow = new LinearLayout(this);
        modsRow.setOrientation(LinearLayout.HORIZONTAL);
        modsRow.addView(ctrl);
        modsRow.addView(alt);
        modsRow.addView(shift);

        TextView baseLabel = new TextView(this);
        baseLabel.setText(R.string.extra_keys_combo_base_label);
        baseLabel.setPadding(0, dp(14), 0, dp(4));

        // Base picker: index 0 is "type a character", the rest are special keys.
        final List<String> labels = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();  // parallel to labels[1..]
        labels.add(getString(R.string.extra_keys_combo_base_char));
        addSpecial(labels, tokens, "← Left", "left");
        addSpecial(labels, tokens, "→ Right", "right");
        addSpecial(labels, tokens, "↑ Up", "up");
        addSpecial(labels, tokens, "↓ Down", "down");
        addSpecial(labels, tokens, "Home", "home");
        addSpecial(labels, tokens, "End", "end");
        addSpecial(labels, tokens, "PgUp", "pgup");
        addSpecial(labels, tokens, "PgDn", "pgdn");
        addSpecial(labels, tokens, "Tab", "tab");
        addSpecial(labels, tokens, "Enter", "enter");
        addSpecial(labels, tokens, "Esc", "esc");
        addSpecial(labels, tokens, "Delete", "del");
        addSpecial(labels, tokens, "Backspace", "bksp");
        addSpecial(labels, tokens, "Insert", "ins");
        for (int i = 1; i <= 12; i++) addSpecial(labels, tokens, "F" + i, "f" + i);

        Spinner spinner = new Spinner(this);
        // Render the arrow labels ("← Left", …) with vector icons too, in both
        // the closed spinner view and the dropdown.
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                Glyphs.applyTo((TextView) v);
                return v;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                Glyphs.applyTo((TextView) v);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        EditText charInput = new EditText(this);
        charInput.setInputType(InputType.TYPE_CLASS_TEXT);
        charInput.setSingleLine(true);
        charInput.setHint(R.string.extra_keys_combo_char_hint);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                charInput.setEnabled(pos == 0);  // only meaningful for "Character"
            }
            @Override
            public void onNothingSelected(AdapterView<?> p) { }
        });

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        container.setPadding(p, p / 2, p, 0);
        container.addView(modsRow);
        container.addView(baseLabel);
        container.addView(spinner);
        container.addView(charInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.extra_keys_add_combo_title)
                .setView(container)
                .setPositiveButton(R.string.theme_color_ok, (d, w) -> {
                    int mods = (ctrl.isChecked() ? TerminalNative.MOD_CTRL : 0)
                            | (alt.isChecked() ? TerminalNative.MOD_ALT : 0)
                            | (shift.isChecked() ? TerminalNative.MOD_SHIFT : 0);
                    if (mods == 0) {
                        toast(R.string.extra_keys_combo_no_mod);
                        return;
                    }
                    int pos = spinner.getSelectedItemPosition();
                    String base;
                    if (pos <= 0) {
                        String t = charInput.getText().toString();
                        if (t.codePointCount(0, t.length()) != 1) {
                            toast(R.string.extra_keys_combo_no_char);
                            return;
                        }
                        base = t.toLowerCase(Locale.ROOT);  // canonical: Ctrl-C == Ctrl-c
                    } else {
                        base = tokens.get(pos - 1);
                    }
                    String id = ExtraKeysConfig.comboId(mods, base);
                    if (items.contains(id)) {
                        toast(R.string.extra_keys_custom_exists);
                        return;
                    }
                    addId(id);
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    private CheckBox comboToggle(int labelRes) {
        CheckBox box = new CheckBox(this);
        box.setText(labelRes);
        box.setTextColor(0xFFEAEAF0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    private static void addSpecial(List<String> labels, List<String> tokens,
                                   String label, String token) {
        labels.add(label);
        tokens.add(token);
    }

    // --- Rendering ---

    private void render() {
        buildPreview();
        buildEnabledList();
        buildAvailableGrid();
        updateAddRowState();
    }

    /** Live preview of the toolbar: one horizontal (scrollable) strip per row. */
    private void buildPreview() {
        previewRows.removeAllViews();
        List<List<String>> rows = toRows();
        for (int r = 0; r < rows.size(); r++) {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setBackgroundColor(0xFF21212A);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            for (String id : rows.get(r)) {
                ExtraKey key = ExtraKeysConfig.resolve(this, id);
                if (key == null) continue;
                TextView chip = new TextView(this);
                chip.setText(key.label);
                chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                chip.setTextColor(Color.WHITE);
                chip.setGravity(Gravity.CENTER);
                int pad = dp(14);
                chip.setPadding(pad, dp(12), pad, dp(12));
                Glyphs.applyTo(chip);
                strip.addView(chip);
            }
            scroll.addView(strip, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r > 0) lp.topMargin = dp(1);  // hairline gap so stacked rows read apart
            previewRows.addView(scroll, lp);
        }
    }

    /**
     * Builds the draggable editor list: a key row per key id and a divider row
     * per {@link #ROW_BREAK}. Both kinds carry the same handle/remove ids so the
     * drag controller and glyph swap treat them uniformly.
     */
    private void buildEnabledList() {
        enabledList.removeAllViews();
        enabledEmpty.setVisibility(hasAnyKey() ? View.GONE : View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            String item = items.get(i);
            int layout = ROW_BREAK.equals(item)
                    ? R.layout.extra_keys_row_break : R.layout.extra_keys_edit_row;
            View rowView = inf.inflate(layout, enabledList, false);
            if (!ROW_BREAK.equals(item)) {
                TextView rowLabel = rowView.findViewById(R.id.row_label);
                rowLabel.setText(labelFor(ExtraKeysConfig.resolve(this, item)));
                Glyphs.applyTo(rowLabel);
            }
            Glyphs.applyTo(rowView.findViewById(R.id.row_handle));  // ☰ → icon
            Glyphs.applyTo(rowView.findViewById(R.id.row_remove));  // ✕ → icon
            rowView.findViewById(R.id.row_remove).setOnClickListener(v -> removeAt(index));
            drag.attach(rowView.findViewById(R.id.row_handle), rowView);
            enabledList.addView(rowView);
        }
    }

    private void buildAvailableGrid() {
        availableGrid.removeAllViews();
        Set<String> enabled = new HashSet<>();
        for (String item : items) if (!ROW_BREAK.equals(item)) enabled.add(item);
        for (ExtraKey key : ExtraKeysConfig.catalog(this).values()) {
            if (enabled.contains(key.id)) continue;
            addAvailableChip(key);
        }
    }

    private boolean hasAnyKey() {
        for (String item : items) if (!ROW_BREAK.equals(item)) return true;
        return false;
    }

    /** Greys out "Add row" at the row cap or when a pending empty row already exists. */
    private void updateAddRowState() {
        boolean pendingRow = !items.isEmpty() && ROW_BREAK.equals(items.get(items.size() - 1));
        boolean canAdd = hasAnyKey() && !pendingRow
                && editorRowCount() < ExtraKeysConfig.MAX_ROWS;
        addRowButton.setEnabled(canAdd);
        addRowButton.setAlpha(canAdd ? 1f : 0.4f);
    }

    private void addAvailableChip(ExtraKey key) {
        TextView chip = new TextView(this);
        chip.setText(getString(R.string.extra_keys_add_chip, key.label));
        chip.setTextColor(0xFFEAEAF0);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(12), dp(8), dp(12));
        chip.setBackgroundColor(0xFF262630);
        chip.setClickable(true);
        chip.setOnClickListener(v -> addId(key.id));
        Glyphs.applyTo(chip);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setGravity(Gravity.FILL_HORIZONTAL);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        chip.setLayoutParams(lp);
        availableGrid.addView(chip);
    }

    /** Display label; custom text keys show their literal verbatim. */
    private String labelFor(ExtraKey key) {
        return key != null ? key.label : "";
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Expands \n → newline and \\n → literal \n; other backslash sequences pass through. */
    private static String expandEscapes(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == 'n') { sb.append('\n'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else { sb.append(raw.charAt(i)); }
            } else {
                sb.append(raw.charAt(i));
            }
        }
        return sb.toString();
    }

    /**
     * Hand-rolled drag-to-reorder for the enabled-keys list (no RecyclerView in
     * this framework-only app). Touching a row's handle picks it up; the row
     * follows the finger via translation while the rows it passes shift to open
     * a gap; releasing commits the new order.
     *
     * Children are never re-parented mid-drag (only translated), so indices stay
     * stable; the dragged row is raised by elevation rather than
     * {@code bringToFront()}, which would reorder the child list. The page
     * ScrollView is told not to intercept, and auto-scrolls when the finger
     * nears an edge.
     */
    private final class DragController {
        private View dragRow;
        private int origIndex;
        private int target;
        private int rowHeight;
        private int count;
        private float downRawY;
        private int scrollStartY;
        private float lastRawY;
        private int autoDir;       // -1 up, +1 down, 0 idle
        private boolean active;

        private final Runnable autoScroll = new Runnable() {
            @Override
            public void run() {
                if (!active || autoDir == 0) return;
                int before = pageScroll.getScrollY();
                pageScroll.scrollBy(0, dp(10) * autoDir);
                if (pageScroll.getScrollY() == before) {  // hit a scroll bound
                    autoDir = 0;
                    return;
                }
                update(lastRawY);
                pageScroll.postOnAnimation(this);
            }
        };

        void attach(View handle, View rowView) {
            // The handle is a dedicated grip with no click action, so consuming
            // touch here (returning true) starts the drag immediately.
            handle.setOnTouchListener((v, e) -> onTouch(rowView, e));
        }

        private boolean onTouch(View rowView, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    begin(rowView, e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (active) {
                        lastRawY = e.getRawY();
                        update(e.getRawY());
                        maybeAutoScroll(e.getRawY());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (active) end();
                    return true;
                default:
                    return false;
            }
        }

        private void begin(View rowView, MotionEvent e) {
            dragRow = rowView;
            origIndex = enabledList.indexOfChild(rowView);
            target = origIndex;
            count = enabledList.getChildCount();
            rowHeight = rowView.getHeight();
            if (rowHeight <= 0) rowHeight = dp(52);
            downRawY = e.getRawY();
            lastRawY = downRawY;
            scrollStartY = pageScroll.getScrollY();
            autoDir = 0;
            active = true;
            dragRow.setAlpha(0.92f);
            dragRow.setElevation(dp(8));
            dragRow.setBackgroundColor(0xFF2B2B3A);
            pageScroll.requestDisallowInterceptTouchEvent(true);
        }

        /** Follows the finger and shifts neighbours to open a gap at the target. */
        private void update(float rawY) {
            if (!active) return;
            float eff = (rawY - downRawY) + (pageScroll.getScrollY() - scrollStartY);
            dragRow.setTranslationY(eff);
            int t = origIndex + Math.round(eff / rowHeight);
            if (t < 0) t = 0;
            if (t > count - 1) t = count - 1;
            target = t;
            for (int i = 0; i < count; i++) {
                View child = enabledList.getChildAt(i);
                if (child == dragRow) continue;
                float ty = 0;
                if (origIndex < target && i > origIndex && i <= target) ty = -rowHeight;
                else if (origIndex > target && i >= target && i < origIndex) ty = rowHeight;
                child.setTranslationY(ty);
            }
        }

        private void maybeAutoScroll(float rawY) {
            int[] loc = new int[2];
            pageScroll.getLocationOnScreen(loc);
            float y = rawY - loc[1];
            int edge = dp(56);
            int dir = 0;
            if (y < edge) dir = -1;
            else if (y > pageScroll.getHeight() - edge) dir = +1;
            if (dir != autoDir) {
                autoDir = dir;
                if (dir != 0) pageScroll.postOnAnimation(autoScroll);
            }
        }

        private void end() {
            active = false;
            autoDir = 0;
            // render() rebuilds the rows fresh, clearing every translation and the
            // dragged row's elevation/alpha, so no manual visual reset is needed.
            if (target != origIndex && target >= 0 && target < items.size()) {
                items.add(target, items.remove(origIndex));
                persistAndRender();
            } else {
                render();
            }
            dragRow = null;
        }
    }
}
