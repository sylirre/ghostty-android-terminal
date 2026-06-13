package dev.androidterm.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
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
import android.widget.OverScroller;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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

    // --- Rich keyboard input (opt-in; AppSettings.richKeyboard). When on AND
    // the terminal is in a plain line-editing state, the soft keyboard runs in
    // composing mode (TYPE_CLASS_TEXT) so suggestions/autocorrect/swipe work.
    // The IME edits a local buffer that we mirror to the PTY by diffing it
    // against what was already sent (richSent), emitting backspaces + new text.
    // Inside full-screen / raw-key apps we fall back to the TYPE_NULL path.
    // The mirror is a best-effort approximation of the remote line: any special
    // key, line submit, or terminal mode change resets it (see resetRichInput).
    private boolean richKeyboardEnabled;
    private boolean richInputActive; // enabled AND terminal currently safe
    private Editable richEditable;   // the active composing connection's buffer
    private String richSent = "";    // text already forwarded for this line
    private boolean restartInputPending; // a debounced restartInput is queued

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private float cellWidth;
    private int cellHeight;
    private int baseline;
    private int cols = 80, rows = 24;

    // --- Kitty graphics. Placement geometry is re-read every frame into gfx
    // (GFX_STRIDE ints each); decoded bitmaps are cached by image id and
    // re-fetched only when an id is new or its dimensions changed. ---
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int[] gfx = new int[TerminalNative.GFX_STRIDE * 4];
    private int gfxCount;
    private final Map<Integer, Bitmap> imageCache = new HashMap<>();
    private final int[] imageWh = new int[2];
    private final Rect imgSrc = new Rect();
    private final RectF imgDst = new RectF();

    private static final float MIN_FONT_SP = 8f;
    private static final float MAX_FONT_SP = 40f;
    private static final float DEFAULT_FONT_SP = 14f;
    private static final String PREFS = "terminal";
    private static final String PREF_FONT_SP = "font_size_sp";

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private float scrollRemainder;
    private float fontSizeSp;

    // --- Fling/momentum scrolling. A flick over scrollback hands its velocity
    // to an OverScroller, whose decelerating position is sampled once per
    // animation frame and converted to whole-row scrollBy() calls (the engine
    // scrolls in integer rows). flingRemainder carries the sub-row fraction
    // between frames.
    private final OverScroller scroller;
    private int lastFlingY;
    private float flingRemainder;
    // Cached [total, offset, len] in rows from emulator.scrollbar(), refreshed
    // each onDraw and after each fling step. Feeds two things: the fling's
    // edge-stop check, and the vertical scroll-position indicator, whose hot
    // per-frame computeVerticalScroll* callbacks read it instead of crossing
    // the JNI boundary themselves.
    private final int[] scrollState = new int[3];
    // Cap peak fling speed so a hard flick on a device reporting a huge
    // velocity can't leap across the whole scrollback in a couple of frames.
    private static final float MAX_FLING_ROWS_PER_SEC = 600f;

    // --- Selection. The emulator owns the selection itself (it tracks its
    // text across scrolling and new output); this view only mirrors it:
    // `selecting` spans the ActionMode lifecycle, the handle rects are
    // recomputed from each snapshot in onDraw and hit-tested on touch.
    private boolean selecting;
    private int draggingHandle = -1; // -1 none, 0 top-left, 1 bottom-right
    private boolean longPressDragging; // extending the selection from a long-press
    private float dragOffsetX, dragOffsetY; // grabbed cell center − touch point
    private ActionMode actionMode;
    private final Drawable handleLeft, handleRight;
    private final RectF startHandleRect = new RectF();
    private final RectF endHandleRect = new RectF();
    // Selection geometry the floating toolbar was last positioned for; lets
    // onDraw reposition it (invalidateContentRect) only when it actually moves.
    private long toolbarSelGeom = Long.MIN_VALUE;

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

        scroller = new OverScroller(context);
        // The vertical scrollbar is declared in the layout (android:scrollbars)
        // so the base View constructor builds the scrollbar drawable — enabling
        // it programmatically here would not, leaving awakenScrollBars() a
        // no-op. It fades by default; the scroll paths awaken it to flash it.
        setScrollbarFadingEnabled(true);

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
                    if (snapshot.altScreen()) {
                        // No scrollback on the alternate screen; feed the swipe
                        // to nano/vim/less as arrow keys so it moves the cursor.
                        // dy > 0 is a finger-up swipe (revealing lower content),
                        // matching scrollBy(+) — i.e. Down arrow.
                        int code = lines > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                                             : KeyEvent.KEYCODE_DPAD_UP;
                        for (int i = Math.abs(lines); i > 0; i--) {
                            session.sendKey(code, 0, null, 0);
                        }
                    } else {
                        session.emulator.scrollBy(lines);
                        if (scrollState[0] > scrollState[2]) awakenScrollBars();
                        invalidate();
                    }
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (session == null || snapshot.altScreen()) return false;
                // No scrollback on the alt screen; the per-row swipe→arrow-key
                // translation in onScroll stays the only motion there.
                scroller.forceFinished(true);
                lastFlingY = 0;
                flingRemainder = 0;
                // onScroll accumulates distanceY (finger-up is positive); a
                // fling's velocityY is the opposite sign, so negate it to keep
                // the coast going the same way the drag did.
                float max = MAX_FLING_ROWS_PER_SEC * cellHeight;
                int v = (int) Math.max(-max, Math.min(max, -vy));
                scroller.fling(0, 0, 0, v, 0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
                postOnAnimation(flingStep);
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true); // a touch catches an in-flight fling
                scrollRemainder = 0;
                return true;
            }
        });
    }

    /**
     * One animation frame of an in-progress fling: samples the scroller's
     * decelerating position, scrolls the viewport by the whole rows crossed
     * since the last frame, and reschedules itself until the scroller finishes
     * or the viewport pins against the edge it is heading for.
     */
    private final Runnable flingStep = new Runnable() {
        @Override
        public void run() {
            if (session == null || !scroller.computeScrollOffset()) return;
            int y = scroller.getCurrY();
            flingRemainder += (y - lastFlingY) / (float) cellHeight;
            lastFlingY = y;
            int lines = (int) flingRemainder;
            if (lines != 0) {
                flingRemainder -= lines;
                session.emulator.scrollBy(lines);
                invalidate();
                // scrollBy clamps silently at the ends; stop coasting once the
                // viewport is pinned against the edge we're moving toward
                // (lines > 0 reveals lower content, lines < 0 goes into history).
                session.emulator.scrollbar(scrollState);
                if (scrollState[0] > scrollState[2]) awakenScrollBars();
                int offset = scrollState[1];
                boolean atTop = offset <= 0;
                boolean atBottom = offset + scrollState[2] >= scrollState[0];
                if ((lines < 0 && atTop) || (lines > 0 && atBottom)) {
                    scroller.forceFinished(true);
                    return;
                }
            }
            postOnAnimation(this);
        }
    };

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
        if (s != session) {
            finishSelection(); // also clears the old session's selection
            clearImageCache(); // image ids belong to the old terminal
            gfxCount = 0;
            resetRichInput(); // the mirror belonged to the old session's line
            scroller.forceFinished(true); // don't coast into the new session
        }
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

    /** Cell pixel size, for seeding the PTY winsize's pixel fields. */
    public int cellWidthPx() {
        return (int) cellWidth;
    }

    public int cellHeightPx() {
        return cellHeight;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateGridSize(w, h);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearImageCache(); // release decoded bitmaps; rebuilt on next draw
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
        // While the long-press finger is still down, dragging extends the
        // selection (we own these events so they never reach the detectors —
        // which also keeps the release from being read as a dismissing tap).
        if (longPressDragging && longPressDragTouch(event)) return true;
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
        // Keep dragging from the long-press to extend: pin the start so the
        // drag moves the end (crossing back over the start flips naturally).
        session.emulator.selectionAnchor(1);
        longPressDragging = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // Refresh the mirror so the toolbar's first (synchronous)
        // onGetContentRect sees the new selection and anchors above the word
        // instead of falling back to a whole-view rect.
        session.emulator.snapshot(snapshot);
        toolbarSelGeom = selectionGeometryKey();
        actionMode = startActionMode(selectionActions, ActionMode.TYPE_FLOATING);
        invalidate();
    }

    /**
     * Packs the visible selection endpoints + visibility flags into a key so
     * onDraw can detect when the toolbar needs repositioning. Each coordinate
     * gets 12 bits (terminal dimensions never approach 4096); returns a
     * sentinel when there is no selection.
     */
    private long selectionGeometryKey() {
        if (!snapshot.hasSelection()) return Long.MIN_VALUE;
        long flags = (snapshot.selectionStartVisible() ? 1 : 0)
                | (snapshot.selectionEndVisible() ? 2 : 0);
        return (flags << 48)
                | ((long) (snapshot.selectionStartX() & 0xFFF) << 36)
                | ((long) (snapshot.selectionStartY() & 0xFFF) << 24)
                | ((long) (snapshot.selectionEndX() & 0xFFF) << 12)
                | (snapshot.selectionEndY() & 0xFFF);
    }

    /** Ends selection mode and clears the emulator's selection. Idempotent. */
    public void finishSelection() {
        if (actionMode != null) {
            actionMode.finish(); // onDestroyActionMode resets the state
        } else if (selecting) {
            selecting = false;
            longPressDragging = false;
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
                reshowToolbar();
                return true;
            default:
                return draggingHandle >= 0;
        }
    }

    /**
     * Extends the selection while the long-press finger stays down. Returns
     * true once it has consumed the gesture's MOVE/UP so they bypass the
     * gesture detectors. A long-press with no movement just selects the word.
     */
    private boolean longPressDragTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                dragSelectionTo(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                longPressDragging = false;
                // Re-show the toolbar (dragSelectionTo hid it) above the
                // final selection.
                reshowToolbar();
                return true;
            default:
                return true;
        }
    }

    /**
     * Repositions the floating toolbar over the current selection and cancels
     * any pending hide scheduled by {@link #dragSelectionTo}, so it reappears
     * immediately when a drag ends. invalidateContentRect() alone only
     * repositions: it leaves the framework's hide-requested flag set, which
     * otherwise keeps the toolbar hidden for the ~2s ActionMode hide duration
     * (the source of the "toolbar appears seconds late" delay). hide(0) runs
     * the reshow now.
     */
    private void reshowToolbar() {
        if (actionMode == null) return;
        toolbarSelGeom = selectionGeometryKey();
        actionMode.invalidateContentRect();
        actionMode.hide(0);
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
            longPressDragging = false;
            toolbarSelGeom = Long.MIN_VALUE;
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

    // --- Vertical scroll-position indicator. The framework draws and fades a
    // thumb on the right edge from these three values (row units); the scroll
    // paths call awakenScrollBars() to flash it while scrolling. They read the
    // scrollState cache (refreshed in onDraw) so these per-frame callbacks
    // never reach across the JNI boundary.
    @Override
    protected int computeVerticalScrollRange() {
        return scrollState[0];
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return scrollState[1];
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return scrollState[2];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (session == null || !session.emulator.snapshot(snapshot)) {
            canvas.drawColor(0xFF000000);
            return;
        }
        session.emulator.scrollbar(scrollState); // keep the indicator current
        updateRichInputActive();
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
        updateGraphics();
        drawImages(canvas, true); // z < 0: above background, below text
        drawCursor(canvas);
        for (int y = 0; y < sr; y++) {
            drawRowText(canvas, y, sc);
        }
        drawImages(canvas, false); // z >= 0: above text (the Kitty default)
        drawSelectionHandles(canvas);
        if (selecting && !snapshot.hasSelection()) {
            // The selected text scrolled out of existence (scrollback
            // pruning, screen switch); retire the UI outside of draw.
            post(this::finishSelection);
        } else if (selecting && actionMode != null
                && draggingHandle < 0 && !longPressDragging) {
            // The selection moved under the toolbar (new output, scroll);
            // reposition it to stay above the selection. Gated on a geometry
            // change so this is idle most frames, and skipped mid-drag where
            // dragSelectionTo deliberately hides the toolbar.
            long geom = selectionGeometryKey();
            if (geom != toolbarSelGeom) {
                toolbarSelGeom = geom;
                post(() -> {
                    if (actionMode != null) actionMode.invalidateContentRect();
                });
            }
        }
    }

    /**
     * Re-reads visible Kitty placements into {@link #gfx}, then refreshes the
     * bitmap cache: decode any image id that is new or whose source size
     * changed, and recycle cached bitmaps whose id is no longer on screen.
     */
    private void updateGraphics() {
        gfxCount = session.emulator.graphics(gfx);
        if (gfxCount * TerminalNative.GFX_STRIDE > gfx.length) {
            gfx = new int[gfxCount * TerminalNative.GFX_STRIDE];
            gfxCount = session.emulator.graphics(gfx);
        }
        if (gfxCount == 0) {
            clearImageCache();
            return;
        }
        for (int p = 0; p < gfxCount; p++) {
            int base = p * TerminalNative.GFX_STRIDE;
            int id = gfx[base + TerminalNative.GFX_IMAGE_ID];
            int iw = gfx[base + TerminalNative.GFX_IMAGE_W];
            int ih = gfx[base + TerminalNative.GFX_IMAGE_H];
            Bitmap bmp = imageCache.get(id);
            if (bmp == null || bmp.getWidth() != iw || bmp.getHeight() != ih) {
                if (bmp != null) bmp.recycle();
                bmp = fetchBitmap(id);
                if (bmp != null) imageCache.put(id, bmp);
                else imageCache.remove(id);
            }
        }
        for (Iterator<Map.Entry<Integer, Bitmap>> it =
                imageCache.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Bitmap> e = it.next();
            if (!placed(e.getKey())) {
                e.getValue().recycle();
                it.remove();
            }
        }
    }

    private boolean placed(int id) {
        for (int p = 0; p < gfxCount; p++) {
            if (gfx[p * TerminalNative.GFX_STRIDE + TerminalNative.GFX_IMAGE_ID] == id) {
                return true;
            }
        }
        return false;
    }

    private Bitmap fetchBitmap(int id) {
        byte[] rgba = session.emulator.imagePixels(id, imageWh);
        int w = imageWh[0], h = imageWh[1];
        if (rgba == null || w <= 0 || h <= 0 || rgba.length < w * h * 4) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
        return bmp;
    }

    /** Draws the cached placements for one z-band; see onDraw for ordering. */
    private void drawImages(Canvas canvas, boolean belowText) {
        for (int p = 0; p < gfxCount; p++) {
            int base = p * TerminalNative.GFX_STRIDE;
            if ((gfx[base + TerminalNative.GFX_Z] < 0) != belowText) continue;
            Bitmap bmp = imageCache.get(gfx[base + TerminalNative.GFX_IMAGE_ID]);
            if (bmp == null) continue;
            int pw = gfx[base + TerminalNative.GFX_PIXEL_W];
            int ph = gfx[base + TerminalNative.GFX_PIXEL_H];
            int sw = gfx[base + TerminalNative.GFX_SRC_W];
            int sh = gfx[base + TerminalNative.GFX_SRC_H];
            if (pw <= 0 || ph <= 0 || sw <= 0 || sh <= 0) continue;
            int sx = gfx[base + TerminalNative.GFX_SRC_X];
            int sy = gfx[base + TerminalNative.GFX_SRC_Y];
            imgSrc.set(sx, sy, sx + sw, sy + sh);
            // col/row go negative when an image is scrolled off the top/left;
            // the canvas is clipped to the view, so partial images clip for free.
            // The pixel offsets nudge within the start cell (aspect centering
            // for placeholders, sub-cell placement for direct images).
            float left = gfx[base + TerminalNative.GFX_COL] * cellWidth
                    + gfx[base + TerminalNative.GFX_OFF_X];
            float top = gfx[base + TerminalNative.GFX_ROW] * cellHeight
                    + gfx[base + TerminalNative.GFX_OFF_Y];
            imgDst.set(left, top, left + pw, top + ph);
            canvas.drawBitmap(bmp, imgSrc, imgDst, imagePaint);
        }
    }

    private void clearImageCache() {
        if (imageCache.isEmpty()) return;
        for (Bitmap b : imageCache.values()) b.recycle();
        imageCache.clear();
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
     * right, like TextView's — and records an enlarged touch target. The x
     * position is clamped to the view so a handle at column 0 or the last
     * column (e.g. a full-line selection) stays fully on-screen and grabbable.
     */
    private void placeHandle(Drawable d, RectF touchRect, boolean left,
            float anchorX, float anchorY) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        int x = (int) (anchorX - (left ? w * 3 / 4f : w / 4f));
        x = Math.max(0, Math.min(x, getWidth() - w));
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
        // This text bypasses the rich-input buffer (hardware key, toolbar, or a
        // modifier combo), so re-sync the mirror from empty afterwards.
        resetRichInput();
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
        // A special key may move the remote cursor or trigger completion, so
        // the mirrored input line no longer matches; drop it.
        resetRichInput();
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
        if (richInputActive) {
            // Composing mode: a real text field so the keyboard offers
            // suggestions, autocorrect and swipe typing. Enter maps to "Go".
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_ACTION_GO;
            RichInputConnection ic = new RichInputConnection();
            richEditable = ic.getEditable();
            richEditable.clear();
            Selection.setSelection(richEditable, 0);
            richSent = "";
            return ic;
        }
        // Plain terminal: TYPE_NULL makes the keyboard forward raw keys.
        richEditable = null;
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

    /**
     * Enables/disables rich (composing-mode) soft input and recreates the
     * input connection so an open keyboard switches modes immediately.
     */
    public void setRichKeyboard(boolean enabled) {
        if (richKeyboardEnabled == enabled) return;
        richKeyboardEnabled = enabled;
        richInputActive = enabled && session != null && !snapshot.rawKeyInput();
        resetRichInput();
        requestRestartInput(); // activating from the TYPE_NULL path needs a restart
    }

    /**
     * Recomputes whether composing mode applies to the current terminal state
     * and, on a change, resets the mirror and rebuilds the input connection.
     * Called every frame from {@link #onDraw}; acts only on transitions.
     */
    private void updateRichInputActive() {
        boolean active = richKeyboardEnabled && !snapshot.rawKeyInput();
        if (active == richInputActive) return;
        richInputActive = active;
        resetRichInput();
        requestRestartInput(); // toggling the connection type needs a restart
    }

    /**
     * Drops the mirrored input line and rebuilds the input connection so the
     * IME fully clears its composing/suggestion state — a plain updateSelection
     * leaves Gboard's word composer (and the suggestion strip) intact, so the
     * connection has to be restarted. No-op unless a composing connection is
     * live, so the plain TYPE_NULL path keeps forwarding keys untouched.
     */
    private void resetRichInput() {
        richSent = "";
        if (richEditable == null) return;
        richEditable.clear();
        Selection.setSelection(richEditable, 0);
        requestRestartInput();
    }

    private void restartInput() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.restartInput(this);
    }

    /**
     * Restarts the IME on the next frame, coalescing bursts (e.g. a backspace
     * run) into a single restart and avoiding reentrancy when called from
     * inside an InputConnection callback such as {@link #submitLine}.
     */
    private void requestRestartInput() {
        if (restartInputPending) return;
        restartInputPending = true;
        post(() -> {
            restartInputPending = false;
            restartInput();
        });
    }

    /**
     * Brings the remote line in step with the IME's local buffer by emitting
     * backspaces back to the longest common prefix, then the new tail. This
     * one operation covers plain typing, swipe (whole-word commit), and
     * autocorrect (word replacement) uniformly, assuming the remote cursor
     * sits at the end of the line — which holds at a normal shell prompt.
     */
    private void reconcileRich() {
        if (session == null || richEditable == null) return;
        String next = richEditable.toString();
        if (next.equals(richSent)) return;

        // A pending toolbar CTRL/ALT means the user wants a control combo, not
        // composed text: send the appended characters through the modifier path
        // and stop mirroring (resetRichInput already cleared the buffer above).
        if ((sticky.ctrl || sticky.alt) && next.length() > richSent.length()
                && next.startsWith(richSent)) {
            dispatchText(next.substring(richSent.length()));
            return;
        }

        int prefix = commonPrefixChars(richSent, next);
        int deletions = richSent.codePointCount(prefix, richSent.length());
        sendBackspaces(deletions);
        if (prefix < next.length()) {
            session.emulator.scrollToBottom();
            session.write(next.substring(prefix));
        }
        richSent = next;
        invalidate();
    }

    /** Sends the current line to the shell and starts a fresh mirror. */
    private void submitLine() {
        reconcileRich();
        if (session != null) session.sendKey(KeyEvent.KEYCODE_ENTER, 0, null, 0);
        resetRichInput();
        invalidate();
    }

    private void sendBackspaces(int count) {
        if (session == null || count <= 0) return;
        byte[] one = session.emulator.encodeKey(KeyEvent.KEYCODE_DEL, 0, null, 0);
        if (one == null || one.length == 0) one = new byte[] {0x7f};
        byte[] out = new byte[one.length * count];
        for (int i = 0; i < count; i++) {
            System.arraycopy(one, 0, out, i * one.length, one.length);
        }
        session.emulator.scrollToBottom();
        session.writeBytes(out);
    }

    /** Length (in chars) of the shared prefix, kept on a code-point boundary. */
    private static int commonPrefixChars(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        // Never split a surrogate pair: a trailing high surrogate whose low
        // half differs must not count as shared.
        if (i > 0 && Character.isHighSurrogate(a.charAt(i - 1))) i--;
        return i;
    }

    /**
     * Composing input connection used while {@link #richInputActive}. Each
     * mutator updates the local {@link Editable} (via super) then reconciles
     * the remote line; Enter and the editor action submit the line.
     */
    private final class RichInputConnection extends BaseInputConnection {
        RichInputConnection() {
            super(TerminalView.this, true);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String s = text.toString();
            if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
                super.commitText(text, newCursorPosition);
                reconcileRich();
                return true;
            }
            // Split on newlines: each completed segment submits its own line.
            int start = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c != '\n' && c != '\r') continue;
                if (i > start) super.commitText(s.substring(start, i), 1);
                submitLine();
                start = i + 1;
            }
            if (start < s.length()) {
                super.commitText(s.substring(start), 1);
                reconcileRich();
            }
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            super.setComposingText(text, newCursorPosition);
            reconcileRich();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (getEditable().length() == 0 && beforeLength > 0 && afterLength == 0) {
                // Nothing local to delete (e.g. after a reset): pass the
                // backspaces through so they still reach the remote line.
                sendBackspaces(beforeLength);
                return true;
            }
            super.deleteSurroundingText(beforeLength, afterLength);
            reconcileRich();
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            if (getEditable().length() == 0 && before > 0 && after == 0) {
                sendBackspaces(before);
                return true;
            }
            super.deleteSurroundingTextInCodePoints(before, after);
            reconcileRich();
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            submitLine();
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            int kc = event.getKeyCode();
            boolean enter = kc == KeyEvent.KEYCODE_ENTER
                    || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (enter) {
                    submitLine();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DEL) {
                    Editable e = getEditable();
                    int len = e.length();
                    if (len == 0) {
                        sendBackspaces(1);
                    } else {
                        int from = len - (len >= 2 && Character.isLowSurrogate(
                                e.charAt(len - 1)) ? 2 : 1);
                        e.delete(from, len);
                        reconcileRich();
                    }
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP
                    && (enter || kc == KeyEvent.KEYCODE_DEL)) {
                return true; // handled on ACTION_DOWN
            }
            return super.sendKeyEvent(event);
        }
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
            dispatchText(utf8); // resets the rich-input mirror itself
        } else {
            resetRichInput(); // this key bypasses the mirror
            session.sendKey(keyCode, mods, utf8, unshifted);
            invalidate();
        }
        return true;
    }
}
