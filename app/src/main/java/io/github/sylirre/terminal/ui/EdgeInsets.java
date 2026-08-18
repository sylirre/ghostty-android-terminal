/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

/**
 * Edge-to-edge inset plumbing for the secondary screens (Settings, theme
 * editor, extra-keys editor). Instead of padding the whole root — which
 * paints a window-background band above the top bar and below the content —
 * this routes each inset to the surface that should extend under it:
 *
 * <ul>
 *   <li>status bar → extra top padding on the top bar, so its surface runs
 *       underneath the status bar;</li>
 *   <li>navigation bar → bottom padding on the scrolling content with
 *       {@code clipToPadding} off, so content scrolls under the bar;</li>
 *   <li>left/right (including display cutouts) → root padding.</li>
 * </ul>
 *
 * MainActivity keeps its own listener: its bottom edge is the extra-keys
 * toolbar and it also tracks the IME.
 */
final class EdgeInsets {

    private EdgeInsets() {}

    static void apply(View root, View topBar, ViewGroup scrollContent) {
        final int topBarBasePadTop = topBar.getPaddingTop();
        final int scrollBasePadBottom = scrollContent.getPaddingBottom();
        scrollContent.setClipToPadding(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, 0, right, 0);
            topBar.setPadding(topBar.getPaddingLeft(), topBarBasePadTop + top,
                    topBar.getPaddingRight(), topBar.getPaddingBottom());
            scrollContent.setPadding(scrollContent.getPaddingLeft(),
                    scrollContent.getPaddingTop(), scrollContent.getPaddingRight(),
                    scrollBasePadBottom + bottom);
            // WindowInsets.CONSUMED is API 30; on 29 (our minSdk) touching it
            // throws NoSuchFieldError, so consume the old way there.
            return Build.VERSION.SDK_INT >= 30
                    ? WindowInsets.CONSUMED
                    : insets.consumeSystemWindowInsets();
        });
    }
}
