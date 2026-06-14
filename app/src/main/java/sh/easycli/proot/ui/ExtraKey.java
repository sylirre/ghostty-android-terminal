package sh.easycli.proot.ui;

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
 *       {@link sh.easycli.proot.term.TerminalNative}{@code .MOD_*} bit).</li>
 * </ul>
 *
 * {@code id} is the stable token persisted by {@link ExtraKeysConfig}: a catalog
 * key for built-ins (e.g. {@code "esc"}), or {@code "lit:<text>"} for a custom
 * text key. Build instances through the {@link #key}/{@link #text}/
 * {@link #modifier} factories rather than the constructor.
 */
final class ExtraKey {

    enum Kind { KEY, TEXT, MODIFIER }

    final String id;
    final String label;
    final Kind kind;
    final int keyCode;   // KEY only
    final String text;   // TEXT only
    final int modifier;  // MODIFIER only (TerminalNative.MOD_*)

    private ExtraKey(String id, String label, Kind kind,
                     int keyCode, String text, int modifier) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.keyCode = keyCode;
        this.text = text;
        this.modifier = modifier;
    }

    static ExtraKey key(String id, String label, int keyCode) {
        return new ExtraKey(id, label, Kind.KEY, keyCode, null, 0);
    }

    static ExtraKey text(String id, String label, String text) {
        return new ExtraKey(id, label, Kind.TEXT, 0, text, 0);
    }

    static ExtraKey modifier(String id, String label, int modifier) {
        return new ExtraKey(id, label, Kind.MODIFIER, 0, null, modifier);
    }
}
