/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandIdentity;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * Dedicated settings screen reached from the top-bar gear. Renders {@link
 * Setting}s grouped into {@link SettingsSection} cards.
 *
 * The screen mutates only the process-wide stores ({@link AppSettings},
 * {@link ThemeStore}, {@link ExtraKeysConfig}); {@link MainActivity} re-applies
 * everything to the live terminal, window and sessions in {@code onResume}
 * ({@code applyAllSettings}) when the user returns. Two userland flows that are
 * bound to the terminal/session lifecycle — backup and restore — are delegated
 * back to {@link MainActivity} via an activity result rather than run here.
 */
public final class SettingsActivity extends Activity {

    /** Result extra naming a flow MainActivity should run after settings closes. */
    static final String EXTRA_ACTION = "io.github.sylirre.terminal.SETTINGS_ACTION";
    static final String ACTION_BACKUP = "backup";
    static final String ACTION_RESTORE = "restore";
    static final String ACTION_SETUP_USERLAND = "setup_userland";

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

        TopBarView topBar = findViewById(R.id.top_bar);
        topBar.setTitle(getString(R.string.settings_dialog_title));
        topBar.setOnBack(this::finish);
        EdgeInsets.apply(findViewById(R.id.root), topBar,
                findViewById(R.id.settings_scroll));

        settings = new AppSettings(this);
        themeStore = new ThemeStore(this);
        extraKeysConfig = new ExtraKeysConfig(this);
        container = findViewById(R.id.settings_container);

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
                getString(R.string.setting_tap_open_links_title),
                getString(R.string.setting_tap_open_links_summary),
                settings::tapToOpenLinks,
                settings::setTapToOpenLinks));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_prompt_nav_title),
                getString(R.string.setting_prompt_nav_summary),
                settings::promptNav,
                settings::setPromptNav));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_progress_title),
                getString(R.string.setting_progress_summary),
                settings::showProgress,
                settings::setShowProgress));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_clipboard_write_title),
                getString(R.string.setting_clipboard_write_summary),
                settings::clipboardWrite,
                settings::setClipboardWrite));
        terminal.add(new Setting.Toggle(
                getString(R.string.setting_clipboard_read_title),
                getString(R.string.setting_clipboard_read_summary),
                settings::clipboardRead,
                settings::setClipboardRead));
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

        // Userland-specific settings: arm64chroot runs the aarch64 rootfs on
        // every host ABI, so these always apply.
        List<Setting> userland = new ArrayList<>();
        // Setup entry, only while there is something to set up: a bundled
        // distro image and no installed rootfs (an installed one — even a
        // broken one — is user data and is never reinstalled over).
        if (!UserlandRootfs.isInstalled(this)
                && !UserlandDistro.bundled(this).isEmpty()) {
            userland.add(new Setting.Action(
                    getString(R.string.setting_install_userland_title),
                    getString(R.string.setting_install_userland_summary),
                    () -> "",
                    () -> delegateAndFinish(ACTION_SETUP_USERLAND)));
        }
        userland.add(new Setting.Action(
                getString(R.string.setting_userland_identity_title),
                getString(R.string.setting_userland_identity_summary),
                settings::userlandIdentity,
                this::showIdentityDialog));
        userland.add(new Setting.Action(
                getString(R.string.setting_userland_home_title),
                getString(R.string.setting_userland_home_summary),
                settings::userlandHome,
                this::showHomeDialog));
        userland.add(new Setting.Action(
                getString(R.string.setting_userland_shell_title),
                getString(R.string.setting_userland_shell_summary),
                settings::userlandLoginShell,
                this::showLoginShellDialog));
        userland.add(new Setting.Action(
                getString(R.string.setting_userland_workdir_title),
                getString(R.string.setting_userland_workdir_summary),
                () -> {
                    String w = settings.userlandWorkDir();
                    return w.isEmpty()
                            ? getString(R.string.setting_userland_workdir_default)
                            : w;
                },
                this::showWorkDirDialog));
        userland.add(new Setting.Action(
                getString(R.string.setting_userland_locale_title),
                getString(R.string.setting_userland_locale_summary),
                () -> {
                    String l = settings.userlandLocale();
                    return l.isEmpty()
                            ? getString(R.string.setting_userland_locale_default)
                            : l;
                },
                this::showLocaleDialog));
        userland.add(new Setting.Action(
                getString(R.string.setting_userland_path_title),
                getString(R.string.setting_userland_path_summary),
                () -> {
                    String p = settings.userlandPath();
                    return p.isEmpty() ? UserlandOptions.DEFAULT_PATH : p;
                },
                this::showPathDialog));
        userland.add(new Setting.RequestToggle(
                getString(R.string.setting_bind_storage_title),
                getString(R.string.setting_bind_storage_summary),
                settings::bindExternalStorage,
                this::setBindExternalStorageRequested));
        userland.add(new Setting.Toggle(
                getString(R.string.setting_userland_jit_title),
                getString(R.string.setting_userland_jit_summary),
                settings::userlandJitEnabled,
                settings::setUserlandJitEnabled));
        userland.add(new Setting.Slider(
                getString(R.string.setting_userland_jit_mb_title),
                getString(R.string.setting_userland_jit_mb_summary),
                4, 128, 4,
                settings::userlandJitBufferMb,
                settings::setUserlandJitBufferMb,
                mb -> getString(R.string.setting_userland_jit_mb_value, mb))
                .enabledWhen(settings::userlandJitEnabled));
        userland.add(new Setting.Action(
                getString(R.string.setting_backup_title),
                getString(R.string.setting_backup_summary),
                () -> "",
                () -> delegateAndFinish(ACTION_BACKUP))
                .enabledWhen(() -> UserlandRootfs.isInstalled(this)));
        userland.add(new Setting.Action(
                getString(R.string.setting_restore_title),
                getString(R.string.setting_restore_summary),
                () -> "",
                () -> delegateAndFinish(ACTION_RESTORE)));
        sections.add(new SettingsSection(getString(R.string.settings_group_userland), userland));
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
                // A Switch is a TextView subclass, so exclude CompoundButtons here;
                // this styling is only for the plain value labels (Choice / Action).
                if (control instanceof TextView && !(control instanceof CompoundButton)) {
                    // Trailing value label (Choice / Action): a muted, compact read-out.
                    TextView label = (TextView) control;
                    label.setTextColor(Chrome.color(this, R.color.text_secondary));
                    label.setTextSize(14);
                    // Keep it to a single, width-bounded line. Free-text values
                    // (e.g. the default PATH) can be long; the trailing slot is
                    // wrap_content and measured before the weight-1 title column,
                    // so without a cap a long value grabs the whole row and the
                    // title/summary collapse to nothing. Ellipsize instead — the
                    // full value is still shown/editable in the row's dialog.
                    label.setSingleLine(true);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    label.setGravity(Gravity.END);
                    label.setMaxWidth(getResources().getDisplayMetrics().widthPixels / 2);
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

    // --- Userland text settings (login shell, identity, home, working dir, locale, path) ---

    private void showLoginShellDialog() {
        showTextSettingDialog(R.string.setting_userland_shell_title,
                R.string.setting_userland_shell_hint, settings.userlandLoginShell(),
                cmd -> {
                    if (isValidShellCommand(cmd)) settings.setUserlandLoginShell(cmd);
                });
    }

    private void showIdentityDialog() {
        showTextSettingDialog(R.string.setting_userland_identity_title,
                R.string.setting_userland_identity_hint, settings.userlandIdentity(),
                id -> {
                    settings.setUserlandIdentity(id);
                    // Populate the home and login-shell settings from the
                    // configured user's passwd entry (they track the identity).
                    if (UserlandRootfs.isInstalled(this)) {
                        File root = UserlandRootfs.dir(this);
                        String home = UserlandIdentity.homeForIdentity(root, id);
                        settings.setUserlandHome(home != null ? home : "/");
                        String shell = UserlandRootfs.deriveLoginShell(root, id);
                        settings.setUserlandLoginShell(
                                shell != null ? shell : "/bin/bash -l");
                    }
                });
    }

    private void showHomeDialog() {
        showTextSettingDialog(R.string.setting_userland_home_title,
                R.string.setting_userland_home_hint, settings.userlandHome(),
                home -> {
                    if (isValidGuestDir(home)) settings.setUserlandHome(home);
                });
    }

    private void showWorkDirDialog() {
        showTextSettingDialog(R.string.setting_userland_workdir_title,
                R.string.setting_userland_workdir_hint, settings.userlandWorkDir(),
                dir -> {
                    if (isValidGuestDir(dir)) settings.setUserlandWorkDir(dir);
                });
    }

    private void showLocaleDialog() {
        showTextSettingDialog(R.string.setting_userland_locale_title,
                R.string.setting_userland_locale_hint, settings.userlandLocale(),
                settings::setUserlandLocale);
    }

    private void showPathDialog() {
        showTextSettingDialog(R.string.setting_userland_path_title,
                R.string.setting_userland_path_hint, settings.userlandPath(),
                settings::setUserlandPath);
    }

    /** Free-text editor shared by the userland string settings. */
    private void showTextSettingDialog(int titleRes, int hintRes, String current,
            Consumer<String> onAccept) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint(hintRes);
        input.setText(current);

        LinearLayout box = new LinearLayout(this);
        int p = Chrome.dp(this, R.dimen.space_5);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        onAccept.accept(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Accepts an empty value (means "derive at spawn") or an absolute path that,
     * when the rootfs is installed, names an existing directory inside it.
     * Rejects a relative or missing path with a Toast — the same rule
     * {@code UserlandRootfs} enforces at spawn time.
     */
    private boolean isValidGuestDir(String path) {
        if (path.isEmpty()) return true;
        if (!path.startsWith("/")) {
            Toast.makeText(this, R.string.setting_userland_path_not_absolute,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (UserlandRootfs.isInstalled(this)) {
            String rel = path.substring(1);
            File target = rel.isEmpty()
                    ? UserlandRootfs.dir(this) : new File(UserlandRootfs.dir(this), rel);
            if (!target.isDirectory()) {
                Toast.makeText(this, R.string.setting_userland_path_missing,
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    /**
     * Accepts an empty value (means "derive at spawn") or a command whose first
     * whitespace-separated token is an absolute path that, when the rootfs is
     * installed, exists inside it. Rejects a relative or missing shell with a
     * Toast; arguments after the path are not checked.
     */
    private boolean isValidShellCommand(String cmd) {
        if (cmd.isEmpty()) return true;
        String path = cmd.split("\\s+")[0];
        if (!path.startsWith("/")) {
            Toast.makeText(this, R.string.setting_userland_path_not_absolute,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (UserlandRootfs.isInstalled(this)
                && !new File(UserlandRootfs.dir(this), path.substring(1)).exists()) {
            Toast.makeText(this, R.string.setting_userland_path_missing,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
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

    /** Hands a terminal-coupled Userland flow back to MainActivity and closes. */
    private void delegateAndFinish(String action) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, action));
        finish();
    }
}
