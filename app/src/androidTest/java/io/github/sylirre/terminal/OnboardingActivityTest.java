/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandRootfs;
import io.github.sylirre.terminal.ui.AppSettings;
import io.github.sylirre.terminal.ui.OnboardingActivity;

/**
 * Onboarding wizard flows that never install a rootfs (the shell-only path
 * and step navigation), so they run in any build — with or without bundled
 * distro assets. All tests skip when a rootfs is already installed on the
 * device: the wizard then legitimately finishes immediately (nothing left to
 * set up), leaving no UI to walk through.
 */
@RunWith(AndroidJUnit4.class)
public class OnboardingActivityTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        assumeFalse("rootfs already installed on this device",
                UserlandRootfs.isInstalled(ctx));
    }

    @Test
    public void welcomeThenShellOnlyCompletes() {
        new AppSettings(ctx).setOnboardingCompleted(false);
        try (ActivityScenario<OnboardingActivity> scenario =
                ActivityScenario.launchActivityForResult(OnboardingActivity.class)) {
            // Step 1: the welcome hero.
            onView(withText(R.string.onb_tagline)).check(matches(isDisplayed()));
            onView(withId(R.id.btn_primary)).perform(click());
            // Step 2: the chooser lists every bundled distro plus the shell option.
            onView(withText(R.string.onb_choose_title)).check(matches(isDisplayed()));
            for (UserlandDistro d : UserlandDistro.bundled(ctx)) {
                if ("alpine".equals(d.id)) {
                    onView(withText(R.string.onb_distro_alpine))
                            .perform(scrollTo()).check(matches(isDisplayed()));
                } else if ("debian".equals(d.id)) {
                    onView(withText(R.string.onb_distro_debian))
                            .perform(scrollTo()).check(matches(isDisplayed()));
                }
            }
            // Tapping the shell card selects it (the card is the clickable
            // ancestor of the label); the primary button then completes.
            onView(withText(R.string.onb_shell_title)).perform(scrollTo(), click());
            onView(withId(R.id.btn_primary)).perform(click());
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
        }
        assertTrue("completion is persisted",
                new AppSettings(ctx).onboardingCompleted());
    }

    @Test
    public void setupOnlyModeStartsAtChooser() {
        List<UserlandDistro> distros = UserlandDistro.bundled(ctx);
        assumeFalse("no distro assets bundled in this build", distros.isEmpty());
        Intent intent = new Intent(ctx, OnboardingActivity.class)
                .putExtra(OnboardingActivity.EXTRA_SETUP_ONLY, true);
        try (ActivityScenario<OnboardingActivity> ignored =
                ActivityScenario.launch(intent)) {
            onView(withText(R.string.onb_choose_title)).check(matches(isDisplayed()));
        }
    }
}
