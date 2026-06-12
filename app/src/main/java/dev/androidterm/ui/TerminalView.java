package dev.androidterm.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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

    private static final float MIN_FONT_SP = 8f;
    private static final float MAX_FONT_SP = 40f;
    private static final float DEFAULT_FONT_SP = 14f;
    private static final String PREFS = "terminal";
    private static final String PREF_FONT_SP = "font_size_sp";

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private float scrollRemainder;
    private float fontSizeSp;

    // --- Selection. The emulator owns the selection itself (it tracks its
    // text across scrolling and new output); this view only mirrors it:
    // `selecting` spans the ActionMode lifecycle, the handle rects are
    // recomputed from each snapshot in onDraw and hit-tested on touch.
    private boolean selecting;
    private int draggingHandle = -1; // -1 none, 0 top-left, 1 bottom-right
    private float dragOffsetX, dragOffsetY; // grabbed cell center − touch point
    private ActionMode actionMode;
    private final Drawable handleLeft, handleRight;
    private final RectF startHandleRect = new RectF();
    private final RectF endHandleRect = new RectF();

    private static final int MENU_COPY = 1;
    private static final int MENU_PASTE = 2;

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);

        textPaint.setTypeface(Typeface.MONOSPACE);
        fontSizeSp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(PREF_FONT_SP, DEFAULT_FONT_SP);
        setTextSizePx(spToPx(fontSizeSp));

        scaleGestures = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                applyFontSize(fontSizeSp * d.getScaleFactor());
                return true; // consume so the factor stays incremental
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                persistFontSize(); // once per gesture, not per frame
            }
        });

        TypedArray handles = context.obtainStyledAttributes(new int[] {
                android.R.attr.textSelectHandleLeft,
                android.R.attr.textSelectHandleRight});
        handleLeft = handles.getDrawable(0);
        handleRight = handles.getDrawable(1);
        handles.recycle();

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (selecting) {
                    finishSelection();
                    return true;
                }
                requestFocus();
                InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
                imm.showSoftInput(TerminalView.this, 0);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                startSelection(e.getX(), e.getY());
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

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    /** Sets the font size (clamped) and persists it; reflows the grid. */
    public void setFontSizeSp(float sp) {
        applyFontSize(sp);
        persistFontSize();
    }

    public float fontSizeSp() {
        return fontSizeSp;
    }

    private void applyFontSize(float sp) {
        sp = Math.max(MIN_FONT_SP, Math.min(MAX_FONT_SP, sp));
        if (sp == fontSizeSp) return;
        fontSizeSp = sp;
        setTextSizePx(spToPx(sp));
        if (getWidth() > 0) {
            updateGridSize(getWidth(), getHeight());
        }
        invalidate();
    }

    private void persistFontSize() {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(PREF_FONT_SP, fontSizeSp).apply();
    }

    public void setStickyModifiers(StickyModifiers mods) {
        sticky = mods;
    }

    /** Binds a session; pass null to detach. Resizes it to fit this view. */
    public void attachSession(TerminalSession s) {
        if (s != session) finishSelection(); // also clears the old session's selection
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
        // Handle drags own the whole gesture; everything else (scroll, tap,
        // long-press, pinch) still works while a selection is showing.
        if (selecting && selectionHandleTouch(event)) return true;
        scaleGestures.onTouchEvent(event);
        // Suppress scrolling (and taps) while a pinch is in progress so the
        // viewport doesn't jump around during zoom.
        if (!scaleGestures.isInProgress()) {
            gestures.onTouchEvent(event);
        }
        return true;
    }

    // --- Selection ---

    private void startSelection(float px, float py) {
        if (session == null || selecting) return;
        int cx = clampToGrid(px / cellWidth, cols);
        int cy = clampToGrid(py / (float) cellHeight, rows);
        if (!session.emulator.selectWord(cx, cy)) return;
        selecting = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        actionMode = startActionMode(selectionActions, ActionMode.TYPE_FLOATING);
        invalidate();
    }

    /** Ends selection mode and clears the emulator's selection. Idempotent. */
    public void finishSelection() {
        if (actionMode != null) {
            actionMode.finish(); // onDestroyActionMode resets the state
        } else if (selecting) {
            selecting = false;
            if (session != null) session.emulator.selectionClear();
            invalidate();
        }
    }

    private boolean selectionHandleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                for (int which = 0; which < 2; which++) {
                    RectF r = which == 0 ? startHandleRect : endHandleRect;
                    if (r.isEmpty() || !r.contains(event.getX(), event.getY())) {
                        continue;
                    }
                    draggingHandle = which;
                    // Drag relative to the grabbed endpoint's cell so the
                    // selection doesn't jump under the finger.
                    int hx = which == 0 ? snapshot.selectionStartX() : snapshot.selectionEndX();
                    int hy = which == 0 ? snapshot.selectionStartY() : snapshot.selectionEndY();
                    dragOffsetX = (hx + 0.5f) * cellWidth - event.getX();
                    dragOffsetY = (hy + 0.5f) * cellHeight - event.getY();
                    if (session != null) session.emulator.selectionAnchor(which);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (draggingHandle < 0) return false;
                dragSelectionTo(event.getX() + dragOffsetX, event.getY() + dragOffsetY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingHandle < 0) return false;
                draggingHandle = -1;
                if (actionMode != null) actionMode.invalidateContentRect();
                return true;
            default:
                return draggingHandle >= 0;
        }
    }

    private void dragSelectionTo(float px, float py) {
        if (session == null) return;
        // Dragging past the edge scrolls a row per move event; the tracked
        // selection stays glued to its text while the viewport moves.
        if (py < 0) {
            session.emulator.scrollBy(-1);
        } else if (py >= rows * cellHeight) {
            session.emulator.scrollBy(1);
        }
        session.emulator.selectionDrag(
                clampToGrid(px / cellWidth, cols),
                clampToGrid(py / (float) cellHeight, rows));
        if (actionMode != null) actionMode.hide(ActionMode.DEFAULT_HIDE_DURATION);
        invalidate();
    }

    private static int clampToGrid(float cell, int count) {
        return Math.max(0, Math.min((int) cell, count - 1));
    }

    /**
     * Metadata-only check (no clip data read) so showing the Paste button
     * doesn't trigger Android's "app accessed the clipboard" toast.
     */
    private boolean clipboardHasText() {
        ClipboardManager cm = getContext().getSystemService(ClipboardManager.class);
        if (cm == null || !cm.hasPrimaryClip()) return false;
        ClipDescription d = cm.getPrimaryClipDescription();
        return d != null && d.hasMimeType("text/*");
    }

    private String clipboardText() {
        ClipboardManager cm = getContext().getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        return text == null || text.length() == 0 ? null : text.toString();
    }

    private void copySelection() {
        String text = session == null ? null : session.emulator.selectionText();
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = getContext().getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    private void pasteClipboard() {
        String text = clipboardText();
        if (text == null || session == null) return;
        byte[] encoded = session.emulator.encodePaste(text);
        if (encoded == null) return;
        session.emulator.scrollToBottom();
        session.writeBytes(encoded);
        invalidate();
    }

    private final ActionMode.Callback2 selectionActions = new ActionMode.Callback2() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy);
            if (clipboardHasText()) {
                menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste);
            }
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == MENU_COPY) {
                copySelection();
            } else if (item.getItemId() == MENU_PASTE) {
                pasteClipboard();
            }
            mode.finish();
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // Single reset path: reached from finishSelection() and from
            // system-initiated dismissals alike.
            actionMode = null;
            selecting = false;
            draggingHandle = -1;
            if (session != null) session.emulator.selectionClear();
            invalidate();
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            // Float the toolbar around the visible part of the selection,
            // leaving room for the handles below it.
            int top = snapshot.selectionStartVisible()
                    ? snapshot.selectionStartY() * cellHeight : 0;
            int bottom = snapshot.selectionEndVisible()
                    ? (snapshot.selectionEndY() + 1) * cellHeight
                            + (handleRight != null ? handleRight.getIntrinsicHeight() : 0)
                    : getHeight();
            int left = 0, right = getWidth();
            if (snapshot.selectionStartVisible() && snapshot.selectionEndVisible()
                    && snapshot.selectionStartY() == snapshot.selectionEndY()) {
                left = (int) (snapshot.selectionStartX() * cellWidth);
                right = (int) ((snapshot.selectionEndX() + 1) * cellWidth);
            }
            outRect.set(left, top, right, bottom);
        }
    };

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
        drawSelectionHandles(canvas);
        if (selecting && !snapshot.hasSelection()) {
            // The selected text scrolled out of existence (scrollback
            // pruning, screen switch); retire the UI outside of draw.
            post(this::finishSelection);
        }
    }

    private void drawSelectionHandles(Canvas canvas) {
        startHandleRect.setEmpty();
        endHandleRect.setEmpty();
        if (!selecting || !snapshot.hasSelection()) return;
        if (snapshot.selectionStartVisible() && handleLeft != null) {
            placeHandle(handleLeft, startHandleRect, true,
                    snapshot.selectionStartX() * cellWidth,
                    (snapshot.selectionStartY() + 1) * cellHeight);
            handleLeft.draw(canvas);
        }
        if (snapshot.selectionEndVisible() && handleRight != null) {
            placeHandle(handleRight, endHandleRect, false,
                    (snapshot.selectionEndX() + 1) * cellWidth,
                    (snapshot.selectionEndY() + 1) * cellHeight);
            handleRight.draw(canvas);
        }
    }

    /**
     * Anchors a handle drawable's pointer tip at (anchorX, anchorY) — the
     * hotspot sits at 3/4 of the width for the left handle and 1/4 for the
     * right, like TextView's — and records an enlarged touch target.
     */
    private void placeHandle(Drawable d, RectF touchRect, boolean left,
            float anchorX, float anchorY) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        int x = (int) (anchorX - (left ? w * 3 / 4f : w / 4f));
        int y = (int) anchorY;
        d.setBounds(x, y, x + w, y + h);
        touchRect.set(x, y, x + w, y + h);
        touchRect.inset(-w / 4f, -h / 4f);
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
        if (selecting) finishSelection();
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
        if (selecting) finishSelection();
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
        if (selecting) finishSelection();

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
