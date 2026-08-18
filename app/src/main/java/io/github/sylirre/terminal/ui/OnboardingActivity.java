/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandIdentity;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * First-run intro and userland setup wizard.
 *
 * Three steps in one layout: a welcome hero with feature highlights, a
 * distribution chooser built from the rootfs assets bundled in this APK
 * ({@link UserlandDistro#bundled}), and an install step that extracts the
 * chosen tarball with determinate progress (tracked against the compressed
 * asset, whose size is known). Finishes with {@code RESULT_OK} once the flow
 * completed — either with a rootfs installed or with the explicit
 * "Android shell only" choice — and {@code RESULT_CANCELED} when backed out
 * of; only completion marks onboarding done, so a canceled first run shows
 * the intro again on the next launch.
 *
 * {@link MainActivity} launches it for the first session when onboarding has
 * never completed, and again in {@link #EXTRA_SETUP_ONLY} mode (chooser +
 * install only, no intro) from the new-tab long-press and the Settings
 * "Install Linux" row while no rootfs is installed.
 */
public final class OnboardingActivity extends Activity {

    /** Skip the welcome step: the user asked to set up a distro, not for a tour. */
    public static final String EXTRA_SETUP_ONLY =
            "io.github.sylirre.terminal.ONBOARDING_SETUP_ONLY";

    private static final int STEP_WELCOME = 0;
    private static final int STEP_CHOOSE = 1;
    private static final int STEP_INSTALL = 2;

    private AppSettings settings;
    /** Back interception for the steps that handle it (predictive back). */
    private BackGesture backGesture;
    private boolean setupOnly;
    private List<UserlandDistro> distros;

    private View stepWelcome;
    private View stepChoose;
    private View stepInstall;
    private LinearLayout dots;
    private TextView btnPrimary;
    private TextView btnSecondary;
    private TextView installBadge;
    private TextView installTitle;
    private ProgressBar installBar;
    private TextView installDetail;

    private int step = STEP_WELCOME;
    /** Chosen distro; null means "Android shell only". */
    private UserlandDistro selected;
    private final List<CardHolder> cards = new ArrayList<>();
    private boolean installing;
    private boolean installDone;
    private boolean installFailed;

    /** A chooser card view paired with its distro (null for the shell card). */
    private static final class CardHolder {
        final UserlandDistro distro;
        final View card;
        final ImageView radio;

        CardHolder(UserlandDistro distro, View card, ImageView radio) {
            this.distro = distro;
            this.card = card;
            this.radio = radio;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);

        // Nothing to set up: a rootfs is already installed (e.g. a stale
        // setup-mode launch). Heal the pref and report success.
        if (UserlandRootfs.isInstalled(this)) {
            settings.setOnboardingCompleted(true);
            setResult(RESULT_OK);
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);
        backGesture = BackGesture.install(this, this::handleBack);
        setupOnly = getIntent().getBooleanExtra(EXTRA_SETUP_ONLY, false);
        distros = UserlandDistro.bundled(this);

        // Pad the content, not the root: the decorative glow keeps its full
        // bleed with its bright center at the true top of the screen.
        View root = findViewById(R.id.onb_root);
        View content = findViewById(R.id.onb_content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                content.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            // WindowInsets.CONSUMED is API 30; on 29 (our minSdk) touching it
            // throws NoSuchFieldError, so consume the old way there.
            return Build.VERSION.SDK_INT >= 30
                    ? WindowInsets.CONSUMED
                    : insets.consumeSystemWindowInsets();
        });

        stepWelcome = findViewById(R.id.step_welcome);
        stepChoose = findViewById(R.id.step_choose);
        stepInstall = findViewById(R.id.step_install);
        dots = findViewById(R.id.onb_dots);
        btnPrimary = findViewById(R.id.btn_primary);
        btnSecondary = findViewById(R.id.btn_secondary);
        installBadge = findViewById(R.id.install_badge);
        installTitle = findViewById(R.id.install_title);
        installBar = findViewById(R.id.install_bar);
        installDetail = findViewById(R.id.install_detail);

        float pill = Chrome.dimen(this, R.dimen.radius_pill);
        btnPrimary.setBackground(Chrome.ripple(this, R.color.accent, pill, 0));
        btnSecondary.setBackground(Chrome.rippleTransparent(this, pill));
        btnPrimary.setOnClickListener(v -> onPrimary());
        btnSecondary.setOnClickListener(v -> onSecondary());
        // The active step dot stretches into a pill; animate that instead of
        // rebuilding the row.
        android.animation.LayoutTransition dotsTransition =
                new android.animation.LayoutTransition();
        dotsTransition.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        dots.setLayoutTransition(dotsTransition);

        buildChooserCards();
        // The suggested starting point is the first bundled distro — the list
        // is sorted alpine-first (small, fast to install) — else shell-only.
        selected = distros.isEmpty() ? null : distros.get(0);
        refreshCards();

        step = setupOnly ? STEP_CHOOSE : STEP_WELCOME;
        stepWelcome.setVisibility(step == STEP_WELCOME ? View.VISIBLE : View.GONE);
        stepChoose.setVisibility(step == STEP_CHOOSE ? View.VISIBLE : View.GONE);
        stepInstall.setVisibility(View.GONE);
        updateChrome();
    }

    // --- Step navigation ---

    private View viewFor(int s) {
        switch (s) {
            case STEP_WELCOME: return stepWelcome;
            case STEP_CHOOSE: return stepChoose;
            default: return stepInstall;
        }
    }

    /** Slide-fades from the current step to {@code next} and refreshes the chrome. */
    private void showStep(int next) {
        if (next == step) return;
        View out = viewFor(step);
        View in = viewFor(next);
        float dx = 32 * getResources().getDisplayMetrics().density
                * (next > step ? 1 : -1);
        out.animate().cancel();
        out.animate().alpha(0f).translationX(-dx).setDuration(180)
                .withEndAction(() -> {
                    out.setVisibility(View.GONE);
                    out.setAlpha(1f);
                    out.setTranslationX(0f);
                });
        in.animate().cancel();
        in.setAlpha(0f);
        in.setTranslationX(dx);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).translationX(0f).setDuration(220);
        step = next;
        updateChrome();
    }

    private void onPrimary() {
        if (step == STEP_WELCOME) {
            showStep(STEP_CHOOSE);
        } else if (step == STEP_CHOOSE) {
            if (selected == null) {
                completeShellOnly();
            } else {
                startInstall();
            }
        } else if (installDone) {
            setResult(RESULT_OK);
            finish();
        } else if (installFailed) {
            startInstall(); // retry; install() is idempotent and restages
        }
    }

    private void onSecondary() {
        if (step == STEP_CHOOSE) {
            if (setupOnly) {
                setResult(RESULT_CANCELED);
                finish();
            } else {
                showStep(STEP_WELCOME);
            }
        } else if (step == STEP_INSTALL && installFailed) {
            showStep(STEP_CHOOSE);
        }
    }

    @Override
    public void onBackPressed() {
        if (backConsumed()) return;
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    /**
     * Back handling for the predictive-back callback, which replaces
     * {@code onBackPressed} on releases that route the gesture through the
     * dispatcher. Registered only for the steps that consume back (see
     * {@link #updateChrome}).
     */
    private void handleBack() {
        if (backConsumed()) return;
        setResult(RESULT_CANCELED);
        finish();
    }

    /**
     * Step navigation for a back press: swallowed while extracting (which is
     * not cancellable), otherwise a step backwards. False when back means
     * "leave the wizard".
     */
    private boolean backConsumed() {
        if (installing) return true;
        if (step == STEP_INSTALL) {
            if (installDone) {
                setResult(RESULT_OK);
                finish();
            } else {
                showStep(STEP_CHOOSE);
            }
            return true;
        }
        if (step == STEP_CHOOSE && !setupOnly) {
            showStep(STEP_WELCOME);
            return true;
        }
        return false;
    }

    /** Whether the current step handles back itself; mirrors {@link #backConsumed}. */
    private boolean backIsCustom() {
        return installing || step == STEP_INSTALL
                || (step == STEP_CHOOSE && !setupOnly);
    }

    /** Re-renders the step dots and the two shared buttons for the current state. */
    private void updateChrome() {
        backGesture.setEnabled(backIsCustom());
        int count = setupOnly ? 2 : 3;
        int active = step - (setupOnly ? 1 : 0);
        int dot = Chrome.dp(this, R.dimen.space_2);
        if (dots.getChildCount() != count) {
            dots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View v = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dot, dot);
                lp.setMarginStart(dot / 2);
                lp.setMarginEnd(dot / 2);
                dots.addView(v, lp);
            }
        }
        // Update in place: the container's LayoutTransition slides the active
        // pill's width change instead of the row popping rebuilt.
        for (int i = 0; i < count; i++) {
            View v = dots.getChildAt(i);
            boolean isActive = i == active;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dot);
            bg.setColor(Chrome.color(this, isActive ? R.color.accent : R.color.surface_4));
            v.setBackground(bg);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            int width = isActive ? dot * 5 / 2 : dot;
            if (lp.width != width) {
                lp.width = width;
                v.setLayoutParams(lp);
            }
        }

        if (step == STEP_WELCOME) {
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setText(R.string.onb_continue);
            btnSecondary.setVisibility(View.INVISIBLE);
        } else if (step == STEP_CHOOSE) {
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setText(selected == null
                    ? getString(R.string.onb_use_shell_button)
                    : getString(R.string.onb_install_button, distroTitle(selected)));
            // Setup-only mode has no welcome step to go back to, but still
            // deserves a visible way out.
            btnSecondary.setText(setupOnly ? R.string.action_cancel : R.string.onb_back);
            btnSecondary.setVisibility(View.VISIBLE);
        } else if (installing) {
            btnPrimary.setVisibility(View.INVISIBLE);
            btnSecondary.setVisibility(View.INVISIBLE);
        } else if (installDone) {
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setText(R.string.onb_start);
            btnSecondary.setVisibility(View.INVISIBLE);
        } else { // failed
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setText(R.string.onb_retry);
            btnSecondary.setText(R.string.onb_back);
            btnSecondary.setVisibility(View.VISIBLE);
        }
    }

    // --- Chooser cards ---

    private String distroTitle(UserlandDistro d) {
        switch (d.id) {
            case "alpine": return getString(R.string.onb_distro_alpine);
            case "debian": return getString(R.string.onb_distro_debian);
            default:
                return d.id.substring(0, 1).toUpperCase() + d.id.substring(1);
        }
    }

    private String distroBlurb(UserlandDistro d) {
        switch (d.id) {
            case "alpine": return getString(R.string.onb_distro_alpine_blurb);
            case "debian": return getString(R.string.onb_distro_debian_blurb);
            default: return "";
        }
    }

    private int distroTileColor(UserlandDistro d) {
        if (d == null) return Chrome.color(this, R.color.surface_3);
        switch (d.id) {
            case "alpine": return Chrome.color(this, R.color.onb_alpine_tile);
            case "debian": return Chrome.color(this, R.color.onb_debian_tile);
            default: return Chrome.color(this, R.color.accent_soft);
        }
    }

    private int distroTextColor(UserlandDistro d) {
        if (d == null) return Chrome.color(this, R.color.text_secondary);
        switch (d.id) {
            case "alpine": return Chrome.color(this, R.color.onb_alpine_ink);
            case "debian": return Chrome.color(this, R.color.onb_debian_ink);
            default: return Chrome.color(this, R.color.accent);
        }
    }

    private void buildChooserCards() {
        LinearLayout container = findViewById(R.id.distro_cards);
        for (UserlandDistro d : distros) {
            String version = d.version.replace('_', ' ');
            String size = d.sizeBytes > 0
                    ? getString(R.string.onb_distro_size, Math.max(1, d.sizeBytes >> 20))
                    : null;
            addCard(container, d, initial(distroTitle(d)), distroTitle(d), version,
                    distroBlurb(d), size);
        }
        if (distros.isEmpty()) {
            // Build without bundled images (e.g. CI): explain, then the shell
            // card below is the only (and preselected) way forward.
            TextView note = new TextView(this);
            note.setText(R.string.onb_none_bundled);
            note.setTextColor(Chrome.color(this, R.color.text_secondary));
            note.setTextSize(14);
            note.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Chrome.dp(this, R.dimen.space_3);
            container.addView(note, lp);
        }
        if (!setupOnly) {
            addCard(container, null, ">_", getString(R.string.onb_shell_title), "",
                    getString(R.string.onb_shell_blurb), null);
        }
    }

    private static String initial(String title) {
        return title.isEmpty() ? "?" : title.substring(0, 1);
    }

    private void addCard(LinearLayout container, UserlandDistro d, String monogram,
            String title, String version, String blurb, String sizeText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Chrome.dp(this, R.dimen.space_4);
        card.setPadding(pad, pad, pad, pad);
        card.setClickable(true);
        card.setFocusable(true);

        TextView tile = new TextView(this);
        tile.setText(monogram);
        tile.setGravity(Gravity.CENTER);
        tile.setTypeface(d == null ? Typeface.MONOSPACE
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tile.setTextSize(d == null ? 15 : 20);
        tile.setTextColor(distroTextColor(d));
        GradientDrawable tileBg = new GradientDrawable();
        tileBg.setColor(distroTileColor(d));
        tileBg.setCornerRadius(Chrome.dimen(this, R.dimen.radius_md));
        tile.setBackground(tileBg);
        int tileSize = Chrome.dp(this, R.dimen.touch_min) - Chrome.dp(this, R.dimen.space_1);
        card.addView(tile, new LinearLayout.LayoutParams(tileSize, tileSize));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.BOTTOM);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Chrome.color(this, R.color.text_primary));
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(titleView);
        if (!version.isEmpty()) {
            TextView versionView = new TextView(this);
            versionView.setText(version);
            versionView.setTextColor(Chrome.color(this, R.color.text_tertiary));
            versionView.setTextSize(12);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = Chrome.dp(this, R.dimen.space_2);
            vlp.bottomMargin = Math.round(2 * getResources().getDisplayMetrics().density);
            titleRow.addView(versionView, vlp);
        }
        texts.addView(titleRow);
        TextView blurbView = new TextView(this);
        blurbView.setText(blurb);
        blurbView.setTextColor(Chrome.color(this, R.color.text_secondary));
        blurbView.setTextSize(13);
        blurbView.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Math.round(2 * getResources().getDisplayMetrics().density);
        texts.addView(blurbView, blp);
        if (sizeText != null) {
            TextView sizeView = new TextView(this);
            sizeView.setText(sizeText);
            sizeView.setTextColor(Chrome.color(this, R.color.text_tertiary));
            sizeView.setTextSize(12);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Chrome.dp(this, R.dimen.space_1);
            texts.addView(sizeView, slp);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = pad;
        card.addView(texts, tlp);

        ImageView radio = new ImageView(this);
        int radioSize = Chrome.dp(this, R.dimen.space_6);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(radioSize, radioSize);
        rlp.leftMargin = Chrome.dp(this, R.dimen.space_3);
        card.addView(radio, rlp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Chrome.dp(this, R.dimen.space_3);
        container.addView(card, clp);

        CardHolder holder = new CardHolder(d, card, radio);
        cards.add(holder);
        card.setOnClickListener(v -> {
            selected = holder.distro;
            refreshCards();
            updateChrome();
        });
    }

    /** Applies the selected/idle look (border, radio) to every chooser card. */
    private void refreshCards() {
        float radius = Chrome.dimen(this, R.dimen.radius_lg);
        for (CardHolder holder : cards) {
            boolean isSelected = holder.distro == selected;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Chrome.color(this, R.color.surface_2));
            bg.setCornerRadius(radius);
            bg.setStroke(isSelected
                            ? Chrome.dp(this, R.dimen.stroke_hairline) * 2
                            : Chrome.dp(this, R.dimen.stroke_hairline),
                    Chrome.color(this, isSelected ? R.color.accent : R.color.border));
            // Rippled: this is the wizard's primary control and it used to
            // give no press feedback at all.
            holder.card.setBackground(Chrome.rippleOver(this, bg, radius));

            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            if (isSelected) {
                ring.setColor(Chrome.color(this, R.color.accent));
                holder.radio.setImageResource(R.drawable.ic_onb_check);
                holder.radio.setImageTintList(android.content.res.ColorStateList
                        .valueOf(Chrome.color(this, R.color.on_accent)));
                int inset = Chrome.dp(this, R.dimen.space_1);
                holder.radio.setPadding(inset, inset, inset, inset);
            } else {
                ring.setColor(Color.TRANSPARENT);
                ring.setStroke(Chrome.dp(this, R.dimen.stroke_hairline) * 2,
                        Chrome.color(this, R.color.border));
                holder.radio.setImageDrawable(null);
            }
            holder.radio.setBackground(ring);
        }
    }

    // --- Install step ---

    private void startInstall() {
        final UserlandDistro d = selected;
        installing = true;
        installDone = false;
        installFailed = false;

        installBadge.setText(initial(distroTitle(d)));
        installBadge.setTextColor(distroTextColor(d));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(distroTileColor(d));
        badgeBg.setCornerRadius(22 * getResources().getDisplayMetrics().density);
        installBadge.setBackground(badgeBg);
        installBadge.setScaleX(1f);
        installBadge.setScaleY(1f);
        installTitle.setText(getString(R.string.onb_installing_title, distroTitle(d)));
        installBar.setVisibility(View.VISIBLE);
        installBar.setIndeterminate(true);
        installDetail.setText("");
        showStep(STEP_INSTALL);
        updateChrome(); // re-entry from Retry stays on this step; refresh buttons

        new Thread(() -> {
            IOException failure = null;
            try {
                UserlandRootfs.install(getApplicationContext(), d.assetName,
                        (extracted, compressedRead, compressedTotal) -> runOnUiThread(
                                () -> onInstallProgress(extracted, compressedRead,
                                        compressedTotal)));
            } catch (IOException e) {
                failure = e;
            }
            final IOException error = failure;
            runOnUiThread(() -> onInstallFinished(d, error));
        }, "userland-install").start();
    }

    private void onInstallProgress(long extracted, long compressedRead,
            long compressedTotal) {
        if (!installing || isFinishing() || isDestroyed()) return;
        long mb = extracted >> 20;
        if (compressedTotal > 0) {
            int pct = (int) Math.min(99, compressedRead * 100 / compressedTotal);
            installBar.setIndeterminate(false);
            installBar.setProgress(pct);
            installDetail.setText(getString(R.string.onb_installing_detail_pct, pct, mb));
        } else {
            installDetail.setText(getString(R.string.onb_installing_detail, mb));
        }
    }

    private void onInstallFinished(UserlandDistro d, IOException error) {
        if (isFinishing() || isDestroyed()) return;
        installing = false;
        if (error == null) {
            installDone = true;
            // Persist the outcome immediately (not on "Start") so a killed
            // process cannot lose the completed setup.
            settings.setUserlandDistroAsset(d.assetName);
            settings.setOnboardingCompleted(true);
            applyPostInstallDefaults();
            installBar.setIndeterminate(false);
            installBar.setProgress(100);
            installTitle.setText(R.string.onb_done_title);
            installDetail.setText(getString(R.string.onb_done_detail, distroTitle(d)));
            installBadge.animate().scaleX(1.08f).scaleY(1.08f).setDuration(140)
                    .withEndAction(() -> installBadge.animate()
                            .scaleX(1f).scaleY(1f).setDuration(140));
        } else {
            installFailed = true;
            installTitle.setText(R.string.onb_failed_title);
            // Actionable guidance first; the raw cause below it for reporting.
            installDetail.setText(getString(R.string.onb_failed_detail,
                    String.valueOf(error.getMessage())));
            installBar.setVisibility(View.GONE);
        }
        updateChrome();
    }

    /**
     * Points the login-shell and home settings at what the freshly installed
     * rootfs actually provides (e.g. {@code /bin/ash -l} on Alpine, whose
     * root user has no bash), mirroring what the Settings identity dialog
     * does when the identity changes.
     */
    private void applyPostInstallDefaults() {
        File root = UserlandRootfs.dir(this);
        String identity = settings.userlandIdentity();
        String shell = UserlandRootfs.deriveLoginShell(root, identity);
        if (shell != null) settings.setUserlandLoginShell(shell);
        String home = UserlandIdentity.homeForIdentity(root, identity);
        if (home != null && !home.trim().isEmpty()) settings.setUserlandHome(home);
    }

    private void completeShellOnly() {
        settings.setOnboardingCompleted(true);
        setResult(RESULT_OK);
        finish();
    }
}
