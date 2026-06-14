package sh.easycli.proot.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import sh.easycli.proot.R;

/**
 * Full-screen terminal theme editor reached from Settings. Lets the user pick
 * a built-in preset or a saved user theme, edit any of the 19 colors via
 * {@link ColorPickerDialog}, watch a live {@link ThemePreviewView}, and
 * save/rename/delete named user themes through a {@link ThemeStore}.
 *
 * Model: a single mutable "working copy" of the colors, loaded from a saved
 * theme ({@code basedOn}). Editing marks it {@code dirty}; only an explicit
 * Save (overwrite a user theme) or Save as… (new user theme) persists colors.
 * Selecting a theme persists the selection immediately, so {@link MainActivity}
 * applies it to live sessions on return ({@code onResume}); unsaved drafts are
 * preview-only and discarded on exit (with a confirm prompt).
 */
public final class ThemeActivity extends Activity {

    // Swatch codes for the three non-ANSI colors; 0–15 are ANSI indices.
    private static final int CODE_FG = 100;
    private static final int CODE_BG = 101;
    private static final int CODE_CURSOR = 102;

    private ThemeStore store;

    // Working copy of the colors being edited.
    private int fg, bg, cursor;
    private final int[] ansi = new int[TerminalTheme.ANSI_COUNT];
    private String basedOn;
    private boolean dirty;

    private ThemePreviewView preview;
    private Button themeName, btnSave, btnRevert, btnRename, btnDelete;
    private final Map<Integer, View> boxes = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme);

        // Edge-to-edge like MainActivity: keep content out from under the bars.
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

        store = new ThemeStore(this);
        preview = findViewById(R.id.theme_preview);
        themeName = findViewById(R.id.theme_name);
        btnSave = findViewById(R.id.theme_save);
        btnRevert = findViewById(R.id.theme_revert);
        btnRename = findViewById(R.id.theme_rename);
        btnDelete = findViewById(R.id.theme_delete);

        findViewById(R.id.theme_done).setOnClickListener(v -> confirmIfDirty(this::finish));
        themeName.setOnClickListener(v -> showThemePicker());
        btnSave.setOnClickListener(v -> saveOverwrite());
        findViewById(R.id.theme_save_as).setOnClickListener(v -> saveAs());
        btnRevert.setOnClickListener(v -> loadInto(resolveBasedOn()));
        btnRename.setOnClickListener(v -> renameCurrent());
        btnDelete.setOnClickListener(v -> deleteCurrent());

        buildSwatchGrid();
        loadInto(store.current());
    }

    @Override
    public void onBackPressed() {
        confirmIfDirty(ThemeActivity.super::onBackPressed);
    }

    // --- Working-copy state ---

    private void loadInto(TerminalTheme t) {
        fg = t.foreground;
        bg = t.background;
        cursor = t.cursor;
        System.arraycopy(t.ansi, 0, ansi, 0, ansi.length);
        basedOn = t.name;
        dirty = false;
        // Persist selection so returning to the terminal applies this theme.
        store.setSelected(t.name);
        refresh();
    }

    private TerminalTheme working() {
        return new TerminalTheme(basedOn, fg, bg, cursor, ansi);
    }

    private TerminalTheme resolveBasedOn() {
        TerminalTheme t = store.findByName(basedOn);
        return t != null ? t : store.current();
    }

    private int colorOf(int code) {
        switch (code) {
            case CODE_FG: return fg;
            case CODE_BG: return bg;
            case CODE_CURSOR: return cursor;
            default: return ansi[code];
        }
    }

    private void setColorOf(int code, int c) {
        switch (code) {
            case CODE_FG: fg = c; break;
            case CODE_BG: bg = c; break;
            case CODE_CURSOR: cursor = c; break;
            default: ansi[code] = c; break;
        }
    }

    private void refresh() {
        preview.setTheme(working());
        themeName.setText(dirty ? getString(R.string.theme_name_modified, basedOn) : basedOn);
        for (Map.Entry<Integer, View> e : boxes.entrySet()) {
            e.getValue().setBackgroundColor(colorOf(e.getKey()));
        }
        boolean userTheme = !store.isPreset(basedOn) && store.findByName(basedOn) != null;
        btnSave.setVisibility(userTheme && dirty ? View.VISIBLE : View.GONE);
        btnRevert.setVisibility(dirty ? View.VISIBLE : View.GONE);
        btnRename.setVisibility(userTheme ? View.VISIBLE : View.GONE);
        btnDelete.setVisibility(userTheme ? View.VISIBLE : View.GONE);
    }

    // --- Swatch grid ---

    private void buildSwatchGrid() {
        GridLayout grid = findViewById(R.id.theme_swatches);
        LayoutInflater inf = LayoutInflater.from(this);
        addSwatch(grid, inf, CODE_FG, getString(R.string.theme_color_foreground));
        addSwatch(grid, inf, CODE_BG, getString(R.string.theme_color_background));
        addSwatch(grid, inf, CODE_CURSOR, getString(R.string.theme_color_cursor));
        String[] ansiNames = getResources().getStringArray(R.array.theme_ansi_names);
        for (int i = 0; i < ansiNames.length; i++) {
            addSwatch(grid, inf, i, ansiNames[i]);
        }
    }

    private void addSwatch(GridLayout grid, LayoutInflater inf, int code, String label) {
        View item = inf.inflate(R.layout.theme_swatch, grid, false);
        ((TextView) item.findViewById(R.id.swatch_label)).setText(label);
        boxes.put(code, item.findViewById(R.id.swatch_color));
        item.setOnClickListener(v -> ColorPickerDialog.show(this, label, colorOf(code), c -> {
            setColorOf(code, c);
            dirty = true;
            refresh();
        }));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setGravity(Gravity.FILL_HORIZONTAL);
        item.setLayoutParams(lp);
        grid.addView(item);
    }

    // --- Theme management ---

    private void showThemePicker() {
        List<TerminalTheme> all = store.all();
        String[] names = new String[all.size()];
        int checked = -1;
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name;
            if (names[i].equals(basedOn)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_picker_label)
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    d.dismiss();
                    TerminalTheme sel = all.get(which);
                    confirmIfDirty(() -> loadInto(sel));
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    private void saveOverwrite() {
        store.saveUserTheme(working());
        store.setSelected(basedOn);
        dirty = false;
        refresh();
        toast(R.string.theme_saved);
    }

    private void saveAs() {
        String base = store.isPreset(basedOn) ? getString(R.string.theme_custom_default) : basedOn;
        promptName(getString(R.string.theme_save_as), store.suggestName(base), name -> {
            if (store.isPreset(name)) {
                toast(R.string.theme_name_taken);
                return;
            }
            TerminalTheme t = working().withName(name);
            store.saveUserTheme(t);
            store.setSelected(name);
            loadInto(t);
            toast(R.string.theme_saved);
        });
    }

    private void renameCurrent() {
        promptName(getString(R.string.theme_rename), basedOn, name -> {
            if (name.equals(basedOn)) return;
            if (store.nameExists(name)) {
                toast(R.string.theme_name_taken);
                return;
            }
            store.renameUserTheme(basedOn, name);
            basedOn = name;
            refresh();
        });
    }

    private void deleteCurrent() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_delete)
                .setMessage(getString(R.string.theme_delete_confirm, basedOn))
                .setPositiveButton(R.string.theme_delete, (d, w) -> {
                    store.deleteUserTheme(basedOn);
                    loadInto(store.current());
                    toast(R.string.theme_deleted);
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    // --- Helpers ---

    private void promptName(String title, String initial, Consumer<String> onName) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(input.getText().length());

        LinearLayout container = new LinearLayout(this);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(p, p / 2, p, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.theme_color_ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(R.string.theme_name_empty);
                        return;
                    }
                    onName.accept(name);
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    private void confirmIfDirty(Runnable proceed) {
        if (!dirty) {
            proceed.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_discard_title)
                .setMessage(R.string.theme_discard_message)
                .setPositiveButton(R.string.theme_discard, (d, w) -> proceed.run())
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
