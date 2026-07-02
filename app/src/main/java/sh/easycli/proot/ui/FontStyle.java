package sh.easycli.proot.ui;

import android.graphics.Typeface;

/**
 * Picks the best available typeface for a bold/italic combination out of up
 * to four custom font roles (default, bold, italic, bold-italic), plus which
 * faux (synthetic) styling is still needed to cover a role the user hasn't
 * set a font for. Shared by {@link TerminalView} and {@link ThemePreviewView}
 * so the live terminal and the theme-editor preview fall back identically.
 *
 * The fallback order for bold+italic prefers a real italic font over a real
 * bold one (faux bold — a heavier stroke — reads better than faux italic's
 * skew), then falls back to the default typeface with both faux styles.
 */
final class FontStyle {

    final Typeface typeface;
    final boolean fakeBold;
    final boolean fakeItalic;

    private FontStyle(Typeface typeface, boolean fakeBold, boolean fakeItalic) {
        this.typeface = typeface;
        this.fakeBold = fakeBold;
        this.fakeItalic = fakeItalic;
    }

    static FontStyle select(Typeface defaultTf, Typeface boldTf, Typeface italicTf,
            Typeface boldItalicTf, boolean bold, boolean italic) {
        if (bold && italic) {
            if (boldItalicTf != null) return new FontStyle(boldItalicTf, false, false);
            if (italicTf != null) return new FontStyle(italicTf, true, false);
            if (boldTf != null) return new FontStyle(boldTf, false, true);
            return new FontStyle(defaultTf, true, true);
        }
        if (bold) {
            return boldTf != null
                    ? new FontStyle(boldTf, false, false)
                    : new FontStyle(defaultTf, true, false);
        }
        if (italic) {
            return italicTf != null
                    ? new FontStyle(italicTf, false, false)
                    : new FontStyle(defaultTf, false, true);
        }
        return new FontStyle(defaultTf, false, false);
    }
}
