/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import android.view.KeyEvent;

import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.TerminalEmulator;
import io.github.sylirre.terminal.term.TerminalNative;

/**
 * Ghostty VT correctness through the JNI boundary — no shell involved, so
 * every assertion is deterministic. Bytes are fed exactly as a PTY would
 * deliver them.
 */
@RunWith(AndroidJUnit4.class)
public class EmulatorVtTest {

    private TerminalEmulator term;
    private final ScreenSnapshot snap = new ScreenSnapshot();

    @Before
    public void setUp() {
        term = new TerminalEmulator(20, 5, 100);
    }

    @After
    public void tearDown() {
        term.close();
    }

    private void feed(String data) {
        byte[] b = data.getBytes(StandardCharsets.UTF_8);
        term.feed(b, b.length);
    }

    /** Feeds a query and returns the terminal's pty response as a string. */
    private String query(String seq) {
        byte[] b = seq.getBytes(StandardCharsets.UTF_8);
        byte[] resp = term.feed(b, b.length);
        assertNotNull("no response for query: " + seq, resp);
        return new String(resp, StandardCharsets.UTF_8);
    }

    private ScreenSnapshot snapshot() {
        assertTrue(term.snapshot(snap));
        return snap;
    }

    private int cell(int x, int y) {
        return y * snap.cols + x;
    }

    @Test
    public void plainTextAndDimensions() {
        feed("hello");
        ScreenSnapshot s = snapshot();
        assertEquals(20, s.cols);
        assertEquals(5, s.rows);
        assertEquals("hello", s.rowText(0));
        assertEquals(5, s.cursorX());
        assertEquals(0, s.cursorY());
    }

    @Test
    public void newlineAndCarriageReturn() {
        feed("one\r\ntwo\r\nthree");
        ScreenSnapshot s = snapshot();
        assertEquals("one", s.rowText(0));
        assertEquals("two", s.rowText(1));
        assertEquals("three", s.rowText(2));
    }

    @Test
    public void lineWrap() {
        feed("aaaaaaaaaaaaaaaaaaaaXY"); // 20 a's fill row 0, XY wraps
        ScreenSnapshot s = snapshot();
        assertEquals("aaaaaaaaaaaaaaaaaaaa", s.rowText(0));
        assertEquals("XY", s.rowText(1));
    }

    @Test
    public void graphemeClusterCombiningMark() {
        // Base letter plus a combining acute accent renders as one glyph but
        // is two codepoints; the base stays in codepoints[], the full cluster
        // is reachable via graphemeAt, and plain cells report no cluster.
        feed("éx");
        ScreenSnapshot s = snapshot();
        assertEquals('e', s.codepoints[cell(0, 0)]);
        assertEquals("é", s.graphemeAt(cell(0, 0)));
        assertEquals('x', s.codepoints[cell(1, 0)]);
        assertNull(s.graphemeAt(cell(1, 0)));
        // rowText splices the cluster back in for copy/debug consumers.
        assertEquals("éx", s.rowText(0));
    }

    @Test
    public void graphemeClusterMultipleMarks() {
        // Several stacked combining marks all attach to the one base cell.
        feed("à́");
        ScreenSnapshot s = snapshot();
        assertEquals('a', s.codepoints[cell(0, 0)]);
        assertEquals("à́", s.graphemeAt(cell(0, 0)));
        assertNull(s.graphemeAt(cell(1, 0)));
    }

    @Test
    public void indicConjunctNotClusteredByDefault() {
        // Devanagari स ् व र: the virama (U+094D, zero width) attaches to its
        // consonant, but with grapheme clustering off the following consonant
        // starts a fresh cell — so the conjunct स्व does not form and only स्
        // is the cluster in cell 0.
        feed("स्वर");
        ScreenSnapshot s = snapshot();
        assertEquals(0x0938, s.codepoints[cell(0, 0)]);          // base स
        assertEquals("स्", s.graphemeAt(cell(0, 0)));  // स् only
        assertEquals(0x0935, s.codepoints[cell(1, 0)]);          // व, own cell
        assertNull(s.graphemeAt(cell(1, 0)));
    }

    @Test
    public void indicConjunctClustersWithMode2027() {
        // Forcing DEC mode 2027 makes the engine apply Indic conjunct breaks:
        // consonant + virama + consonant merge into one (wide) cell, so स ् व
        // cluster in cell 0 and र lands two columns over.
        term.setGraphemeClustering(true);
        feed("स्वर");
        ScreenSnapshot s = snapshot();
        assertEquals(0x0938, s.codepoints[cell(0, 0)]);          // base स
        assertEquals("स्व", s.graphemeAt(cell(0, 0))); // स्व cluster
        assertTrue((s.attrs[cell(0, 0)] & TerminalNative.ATTR_WIDE) != 0);
        assertEquals(0x0930, s.codepoints[cell(2, 0)]);          // र past the spacer
        assertNull(s.graphemeAt(cell(2, 0)));
    }

    @Test
    public void cursorMovement() {
        feed("\u001b[3;5Hx"); // CUP row 3, col 5 (1-based)
        ScreenSnapshot s = snapshot();
        assertEquals('x', s.codepoints[cell(4, 2)]);
        assertEquals(5, s.cursorX());
        assertEquals(2, s.cursorY());
    }

    @Test
    public void eraseDisplay() {
        feed("junk\r\nmore");
        feed("\u001b[2J\u001b[H");
        ScreenSnapshot s = snapshot();
        assertEquals("", s.text().trim());
        assertEquals(0, s.cursorX());
        assertEquals(0, s.cursorY());
    }

    @Test
    public void sgrColorsAndAttributes() {
        feed("\u001b[31mr\u001b[0m\u001b[1;4mb\u001b[0m\u001b[48;2;0;128;0mg");
        ScreenSnapshot s = snapshot();
        // Palette red foreground on 'r'.
        assertFalse(s.fg[cell(0, 0)] == s.defaultFg());
        // Bold+underline attrs on 'b'.
        int attr = s.attrs[cell(1, 0)];
        assertTrue((attr & TerminalNative.ATTR_BOLD) != 0);
        assertTrue((attr & TerminalNative.ATTR_UNDERLINE) != 0);
        // Truecolor background on 'g'.
        assertEquals(0xFF008000, s.bg[cell(2, 0)]);
    }

    @Test
    public void sgrUnderlineStyles() {
        // SGR 4:n selects the underline shape: 1 single, 2 double, 3 curly,
        // 4 dotted, 5 dashed, 0 off. Each char carries the style in effect.
        feed("\u001b[4:1ma\u001b[4:2mb\u001b[4:3mc"
                + "\u001b[4:4md\u001b[4:5me\u001b[4:0mf");
        ScreenSnapshot s = snapshot();
        assertEquals(TerminalNative.UNDERLINE_SINGLE, underlineStyle(s, 0));
        assertEquals(TerminalNative.UNDERLINE_DOUBLE, underlineStyle(s, 1));
        assertEquals(TerminalNative.UNDERLINE_CURLY, underlineStyle(s, 2));
        assertEquals(TerminalNative.UNDERLINE_DOTTED, underlineStyle(s, 3));
        assertEquals(TerminalNative.UNDERLINE_DASHED, underlineStyle(s, 4));
        assertEquals(TerminalNative.UNDERLINE_NONE, underlineStyle(s, 5));
        // The presence bit tracks the field: set for the curl, clear once off.
        assertTrue((s.attrs[cell(2, 0)] & TerminalNative.ATTR_UNDERLINE) != 0);
        assertFalse((s.attrs[cell(5, 0)] & TerminalNative.ATTR_UNDERLINE) != 0);
        // Legacy SGR 4 still maps to a single underline.
        feed("\u001b[H\u001b[4mg");
        assertEquals(TerminalNative.UNDERLINE_SINGLE, underlineStyle(snapshot(), 0));
    }

    private int underlineStyle(ScreenSnapshot s, int x) {
        return (s.attrs[cell(x, 0)] & TerminalNative.ATTR_UL_MASK)
                >> TerminalNative.ATTR_UL_SHIFT;
    }

    @Test
    public void osc8Hyperlink() {
        // OSC 8 opens a hyperlink for "LINK"; an empty OSC 8 closes it before
        // " x" is written. ST is ESC \.
        feed("\u001b]8;;https://example.com\u001b\\LINK\u001b]8;;\u001b\\ x");
        ScreenSnapshot s = snapshot();
        assertEquals("LINK x", s.rowText(0));
        // The four link cells carry the hyperlink attr; the space and 'x' don't.
        for (int x = 0; x < 4; x++) {
            assertTrue("cell " + x + " should be a hyperlink", s.hasHyperlink(cell(x, 0)));
            assertTrue((s.attrs[cell(x, 0)] & TerminalNative.ATTR_HYPERLINK) != 0);
        }
        assertFalse(s.hasHyperlink(cell(4, 0))); // space
        assertFalse(s.hasHyperlink(cell(5, 0))); // 'x'
        // The URI resolves at any link cell and nowhere else.
        assertEquals("https://example.com", term.hyperlinkAt(0, 0));
        assertEquals("https://example.com", term.hyperlinkAt(3, 0));
        assertNull(term.hyperlinkAt(4, 0));
        assertNull(term.hyperlinkAt(5, 0));
    }

    @Test
    public void osc133PromptNavigation() {
        // Two prompts with the first pushed into scrollback by intervening
        // output; the terminal is 20x5 (see setUp). OSC 133;A starts a prompt,
        // B ends it / starts the command, C starts command output.
        feed("\u001b]133;A\u001b\\P1$ first\r\n");
        feed("a\r\nb\r\nc\r\nd\r\ne\r\n");
        feed("\u001b]133;A\u001b\\P2$ second\r\n");
        // At the live bottom, jumping back lands the first prompt at the top.
        assertTrue(term.promptNav(-1));
        assertTrue(snapshot().rowText(0).startsWith("P1$"));
        // No prompt earlier than the first.
        assertFalse(term.promptNav(-1));
    }

    @Test
    public void themeColorsApply() {
        // A palette where ANSI red (index 1) is a recognizable color; the rest
        // are black. fg/bg/cursor are distinct so each meta slot is testable.
        int[] palette = new int[256];
        for (int i = 0; i < palette.length; i++) palette[i] = 0xFF000000;
        palette[1] = 0xFFAB1234;
        term.setColors(0xFF112233, 0xFF445566, 0xFF778899, palette);

        feed("\u001b[31mX\u001b[0m");
        ScreenSnapshot s = snapshot();
        assertEquals(0xFF112233, s.defaultFg());
        assertEquals(0xFF445566, s.defaultBg());
        assertEquals(0xFF778899, s.cursorColor());
        // SGR 31 resolves through the palette to the value we set.
        assertEquals(0xFFAB1234, s.fg[cell(0, 0)]);
    }

    @Test
    public void cursorStyleDefaultApplies() {
        // A fresh terminal has no program cursor override, so the default style
        // and blink we push show up immediately in the snapshot.
        term.setCursorStyle(TerminalNative.CURSOR_BAR, true);
        ScreenSnapshot s = snapshot();
        assertEquals(TerminalNative.CURSOR_BAR, s.cursorStyle());
        assertTrue(s.cursorBlinking());

        // Changing it again still applies while nothing has overridden it.
        term.setCursorStyle(TerminalNative.CURSOR_UNDERLINE, false);
        s = snapshot();
        assertEquals(TerminalNative.CURSOR_UNDERLINE, s.cursorStyle());
        assertFalse(s.cursorBlinking());
    }

    @Test
    public void programCursorStyleOverridesDefaultUntilReset() {
        term.setCursorStyle(TerminalNative.CURSOR_BAR, true);
        // DECSCUSR steady block (CSI 2 q): a program override wins over the
        // default we set, both for shape and blink.
        feed("\u001b[2 q");
        ScreenSnapshot s = snapshot();
        assertEquals(TerminalNative.CURSOR_BLOCK, s.cursorStyle());
        assertFalse(s.cursorBlinking());

        // DECSCUSR reset (CSI 0 q) falls back to our default again.
        feed("\u001b[0 q");
        s = snapshot();
        assertEquals(TerminalNative.CURSOR_BAR, s.cursorStyle());
        assertTrue(s.cursorBlinking());
    }

    @Test
    public void inverseIsResolvedNatively() {
        feed("\u001b[7mX");
        ScreenSnapshot s = snapshot();
        assertEquals(s.defaultBg(), s.fg[cell(0, 0)]);
        assertEquals(s.defaultFg(), s.bg[cell(0, 0)]);
    }

    @Test
    public void wideCharacterOccupiesTwoCells() {
        feed("漢x");
        ScreenSnapshot s = snapshot();
        assertEquals('漢', s.codepoints[cell(0, 0)]);
        assertTrue((s.attrs[cell(0, 0)] & TerminalNative.ATTR_WIDE) != 0);
        assertEquals(0, s.codepoints[cell(1, 0)]); // spacer tail
        assertEquals('x', s.codepoints[cell(2, 0)]);
    }

    @Test
    public void resizeReflowsPrimaryScreen() {
        feed("hello world");
        term.resize(40, 10, 8, 16);
        ScreenSnapshot s = snapshot();
        assertEquals(40, s.cols);
        assertEquals(10, s.rows);
        assertEquals("hello world", s.rowText(0));
    }

    @Test
    public void scrollbackAndViewport() {
        for (int i = 1; i <= 20; i++) {
            feed("line" + i + "\r\n");
        }
        // 5 visible rows; line20 + prompt row at bottom, rest in history.
        ScreenSnapshot s = snapshot();
        assertEquals("line20", s.rowText(s.rows - 2));

        int[] sb = new int[3];
        term.scrollbar(sb);
        assertTrue("history exists", sb[0] > sb[2]);

        term.scrollBy(-1000); // clamp to top
        s = snapshot();
        assertEquals("line1", s.rowText(0));

        term.scrollToBottom();
        s = snapshot();
        assertEquals("line20", s.rowText(s.rows - 2));
    }

    @Test
    public void scrollbackHonorsConfiguredLineCount() {
        // Regression: Ghostty's max_scrollback is a byte budget, not a line
        // count, and a budget below a ~2-page floor is ignored entirely. Before
        // the lines->bytes conversion in terminalNew, every configured size
        // therefore collapsed to a few hundred rows of history. Configure a
        // deep buffer, feed past it, and confirm history grows accordingly.
        final int lines = 20_000;
        TerminalEmulator big = new TerminalEmulator(80, 24, lines);
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 25_000; i++) {
                sb.append('L').append(i).append("\r\n");
            }
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            big.feed(b, b.length);

            int[] bar = new int[3];
            big.scrollbar(bar);
            int total = bar[0]; // scrollback + viewport rows
            assertTrue("history collapsed to " + total + " rows; expected >= "
                    + lines, total >= lines);
        } finally {
            big.close();
        }
    }

    @Test
    public void shrinkReflowKeepsText() {
        feed("hello world");
        term.resize(10, 5, 8, 16);
        ScreenSnapshot s = snapshot();
        assertTrue(s.text().contains("hello"));
    }

    @Test
    public void alternateScreenSwitch() {
        feed("primary");
        feed("\u001b[?1049h\u001b[H"); // enter alt screen + home (1049 keeps cursor pos)
        feed("alt");
        ScreenSnapshot s = snapshot();
        assertEquals("alt", s.rowText(0));
        feed("\u001b[?1049l"); // back to primary
        s = snapshot();
        assertEquals("primary", s.rowText(0));
    }

    @Test
    public void cursorPositionQueryProducesPtyResponse() {
        // DSR 6 exercises the write-pty callback path: the response must
        // come back from feed() so the session can write it to the shell.
        byte[] q = "\u001b[6n".getBytes(StandardCharsets.UTF_8);
        byte[] resp = term.feed(q, q.length);
        assertNotNull("DSR must produce a cursor position report", resp);
        assertEquals("\u001b[1;1R", new String(resp, StandardCharsets.UTF_8));
    }

    @Test
    public void bellEventIsReported() {
        feed("\u0007");
        assertTrue((term.events() & TerminalNative.EVENT_BELL) != 0);
        assertEquals(0, term.events()); // get-and-clear semantics
    }

    @Test
    public void titleChangeEventAndValue() {
        feed("\u001b]2;my title\u0007");
        assertTrue((term.events() & TerminalNative.EVENT_TITLE) != 0);
        assertEquals("my title", term.title());
    }

    @Test
    public void arrowKeyEncodingHonorsCursorKeyMode() {
        byte[] normal = term.encodeKey(KeyEvent.KEYCODE_DPAD_UP, 0, null, 0);
        assertArrayEquals("\u001b[A".getBytes(StandardCharsets.US_ASCII), normal);

        feed("\u001b[?1h"); // DECCKM application mode
        byte[] app = term.encodeKey(KeyEvent.KEYCODE_DPAD_UP, 0, null, 0);
        assertArrayEquals("\u001bOA".getBytes(StandardCharsets.US_ASCII), app);
    }

    @Test
    public void ctrlKeyEncoding() {
        byte[] ctrlC = term.encodeKey(KeyEvent.KEYCODE_C,
                TerminalNative.MOD_CTRL, "c", 'c');
        assertArrayEquals(new byte[] {0x03}, ctrlC);
    }

    @Test
    public void escapeAndEnterEncoding() {
        assertArrayEquals(new byte[] {0x1b},
                term.encodeKey(KeyEvent.KEYCODE_ESCAPE, 0, null, 0));
        assertArrayEquals(new byte[] {0x0d},
                term.encodeKey(KeyEvent.KEYCODE_ENTER, 0, null, 0));
    }

    @Test
    public void selectWordHighlightsAndExtractsText() {
        feed("hello world");
        assertTrue(term.selectWord(1, 0));
        assertEquals("hello", term.selectionText());

        ScreenSnapshot s = snapshot();
        assertTrue(s.hasSelection());
        assertTrue(s.selectionStartVisible());
        assertTrue(s.selectionEndVisible());
        assertEquals(0, s.selectionStartX());
        assertEquals(0, s.selectionStartY());
        assertEquals(4, s.selectionEndX());
        assertEquals(0, s.selectionEndY());
        // Selected cells render as inverse video; unselected ones don't.
        assertEquals(s.defaultBg(), s.fg[cell(0, 0)]);
        assertEquals(s.defaultFg(), s.bg[cell(0, 0)]);
        assertEquals(s.defaultFg(), s.fg[cell(6, 0)]);
        assertEquals(s.defaultBg(), s.bg[cell(6, 0)]);
    }

    @Test
    public void selectionDragMovesGrabbedEndpoint() {
        feed("hello world");
        term.selectWord(1, 0); // "hello"
        term.selectionAnchor(1); // grab the bottom-right handle
        term.selectionDrag(8, 0);
        assertEquals("hello wor", term.selectionText());

        term.selectionAnchor(0); // grab the top-left handle instead
        term.selectionDrag(6, 0);
        assertEquals("wor", term.selectionText());
    }

    @Test
    public void selectionDragAcrossAnchorFlips() {
        feed("hello world");
        term.selectWord(7, 0); // "world" (cols 6..10)
        term.selectionAnchor(1); // anchor at the start, drag the end
        term.selectionDrag(2, 0); // cross the anchor leftwards
        assertEquals("llo w", term.selectionText());

        ScreenSnapshot s = snapshot();
        assertEquals(2, s.selectionStartX()); // endpoints report reordered
        assertEquals(6, s.selectionEndX());
    }

    @Test
    public void selectLineSelectsWholeLine() {
        feed("hello world");
        assertTrue(term.selectLine(3, 0));
        assertEquals("hello world", term.selectionText());

        ScreenSnapshot s = snapshot();
        assertTrue(s.hasSelection());
        assertTrue(s.selectionStartVisible());
        assertEquals(0, s.selectionStartX());
        assertEquals(0, s.selectionStartY());
        assertEquals(10, s.selectionEndX());
        assertEquals(0, s.selectionEndY());
    }

    @Test
    public void selectLineJoinsSoftWrappedRows() {
        // A token longer than the 80-col grid soft-wraps onto the next row;
        // a line selection on either row yields the whole unwrapped line.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        feed(sb.toString());
        assertTrue(term.selectLine(0, 1)); // the wrapped continuation row
        assertEquals(sb.toString(), term.selectionText());
    }

    @Test
    public void selectAllCoversScrollback() {
        feed("alpha\r\n");
        for (int i = 0; i < 8; i++) {
            feed("filler" + i + "\r\n");
        }
        assertTrue(term.selectAll());
        String all = term.selectionText();
        // Both the scrolled-off first line and the latest one are included.
        assertTrue(all.startsWith("alpha"));
        assertTrue(all.contains("filler7"));
        assertTrue(snapshot().hasSelection());
    }

    @Test
    public void selectWordOnBlankCellSelectsThatCell() {
        feed("a b");
        assertTrue(term.selectWord(1, 0)); // the space between the words
        ScreenSnapshot s = snapshot();
        assertTrue(s.hasSelection());
        assertEquals(1, s.selectionStartX());
        assertEquals(1, s.selectionEndX());
    }

    @Test
    public void selectionTracksTextIntoScrollback() {
        feed("alpha\r\n");
        term.selectWord(0, 0);
        assertEquals("alpha", term.selectionText());

        for (int i = 0; i < 8; i++) {
            feed("filler" + i + "\r\n");
        }
        // "alpha" scrolled into history; the tracked selection followed it
        // and its endpoints are no longer in the viewport.
        assertEquals("alpha", term.selectionText());
        ScreenSnapshot s = snapshot();
        assertTrue(s.hasSelection());
        assertFalse(s.selectionStartVisible());

        term.scrollBy(-100); // clamp to top; the selection comes back on screen
        s = snapshot();
        assertTrue(s.selectionStartVisible());
        assertEquals(0, s.selectionStartY());
        assertEquals(s.defaultBg(), s.fg[cell(0, 0)]);
    }

    @Test
    public void selectionClearRemovesSelection() {
        feed("hello");
        term.selectWord(0, 0);
        term.selectionClear();
        assertNull(term.selectionText());
        assertFalse(snapshot().hasSelection());
    }

    @Test
    public void pasteEncodingHonorsBracketedMode() {
        // Plain mode: newlines become carriage returns.
        assertArrayEquals("ab\rcd".getBytes(StandardCharsets.US_ASCII),
                term.encodePaste("ab\ncd"));

        feed("\u001b[?2004h"); // app enables bracketed paste
        assertArrayEquals(
                "\u001b[200~ab\u001b[201~".getBytes(StandardCharsets.US_ASCII),
                term.encodePaste("ab"));
    }

    @Test
    public void kittyGraphicsPlacementAndPixelReadback() {
        term.resize(20, 5, 10, 20); // give the terminal cell pixel geometry
        // 1x1 RGB red pixel, transmit and display at the cursor (a=T).
        // base64 of bytes {0xff, 0x00, 0x00} is "/wAA".
        feed("\u001b_Ga=T,f=24,s=1,v=1;/wAA\u001b\\");

        int[] g = new int[TerminalNative.GFX_STRIDE * 4];
        assertEquals(1, term.graphics(g));
        assertEquals(1, g[TerminalNative.GFX_IMAGE_W]);
        assertEquals(1, g[TerminalNative.GFX_IMAGE_H]);
        assertEquals(0, g[TerminalNative.GFX_COL]);
        assertEquals(0, g[TerminalNative.GFX_ROW]);

        int[] wh = new int[2];
        byte[] rgba = term.imagePixels(g[TerminalNative.GFX_IMAGE_ID], wh);
        assertNotNull(rgba);
        assertEquals(1, wh[0]);
        assertEquals(1, wh[1]);
        // The RGB source gains an opaque alpha channel on read-back.
        assertArrayEquals(new byte[] {(byte) 0xff, 0, 0, (byte) 0xff}, rgba);
    }

    @Test
    public void kittyStrayNulInPayloadStillStores() {
        term.resize(20, 5, 10, 20);
        // mpv's --vo=kitty appends a stray NUL to each frame's final graphics
        // chunk. NUL is an ignore control character (ECMA-48); it must not
        // corrupt the base64 payload -- it previously made the engine reject
        // the whole image (a black screen). terminalFeed strips NULs first.
        // 1x1 red image (base64 "/wAA") with a NUL before the ST terminator.
        feed("\u001b_Ga=T,f=24,s=1,v=1;/wAA\u0000\u001b\\");
        int[] g = new int[TerminalNative.GFX_STRIDE * 4];
        assertEquals("a stray NUL in a kitty payload must not drop the image",
                1, term.graphics(g));
    }

    @Test
    public void xtwinopsSizeReports() {
        term.resize(20, 5, 10, 20); // cell 10x20 -> text area 200x100 px
        // CSI 18 t: text area in cells -> CSI 8 ; rows ; cols t.
        assertEquals("\u001b[8;5;20t", query("\u001b[18t"));
        // CSI 14 t: text area in pixels -> CSI 4 ; height ; width t.
        assertEquals("\u001b[4;100;200t", query("\u001b[14t"));
        // CSI 16 t: cell size in pixels -> CSI 6 ; height ; width t.
        assertEquals("\u001b[6;20;10t", query("\u001b[16t"));
    }

    @Test
    public void kittyGraphicsAbsentWhenNoImages() {
        int[] g = new int[TerminalNative.GFX_STRIDE * 4];
        assertEquals(0, term.graphics(g));
        assertNull(term.imagePixels(123, new int[2]));
    }

    @Test
    public void kittyGraphicsVirtualUnicodePlaceholder() {
        term.resize(20, 5, 10, 20);
        // Transmit a 1x1 red image and create a virtual placement (U=1) with a
        // 1x1 cell grid. i=1 -> image id 1. base64 of {0xff,0,0} is "/wAA".
        feed("\u001b_Ga=T,U=1,i=1,f=24,s=1,v=1,c=1,r=1;/wAA\u001b\\");

        // One placeholder cell for image id 1 (fg RGB 0,0,1) with row=0,col=0
        // diacritics (U+0305 is rowcolumn index 0).
        String ph = new String(Character.toChars(0x10EEEE)) + "\u0305\u0305";
        feed("\u001b[38;2;0;0;1m" + ph + "\u001b[0m");

        int[] g = new int[TerminalNative.GFX_STRIDE * 4];
        assertEquals(1, term.graphics(g));
        assertEquals(1, g[TerminalNative.GFX_IMAGE_ID]);
        assertEquals(1, g[TerminalNative.GFX_IMAGE_W]);
        assertEquals(1, g[TerminalNative.GFX_IMAGE_H]);
        assertEquals(0, g[TerminalNative.GFX_COL]);
        assertEquals(0, g[TerminalNative.GFX_ROW]);
        assertEquals(1, g[TerminalNative.GFX_SRC_W]);
        assertEquals(1, g[TerminalNative.GFX_SRC_H]);
        // A 1x1 image centered in a 10x20 cell scales to 10x10, pushed 5px down.
        assertEquals(10, g[TerminalNative.GFX_PIXEL_W]);
        assertEquals(10, g[TerminalNative.GFX_PIXEL_H]);
        assertEquals(0, g[TerminalNative.GFX_OFF_X]);
        assertEquals(5, g[TerminalNative.GFX_OFF_Y]);
    }

    @Test
    public void searchFindsMatchAcrossScrollbackAndReveals() {
        feed("needle\r\n");
        for (int i = 0; i < 10; i++) {
            feed("filler" + i + "\r\n"); // push "needle" into history
        }
        int[] out = new int[2];
        assertEquals(1, term.searchSet("needle", false, out));
        assertEquals(1, out[0]); // current match (1-based)
        assertEquals(1, out[1]); // navigable count

        // searchSet highlights the match and scrolls it back into the viewport.
        assertEquals("needle", term.selectionText());
        ScreenSnapshot s = snapshot();
        assertTrue(s.hasSelection());
        assertTrue(s.selectionStartVisible());
        assertEquals("needle", s.rowText(s.selectionStartY()));
    }

    @Test
    public void searchCaseSensitivity() {
        feed("Foo foo FOO");
        int[] out = new int[2];
        assertEquals(3, term.searchSet("foo", false, out)); // all three
        assertEquals(1, term.searchSet("foo", true, out));  // only the lowercase
        assertEquals(1, term.searchSet("FOO", true, out));
        assertEquals(0, term.searchSet("bar", false, out));
    }

    @Test
    public void searchUnicodeCaseFolding() {
        // Non-ASCII letters fold case-insensitively via the generated table:
        // Latin-1 (E-acute), Latin Extended-A (C-caron), Cyrillic (Ya) and
        // Greek (Sigma).
        feed("Café café CAFÉ\r\n"
                + "Čau čau\r\n"
                + "Я я\r\n"
                + "Σ σ");
        int[] out = new int[2];
        assertEquals(3, term.searchSet("café", false, out)); // all three
        assertEquals(1, term.searchSet("café", true, out));  // only lowercase
        assertEquals(2, term.searchSet("čau", false, out));  // Čau / čau
        assertEquals(2, term.searchSet("я", false, out));    // Я / я
        assertEquals(2, term.searchSet("σ", false, out));    // Σ / σ
        // Folding is case-, not accent-insensitive: "cafe" must not match.
        assertEquals(0, term.searchSet("cafe", false, out));
    }

    @Test
    public void searchSpansSoftWrap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 18; i++) sb.append('x'); // fill toward the 20th col
        sb.append("needle"); // straddles the soft-wrap boundary
        feed(sb.toString());

        int[] out = new int[2];
        assertEquals(1, term.searchSet("needle", false, out));
        // The selection spans the wrap; unwrapped text is the whole token.
        assertEquals("needle", term.selectionText());
    }

    @Test
    public void searchNoMatchesClearsSelection() {
        feed("hello world");
        int[] out = new int[2];
        // A prior match is highlighted, then a query with no hits clears it.
        assertEquals(1, term.searchSet("hello", false, out));
        assertTrue(snapshot().hasSelection());
        assertEquals(0, term.searchSet("zzz", false, out));
        assertEquals(0, out[0]);
        assertEquals(0, out[1]);
        assertFalse(snapshot().hasSelection());
    }

    @Test
    public void searchStepWraps() {
        feed("match\r\nmatch\r\nmatch");
        int[] out = new int[2];
        assertEquals(3, term.searchSet("match", false, out));
        assertEquals(3, out[0]);             // lands on the last (nearest viewport)
        term.searchStep(1, out);
        assertEquals(1, out[0]);             // wraps to the first
        term.searchStep(-1, out);
        assertEquals(3, out[0]);             // back to the last
        assertEquals("match", term.selectionText());
    }

    @Test
    public void searchStepRescansAfterOutput() {
        feed("alpha\r\n");
        int[] out = new int[2];
        assertEquals(1, term.searchSet("alpha", false, out));
        // New output adds a match; stepping must re-scan and count it.
        feed("alpha\r\n");
        assertEquals(2, term.searchStep(1, out));
        assertEquals(2, out[1]);
    }

    @Test
    public void searchClearRemovesMatchesAndSelection() {
        feed("hello world");
        int[] out = new int[2];
        assertEquals(1, term.searchSet("world", false, out));
        assertTrue(snapshot().hasSelection());

        term.searchClear();
        assertFalse(snapshot().hasSelection());
        assertEquals(0, term.searchStep(1, out)); // search state was freed
        assertEquals(0, out[0]);
    }

    @Test
    public void searchCountsPastTheNavigableCapAndKeepsNewestReachable() {
        // More matches than the navigable window: the whole buffer must still be
        // scanned (an honest total), and the most recent matches must stay
        // reachable — the regression where the scan stopped at the cap and the
        // newest output was never searched.
        final int lines = 60_000; // > MAX_MATCHES (50_000)
        TerminalEmulator big = new TerminalEmulator(4, 2, 70_000);
        try {
            StringBuilder sb = new StringBuilder(lines * 3);
            for (int i = 0; i < lines; i++) sb.append("x\r\n");
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            big.feed(b, b.length);

            int[] out = new int[2];
            int total = big.searchSet("x", false, out);
            assertEquals("every match counted", lines, total);
            assertEquals("navigable window capped", 50_000, out[1]);
            // The newest hit (bottom of the buffer) is current and highlighted.
            assertEquals("newest match reachable", lines, out[0]);
            assertEquals("x", big.selectionText());
        } finally {
            big.close();
        }
    }
}
