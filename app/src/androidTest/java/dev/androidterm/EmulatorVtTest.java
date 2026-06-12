package dev.androidterm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import android.view.KeyEvent;

import dev.androidterm.term.ScreenSnapshot;
import dev.androidterm.term.TerminalEmulator;
import dev.androidterm.term.TerminalNative;

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
}
