/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Keeps a screen's custom back handling working under predictive back.
 *
 * With targetSdk 36, Android 16 routes the back gesture through
 * {@link OnBackInvokedDispatcher} and no longer calls {@code onBackPressed()}
 * — so a screen that only overrides that method silently loses its handling
 * (unsaved theme edits discarded without a prompt, back leaving the app
 * instead of closing the find bar, the onboarding wizard skipping its step
 * navigation). Registering a platform callback restores it without pulling in
 * AndroidX's dispatcher, which this app otherwise has no need for.
 *
 * The callback is registered only while the screen actually wants to
 * intercept back ({@link #setEnabled}), so the ordinary "leave this screen"
 * case keeps the system's own predictive animation. Activities keep their
 * {@code onBackPressed()} override for releases (and configurations) where
 * the dispatcher is not used; both entry points run the same handler.
 */
final class BackGesture {

    private final Activity activity;
    private final Runnable action;
    /** The registered {@code OnBackInvokedCallback} (API 33+), or null. */
    private Object callback;
    private boolean enabled;

    private BackGesture(Activity activity, Runnable action) {
        this.activity = activity;
        this.action = action;
    }

    /** Prepares back interception for {@code activity}; starts out disabled. */
    static BackGesture install(Activity activity, Runnable action) {
        return new BackGesture(activity, action);
    }

    /**
     * Whether back should run the handler instead of leaving the screen. A
     * no-op below API 33, where the platform still calls {@code onBackPressed()}.
     */
    void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (Build.VERSION.SDK_INT < 33) return;
        if (on) {
            callback = Api33.register(activity, action);
        } else {
            Api33.unregister(activity, callback);
            callback = null;
        }
    }

    /** The API 33+ calls, in their own class so nothing loads them below 33. */
    private static final class Api33 {
        static Object register(Activity activity, Runnable action) {
            OnBackInvokedCallback cb = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            return cb;
        }

        static void unregister(Activity activity, Object cb) {
            if (cb == null) return;
            activity.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback((OnBackInvokedCallback) cb);
        }
    }
}
