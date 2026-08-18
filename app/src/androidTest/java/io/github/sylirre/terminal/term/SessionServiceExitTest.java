/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import static io.github.sylirre.terminal.TestUtil.waitFor;

import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.github.sylirre.terminal.ui.MainActivity;

/**
 * The notification's "Exit" action, end to end: the service kills every
 * session and the Activity drops its UI.
 *
 * The second half is what needs covering. It used to ride on a broadcast the
 * Activity registered a receiver for; it is now a direct in-process callback
 * ({@link SessionService#setExitListener}), and nothing else would notice if
 * that call were dropped — "Exit" would tear the shells down and leave a live
 * window behind with no sessions in it.
 */
@RunWith(AndroidJUnit4.class)
public class SessionServiceExitTest {

    private static final long TIMEOUT_MS = 15_000;

    @Test
    public void exitTearsDownSessionsAndTheActivity() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // Sessions are process-wide and this class may run after another; start
        // from a known-empty list, and leave one behind.
        SessionManager.get().closeAll();
        // Force the plain Android shell: whether a rootfs is bundled has no
        // bearing on the teardown path under test.
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                new Intent(ctx, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_FORCE_SHELL, true));
        try {
            waitFor("first session", TIMEOUT_MS, () -> !SessionManager.get().isEmpty());

            // Exactly what the notification action's PendingIntent delivers.
            ctx.startService(new Intent(ctx, SessionService.class)
                    .setAction(SessionService.ACTION_EXIT));

            waitFor("sessions torn down", TIMEOUT_MS,
                    () -> SessionManager.get().isEmpty());
            waitFor("activity finished", TIMEOUT_MS,
                    () -> scenario.getState() == Lifecycle.State.DESTROYED);
        } finally {
            SessionManager.get().closeAll();
            scenario.close();
        }
    }
}
