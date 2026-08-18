/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.TerminalNative;
import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandIdentity;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.UserlandRootfs;
import io.github.sylirre.terminal.term.VmImages;
import io.github.sylirre.terminal.term.VmMachine;
import io.github.sylirre.terminal.term.VmOptions;

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
                () -> startActivity(new Intent(this, ThemeActivity.class)))
                .navigates());
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
                .enabledWhen(settings::extraKeysEnabled)
                .navigates());
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
                getString(R.string.setting_confirm_close_title),
                getString(R.string.setting_confirm_close_summary),
                settings::confirmSessionClose,
                settings::setConfirmSessionClose));
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
                    () -> delegateAndFinish(ACTION_SETUP_USERLAND))
                    .navigates());
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
        // Engine choice exists only where libterm.so carries chroot-ng
        // (arm64-v8a builds); elsewhere the row is hidden and arm64chroot is
        // simply what runs. The JIT rows belong to arm64chroot, so they gray
        // out while chroot-ng is selected.
        boolean hasChrootNg = TerminalNative.hasChrootNg();
        if (hasChrootNg) {
            userland.add(new Setting.Toggle(
                    getString(R.string.setting_userland_chroot_ng_title),
                    getString(R.string.setting_userland_chroot_ng_summary),
                    settings::userlandChrootNgEnabled,
                    settings::setUserlandChrootNgEnabled));
        }
        BooleanSupplier arm64chrootActive =
                () -> !(hasChrootNg && settings.userlandChrootNgEnabled());
        userland.add(new Setting.Toggle(
                getString(R.string.setting_userland_jit_title),
                getString(R.string.setting_userland_jit_summary),
                settings::userlandJitEnabled,
                settings::setUserlandJitEnabled)
                .enabledWhen(arm64chrootActive));
        userland.add(new Setting.Slider(
                getString(R.string.setting_userland_jit_mb_title),
                getString(R.string.setting_userland_jit_mb_summary),
                4, 128, 4,
                settings::userlandJitBufferMb,
                settings::setUserlandJitBufferMb,
                mb -> getString(R.string.setting_userland_jit_mb_value, mb))
                .enabledWhen(() -> arm64chrootActive.getAsBoolean()
                        && settings.userlandJitEnabled()));
        userland.add(new Setting.Action(
                getString(R.string.setting_backup_title),
                getString(R.string.setting_backup_summary),
                () -> "",
                () -> delegateAndFinish(ACTION_BACKUP))
                .enabledWhen(() -> UserlandRootfs.isInstalled(this))
                .navigates());
        userland.add(new Setting.Action(
                getString(R.string.setting_restore_title),
                getString(R.string.setting_restore_summary),
                () -> "",
                () -> delegateAndFinish(ACTION_RESTORE))
                .navigates());
        sections.add(new SettingsSection(getString(R.string.settings_group_userland), userland));

        // Guest machine. Shown only where there is a machine to configure —
        // the images are a large, optional build input, and a section about a
        // session type this build cannot open would be noise. Memory and
        // terminal count are fixed when a machine boots (the emulator sizes the
        // guest's device tree and creates its consoles once), so both say they
        // apply to the next boot rather than pretending to be live.
        if (VmImages.assetsAvailable(this) || VmImages.isInstalled(this)) {
            List<Setting> vm = new ArrayList<>();
            vm.add(new Setting.Slider(
                    getString(R.string.setting_vm_memory_title),
                    getString(R.string.setting_vm_memory_summary),
                    256, 2048, 128,
                    settings::vmMemoryMb,
                    settings::setVmMemoryMb,
                    mb -> getString(R.string.setting_vm_memory_value, mb)));
            vm.add(new Setting.Slider(
                    getString(R.string.setting_vm_terminals_title),
                    getString(R.string.setting_vm_terminals_summary),
                    0, VmOptions.MAX_HVC, 1,
                    settings::vmTerminals,
                    settings::setVmTerminals,
                    n -> Integer.toString(n + 1)));   // + the console itself
            vm.add(new Setting.Toggle(
                    getString(R.string.setting_vm_jit_title),
                    getString(R.string.setting_vm_jit_summary),
                    settings::vmJitEnabled,
                    settings::setVmJitEnabled));
            vm.add(new Setting.Action(
                    getString(R.string.setting_vm_images_title),
                    getString(R.string.setting_vm_images_summary),
                    this::vmImagesStatus,
                    this::confirmRemoveVmImages)
                    .enabledWhen(() -> VmImages.isInstalled(this)));
            sections.add(new SettingsSection(getString(R.string.settings_section_vm), vm));
        }
        return sections;
    }

    /** One line describing where the machine images stand, for the row's value. */
    private String vmImagesStatus() {
        if (!VmImages.isInstalled(this)) {
            return getString(VmImages.assetsAvailable(this)
                    ? R.string.setting_vm_images_not_installed
                    : R.string.setting_vm_images_absent);
        }
        return getString(R.string.setting_vm_images_installed, formatSize(
                VmImages.installedSize(this)));
    }

    /**
     * Deleting the unpacked images is safe but only while nothing is booted
     * from them — the emulator holds them open, and an ISO guest reads from its
     * image for as long as it runs.
     */
    private void confirmRemoveVmImages() {
        if (VmMachine.isRunning()) {
            Toast.makeText(this, R.string.toast_vm_images_busy, Toast.LENGTH_LONG).show();
            return;
        }
        String size = formatSize(VmImages.installedSize(this));
        Dialogs.confirmDanger(this, getString(R.string.vm_images_remove_confirm_title),
                getString(R.string.vm_images_remove_confirm_message, size),
                R.string.setting_vm_images_remove, () -> {
                    VmImages.uninstall(this);
                    Toast.makeText(this, R.string.toast_vm_images_removed,
                            Toast.LENGTH_SHORT).show();
                    recreate();      // the row's value and enabled state changed
                });
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0);
        }
        return String.format(java.util.Locale.US, "%d MB", bytes / 1048576);
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
                if (setting.isNavigation()) {
                    row.findViewById(R.id.setting_chevron).setVisibility(View.VISIBLE);
                }

                View control = setting.createControl(this);
                // A Switch is a TextView subclass, so exclude CompoundButtons here;
                // plain TextViews are the value labels of Choice / Action rows.
                if (control instanceof TextView && !(control instanceof CompoundButton)) {
                    bindValueLabel(row, (TextView) control);
                } else if (control != null) {
                    // Wide controls (Slider) span the row under the summary;
                    // compact ones (Switch) sit in the trailing slot.
                    boolean wide = setting instanceof Setting.Slider;
                    FrameLayout slot = row.findViewById(
                            wide ? R.id.setting_control_wide : R.id.setting_control);
                    slot.addView(control, new FrameLayout.LayoutParams(
                            wide ? FrameLayout.LayoutParams.MATCH_PARENT
                                 : FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT));
                    slot.setVisibility(View.VISIBLE);
                }
                if (setting instanceof Setting.Slider) {
                    // The bar owns all interaction: no dead ripple on the row.
                    row.setBackground(null);
                } else {
                    row.setOnClickListener(v -> {
                        if (setting.isEnabled()) setting.onRowClick(control);
                    });
                }
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
        // Inset to the row text start so dividers align with the content grid.
        lp.setMarginStart(Chrome.dp(this, R.dimen.space_4));
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(Chrome.color(this, R.color.divider));
        return divider;
    }

    /**
     * Hosts a Choice/Action value label in the row, placed adaptively: inline
     * after the title when both fit on one line (and the value stays under
     * ~45% of the row width), else on its own full-width line under the
     * summary with a middle ellipsis so a path's head and tail both stay
     * readable. Re-evaluated whenever the row width changes (rotation-proof —
     * replaces the old one-shot displayWidth/2 cap) and whenever the label
     * text changes (Choice picks, Action refresh on window focus).
     */
    private void bindValueLabel(View row, TextView label) {
        label.setTextColor(Chrome.color(this, R.color.text_primary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Chrome.dimen(this, R.dimen.text_row_value));
        label.setSingleLine(true);
        // Parked here until the first layout gives us a width to place by;
        // both slots stay gone, so the first frame never squeezes the title.
        ((FrameLayout) row.findViewById(R.id.setting_value_inline)).addView(label);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) placeValueLabel(row, label);
        });
        label.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                placeValueLabel(row, label);
            }
        });
    }

    private void placeValueLabel(View row, TextView label) {
        FrameLayout inlineSlot = row.findViewById(R.id.setting_value_inline);
        FrameLayout belowSlot = row.findViewById(R.id.setting_value_below);
        int rowWidth = row.getWidth() - row.getPaddingStart() - row.getPaddingEnd();
        CharSequence value = label.getText();
        if (rowWidth <= 0 || value.length() == 0) {
            inlineSlot.setVisibility(View.GONE);
            belowSlot.setVisibility(View.GONE);
            return;
        }
        TextView title = row.findViewById(R.id.setting_title);
        View chevron = row.findViewById(R.id.setting_chevron);
        int gap = Chrome.dp(this, R.dimen.space_4);
        float titleWidth = title.getPaint().measureText(title.getText().toString());
        float valueWidth = label.getPaint().measureText(value.toString());
        int trailing = chevron.getVisibility() == View.VISIBLE
                ? chevron.getWidth() + Chrome.dp(this, R.dimen.space_2) : 0;
        boolean inline = titleWidth + gap + valueWidth + trailing <= rowWidth
                && valueWidth <= rowWidth * 0.45f;

        FrameLayout target = inline ? inlineSlot : belowSlot;
        if (label.getParent() != target) {
            ((ViewGroup) label.getParent()).removeView(label);
            target.addView(label);
        }
        label.setLayoutParams(new FrameLayout.LayoutParams(
                inline ? FrameLayout.LayoutParams.WRAP_CONTENT
                       : FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        // The hard cap keeps a mid-frame text change from squeezing the title
        // before the next placement pass runs.
        label.setEllipsize(inline
                ? TextUtils.TruncateAt.END : TextUtils.TruncateAt.MIDDLE);
        label.setMaxWidth(inline ? Math.round(rowWidth * 0.45f) : Integer.MAX_VALUE);
        label.setGravity(inline ? Gravity.END : Gravity.START);
        inlineSlot.setVisibility(inline ? View.VISIBLE : View.GONE);
        belowSlot.setVisibility(inline ? View.GONE : View.VISIBLE);
    }

    /** A rendered row paired with its setting, so enabled state can be re-applied. */
    private final class Row {
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
            boolean clickable = on && !(setting instanceof Setting.Slider);
            view.setEnabled(on);
            view.setClickable(clickable);
            view.setFocusable(clickable);
            // Token-colored disabled state instead of a whole-row alpha fade:
            // the text drops to tertiary and only the control itself dims.
            TextView title = view.findViewById(R.id.setting_title);
            TextView summary = view.findViewById(R.id.setting_summary);
            title.setTextColor(Chrome.color(SettingsActivity.this,
                    on ? R.color.text_primary : R.color.text_tertiary));
            summary.setTextColor(Chrome.color(SettingsActivity.this,
                    on ? R.color.text_secondary : R.color.text_tertiary));
            view.findViewById(R.id.setting_chevron).setAlpha(on ? 1f : 0.4f);
            if (control != null) {
                control.setEnabled(on);
                control.setAlpha(on ? 1f : 0.4f);
            }
        }
    }

    // --- Userland text settings (login shell, identity, home, working dir, locale, path) ---

    private void showLoginShellDialog() {
        Dialogs.prompt(this, R.string.setting_userland_shell_title,
                settings.userlandLoginShell(),
                getString(R.string.setting_userland_shell_hint), true,
                this::validateShellCommand,
                settings::setUserlandLoginShell,
                () -> settings.setUserlandLoginShell(""));
    }

    private void showIdentityDialog() {
        Dialogs.prompt(this, R.string.setting_userland_identity_title,
                settings.userlandIdentity(),
                getString(R.string.setting_userland_identity_hint), false,
                null,
                this::applyIdentity,
                () -> applyIdentity(""));
    }

    /**
     * Persists the identity and re-populates the home and login-shell settings
     * from the configured user's passwd entry (they track the identity).
     */
    private void applyIdentity(String id) {
        settings.setUserlandIdentity(id);
        if (UserlandRootfs.isInstalled(this)) {
            File root = UserlandRootfs.dir(this);
            String home = UserlandIdentity.homeForIdentity(root, id);
            settings.setUserlandHome(home != null ? home : "/");
            String shell = UserlandRootfs.deriveLoginShell(root, id);
            settings.setUserlandLoginShell(shell != null ? shell : "/bin/bash -l");
        }
    }

    private void showHomeDialog() {
        Dialogs.prompt(this, R.string.setting_userland_home_title,
                settings.userlandHome(),
                getString(R.string.setting_userland_home_hint), true,
                this::validateGuestDir,
                settings::setUserlandHome,
                () -> settings.setUserlandHome(""));
    }

    private void showWorkDirDialog() {
        Dialogs.prompt(this, R.string.setting_userland_workdir_title,
                settings.userlandWorkDir(),
                getString(R.string.setting_userland_workdir_hint), true,
                this::validateGuestDir,
                settings::setUserlandWorkDir,
                () -> settings.setUserlandWorkDir(""));
    }

    private void showLocaleDialog() {
        Dialogs.prompt(this, R.string.setting_userland_locale_title,
                settings.userlandLocale(),
                getString(R.string.setting_userland_locale_hint), false,
                null,
                settings::setUserlandLocale,
                () -> settings.setUserlandLocale(""));
    }

    private void showPathDialog() {
        Dialogs.prompt(this, R.string.setting_userland_path_title,
                settings.userlandPath(),
                getString(R.string.setting_userland_path_hint), true,
                this::validateSearchPath,
                settings::setUserlandPath,
                () -> settings.setUserlandPath(""));
    }

    /**
     * Empty means "derive at spawn"; otherwise an absolute path that, when the
     * rootfs is installed, names an existing directory inside it — the same
     * rule {@code UserlandRootfs} enforces at spawn time. Returns the message
     * to show inline in the dialog, or null when the value is acceptable.
     */
    private String validateGuestDir(String path) {
        if (path.isEmpty()) return null;
        if (!path.startsWith("/")) {
            return getString(R.string.setting_userland_path_not_absolute);
        }
        // Resolved inside the guest, like the emulator does it: a host-side
        // File check chases an absolute link target (/var/run -> /run) against
        // the host root and rejects a perfectly good directory.
        if (UserlandRootfs.isInstalled(this)
                && !UserlandRootfs.guestDirExists(UserlandRootfs.dir(this), path)) {
            return getString(R.string.setting_userland_path_missing);
        }
        return null;
    }

    /**
     * Empty means "derive at spawn"; otherwise the command's first
     * whitespace-separated token must be an absolute path that, when the
     * rootfs is installed, exists inside it. Arguments are not checked.
     */
    private String validateShellCommand(String cmd) {
        if (cmd.isEmpty()) return null;
        String path = cmd.split("\\s+")[0];
        if (!path.startsWith("/")) {
            return getString(R.string.setting_userland_path_not_absolute);
        }
        // Guest-side resolution, as above — Alpine's shells are all absolute
        // links to /bin/busybox, so a host-side File.exists() rejects every
        // one of them, including the value the installer itself derived.
        if (UserlandRootfs.isInstalled(this)
                && !UserlandRootfs.guestPathExists(UserlandRootfs.dir(this), path)) {
            return getString(R.string.setting_userland_path_missing);
        }
        return null;
    }

    /** Every colon-separated PATH entry must be absolute; empty means default. */
    private String validateSearchPath(String path) {
        if (path.isEmpty()) return null;
        for (String entry : path.split(":", -1)) {
            if (!entry.isEmpty() && !entry.startsWith("/")) {
                return getString(R.string.setting_userland_path_not_absolute);
            }
        }
        return null;
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
