/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.TerminalNative;

/**
 * Full-screen WYSIWYG editor for the extra-keys toolbar, reached from Settings.
 *
 * The screen has two parts: a <b>profile bar</b> for switching / adding / editing
 * named layouts, and the <b>live grid</b> — the active profile rendered exactly
 * as the toolbar renders it ({@link KeyCapView} caps at their flex widths). You
 * edit the keyboard directly:
 * <ul>
 *   <li>tap a key → an edit dialog (change key, width, swipe-up secondary, remove);</li>
 *   <li>long-press a key → drag it to another slot / row (platform drag-and-drop);</li>
 *   <li>a trailing <b>+</b> cell on each row, and an <b>Add row</b> action, insert keys.</li>
 * </ul>
 *
 * Model mirrors {@link ThemeActivity}: a mutable working copy of the active
 * profile's rows is loaded from {@link ExtraKeysConfig} and persisted immediately
 * on every change, so {@link MainActivity} reflects edits when it reloads the
 * toolbar in {@code onResume}. There is no Save/dirty step.
 */
public final class ExtraKeysActivity extends Activity {

    private ExtraKeysConfig config;
    private AppSettings settings;
    /** Fixed dark token palette; the editor keeps the app chrome, not the theme's. */
    private ChromePalette palette;

    /** Working copy of the active profile's rows; persisted after each edit. */
    private final List<List<ExtraKeysConfig.KeySpec>> rows = new ArrayList<>();

    private LinearLayout profileBar;
    private LinearLayout rowHeightBar;
    private LinearLayout grid;
    private View addRowButton;
    /** Danger drop bar shown along the bottom edge while a key is dragged. */
    private TextView dropDelete;

    /** Bounds of the row-height slider (vertical padding per cap, dp). */
    private static final int ROW_HEIGHT_MIN_DP = 2;
    private static final int ROW_HEIGHT_MAX_DP = 28;

    /** Real (draggable) caps in the current grid, with their model coordinates. */
    private final List<CapRef> caps = new ArrayList<>();

    /** Key lifted out of the model for the in-flight drag, or null when idle. */
    private ExtraKeysConfig.KeySpec draggingSpec;
    /** {row, index} where the open gap is currently drawn during a drag, or null. */
    private int[] gapAt;
    /** The gap view currently in the grid, moved incrementally during a drag. */
    private View gapView;
    /** {row, index} the dragged key was lifted from, for cancel/exit restore. */
    private int[] dragOrigin;

    private static final class CapRef {
        final KeyCapView view;
        final int row;
        final int index;
        CapRef(KeyCapView view, int row, int index) {
            this.view = view;
            this.row = row;
            this.index = index;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extra_keys);

        palette = ChromePalette.from(this, android.graphics.Color.BLACK);
        TopBarView topBar = findViewById(R.id.top_bar);
        topBar.setTitle(getString(R.string.extra_keys_title));
        topBar.setOnBack(this::finish);
        TextView reset = topBar.addTextAction(R.string.extra_keys_reset, false);
        reset.setId(R.id.extra_keys_reset);
        reset.setOnClickListener(v -> Dialogs.confirmDanger(this,
                getString(R.string.extra_keys_reset),
                getString(R.string.extra_keys_reset_confirm),
                R.string.extra_keys_reset, this::resetActiveProfile));
        TextView done = topBar.addTextAction(R.string.extra_keys_done, true);
        done.setId(R.id.extra_keys_done);
        done.setOnClickListener(v -> finish());
        EdgeInsets.apply(findViewById(R.id.root), topBar,
                findViewById(R.id.page_scroll));

        config = new ExtraKeysConfig(this);
        settings = new AppSettings(this);
        profileBar = findViewById(R.id.profile_bar);
        rowHeightBar = findViewById(R.id.row_height_bar);
        grid = findViewById(R.id.grid);
        addRowButton = findViewById(R.id.extra_keys_add_row);
        addRowButton.setOnClickListener(v -> addRow());
        grid.setOnDragListener(this::onGridDrag);
        // The live toolbar's outer gutter (key_gap/2 around key_gap/2 cap
        // margins), so the framed grid previews exactly what the toolbar draws.
        int gridPad = Chrome.dp(this, R.dimen.key_gap) / 2;
        grid.setPadding(gridPad, gridPad, gridPad, gridPad);

        loadRows();
        render();
    }

    // --- Model ---

    private void loadRows() {
        rows.clear();
        rows.addAll(config.activeRows());
    }

    private void persist() {
        config.setActiveRows(rows);
    }

    private void cleanupEmptyRows() {
        for (int r = rows.size() - 1; r >= 0; r--) {
            if (rows.get(r).isEmpty()) rows.remove(r);
        }
    }

    private void resetActiveProfile() {
        rows.clear();
        for (List<String> row : ExtraKeysConfig.DEFAULT_ROWS) {
            List<ExtraKeysConfig.KeySpec> r = new ArrayList<>(row.size());
            for (String id : row) r.add(new ExtraKeysConfig.KeySpec(id));
            rows.add(r);
        }
        persist();
        settings.resetExtraKeysVerticalPadding();  // restore default row height
        render();
    }

    private void addRow() {
        if (rows.size() >= ExtraKeysConfig.MAX_ROWS) return;
        pickKey(false, id -> {
            List<ExtraKeysConfig.KeySpec> row = new ArrayList<>();
            row.add(new ExtraKeysConfig.KeySpec(id));
            rows.add(row);
            persist();
            render();
        });
    }

    private void addKeyToRow(int r, String id) {
        if (r < 0 || r >= rows.size()) return;
        rows.get(r).add(new ExtraKeysConfig.KeySpec(id));
        persist();
        render();
    }

    private void setWidth(int r, int i, float width) {
        rows.get(r).set(i, rows.get(r).get(i).withWidth(width));
        persist();
        render();
    }

    private void setSecondary(int r, int i, String secondaryId) {
        rows.get(r).set(i, rows.get(r).get(i).withSecondary(secondaryId));
        persist();
        render();
    }

    private void changeKey(int r, int i, String newId) {
        ExtraKeysConfig.KeySpec old = rows.get(r).get(i);
        rows.get(r).set(i, new ExtraKeysConfig.KeySpec(newId, old.width, old.secondaryId));
        persist();
        render();
    }

    private void removeKey(int r, int i) {
        rows.get(r).remove(i);
        cleanupEmptyRows();
        persist();
        render();
    }

    // --- Rendering ---

    private void render() {
        renderProfiles();
        renderRowHeight();
        renderGrid();
        updateAddRowState();
    }

    private void renderProfiles() {
        profileBar.removeAllViews();
        List<ExtraKeysConfig.Profile> profiles = config.profiles();
        int active = config.activeIndex();
        for (int i = 0; i < profiles.size(); i++) {
            final int index = i;
            TextView chip = pillChip(profiles.get(i).name, i == active);
            chip.setOnClickListener(v -> switchProfile(index));
            chip.setOnLongClickListener(v -> { showProfileMenu(index); return true; });
            profileBar.addView(chip);
        }
        if (profiles.size() < ExtraKeysConfig.MAX_PROFILES) {
            TextView add = pillChip(getString(R.string.extra_keys_profile_add), false);
            add.setTextColor(Chrome.color(this, R.color.accent));
            add.setOnClickListener(v -> promptAddProfile());
            profileBar.addView(add);
        }
    }

    private TextView pillChip(String label, boolean active) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextColor(active ? Chrome.color(this, R.color.on_accent)
                : Chrome.color(this, R.color.text_primary));
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(16), dp(9), dp(16), dp(9));
        chip.setBackground(active
                ? Chrome.ripple(this, R.color.accent, Chrome.dimen(this, R.dimen.radius_pill), 0)
                : Chrome.ripple(this, R.color.surface_2, Chrome.dimen(this, R.dimen.radius_pill),
                        R.color.border));
        chip.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        return chip;
    }

    /**
     * Row-height slider: a {@link SeekBar} over the caps' vertical padding (dp)
     * with a live "NN dp" readout. Dragging re-renders only the grid — not the
     * whole screen, which would rebuild the SeekBar out from under the drag — so
     * the keyboard below previews the chosen height live. Stored globally in
     * {@link AppSettings} and applied to the live toolbar by {@link MainActivity}.
     */
    private void renderRowHeight() {
        rowHeightBar.removeAllViews();
        int current = Math.max(ROW_HEIGHT_MIN_DP,
                Math.min(ROW_HEIGHT_MAX_DP, settings.extraKeysVerticalPadding()));

        TextView value = new TextView(this);
        value.setText(getString(R.string.extra_keys_row_height_value, current));
        value.setTextColor(Chrome.color(this, R.color.text_secondary));
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(48));

        SeekBar bar = new SeekBar(this);
        bar.setMin(ROW_HEIGHT_MIN_DP);
        bar.setMax(ROW_HEIGHT_MAX_DP);
        bar.setProgress(current);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                value.setText(getString(R.string.extra_keys_row_height_value, progress));
                if (settings.extraKeysVerticalPadding() == progress) return;
                settings.setExtraKeysVerticalPadding(progress);
                renderGrid();  // preview the new height without rebuilding the slider
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rowHeightBar.addView(bar, barLp);
        rowHeightBar.addView(value);
    }

    private void renderGrid() {
        grid.removeAllViews();
        caps.clear();
        gapView = null;
        for (int r = 0; r < rows.size(); r++) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            // Siblings slide out of the gap's way during a drag instead of
            // teleporting; appear/disappear stay instant so full re-renders
            // (row-height drags) don't flicker.
            android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
            lt.disableTransitionType(android.animation.LayoutTransition.APPEARING);
            lt.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
            rowView.setLayoutTransition(lt);
            // Same per-row uniform label sizing as the live toolbar (WYSIWYG).
            rowView.addOnLayoutChangeListener((v, l, t, ri, b, ol, ot, or, ob) -> {
                if (ri - l != or - ol) KeyCaps.uniformize((LinearLayout) v);
            });
            List<ExtraKeysConfig.KeySpec> row = rows.get(r);
            boolean gapHere = gapAt != null && gapAt[0] == r;
            for (int i = 0; i < row.size(); i++) {
                if (gapHere && gapAt[1] == i) {
                    gapView = makeGap();
                    rowView.addView(gapView, gapParams());
                }
                KeyCapView cap = makeCap(row.get(i), r, i);
                caps.add(new CapRef(cap, r, i));
                // MATCH_PARENT height: caps autosize their labels to different text
                // sizes, so LinearLayout stretches every cap in the row to the
                // tallest one — uniform keycap boxes instead of top-aligned ragged
                // ones. Mirrors ExtraKeysView.addKey so the editor is WYSIWYG.
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, row.get(i).width);
                int m = dp(2);
                lp.setMargins(m, m, m, m);
                rowView.addView(cap, lp);
            }
            if (gapHere && gapAt[1] >= row.size()) {
                gapView = makeGap();
                rowView.addView(gapView, gapParams());
            }
            rowView.addView(addCell(r), addCellParams());
            grid.addView(rowView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private KeyCapView makeCap(ExtraKeysConfig.KeySpec spec, int r, int i) {
        ExtraKey key = ExtraKeysConfig.resolve(this, spec.id);
        CharSequence secondaryHint = null;
        if (spec.secondaryId != null) {
            ExtraKey sec = ExtraKeysConfig.resolve(this, spec.secondaryId);
            if (sec != null) secondaryHint = sec.label;
        }
        // Vertical padding tracks the toolbar's configured row height; the
        // shared factory guarantees everything else matches the live toolbar.
        KeyCapView cap = KeyCaps.make(this, palette,
                key != null ? key.label : spec.id, secondaryHint,
                dp(settings.extraKeysVerticalPadding()));
        cap.setOnClickListener(v -> editKey(r, i));
        cap.setOnLongClickListener(v -> startDrag(cap, r, i));
        return cap;
    }

    private TextView addCell(int r) {
        TextView cell = new TextView(this);
        cell.setText(R.string.extra_keys_add_key_label);
        cell.setTextColor(Chrome.color(this, R.color.accent));
        cell.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        // Match the caps' max text size so the add-cell is the same height as a
        // keycap; a larger size would make this cell taller and unbalance the row.
        cell.setTextSize(15);
        cell.setGravity(Gravity.CENTER);
        int vpad = dp(settings.extraKeysVerticalPadding());
        cell.setPadding(dp(6), vpad, dp(6), vpad);
        cell.setBackground(Chrome.ripple(this, R.color.surface_2,
                Chrome.dimen(this, R.dimen.key_radius), R.color.border));
        cell.setClickable(true);
        cell.setOnClickListener(v -> pickKey(false, id -> addKeyToRow(r, id)));
        return cell;
    }

    private LinearLayout.LayoutParams addCellParams() {
        // MATCH_PARENT height so the add-cell stretches to the row's keycap height
        // just like the caps; its 15sp label keeps it from being the tallest child.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(34), LinearLayout.LayoutParams.MATCH_PARENT);
        int m = dp(2);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private void updateAddRowState() {
        boolean canAdd = rows.size() < ExtraKeysConfig.MAX_ROWS;
        addRowButton.setEnabled(canAdd);
        addRowButton.setAlpha(canAdd ? 1f : 0.4f);
    }

    // --- Profiles ---

    private void switchProfile(int index) {
        config.setActiveIndex(index);
        loadRows();
        render();
    }

    private void promptAddProfile() {
        promptName(getString(R.string.extra_keys_profile_add_title), "", name -> {
            config.addProfile(name);
            loadRows();
            render();
        });
    }

    private void showProfileMenu(int index) {
        CharSequence[] items = {
                getString(R.string.extra_keys_profile_menu_rename),
                getString(R.string.extra_keys_profile_menu_duplicate),
                getString(R.string.extra_keys_profile_menu_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(config.profiles().get(index).name)
                .setItems(items, (d, which) -> {
                    if (which == 0) promptRenameProfile(index);
                    else if (which == 1) { config.duplicateProfile(index); loadRows(); render(); }
                    else confirmDeleteProfile(index);
                })
                .show();
    }

    private void promptRenameProfile(int index) {
        String current = config.profiles().get(index).name;
        promptName(getString(R.string.extra_keys_profile_rename_title), current, name -> {
            config.renameProfile(index, name);
            render();
        });
    }

    private void confirmDeleteProfile(int index) {
        if (config.profileCount() <= 1) return;  // last profile can't be removed
        String name = config.profiles().get(index).name;
        Dialogs.confirmDanger(this, getString(R.string.extra_keys_profile_delete_title),
                getString(R.string.extra_keys_profile_delete_message, name),
                R.string.extra_keys_profile_menu_delete, () -> {
                    config.removeProfile(index);
                    loadRows();
                    render();
                });
    }

    private void promptName(String title, String initial, Consumer<String> onName) {
        Dialogs.prompt(this, title, initial,
                getString(R.string.extra_keys_profile_name_hint), false,
                name -> name.isEmpty()
                        ? getString(R.string.extra_keys_profile_name_empty) : null,
                onName, null);
    }

    // --- Edit a key ---

    private void editKey(int r, int i) {
        if (r < 0 || r >= rows.size() || i < 0 || i >= rows.get(r).size()) return;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        container.setPadding(p, p / 2, p, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(wrapScroll(container))
                .setNeutralButton(R.string.extra_keys_edit_change, null)
                .setNegativeButton(R.string.extra_keys_edit_remove, null)
                .setPositiveButton(R.string.extra_keys_done, null)
                .create();

        // Repopulates the dialog from the current model so width/secondary edits
        // are reflected live (render() only rebuilds the grid, not this dialog).
        final int fr = r, fi = i;
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            if (fr >= rows.size() || fi >= rows.get(fr).size()) { dialog.dismiss(); return; }
            ExtraKeysConfig.KeySpec spec = rows.get(fr).get(fi);
            ExtraKey key = ExtraKeysConfig.resolve(this, spec.id);
            dialog.setTitle(spannedTitle(key != null ? key.label : spec.id));
            container.removeAllViews();
            container.addView(sectionLabel(getString(R.string.extra_keys_edit_width)));
            container.addView(widthControl(fr, fi, spec.width, refresh[0]));
            container.addView(sectionLabel(getString(R.string.extra_keys_edit_secondary)));
            container.addView(secondaryControl(fr, fi, spec.secondaryId, refresh[0]));
        };
        refresh[0].run();

        dialog.show();
        // Change/Remove mutate then close; wired after show to bypass auto-dismiss.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            dialog.dismiss();
            pickKey(false, id -> changeKey(fr, fi, id));
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            dialog.dismiss();
            removeKey(fr, fi);
        });
    }

    private View widthControl(int r, int i, float current, Runnable onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        float[] widths = {ExtraKeysConfig.WIDTH_1, ExtraKeysConfig.WIDTH_1_5, ExtraKeysConfig.WIDTH_2};
        String[] labels = {"1×", "1.5×", "2×"};
        for (int w = 0; w < widths.length; w++) {
            final float width = widths[w];
            boolean sel = Math.abs(current - width) < 0.01f;
            TextView seg = new TextView(this);
            seg.setText(labels[w]);
            seg.setGravity(Gravity.CENTER);
            seg.setTextColor(sel ? Chrome.color(this, R.color.on_accent)
                    : Chrome.color(this, R.color.text_primary));
            seg.setPadding(dp(12), dp(10), dp(12), dp(10));
            seg.setBackground(sel
                    ? Chrome.ripple(this, R.color.accent, Chrome.dimen(this, R.dimen.radius_sm), 0)
                    : Chrome.ripple(this, R.color.surface_2, Chrome.dimen(this, R.dimen.radius_sm),
                            R.color.border));
            seg.setClickable(true);
            seg.setOnClickListener(v -> { setWidth(r, i, width); onChanged.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(w < widths.length - 1 ? dp(8) : 0);
            row.addView(seg, lp);
        }
        return row;
    }

    private View secondaryControl(int r, int i, String secondaryId, Runnable onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView value = new TextView(this);
        ExtraKey sec = secondaryId != null ? ExtraKeysConfig.resolve(this, secondaryId) : null;
        value.setText(sec != null ? sec.label : getString(R.string.extra_keys_edit_secondary_none));
        value.setTextColor(Chrome.color(this, sec != null ? R.color.text_primary : R.color.text_tertiary));
        Glyphs.applyTo(value);
        row.addView(value, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView set = linkButton(getString(R.string.extra_keys_edit_set));
        set.setOnClickListener(v -> pickKey(true, id -> { setSecondary(r, i, id); onChanged.run(); }));
        row.addView(set);

        if (sec != null) {
            TextView clear = linkButton(getString(R.string.extra_keys_edit_clear));
            clear.setTextColor(Chrome.color(this, R.color.text_secondary));
            clear.setOnClickListener(v -> { setSecondary(r, i, null); onChanged.run(); });
            row.addView(clear);
        }
        return row;
    }

    // --- Key picker ---

    /**
     * A chooser of every catalog key (optionally excluding sticky modifiers, for
     * a secondary) plus custom-text / modifier-combo entry points. Invokes
     * {@code onPicked} with the chosen id.
     */
    private void pickKey(boolean excludeModifiers, Consumer<String> onPicked) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        container.setPadding(p, p, p, p);

        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        final AlertDialog[] holder = new AlertDialog[1];
        for (ExtraKey k : ExtraKeysConfig.catalog(this).values()) {
            if (excludeModifiers && k.kind == ExtraKey.Kind.MODIFIER) continue;
            g.addView(keyChip(k, () -> { holder[0].dismiss(); onPicked.accept(k.id); }));
        }
        container.addView(g);

        container.addView(pickerAction(getString(R.string.extra_keys_add_custom),
                () -> { holder[0].dismiss(); promptCustom(onPicked); }));
        container.addView(pickerAction(getString(R.string.extra_keys_add_combo),
                () -> { holder[0].dismiss(); promptCombo(onPicked); }));

        holder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.extra_keys_pick_title)
                .setView(wrapScroll(container))
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        holder[0].show();
    }

    private TextView keyChip(ExtraKey key, Runnable onClick) {
        TextView chip = new TextView(this);
        chip.setText(key.label);
        chip.setTextColor(Chrome.color(this, R.color.text_primary));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(12), dp(8), dp(12));
        chip.setBackground(getDrawable(R.drawable.bg_chip));
        chip.setClickable(true);
        chip.setOnClickListener(v -> onClick.run());
        Glyphs.applyTo(chip);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setGravity(Gravity.FILL_HORIZONTAL);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        chip.setLayoutParams(lp);
        return chip;
    }

    private void promptCustom(Consumer<String> onPicked) {
        // Not Dialogs.prompt: that trims the value, and leading/trailing
        // spaces are legitimate in a custom text key. Same field styling.
        EditText input = Dialogs.field(this, "",
                getString(R.string.extra_keys_add_custom_hint), false);
        LinearLayout container = new LinearLayout(this);
        int padH = Chrome.dp(this, R.dimen.space_5);
        container.setPaddingRelative(padH, Chrome.dp(this, R.dimen.space_2), padH, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.extra_keys_add_custom_title)
                .setView(container)
                .setPositiveButton(R.string.action_ok, (d, w) -> {
                    String text = expandEscapes(input.getText().toString());
                    if (text.isEmpty()) { toast(R.string.extra_keys_custom_empty); return; }
                    onPicked.accept(ExtraKeysConfig.literalId(text));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Builds a single-tap modifier combo (Ctrl-C, Ctrl-→, …): CTRL/ALT/SHIFT
     * toggles plus a base picker — either a typed character or a special key from
     * the spinner. Produces a {@code combo:} id via {@link ExtraKeysConfig#comboId}.
     */
    private void promptCombo(Consumer<String> onPicked) {
        CheckBox ctrl = comboToggle(R.string.key_ctrl);
        CheckBox alt = comboToggle(R.string.key_alt);
        CheckBox shift = comboToggle(R.string.key_shift);
        LinearLayout modsRow = new LinearLayout(this);
        modsRow.setOrientation(LinearLayout.HORIZONTAL);
        modsRow.addView(ctrl);
        modsRow.addView(alt);
        modsRow.addView(shift);

        TextView baseLabel = sectionLabel(getString(R.string.extra_keys_combo_base_label));

        // Base-key catalog from resources (localizable) plus generated F-keys.
        final List<CharSequence> labels = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();  // parallel to labels[1..]
        labels.add(getString(R.string.extra_keys_combo_base_char));
        String[] specialLabels = getResources().getStringArray(R.array.extra_keys_combo_labels);
        String[] specialTokens = getResources().getStringArray(R.array.extra_keys_combo_tokens);
        for (int i = 0; i < specialLabels.length; i++) {
            labels.add(specialLabels[i]);
            tokens.add(specialTokens[i]);
        }
        for (int i = 1; i <= 12; i++) {
            labels.add("F" + i);
            tokens.add("f" + i);
        }

        EditText charInput = Dialogs.field(this, "",
                getString(R.string.extra_keys_combo_char_hint), false);

        // The base picker: a tonal button opening the app's single-choice
        // dialog — the raw platform Spinner was the one un-themed control left.
        final int[] basePos = {0};
        Button baseButton = new Button(this, null, 0, R.style.ButtonTonal);
        Runnable syncBase = () -> {
            baseButton.setText(labels.get(basePos[0]));
            Glyphs.applyTo(baseButton);
            charInput.setVisibility(basePos[0] == 0 ? View.VISIBLE : View.GONE);
        };
        syncBase.run();
        baseButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.extra_keys_combo_base_label)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), basePos[0],
                        (d, which) -> {
                            basePos[0] = which;
                            syncBase.run();
                            d.dismiss();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show());

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padH = Chrome.dp(this, R.dimen.space_5);
        container.setPaddingRelative(padH, Chrome.dp(this, R.dimen.space_2), padH, 0);
        container.addView(modsRow);
        container.addView(baseLabel);
        container.addView(baseButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams charLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        charLp.topMargin = Chrome.dp(this, R.dimen.space_2);
        container.addView(charInput, charLp);

        new AlertDialog.Builder(this)
                .setTitle(R.string.extra_keys_add_combo_title)
                .setView(container)
                .setPositiveButton(R.string.action_ok, (d, w) -> {
                    int mods = (ctrl.isChecked() ? TerminalNative.MOD_CTRL : 0)
                            | (alt.isChecked() ? TerminalNative.MOD_ALT : 0)
                            | (shift.isChecked() ? TerminalNative.MOD_SHIFT : 0);
                    if (mods == 0) { toast(R.string.extra_keys_combo_no_mod); return; }
                    String base;
                    if (basePos[0] == 0) {
                        String t = charInput.getText().toString();
                        if (t.codePointCount(0, t.length()) != 1) {
                            toast(R.string.extra_keys_combo_no_char);
                            return;
                        }
                        base = t.toLowerCase(Locale.ROOT);
                    } else {
                        base = tokens.get(basePos[0] - 1);
                    }
                    onPicked.accept(ExtraKeysConfig.comboId(mods, base));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private CheckBox comboToggle(int labelRes) {
        CheckBox box = new CheckBox(this);
        box.setText(labelRes);
        box.setTextColor(Chrome.color(this, R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(12));
        box.setLayoutParams(lp);
        return box;
    }

    // --- Drag & drop ---

    /**
     * Long-press: lift the key out of the model into a platform drag. The grid
     * re-renders with an open gap where the key was; the gap then follows the
     * finger (see {@link #moveGap}) so the drop destination is always visible.
     */
    private boolean startDrag(KeyCapView cap, int r, int i) {
        ClipData data = ClipData.newPlainText("", "");
        // Shadow is a bitmap snapshot taken now, so it survives the re-render
        // below that tears the source cap down.
        View.DragShadowBuilder shadow = new LiftShadow(cap);
        boolean started = cap.startDragAndDrop(data, shadow, null, 0);
        if (started) {
            draggingSpec = rows.get(r).remove(i);
            dragOrigin = new int[]{r, i};
            gapAt = new int[]{r, i};
            renderGrid();
            showDropTarget(true);
        }
        return started;
    }

    /**
     * The drag shadow as a "lifted" key: the cap drawn slightly scaled up, the
     * standard pick-it-up affordance, instead of the flat 1:1 default.
     */
    private static final class LiftShadow extends View.DragShadowBuilder {
        private static final float SCALE = 1.12f;

        LiftShadow(View view) {
            super(view);
        }

        @Override
        public void onProvideShadowMetrics(Point size, Point touch) {
            View v = getView();
            size.set(Math.max(1, Math.round(v.getWidth() * SCALE)),
                    Math.max(1, Math.round(v.getHeight() * SCALE)));
            touch.set(size.x / 2, size.y / 2);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            canvas.scale(SCALE, SCALE);
            getView().draw(canvas);
        }
    }

    /** Lazily adds the danger drop bar (delete-by-drag) to the activity root. */
    private void ensureDropTarget() {
        if (dropDelete != null) return;
        dropDelete = new TextView(this);
        dropDelete.setText(R.string.extra_keys_edit_remove);
        dropDelete.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                Chrome.dimen(this, R.dimen.text_action));
        dropDelete.setTypeface(Typeface.DEFAULT_BOLD);
        dropDelete.setGravity(Gravity.CENTER);
        dropDelete.setMinHeight(Chrome.dp(this, R.dimen.touch_min));
        dropDelete.setVisibility(View.GONE);
        styleDropTarget(false);
        android.widget.FrameLayout root = findViewById(R.id.root);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        int m = Chrome.dp(this, R.dimen.space_4);
        lp.setMargins(m, m, m, m);
        root.addView(dropDelete, lp);
        dropDelete.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return draggingSpec != null;
                case DragEvent.ACTION_DRAG_ENTERED:
                    styleDropTarget(true);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    styleDropTarget(false);
                    return true;
                case DragEvent.ACTION_DROP:
                    deleteDragged();
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    styleDropTarget(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void styleDropTarget(boolean hot) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Chrome.dimen(this, R.dimen.radius_md));
        if (hot) {
            bg.setColor(Chrome.color(this, R.color.danger));
            dropDelete.setTextColor(Chrome.color(this, R.color.on_accent));
        } else {
            bg.setColor(Chrome.color(this, R.color.surface_2));
            bg.setStroke(Chrome.dp(this, R.dimen.stroke_hairline),
                    Chrome.color(this, R.color.danger));
            dropDelete.setTextColor(Chrome.color(this, R.color.danger));
        }
        dropDelete.setBackground(bg);
    }

    private void showDropTarget(boolean show) {
        ensureDropTarget();
        // Keep it clear of the navigation bar (the root itself is not padded).
        WindowInsets wi = dropDelete.getRootWindowInsets();
        int nav = 0;
        if (wi != null) {
            nav = Build.VERSION.SDK_INT >= 30
                    ? wi.getInsets(WindowInsets.Type.systemBars()).bottom
                    : wi.getSystemWindowInsetBottom();
        }
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) dropDelete.getLayoutParams();
        lp.bottomMargin = Chrome.dp(this, R.dimen.space_4) + nav;
        dropDelete.setLayoutParams(lp);
        dropDelete.animate().cancel();
        if (show) {
            dropDelete.setVisibility(View.VISIBLE);
            dropDelete.setAlpha(0f);
            dropDelete.setTranslationY(Chrome.dp(this, R.dimen.space_4));
            dropDelete.animate().alpha(1f).translationY(0f).setDuration(160).start();
        } else {
            dropDelete.animate().alpha(0f)
                    .translationY(Chrome.dp(this, R.dimen.space_4)).setDuration(120)
                    .withEndAction(() -> {
                        dropDelete.setVisibility(View.GONE);
                        dropDelete.setAlpha(1f);
                        dropDelete.setTranslationY(0f);
                    }).start();
        }
    }

    /** Drop on the danger bar: the lifted key is simply not re-inserted. */
    private void deleteDragged() {
        if (draggingSpec == null) return;
        draggingSpec = null;
        gapAt = null;
        gapView = null;
        dragOrigin = null;
        cleanupEmptyRows();
        persist();
        render();
    }

    /** Grid-level drag listener: opens the gap under the finger and commits the drop. */
    private boolean onGridDrag(View v, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return draggingSpec != null;
            case DragEvent.ACTION_DRAG_LOCATION:
                moveGap(event.getX(), event.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                returnGapHome();  // finger left the grid: release = no change
                return true;
            case DragEvent.ACTION_DROP:
                commitDrop();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                showDropTarget(false);
                cancelDrag();  // no-op if ACTION_DROP already consumed the key
                return true;
            default:
                return true;
        }
    }

    /** Row container index under grid-space y, or the nearest end row. */
    private int rowAt(float y) {
        int n = grid.getChildCount();
        if (n == 0) return -1;
        for (int r = 0; r < n; r++) {
            View child = grid.getChildAt(r);
            if (y >= child.getTop() && y <= child.getBottom()) return r;
        }
        return y < grid.getChildAt(0).getTop() ? 0 : n - 1;
    }

    /** Recompute the drop slot from the pointer and re-open the gap there if it moved. */
    private void moveGap(float x, float y) {
        if (draggingSpec == null) return;
        int tr = rowAt(y);
        if (tr < 0 || tr >= rows.size()) return;
        placeGap(tr, insertIndex(tr, x));
    }

    /** Finger left the grid: show the key back at its origin so releasing is a no-op. */
    private void returnGapHome() {
        if (draggingSpec == null || dragOrigin == null) return;
        placeGap(dragOrigin[0], dragOrigin[1]);
    }

    /**
     * Moves the single gap view to slot {@code before} in row {@code r}
     * incrementally — no grid rebuild — so the rows' LayoutTransitions slide
     * the neighbouring keys aside as the gap travels with the finger.
     */
    private void placeGap(int r, int before) {
        if (gapAt != null && gapAt[0] == r && gapAt[1] == before) return;
        gapAt = new int[]{r, before};
        if (gapView != null && gapView.getParent() != null) {
            ((ViewGroup) gapView.getParent()).removeView(gapView);
        }
        if (r < 0 || r >= grid.getChildCount()) {
            gapView = null;
            return;
        }
        LinearLayout rowView = (LinearLayout) grid.getChildAt(r);
        gapView = makeGap();
        // Children are the row's caps in model order plus the trailing
        // add-cell, so the model slot maps straight to a child index.
        int childIndex = Math.max(0, Math.min(before, rowView.getChildCount() - 1));
        rowView.addView(gapView, childIndex, gapParams());
    }

    /** Drop: insert the dragged key where the gap is shown, then persist. */
    private void commitDrop() {
        if (draggingSpec == null) return;
        int r = (gapAt != null) ? gapAt[0] : dragOrigin[0];
        int i = (gapAt != null) ? gapAt[1] : dragOrigin[1];
        if (r < 0 || r >= rows.size()) { cancelDrag(); return; }
        List<ExtraKeysConfig.KeySpec> target = rows.get(r);
        if (i < 0) i = 0;
        if (i > target.size()) i = target.size();
        target.add(i, draggingSpec);
        draggingSpec = null;
        gapAt = null;
        dragOrigin = null;
        cleanupEmptyRows();
        persist();
        render();
    }

    /** Restore the lifted key to its origin (drop outside the grid, or aborted). */
    private void cancelDrag() {
        if (draggingSpec == null) return;  // ACTION_DROP already committed it
        int r = dragOrigin[0], i = dragOrigin[1];
        if (r >= 0 && r < rows.size()) {
            List<ExtraKeysConfig.KeySpec> row = rows.get(r);
            if (i < 0) i = 0;
            if (i > row.size()) i = row.size();
            row.add(i, draggingSpec);
        }
        draggingSpec = null;
        gapAt = null;
        dragOrigin = null;
        render();
    }

    /** Model index (in row {@code tr}) to insert before, from grid-space x. */
    private int insertIndex(int tr, float x) {
        View rowView = grid.getChildAt(tr);
        int rowLeft = rowView.getLeft();
        int before = -1;
        for (CapRef c : caps) {
            if (c.row != tr) continue;
            float center = rowLeft + c.view.getLeft() + c.view.getWidth() / 2f;
            if (x < center) { before = c.index; break; }
        }
        return before >= 0 ? before : rows.get(tr).size();
    }

    /**
     * An empty recessed slot marking where the dragged key will land. Built as a
     * text-bearing cell (like {@link #addCell}) rather than a bare View so it
     * self-measures to the same height as a keycap — a content-less MATCH_PARENT
     * child would not anchor the row height and the row would balloon vertically.
     */
    private View makeGap() {
        TextView gap = new TextView(this);
        gap.setText(" ");
        gap.setMaxLines(1);
        gap.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        // 15sp matches the caps' max auto-size text (see addCell) so the slot is
        // exactly a keycap tall.
        gap.setTextSize(15);
        gap.setGravity(Gravity.CENTER);
        int vpad = dp(settings.extraKeysVerticalPadding());
        int padH = Chrome.dp(this, R.dimen.key_pad_h);
        gap.setPadding(padH, vpad, padH, vpad);
        gap.setBackground(Chrome.rounded(this, R.color.surface_1,
                Chrome.dimen(this, R.dimen.key_radius), R.color.accent));
        return gap;
    }

    /** Layout params for the gap: same footprint (weight/margins) as the dragged key. */
    private LinearLayout.LayoutParams gapParams() {
        float w = draggingSpec != null ? draggingSpec.width : ExtraKeysConfig.WIDTH_1;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, w);
        int m = dp(2);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    // --- Small helpers ---

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Chrome.color(this, R.color.text_secondary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                Chrome.dimen(this, R.dimen.text_row_summary));
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private TextView linkButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Chrome.color(this, R.color.accent));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(6), dp(8));
        t.setClickable(true);
        t.setBackground(Chrome.rippleTransparent(this, Chrome.dimen(this, R.dimen.radius_sm)));
        return t;
    }

    private TextView pickerAction(String text, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                Chrome.dimen(this, R.dimen.text_action));
        t.setTextColor(Chrome.color(this, R.color.accent));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(16), dp(8), dp(16));
        t.setClickable(true);
        t.setBackground(Chrome.rippleTransparent(this, Chrome.dimen(this, R.dimen.radius_sm)));
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private ScrollView wrapScroll(View content) {
        ScrollView s = new ScrollView(this);
        s.addView(content);
        return s;
    }

    /** Runs the title through Glyphs so a symbol key (arrow, ↵) shows its vector. */
    private CharSequence spannedTitle(String title) {
        TextView probe = new TextView(this);
        return Glyphs.apply(this, title, probe.getTextSize(),
                Chrome.color(this, R.color.text_primary));
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
}
