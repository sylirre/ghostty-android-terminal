/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import java.util.List;

/**
 * A titled group of {@link Setting}s, rendered as one card under a section
 * header in {@link SettingsActivity}.
 */
final class SettingsSection {

    final String title;
    final List<Setting> settings;

    SettingsSection(String title, List<Setting> settings) {
        this.title = title;
        this.settings = settings;
    }
}
