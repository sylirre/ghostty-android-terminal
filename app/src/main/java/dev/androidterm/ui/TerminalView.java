package dev.androidterm.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import dev.androidterm.term.ScreenSnapshot;
import dev.androidterm.term.TerminalNative;
import dev.androidterm.term.TerminalSession;

/**
 * Renders one session's viewport as a monospace cell grid and feeds user
 * input back to it.
 *
 * Drawing pulls a fresh {@link ScreenSnapshot} per frame (cheap flat-array
 * copy) instead of listening for deltas, so a missed invalidate can never
 * show stale state. Input uses a TYPE_NULL InputConnection — the standard
 * terminal-app trick that makes soft keyboards send raw key events and
 * commitText instead of rich-editing the "text field".
 */
public class TerminalView extends View {

    /** Sticky CTRL/ALT state shared with the extra-keys toolbar. */
    public static class StickyModifiers {
        public boolean ctrl;
        public boolean alt;
        public Runnable onChanged;

        int consume() {
            int mods = (ctrl ? TerminalNative.MOD_CTRL : 0)
                    | (alt ? TerminalNative.MOD_ALT : 0);
            if (mods != 0) {
                ctrl = alt = false;
                if (onChanged != null) onChanged.run();
            }
            return mods;
        }
    }

    private TerminalSession session;
    private final ScreenSnapshot snapshot = new ScreenSnapshot();
    private StickyModifiers sticky = new StickyModifiers();

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private float cellWidth;
    private int cellHeight;
    private int baseline;
    private int cols = 80, rows = 24;

    private final GestureDetector gestures;
    private float scrollRemainder;

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);

        textPaint.setTypeface(Typeface.MONOSPACE);
        setTextSizePx(14 * getResources().getDisplayMetrics().scaledDensity);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                requestFocus();
                InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
                imm.showSoftInput(TerminalView.this, 0);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (session == null) return true;
                scrollRemainder += dy / cellHeight;
                int lines = (int) scrollRemainder;
                if (lines != 0) {
                    scrollRemainder -= lines;
                    session.emulator.scrollBy(lines);
                    invalidate();
                }
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                scrollRemainder = 0;
                return true;
            }
        });
    }

    private void setTextSizePx(float px) {
        textPaint.setTextSize(px);
        Paint.FontMetricsInt fm = textPaint.getFontMetricsInt();
        cellWidth = textPaint.measureText("M");
        cellHeight = fm.descent - fm.ascent;
        baseline = -fm.ascent;
    }

    public void setStickyModifiers(StickyModifiers mods) {
        sticky = mods;
    }

    /** Binds a session; pass null to detach. Resizes it to fit this view. */
    public void attachSession(TerminalSession s) {
        session = s;
        if (s != null && getWidth() > 0) {
            updateGridSize(getWidth(), getHeight());
        }
        invalidate();
    }

    public TerminalSession session() {
        return session;
    }

    /** Grid size implied by the current view bounds (80x24 until laid out). */
    public int gridCols() {
        return cols;
    }

    public int gridRows() {
        return rows;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateGridSize(w, h);
    }

    private void updateGridSize(int w, int h) {
        cols = Math.max(4, (int) (w / cellWidth));
        rows = Math.max(2, h / cellHeight);
        if (session != null) {
            session.resize(cols, rows, (int) cellWidth, cellHeight);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (session == null || !session.emulator.snapshot(snapshot)) {
            canvas.drawColor(0xFF000000);
            return;
        }
        canvas.drawColor(snapshot.defaultBg());

        int sc = snapshot.cols, sr = snapshot.rows;
        // Background runs first so glyphs never get painted over.
        for (int y = 0; y < sr; y++) {
            float top = y * cellHeight, bottom = top + cellHeight;
            int runStart = 0;
            int runBg = snapshot.bg[y * sc];
            for (int x = 1; x <= sc; x++) {
                int bg = x < sc ? snapshot.bg[y * sc + x] : 0;
                if (x == sc || bg != runBg) {
                    if (runBg != snapshot.defaultBg()) {
                        bgPaint.setColor(runBg);
                        canvas.drawRect(runStart * cellWidth, top, x * cellWidth, bottom, bgPaint);
                    }
                    runStart = x;
                    runBg = bg;
                }
            }
        }
        drawCursor(canvas);
        for (int y = 0; y < sr; y++) {
            drawRowText(canvas, y, sc);
        }
    }

    private final StringBuilder runText = new StringBuilder(128);

    private void drawRowText(Canvas canvas, int y, int sc) {
        float top = y * cellHeight;
        int runStart = -1, runFg = 0, runAttr = 0;
        runText.setLength(0);
        for (int x = 0; x <= sc; x++) {
            int i = y * sc + x;
            int cp = x < sc ? snapshot.codepoints[i] : 0;
            int fg = x < sc ? snapshot.fg[i] : 0;
            int attr = x < sc ? (snapshot.attrs[i] & ~TerminalNative.ATTR_WIDE) : 0;
            boolean breakRun = x == sc || cp == 0 || fg != runFg || attr != runAttr
                    // Wide glyphs advance two cells; keep them out of batched runs.
                    || (x < sc && (snapshot.attrs[i] & TerminalNative.ATTR_WIDE) != 0);
            if (breakRun && runStart >= 0 && runText.length() > 0) {
                applyStyle(runFg, runAttr);
                canvas.drawText(runText, 0, runText.length(),
                        runStart * cellWidth, top + baseline, textPaint);
                runStart = -1;
                runText.setLength(0);
            }
            if (x == sc || cp == 0) continue;
            if ((snapshot.attrs[i] & TerminalNative.ATTR_WIDE) != 0) {
                applyStyle(fg, attr);
                String s = new String(Character.toChars(cp));
                canvas.drawText(s, x * cellWidth, top + baseline, textPaint);
                continue;
            }
            if (runStart < 0) {
                runStart = x;
                runFg = fg;
                runAttr = attr;
            }
            runText.appendCodePoint(cp);
        }
    }

    private void applyStyle(int fg, int attr) {
        textPaint.setColor(fg);
        textPaint.setFakeBoldText((attr & TerminalNative.ATTR_BOLD) != 0);
        textPaint.setTextSkewX((attr & TerminalNative.ATTR_ITALIC) != 0 ? -0.25f : 0);
        textPaint.setUnderlineText((attr & TerminalNative.ATTR_UNDERLINE) != 0);
        textPaint.setStrikeThruText((attr & TerminalNative.ATTR_STRIKE) != 0);
    }

    private void drawCursor(Canvas canvas) {
        if (!snapshot.cursorInViewport() || !snapshot.cursorVisible()) return;
        float left = snapshot.cursorX() * cellWidth;
        float top = snapshot.cursorY() * cellHeight;
        boolean wide = snapshot.cursorX() < snapshot.cols
                && (snapshot.attrs[snapshot.cursorY() * snapshot.cols + snapshot.cursorX()]
                        & TerminalNative.ATTR_WIDE) != 0;
        float right = left + cellWidth * (wide ? 2 : 1);
        bgPaint.setColor(snapshot.defaultFg());
        switch (snapshot.cursorStyle()) {
            case TerminalNative.CURSOR_BAR:
                canvas.drawRect(left, top, left + cellWidth / 4, top + cellHeight, bgPaint);
                break;
            case TerminalNative.CURSOR_UNDERLINE:
                canvas.drawRect(left, top + cellHeight - cellHeight / 8f,
                        right, top + cellHeight, bgPaint);
                break;
            case TerminalNative.CURSOR_BLOCK_HOLLOW: {
                float w = Math.max(1, cellWidth / 8);
                canvas.drawRect(left, top, right, top + w, bgPaint);
                canvas.drawRect(left, top + cellHeight - w, right, top + cellHeight, bgPaint);
                canvas.drawRect(left, top, left + w, top + cellHeight, bgPaint);
                canvas.drawRect(right - w, top, right, top + cellHeight, bgPaint);
                break;
            }
            default: { // block: invert the cell
                canvas.drawRect(left, top, right, top + cellHeight, bgPaint);
                int i = snapshot.cursorY() * snapshot.cols + snapshot.cursorX();
                int cp = snapshot.codepoints[i];
                if (cp != 0) {
                    applyStyle(snapshot.bg[i], snapshot.attrs[i]);
                    canvas.drawText(new String(Character.toChars(cp)),
                            left, top + baseline, textPaint);
                }
                // Glyph is drawn here in inverse; null it so the text pass skips it.
                snapshot.codepoints[i] = 0;
                break;
            }
        }
    }

    // --- Input ---

    /** Sends printable text, applying any sticky CTRL/ALT to single chars. */
    public void dispatchText(String text) {
        if (session == null || text.isEmpty()) return;
        int mods = sticky.consume();
        if (mods == 0 || text.codePointCount(0, text.length()) > 1) {
            session.emulator.scrollToBottom();
            session.write(text);
        } else {
            char ch = text.charAt(0);
            byte[] encoded = session.emulator.encodeKey(
                    keycodeForChar(ch), mods, text, Character.toLowerCase(ch));
            session.emulator.scrollToBottom();
            if (encoded != null) {
                session.writeBytes(encoded);
            } else if ((mods & TerminalNative.MOD_CTRL) != 0) {
                // Encoder couldn't map the key; classic ^X arithmetic.
                int c = Character.toUpperCase(ch);
                if (c >= '@' && c <= '_') session.writeBytes(new byte[] {(byte) (c & 0x1F)});
            }
        }
        invalidate();
    }

    /** Sends a non-printable key (arrows, ESC, …) through the VT encoder. */
    public void dispatchKey(int androidKeyCode) {
        if (session == null) return;
        session.sendKey(androidKeyCode, sticky.consume(), null, 0);
        invalidate();
    }

    private static int keycodeForChar(char ch) {
        char c = Character.toLowerCase(ch);
        if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        if (c == ' ') return KeyEvent.KEYCODE_SPACE;
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                dispatchText(text.toString());
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) {
                    dispatchKey(KeyEvent.KEYCODE_DEL);
                }
                return true;
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (session == null || keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        }
        if (event.isSystem()) return super.onKeyDown(keyCode, event);

        int mods = sticky.consume();
        if (event.isCtrlPressed()) mods |= TerminalNative.MOD_CTRL;
        if (event.isAltPressed()) mods |= TerminalNative.MOD_ALT;
        if (event.isShiftPressed()) mods |= TerminalNative.MOD_SHIFT;

        // Text the key would produce without ctrl/alt (so Ctrl+C yields "c").
        int unicode = event.getUnicodeChar(event.getMetaState()
                & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK));
        String utf8 = unicode > 0 ? new String(Character.toChars(unicode)) : null;
        int unshifted = event.getUnicodeChar(0);

        if (mods == 0 && utf8 != null && keyCode != KeyEvent.KEYCODE_ENTER
                && keyCode != KeyEvent.KEYCODE_TAB) {
            dispatchText(utf8);
        } else {
            session.sendKey(keyCode, mods, utf8, unshifted);
            invalidate();
        }
        return true;
    }
}
