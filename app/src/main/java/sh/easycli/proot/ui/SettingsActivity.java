package sh.easycli.proot.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import sh.easycli.proot.R;
import sh.easycli.proot.term.DebianRootfs;

/**
 * Dedicated settings screen reached from the top-bar gear. Renders {@link
 * Setting}s grouped into {@link SettingsSection} cards.
 *
 * The screen mutates only the process-wide stores ({@link AppSettings},
 * {@link ThemeStore}, {@link ExtraKeysConfig}); {@link MainActivity} re-applies
 * everything to the live terminal, window and sessions in {@code onResume}
 * ({@code applyAllSettings}) when the user returns. Two Debian flows that are
 * bound to the terminal/session lifecycle — backup and restore — are delegated
 * back to {@link MainActivity} via an activity result rather than run here.
 */
public final class SettingsActivity extends Activity {

    /** Result extra naming a flow MainActivity should run after settings closes. */
    static final String EXTRA_ACTION = "sh.easycli.proot.SETTINGS_ACTION";
    static final String ACTION_BACKUP = "backup";
    static final String ACTION_RESTORE = "restore";

    private static final int REQ_STORAGE_PERMISSION = 2;

    private AppSettings settings;
    private ThemeStore themeStore;
    private ExtraKeysConfig extraKeysConfig;
    private LinearLayout container;
    private boolean pendingEnableStorageBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Edge-to-edge like the other screens: keep content clear of the bars.
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return WindowInsets.CONSUMED;
        });

        settings = new AppSettings(this);
        themeStore = new ThemeStore(this);
        extraKeysConfig = new ExtraKeysConfig(this);
        container = findViewById(R.id.settings_container);
        findViewById(R.id.settings_back).setOnClickListener(v -> finish());

        render(buildSections());
    }

    @Override
    protected void onResume() {
        super.onResume();
        completePendingStorageBindingIfGranted();
    }

    // --- Section model ---

    private List<SettingsSection> buildSections() {
        List<SettingsSection> sections = new ArrayList<>();

        List<Setting> appearance = new ArrayList<>();
        appearance.add(new Setting.Action(
                getString(R.string.setting_theme_title),
                getString(R.string.setting_theme_summary),
                () -> themeStore.current().name,
                () -> startActivity(new Intent(this, ThemeActivity.class))));
        appearance.add(new Setting.Choice(
                getString(R.string.setting_terminal_bell_title),
                getString(R.string.setting_terminal_bell_summary),
                getResources().getIntArray(R.array.terminal_bell_mode_values),
                getResources().getStringArray(R.array.terminal_bell_mode_labels),
                settings::terminalBellMode,
                settings::setTerminalBellMode));
        appearance.add(new Setting.Choice(
                getString(R.string.setting_text_margin_left_title),
                getString(R.string.setting_text_margin_left_summary),
                getResources().getIntArray(R.array.text_margin_option_values),
                getResources().getStringArray(R.array.text_margin_option_labels),
                settings::textMarginLeft,
                settings::setTextMarginLeft));
        appearance.add(new Setting.Choice(
                getString(R.string.setting_text_margin_right_title),
                getString(R.string.setting_text_margin_right_summary),
                getResources().getIntArray(R.array.text_margin_option_values),
                getResources().getStringArray(R.array.text_margin_option_labels),
                settings::textMarginRight,
                settings::setTextMarginRight));
        sections.add(new SettingsSection(getString(R.string.settings_group_appearance), appearance));

        List<Setting> keyboard = new ArrayList<>();
        keyboard.add(new Setting.Toggle(
                getString(R.string.setting_touch_keyboard_title),
                getString(R.string.setting_touch_keyboard_summary),
                settings::touchKeyboard,
                settings::setTouchKeyboard));
        keyboard.add(new Setting.Toggle(
                getString(R.string.setting_rich_keyboard_title),
                getString(R.string.setting_rich_keyboard_summary),
                settings::richKeyboard,
                settings::setRichKeyboard)
                .enabledWhen(settings::touchKeyboard));
        keyboard.add(new Setting.Toggle(
                getString(R.string.setting_extra_keys_enabled_title),
                getString(R.string.setting_extra_keys_enabled_summary),
                settings::extraKeysEnabled,
                settings::setExtraKeysEnabled));
        keyboard.add(new Setting.Toggle(
                getString(R.string.setting_extra_keys_hide_when_kb_hidden_title),
                getString(R.string.setting_extra_keys_hide_when_kb_hidden_summary),
                settings::hideExtraKeysWhenKeyboardHidden,
                settings::setHideExtraKeysWhenKeyboardHidden)
                .enabledWhen(settings::extraKeysEnabled));
        keyboard.add(new Setting.Toggle(
                getString(R.string.setting_extra_keys_switch_title),
                getString(R.string.setting_extra_keys_switch_summary),
                settings::showExtraKeysSwitch,
                settings::setShowExtraKeysSwitch)
                .enabledWhen(settings::extraKeysEnabled));
        keyboard.add(new Setting.Choice(
                getString(R.string.setting_extra_keys_row_height_title),
                getString(R.string.setting_extra_keys_row_height_summary),
                getResources().getIntArray(R.array.extra_keys_row_height_values),
                getResources().getStringArray(R.array.extra_keys_row_height_labels),
                settings::extraKeysVerticalPadding,
                settings::setExtraKeysVerticalPadding)
                .enabledWhen(settings::extraKeysEnabled));
        keyboard.add(new Setting.Action(
                getString(R.string.setting_extra_keys_title),
                getString(R.string.setting_extra_keys_summary),
                () -> {
                    int keys = extraKeysConfig.order().size();
                    int profs = extraKeysConfig.profileCount();
                    return profs > 1
                            ? getString(R.string.extra_keys_summary_profiles, keys, profs)
                            : getResources().getQuantityString(R.plurals.extra_keys_count, keys, keys);
                },
                () -> startActivity(new Intent(this, ExtraKeysActivity.class)))
                .enabledWhen(settings::extraKeysEnabled));
        sections.add(new SettingsSection(getString(R.string.settings_group_keyboard), keyboard));

        List<Setting> terminal = new ArrayList<>();
        terminal.add(new Setting.Choice(
                getString(R.string.setting_scrollback_title),
                getString(R.string.setting_scrollback_summary),
                getResources().getIntArray(R.array.scrollback_option_values),
                getResources().getStringArray(R.array.scrollback_option_labels),
                settings::scrollbackLines,
                settings::setScrollbackLines));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_grapheme_title),
                getString(R.string.setting_grapheme_summary),
                settings::graphemeClustering,
                settings::setGraphemeClustering));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_smooth_scroll_title),
                getString(R.string.setting_smooth_scroll_summary),
                settings::smoothScroll,
                settings::setSmoothScroll));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_mouse_tracking_title),
                getString(R.string.setting_mouse_tracking_summary),
                settings::mouseTracking,
                settings::setMouseTracking));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_keep_screen_on_title),
                getString(R.string.setting_keep_screen_on_summary),
                settings::keepScreenOn,
                settings::setKeepScreenOn));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_immersive_mode_title),
                getString(R.string.setting_immersive_mode_summary),
                settings::immersiveMode,
                settings::setImmersiveMode));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_terminate_processes_title),
                getString(R.string.setting_terminate_processes_summary),
                settings::terminateProcessesOnExit,
                settings::setTerminateProcessesOnExit));
        sections.add(new SettingsSection(getString(R.string.settings_group_terminal), terminal));

        // Debian-specific settings: only meaningful on an ABI that can run it.
        if (DebianRootfs.assetName() != null) {
            List<Setting> debian = new ArrayList<>();
            debian.add(new Setting.Action(
                    getString(R.string.setting_proot_shell_title),
                    getString(R.string.setting_proot_shell_summary),
                    settings::prootLoginShell,
                    this::showLoginShellDialog));
            debian.add(new Setting.RequestToggle(
                    getString(R.string.setting_bind_storage_title),
                    getString(R.string.setting_bind_storage_summary),
                    settings::bindExternalStorage,
                    this::setBindExternalStorageRequested));
            debian.add(new Setting.Action(
                    getString(R.string.setting_backup_title),
                    getString(R.string.setting_backup_summary),
                    () -> "",
                    () -> delegateAndFinish(ACTION_BACKUP))
                    .enabledWhen(() -> DebianRootfs.isInstalled(this)));
            debian.add(new Setting.Action(
                    getString(R.string.setting_restore_title),
                    getString(R.string.setting_restore_summary),
                    () -> "",
                    () -> delegateAndFinish(ACTION_RESTORE)));
            sections.add(new SettingsSection(getString(R.string.settings_group_debian), debian));
        }
        return sections;
    }

    // --- Rendering ---

    private void render(List<SettingsSection> sections) {
        LayoutInflater inflater = LayoutInflater.from(this);
        List<Row> rows = new ArrayList<>();
        Runnable refresh = () -> { for (Row r : rows) r.applyEnabled(); };

        for (SettingsSection section : sections) {
            TextView header = new TextView(this, null, 0, R.style.SectionHeader);
            header.setText(section.title);
            container.addView(header);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(getDrawable(R.drawable.bg_card));
            card.setClipToOutline(true);  // clip row ripples to the rounded corners

            boolean first = true;
            for (Setting setting : section.settings) {
                if (!first) card.addView(makeDivider());
                first = false;

                View row = inflater.inflate(R.layout.settings_row, card, false);
                ((TextView) row.findViewById(R.id.setting_title)).setText(setting.title);
                ((TextView) row.findViewById(R.id.setting_summary)).setText(setting.summary);

                View control = setting.createControl(this);
                if (control instanceof TextView) {
                    // Trailing value label (Choice / Action): a muted, compact read-out.
                    TextView label = (TextView) control;
                    label.setTextColor(Chrome.color(this, R.color.text_secondary));
                    label.setTextSize(14);
                }
                if (control != null) {
                    ((FrameLayout) row.findViewById(R.id.setting_control)).addView(control);
                }
                row.setOnClickListener(v -> {
                    if (setting.isEnabled()) setting.onRowClick(control);
                });
                setting.onChanged = refresh;
                rows.add(new Row(setting, row, control));
                card.addView(row);
            }

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = Chrome.dp(this, R.dimen.space_2);
            container.addView(card, cardLp);
        }
        refresh.run(); // seed the initial greyed/active state
    }

    private View makeDivider() {
        View divider = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Chrome.dp(this, R.dimen.stroke_hairline));
        lp.leftMargin = Chrome.dp(this, R.dimen.space_5);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(Chrome.color(this, R.color.divider));
        return divider;
    }

    /** A rendered row paired with its setting, so enabled state can be re-applied. */
    private static final class Row {
        final Setting setting;
        final View view;
        final View control;

        Row(Setting setting, View view, View control) {
            this.setting = setting;
            this.view = view;
            this.control = control;
        }

        void applyEnabled() {
            boolean on = setting.isEnabled();
            view.setEnabled(on);
            view.setClickable(on);
            view.setAlpha(on ? 1f : 0.4f);
            if (control != null) control.setEnabled(on);
        }
    }

    // --- Debian login shell ---

    private void showLoginShellDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint(R.string.setting_proot_shell_hint);
        input.setText(settings.prootLoginShell());

        LinearLayout box = new LinearLayout(this);
        int p = Chrome.dp(this, R.dimen.space_5);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.setting_proot_shell_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String shell = input.getText().toString().trim();
                    if (!shell.isEmpty()) settings.setProotLoginShell(shell);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- Storage binding permission ---

    private boolean setBindExternalStorageRequested(boolean enabled) {
        if (!enabled) {
            pendingEnableStorageBinding = false;
            settings.setBindExternalStorage(false);
            return true;
        }
        if (StoragePermission.granted(this)) {
            settings.setBindExternalStorage(true);
            return true;
        }
        pendingEnableStorageBinding = true;
        requestStorageBindingPermission();
        return false;
    }

    private void requestStorageBindingPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent appSettings = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(appSettings);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException ignored) {
                    pendingEnableStorageBinding = false;
                    Toast.makeText(this, R.string.storage_permission_settings_unavailable,
                            Toast.LENGTH_LONG).show();
                }
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE_PERMISSION);
        }
    }

    private void completePendingStorageBindingIfGranted() {
        if (!pendingEnableStorageBinding || Build.VERSION.SDK_INT < 30) return;
        pendingEnableStorageBinding = false;
        if (StoragePermission.granted(this)) {
            settings.setBindExternalStorage(true);
            Toast.makeText(this, R.string.storage_binding_enabled, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE_PERMISSION) return;
        pendingEnableStorageBinding = false;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            settings.setBindExternalStorage(true);
            Toast.makeText(this, R.string.storage_binding_enabled, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    // --- Delegated flows ---

    /** Hands a terminal-coupled Debian flow back to MainActivity and closes. */
    private void delegateAndFinish(String action) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, action));
        finish();
    }
}
