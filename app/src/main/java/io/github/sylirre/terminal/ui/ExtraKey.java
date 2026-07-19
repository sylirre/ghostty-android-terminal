/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

/**
 * Immutable descriptor of one extra-keys toolbar button.
 *
 * A key is one of three kinds:
 * <ul>
 *   <li>{@link Kind#KEY}: a non-printable key sent through the VT encoder via
 *       {@code TerminalView.dispatchKey} (an Android keycode) — ESC, arrows,
 *       F-keys, …</li>
 *   <li>{@link Kind#TEXT}: a literal string written to the PTY via
 *       {@code TerminalView.dispatchText} — "-", "|", or a user-defined
 *       snippet.</li>
 *   <li>{@link Kind#MODIFIER}: a sticky CTRL/ALT toggle (a
 *       {@link io.github.sylirre.terminal.term.TerminalNative}{@code .MOD_*} bit).</li>
 * </ul>
 *
 * A {@link Kind#KEY} or {@link Kind#TEXT} key may also carry a non-zero
 * {@link #mods} bitmask ({@code TerminalNative.MOD_*}): these are the
 * single-tap modifier combos (Ctrl-C, Ctrl-→, …). The mask is baked into the
 * button and applied atomically when tapped — distinct from a sticky
 * {@link Kind#MODIFIER}, which arms the <em>next</em> key. A combo's mods are
 * OR-ed with any sticky mods at dispatch time.
 *
 * Two per-placement attributes decorate a resolved key (they are not part of
 * its {@link #id} identity, so the same catalog key can appear at different
 * widths / with different secondaries):
 * <ul>
 *   <li>{@link #width}: a flex-grid width multiplier (1.0 / 1.5 / 2.0) used as
 *       the keycap's {@code layout_weight} so it can be emphasized (wide ESC,
 *       TAB, …).</li>
 *   <li>{@link #secondaryId}: the {@link ExtraKeysConfig} id of an optional
 *       secondary key emitted on swipe-up / long-press and hinted in the cap's
 *       corner. Restricted to KEY/TEXT/combo keys (never a sticky modifier);
 *       resolved lazily through {@link ExtraKeysConfig#resolve}.</li>
 * </ul>
 *
 * {@code id} is the stable token persisted by {@link ExtraKeysConfig}: a catalog
 * key for built-ins (e.g. {@code "esc"}), {@code "lit:<text>"} for a custom
 * text key, or {@code "combo:<mods>:<base>"} for a modifier combo. Build
 * instances through the {@link #key}/{@link #text}/{@link #modifier}/
 * {@link #comboKey}/{@link #comboText} factories rather than the constructor,
 * then decorate with {@link #withWidth}/{@link #withSecondary} as needed.
 */
final class ExtraKey {

    enum Kind { KEY, TEXT, MODIFIER }

    final String id;
    final String label;
    final Kind kind;
    final int keyCode;   // KEY only
    final String text;   // TEXT only
    final int modifier;  // MODIFIER only (TerminalNative.MOD_*)
    final int mods;      // KEY/TEXT combos: baked-in TerminalNative.MOD_* bits
    final float width;         // flex-grid width multiplier (1.0 / 1.5 / 2.0)
    final String secondaryId;  // swipe-up secondary key id, or null

    private ExtraKey(String id, String label, Kind kind, int keyCode, String text,
                     int modifier, int mods, float width, String secondaryId) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.keyCode = keyCode;
        this.text = text;
        this.modifier = modifier;
        this.mods = mods;
        this.width = width;
        this.secondaryId = secondaryId;
    }

    static ExtraKey key(String id, String label, int keyCode) {
        return new ExtraKey(id, label, Kind.KEY, keyCode, null, 0, 0, 1f, null);
    }

    static ExtraKey text(String id, String label, String text) {
        return new ExtraKey(id, label, Kind.TEXT, 0, text, 0, 0, 1f, null);
    }

    static ExtraKey modifier(String id, String label, int modifier) {
        return new ExtraKey(id, label, Kind.MODIFIER, 0, null, modifier, 0, 1f, null);
    }

    /** A combo over a non-printable key (Ctrl-→, Shift-Tab) — mode-aware encoder. */
    static ExtraKey comboKey(String id, String label, int keyCode, int mods) {
        return new ExtraKey(id, label, Kind.KEY, keyCode, null, 0, mods, 1f, null);
    }

    /** A combo over a printable base char (Ctrl-C, Alt-.) — the dispatchText path. */
    static ExtraKey comboText(String id, String label, String text, int mods) {
        return new ExtraKey(id, label, Kind.TEXT, 0, text, 0, mods, 1f, null);
    }

    /** A copy at the given flex-grid width multiplier. */
    ExtraKey withWidth(float w) {
        return new ExtraKey(id, label, kind, keyCode, text, modifier, mods, w, secondaryId);
    }

    /** A copy carrying {@code secondaryId} as its swipe-up secondary (null clears it). */
    ExtraKey withSecondary(String secondaryId) {
        return new ExtraKey(id, label, kind, keyCode, text, modifier, mods, width, secondaryId);
    }

    boolean hasSecondary() {
        return secondaryId != null && !secondaryId.isEmpty();
    }
}
