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
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sh.easycli.proot.R;

/**
 * Full-screen editor for the extra-keys toolbar, reached from Settings. Lets the
 * user choose which keys appear, reorder them by dragging a row's handle, remove
 * keys, and add custom text keys.
 *
 * Model mirrors {@link ThemeActivity}: a mutable working list ({@code ids}) is
 * loaded from {@link ExtraKeysConfig} and persisted immediately on every change,
 * so {@link MainActivity} reflects edits when it reloads the toolbar in
 * {@code onResume}. There is no Save/dirty step.
 */
public final class ExtraKeysActivity extends Activity {

    private ExtraKeysConfig config;
    private final List<String> ids = new ArrayList<>();

    private ScrollView pageScroll;
    private LinearLayout previewRow;
    private LinearLayout enabledList;
    private TextView enabledEmpty;
    private GridLayout availableGrid;

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
        previewRow = findViewById(R.id.preview_row);
        enabledList = findViewById(R.id.enabled_list);
        enabledEmpty = findViewById(R.id.enabled_empty);
        availableGrid = findViewById(R.id.available_grid);

        findViewById(R.id.extra_keys_done).setOnClickListener(v -> finish());
        findViewById(R.id.extra_keys_reset).setOnClickListener(v -> resetToDefaults());
        findViewById(R.id.extra_keys_add_custom).setOnClickListener(v -> promptCustom());

        loadIds();
        render();
    }

    /** Loads the saved order, dropping any id that no longer resolves. */
    private void loadIds() {
        ids.clear();
        for (String id : config.order()) {
            if (ExtraKeysConfig.resolve(this, id) != null) ids.add(id);
        }
    }

    private void persistAndRender() {
        config.setOrder(ids);
        render();
    }

    private void resetToDefaults() {
        config.reset();
        loadIds();
        render();
    }

    // --- Mutations ---

    private void addId(String id) {
        if (ids.contains(id)) return;
        ids.add(id);
        persistAndRender();
    }

    private void removeAt(int index) {
        if (index < 0 || index >= ids.size()) return;
        ids.remove(index);
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
                    String text = input.getText().toString();
                    if (text.isEmpty()) {
                        toast(R.string.extra_keys_custom_empty);
                        return;
                    }
                    String id = ExtraKeysConfig.literalId(text);
                    if (ids.contains(id)) {
                        toast(R.string.extra_keys_custom_exists);
                        return;
                    }
                    addId(id);
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    // --- Rendering ---

    private void render() {
        buildPreview();
        buildEnabledList();
        buildAvailableGrid();
    }

    private void buildPreview() {
        previewRow.removeAllViews();
        for (String id : ids) {
            ExtraKey key = ExtraKeysConfig.resolve(this, id);
            if (key == null) continue;
            TextView chip = new TextView(this);
            chip.setText(key.label);
            chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            chip.setTextColor(Color.WHITE);
            chip.setGravity(Gravity.CENTER);
            int pad = dp(14);
            chip.setPadding(pad, dp(12), pad, dp(12));
            previewRow.addView(chip);
        }
    }

    private void buildEnabledList() {
        enabledList.removeAllViews();
        enabledEmpty.setVisibility(ids.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < ids.size(); i++) {
            final int index = i;
            ExtraKey key = ExtraKeysConfig.resolve(this, ids.get(i));
            View rowView = inf.inflate(R.layout.extra_keys_edit_row, enabledList, false);
            ((TextView) rowView.findViewById(R.id.row_label)).setText(labelFor(key));
            rowView.findViewById(R.id.row_remove).setOnClickListener(v -> removeAt(index));
            drag.attach(rowView.findViewById(R.id.row_handle), rowView);
            enabledList.addView(rowView);
        }
    }

    private void buildAvailableGrid() {
        availableGrid.removeAllViews();
        Set<String> enabled = new HashSet<>(ids);
        for (ExtraKey key : ExtraKeysConfig.catalog(this).values()) {
            if (enabled.contains(key.id)) continue;
            addAvailableChip(key);
        }
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
            if (target != origIndex && target >= 0 && target < ids.size()) {
                ids.add(target, ids.remove(origIndex));
                persistAndRender();
            } else {
                render();
            }
            dragRow = null;
        }
    }
}
