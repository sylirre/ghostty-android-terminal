package sh.easycli.proot.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import sh.easycli.proot.R;
import sh.easycli.proot.term.TerminalNative;

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

    private static final int REQ_PICK_BG_IMAGE = 1;
    private static final int REQ_PICK_FONT_DEFAULT = 2;
    private static final int REQ_PICK_FONT_ITALIC = 3;

    private ThemeStore store;
    private AppSettings settings;

    // Working copy of the colors being edited.
    private int fg, bg, cursor;
    private final int[] ansi = new int[TerminalTheme.ANSI_COUNT];
    private String basedOn;
    private boolean dirty;

    private ThemePreviewView preview;
    private Button themeName, btnSave, btnRevert, btnRename, btnDelete;
    private final Map<Integer, View> boxes = new LinkedHashMap<>();

    // Background wallpaper controls (a global setting, not part of the theme's
    // color working copy). bgPreviewBitmap is owned here and drawn in the
    // preview; the live terminal gets its own copy from MainActivity.
    private Button btnBgChoose, btnBgRemove;
    private View bgOpacityRow, bgBlurRow;
    private SeekBar bgOpacity, bgBlur;
    private Bitmap bgPreviewBitmap;

    // Custom default/italic fonts (also a global setting, not part of the
    // theme's color working copy). The stored paths live in AppSettings;
    // this activity only imports/removes files via FontStore and refreshes
    // the button labels + preview.
    private Button btnFontDefaultChoose, btnFontDefaultRemove;
    private Button btnFontItalicChoose, btnFontItalicRemove;

    // Cursor shape + blink: global settings (like the background image), not
    // part of the color working copy, so editing them never marks the theme
    // dirty. The picker offers block/underline/bar; the hollow block the engine
    // supports is intentionally not exposed.
    private static final int[] CURSOR_STYLES = {
            TerminalNative.CURSOR_BLOCK,
            TerminalNative.CURSOR_UNDERLINE,
            TerminalNative.CURSOR_BAR,
    };
    private Button btnCursorStyle;
    private Switch cursorBlink;

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
        settings = new AppSettings(this);
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

        setupBackgroundControls();
        setupFontControls();
        setupCursorControls();
        buildSwatchGrid();
        loadInto(store.current());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bgPreviewBitmap != null) {
            bgPreviewBitmap.recycle();
            bgPreviewBitmap = null;
        }
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

    // --- Background image (global wallpaper) ---

    private void setupBackgroundControls() {
        btnBgChoose = findViewById(R.id.theme_bg_choose);
        btnBgRemove = findViewById(R.id.theme_bg_remove);
        bgOpacityRow = findViewById(R.id.theme_bg_opacity_row);
        bgOpacity = findViewById(R.id.theme_bg_opacity);
        bgBlurRow = findViewById(R.id.theme_bg_blur_row);
        bgBlur = findViewById(R.id.theme_bg_blur);

        btnBgChoose.setOnClickListener(v -> pickBackgroundImage());
        btnBgRemove.setOnClickListener(v -> removeBackgroundImage());
        bgOpacity.setProgress(settings.backgroundImageOpacity());
        bgOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                settings.setBackgroundImageOpacity(progress);
                applyBackgroundToPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        // Blur is baked into the decoded bitmap, so re-decode only when the
        // user lifts their finger rather than on every drag tick.
        bgBlur.setProgress(settings.backgroundImageBlur());
        bgBlur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) settings.setBackgroundImageBlur(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                reloadBackgroundBitmap();
            }
        });
        reloadBackgroundBitmap();
    }

    private void pickBackgroundImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQ_PICK_BG_IMAGE);
        } catch (ActivityNotFoundException e) {
            toast(R.string.theme_bg_image_failed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        switch (requestCode) {
            case REQ_PICK_BG_IMAGE:
                importBackgroundImage(uri);
                break;
            case REQ_PICK_FONT_DEFAULT:
                importFont(uri, FontStore.SLOT_DEFAULT);
                break;
            case REQ_PICK_FONT_ITALIC:
                importFont(uri, FontStore.SLOT_ITALIC);
                break;
            default:
                break;
        }
    }

    private void importBackgroundImage(Uri uri) {
        try {
            String path = BackgroundImageStore.importFrom(this, uri);
            if (path == null) {
                toast(R.string.theme_bg_image_failed);
                return;
            }
            settings.setBackgroundImagePath(path);
            reloadBackgroundBitmap();
        } catch (IOException e) {
            toast(R.string.theme_bg_image_failed);
        }
    }

    private void importFont(Uri uri, String slot) {
        try {
            String path = FontStore.importFrom(this, uri, slot);
            if (path == null) {
                toast(R.string.theme_font_failed);
                return;
            }
            if (slot.equals(FontStore.SLOT_DEFAULT)) {
                settings.setCustomFontDefaultPath(path);
            } else {
                settings.setCustomFontItalicPath(path);
            }
            refreshFontLabels();
            reloadPreviewFonts();
        } catch (IOException e) {
            toast(R.string.theme_font_failed);
        }
    }

    private void removeBackgroundImage() {
        BackgroundImageStore.clear(this);
        settings.setBackgroundImagePath(null);
        reloadBackgroundBitmap();
        toast(R.string.theme_bg_image_removed);
    }

    /** Decodes (or drops) the stored wallpaper, then refreshes preview + controls. */
    private void reloadBackgroundBitmap() {
        preview.setBackgroundImage(null, 0); // drop the reference before recycling
        if (bgPreviewBitmap != null) {
            bgPreviewBitmap.recycle();
            bgPreviewBitmap = null;
        }
        String path = settings.backgroundImagePath();
        if (path != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            bgPreviewBitmap = BackgroundImageStore.decode(path, dm.widthPixels,
                    dm.heightPixels, settings.backgroundImageBlur());
            if (bgPreviewBitmap == null) {
                // The file went missing or is corrupt: forget it.
                settings.setBackgroundImagePath(null);
            }
        }
        applyBackgroundToPreview();
    }

    private void applyBackgroundToPreview() {
        boolean hasImage = bgPreviewBitmap != null;
        int alpha = Math.round(settings.backgroundImageOpacity() * 2.55f);
        preview.setBackgroundImage(bgPreviewBitmap, alpha);
        btnBgChoose.setText(hasImage
                ? R.string.theme_bg_image_change : R.string.theme_bg_image_choose);
        btnBgRemove.setVisibility(hasImage ? View.VISIBLE : View.GONE);
        bgOpacityRow.setVisibility(hasImage ? View.VISIBLE : View.GONE);
        bgBlurRow.setVisibility(hasImage ? View.VISIBLE : View.GONE);
    }

    // --- Custom fonts (global, like the wallpaper and cursor) ---

    private void setupFontControls() {
        btnFontDefaultChoose = findViewById(R.id.theme_font_default_choose);
        btnFontDefaultRemove = findViewById(R.id.theme_font_default_remove);
        btnFontItalicChoose = findViewById(R.id.theme_font_italic_choose);
        btnFontItalicRemove = findViewById(R.id.theme_font_italic_remove);

        btnFontDefaultChoose.setOnClickListener(v -> pickFont(REQ_PICK_FONT_DEFAULT));
        btnFontDefaultRemove.setOnClickListener(v -> removeFont(FontStore.SLOT_DEFAULT));
        btnFontItalicChoose.setOnClickListener(v -> pickFont(REQ_PICK_FONT_ITALIC));
        btnFontItalicRemove.setOnClickListener(v -> removeFont(FontStore.SLOT_ITALIC));

        refreshFontLabels();
        reloadPreviewFonts();
    }

    private void pickFont(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Font MIME types are registered inconsistently across file managers
        // (often reported as application/octet-stream, unlike image/*), so
        // this deliberately doesn't filter by type; FontStore validates the
        // pick afterward.
        intent.setType("*/*");
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            toast(R.string.theme_font_failed);
        }
    }

    private void removeFont(String slot) {
        FontStore.clear(this, slot);
        if (slot.equals(FontStore.SLOT_DEFAULT)) {
            settings.setCustomFontDefaultPath(null);
        } else {
            settings.setCustomFontItalicPath(null);
        }
        refreshFontLabels();
        reloadPreviewFonts();
        toast(R.string.theme_font_removed);
    }

    private void refreshFontLabels() {
        boolean hasDefault = settings.customFontDefaultPath() != null;
        boolean hasItalic = settings.customFontItalicPath() != null;
        btnFontDefaultChoose.setText(hasDefault
                ? R.string.theme_font_change : R.string.theme_font_choose);
        btnFontDefaultRemove.setVisibility(hasDefault ? View.VISIBLE : View.GONE);
        btnFontItalicChoose.setText(hasItalic
                ? R.string.theme_font_change : R.string.theme_font_choose);
        btnFontItalicRemove.setVisibility(hasItalic ? View.VISIBLE : View.GONE);
    }

    /** Re-decodes the stored fonts (if any) and pushes them to the preview. */
    private void reloadPreviewFonts() {
        Typeface def = loadTypefaceOrNull(settings.customFontDefaultPath());
        Typeface ital = loadTypefaceOrNull(settings.customFontItalicPath());
        preview.setFont(def != null ? def : Typeface.MONOSPACE, ital);
    }

    private Typeface loadTypefaceOrNull(String path) {
        if (path == null) return null;
        try {
            return Typeface.createFromFile(new File(path));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- Cursor shape + blink (global, like the wallpaper) ---

    private void setupCursorControls() {
        btnCursorStyle = findViewById(R.id.theme_cursor_style);
        cursorBlink = findViewById(R.id.theme_cursor_blink);

        btnCursorStyle.setOnClickListener(v -> showCursorStylePicker());
        cursorBlink.setChecked(settings.cursorBlink());
        cursorBlink.setOnCheckedChangeListener((btn, checked) -> {
            settings.setCursorBlink(checked);
            applyCursorToPreview();
        });
        updateCursorStyleLabel();
        applyCursorToPreview();
    }

    private void showCursorStylePicker() {
        String[] labels = cursorStyleLabels();
        int current = settings.cursorStyle();
        int checked = 0;
        for (int i = 0; i < CURSOR_STYLES.length; i++) {
            if (CURSOR_STYLES[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_cursor_style)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    settings.setCursorStyle(CURSOR_STYLES[which]);
                    updateCursorStyleLabel();
                    applyCursorToPreview();
                    d.dismiss();
                })
                .setNegativeButton(R.string.theme_color_cancel, null)
                .show();
    }

    private void updateCursorStyleLabel() {
        String[] labels = cursorStyleLabels();
        int current = settings.cursorStyle();
        for (int i = 0; i < CURSOR_STYLES.length; i++) {
            if (CURSOR_STYLES[i] == current) {
                btnCursorStyle.setText(labels[i]);
                return;
            }
        }
        btnCursorStyle.setText(labels[0]);
    }

    private String[] cursorStyleLabels() {
        return new String[] {
                getString(R.string.theme_cursor_block),
                getString(R.string.theme_cursor_underline),
                getString(R.string.theme_cursor_bar),
        };
    }

    private void applyCursorToPreview() {
        preview.setCursor(settings.cursorStyle(), settings.cursorBlink());
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
