/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
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
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.TerminalNative;
import io.github.sylirre.terminal.term.TerminalSession;

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
        // When locked the modifier survives consume() — stays active across keys.
        public boolean ctrlLocked;
        public boolean altLocked;
        public Runnable onChanged;

        int consume() {
            int mods = (ctrl ? TerminalNative.MOD_CTRL : 0)
                    | (alt ? TerminalNative.MOD_ALT : 0);
            if (mods != 0) {
                boolean changed = (!ctrlLocked && ctrl) || (!altLocked && alt);
                if (!ctrlLocked) ctrl = false;
                if (!altLocked) alt = false;
                if (changed && onChanged != null) onChanged.run();
            }
            return mods;
        }
    }

    private TerminalSession session;
    private final ScreenSnapshot snapshot = new ScreenSnapshot();
    // Holds the single row just above the viewport while smooth-scrolling, so
    // the renderer can draw the partial row exposed at the top by a sub-row
    // pixel offset. Only filled (via snapshotSmooth) when pixelScrollOffset > 0.
    private final ScreenSnapshot aboveSnapshot = new ScreenSnapshot();
    private StickyModifiers sticky = new StickyModifiers();

    // Cursor blink. The VT engine only reports whether the cursor *should*
    // blink (snapshot.cursorBlinking(), driven by the user's setting and any
    // program DECSCUSR); the renderer does the on/off animation. The phase is
    // reset to "on" whenever the cursor moves, so it stays solid while typing
    // and scrolling and only blinks when idle. See updateCursorBlink/drawCursor.
    private static final long CURSOR_BLINK_MS = 530;
    private boolean cursorBlinkOn = true;
    private boolean cursorBlinkRunning;
    private int lastCursorX = -1, lastCursorY = -1;
    private final Runnable cursorBlinkTick = new Runnable() {
        @Override
        public void run() {
            cursorBlinkOn = !cursorBlinkOn;
            invalidate();
            postDelayed(this, CURSOR_BLINK_MS);
        }
    };

    // Text blink (SGR 5). Runs only while at least one cell in the current
    // snapshot carries ATTR_BLINK; stops automatically when none do.
    private static final long TEXT_BLINK_MS = 600;
    private boolean textBlinkOn = true;
    private boolean textBlinkRunning;
    private final Runnable textBlinkTick = new Runnable() {
        @Override
        public void run() {
            textBlinkOn = !textBlinkOn;
            invalidate();
            postDelayed(this, TEXT_BLINK_MS);
        }
    };

    // Visual bell: a short white overlay over the already-rendered terminal.
    private static final long BELL_FLASH_MS = 120;
    private final Paint bellFlashPaint = new Paint();
    private long bellFlashUntil;

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
    private boolean touchKeyboardEnabled = true;
    private Editable richEditable;   // the active composing connection's buffer
    private String richSent = "";    // text already forwarded for this line
    private boolean restartInputPending; // a debounced restartInput is queued

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    // Stylized underlines (SGR 4:2..4:5) are stroked by hand — Paint only does a
    // single solid line. The dash effects depend only on the cell metrics, so
    // they are rebuilt in setTextSizePx and the Path is reused per run.
    private final Paint underlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path underlinePath = new Path();
    private DashPathEffect dottedEffect;
    private DashPathEffect dashedEffect;
    private float underlineThickness;
    private float cellWidth;
    private int cellHeight;
    private int baseline;
    private int cols = 80, rows = 24;
    private int textMarginLeft;
    private int textMarginRight;

    // --- Grid-size HUD. A transient COLSxROWS chip drawn in onDraw while
    // pinch-zooming and held briefly after. Drawn in-view (rather than as a
    // Toast) so it reads cols/rows live every frame — a reused Toast on
    // targetSdk >= 30 freezes its text at the first show() of a rapid burst,
    // showing a stale size for the whole gesture. uptimeMillis of the last
    // announce, 0 when idle; the overlay holds opaque then fades out.
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF overlayRect = new RectF();
    private float overlayPadding, overlayRadius;
    private long sizeOverlayShownAt;
    private static final long SIZE_OVERLAY_HOLD_MS = 750;
    private static final long SIZE_OVERLAY_FADE_MS = 350;

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

    // --- Background wallpaper. A global, theme-independent image drawn over the
    // default background and beneath everything else (per-cell colors, text,
    // cursor). Owned here and recycled on detach; the alpha is the user's
    // opacity slider, so the solid theme bg shows through for legibility. ---
    private final Paint bgImagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap backgroundImage;
    private int backgroundImageAlpha = 255;
    private final Rect bgImgSrc = new Rect();
    private final Rect bgImgDst = new Rect();

    private static final float MIN_FONT_SP = 8f;
    private static final float MAX_FONT_SP = 40f;
    private static final float DEFAULT_FONT_SP = 14f;
    private static final String PREFS = "terminal";
    private static final String PREF_FONT_SP = "font_size_sp";

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private float scrollRemainder;
    private float fontSizeSp;
    private Typeface regularTypeface = Typeface.MONOSPACE;
    private Typeface boldTypeface;
    private Typeface italicTypeface;
    private Typeface boldItalicTypeface;

    // --- Smooth (sub-row) scrolling. When enabled, scroll/fling motion is
    // tracked in pixels: whole rows still go through emulator.scrollBy(), and
    // pixelScrollOffset carries the leftover [0, cellHeight) that the renderer
    // applies as a canvas translation (drawing one extra row of history above
    // the viewport to fill the gap it opens). Off → the integer scrollRemainder
    // path above is used unchanged. Only active on the main screen (the alt
    // screen has no scrollback). See onDraw and scrollByPixels.
    private boolean smoothScroll = true;
    private float pixelScrollOffset;

    // --- Mouse reporting. When the user has enabled it (the master switch
    // below) AND the running program turned on a mouse tracking mode
    // (snapshot.mouseTracking()), touch gestures are encoded as mouse events
    // and sent to the PTY instead of driving the local scrollback: a tap is a
    // left-button click, a swipe is wheel scroll. Each gesture locks to its
    // dominant axis (mouseAxis: 0 none, 1 vertical, 2 horizontal) so a slightly
    // diagonal drag doesn't emit both vertical and horizontal wheel reports;
    // mouseWheelRemainder carries the sub-cell leftover along that axis.
    private boolean mouseTrackingEnabled = true;
    private int mouseAxis;
    private float mouseWheelRemainder;
    // A long-press with mouse reporting active holds the left button down and
    // turns subsequent movement into drag (motion-with-button) reports, ending
    // on finger-up. mouseDragCellX/Y is the last cell a motion was reported for,
    // so a drag within one cell isn't re-reported (the protocol is cell-grained).
    private boolean mouseDragging;
    private int mouseDragCellX = -1, mouseDragCellY = -1;

    // --- OSC 8 hyperlinks. When enabled, cells carrying a hyperlink are drawn
    // underlined (the only tap affordance available without a hover) and a
    // single tap on one previews the URI in a dialog before opening it. Off
    // suppresses both the underline and the tap handling. Pushed in from the
    // AppSettings toggle by MainActivity.
    private boolean tapToOpenLinks = true;

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

    /** Notified (on the main thread) when the viewport enters or leaves the live bottom. */
    public interface ScrollStateListener {
        void onScrollStateChanged(boolean atBottom);
    }

    private ScrollStateListener scrollStateListener;
    private boolean lastAtBottom = true;

    public void setScrollStateListener(ScrollStateListener l) {
        scrollStateListener = l;
    }

    /** True when the viewport is at the live bottom (not scrolled into history). */
    public boolean isAtBottom() {
        return lastAtBottom;
    }

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

    // --- Multi-tap selection. A single tap raises the keyboard (or opens a
    // link); a double tap selects the word and a triple tap the line under the
    // finger. GestureDetector's own double-tap detection is disabled (see the
    // constructor) so onSingleTapUp fires for every tap and we count them here:
    // consecutive taps within the platform double-tap window and slop advance
    // the count, and the count drives the action immediately — no deferral, so
    // the single-tap keyboard stays instant. Long-press keeps its own
    // select-and-drag path; mouse-reporting taps bypass counting entirely.
    private int tapCount;
    private long lastTapTime;
    private float lastTapX, lastTapY;
    private final int tapTimeoutMs = ViewConfiguration.getDoubleTapTimeout();
    private final int tapSlopPx;

    // --- Text search. The emulator owns the search state (query, matches,
    // current index) and reuses the selection slot to highlight/reveal the
    // current match; this view just relays the query and navigation and reports
    // the count back to the search UI. The match highlight deliberately does not
    // enter `selecting`, so no handles or Copy toolbar appear — it reads as a
    // plain search hit.
    private final int[] searchOut = new int[2]; // [0] current (1-based), [1] count
    private SearchListener searchListener;

    /** Reports the current match position (1-based, 0 if none) and total. */
    public interface SearchListener {
        void onSearchUpdated(int current, int total);
    }

    private static final int MENU_COPY = 1;
    private static final int MENU_PASTE = 2;
    private static final int MENU_SELECT_ALL = 3;

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);

        textPaint.setTypeface(regularTypeface);
        fontSizeSp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(PREF_FONT_SP, DEFAULT_FONT_SP);
        setTextSizePx(spToPx(fontSizeSp));

        overlayTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        overlayTextPaint.setTextAlign(Paint.Align.CENTER);
        overlayTextPaint.setTextSize(spToPx(16f));
        overlayPadding = spToPx(12f);
        overlayRadius = spToPx(8f);

        tapSlopPx = ViewConfiguration.get(context).getScaledDoubleTapSlop();

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
                // A program tracking the mouse gets the tap as a left click,
                // and neither raises the keyboard nor drives selection — so it
                // stays outside the tap counter (a "double click" there is just
                // two clicks, which is what such programs expect).
                if (mouseReporting()) {
                    tapCount = 0;
                    requestFocus();
                    sendMouseClick(e.getX(), e.getY());
                    return true;
                }
                handleTap(e);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // With mouse reporting active, a long-press grabs the left
                // button so the following drag is reported to the program
                // (text/region select, slider, divider) instead of starting a
                // local copy selection.
                if (mouseReporting()) {
                    startMouseDrag(e.getX(), e.getY());
                    return;
                }
                startSelection(e.getX(), e.getY());
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (session == null) return true;
                // Mouse path takes precedence over both scrollback and the
                // alt-screen arrow-key translation: encode the swipe as wheel
                // scroll along the gesture's dominant axis.
                if (mouseReporting()) {
                    handleMouseWheel(e2, dx, dy);
                    return true;
                }
                // Smooth path: track pixels on the main screen (not while
                // selecting, where the swipe scrolls the selection into view in
                // whole rows). dy > 0 is a finger-up swipe revealing lower
                // content, matching scrollBy(+) / scrollByPixels(+).
                if (smoothScroll && !selecting && !snapshot.altScreen()) {
                    scrollByPixels(dy);
                    invalidate();
                    return true;
                }
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
                // Mouse reporting already emitted discrete wheel reports during
                // the drag; don't coast the local viewport on top of that.
                if (mouseReporting()) return false;
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
                mouseAxis = 0; // re-decide the locked axis for this gesture
                mouseWheelRemainder = 0;
                return true;
            }
        });
        // Disable GestureDetector's own double-tap detection: with it on, the
        // second tap's up is routed to onDoubleTapEvent instead of
        // onSingleTapUp, hiding it from our tap counter. Off, onSingleTapUp
        // fires for every tap and handleTap() counts double/triple itself.
        gestures.setOnDoubleTapListener(null);
    }

    /** Flashes the terminal surface once for BEL when visual bell mode is enabled. */
    public void flashBell() {
        bellFlashUntil = SystemClock.uptimeMillis() + BELL_FLASH_MS;
        invalidate();
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
            // Smooth path: feed the per-frame pixel delta straight through;
            // scrollByPixels reports when the viewport pins against the edge
            // we're heading for, which ends the coast (it never reaches the
            // alt screen — onFling refuses to start there).
            if (smoothScroll && !snapshot.altScreen()) {
                float deltaPx = y - lastFlingY;
                lastFlingY = y;
                if (deltaPx != 0f) {
                    boolean pinned = scrollByPixels(deltaPx);
                    invalidate();
                    if (pinned) {
                        scroller.forceFinished(true);
                        return;
                    }
                }
                postOnAnimation(this);
                return;
            }
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

    /**
     * Smooth-scroll core: advances the viewport by {@code deltaPx} pixels
     * (positive reveals lower content, matching {@link TerminalEmulator#scrollBy}).
     * Whole rows crossed are applied via scrollBy; the leftover sub-row amount is
     * kept in {@link #pixelScrollOffset} ∈ [0, cellHeight) for the renderer to
     * translate by. Clamps the offset to 0 at the history edges (no partial row
     * exists past the top, and the live bottom can't be passed). Returns true
     * when the viewport is pinned against the edge it was moving toward.
     */
    private boolean scrollByPixels(float deltaPx) {
        // pixelScrollOffset measures how far the viewport is scrolled up from a
        // row boundary, so moving toward present (deltaPx > 0) decreases it.
        pixelScrollOffset -= deltaPx;
        int lines = 0;
        while (pixelScrollOffset < 0) {
            pixelScrollOffset += cellHeight;
            lines += 1; // crossed a boundary toward present
        }
        while (pixelScrollOffset >= cellHeight) {
            pixelScrollOffset -= cellHeight;
            lines -= 1; // crossed a boundary into history
        }
        session.emulator.scrollbar(scrollState);
        int before = scrollState[1];
        if (lines != 0) session.emulator.scrollBy(lines);
        session.emulator.scrollbar(scrollState);
        int after = scrollState[1];
        boolean atTop = after <= 0;
        boolean atBottom = after + scrollState[2] >= scrollState[0];
        if (atTop) {
            pixelScrollOffset = 0; // no row above the top to expose
        } else if (atBottom && deltaPx > 0 && after == before) {
            // Pushed toward present but already pinned to the live bottom: the
            // borrowed boundary can't be honored, so settle on the boundary.
            pixelScrollOffset = 0;
        }
        if (scrollState[0] > scrollState[2]) awakenScrollBars();
        return (deltaPx > 0 && atBottom) || (deltaPx < 0 && atTop);
    }

    /**
     * Enables or disables smooth (pixel-level) scrolling of the scrollback.
     * When turned off, any in-progress sub-row offset is cleared so the grid
     * snaps back to whole-row alignment.
     */
    public void setSmoothScroll(boolean enabled) {
        if (smoothScroll == enabled) return;
        smoothScroll = enabled;
        if (!enabled && pixelScrollOffset != 0) {
            pixelScrollOffset = 0;
            invalidate();
        }
    }

    /**
     * Master switch for mouse reporting. When on, touch gestures are forwarded
     * to programs that have enabled a mouse tracking mode (taps as left clicks,
     * swipes as wheel scroll); when off, gestures always drive the local
     * scrollback / raise the keyboard regardless of what the program requested.
     */
    public void setMouseTracking(boolean enabled) {
        mouseTrackingEnabled = enabled;
    }

    /**
     * Master switch for OSC 8 hyperlinks. When on, hyperlink cells are drawn
     * underlined and a tap on one previews and opens its URI; when off, links
     * render like ordinary text and taps just raise the keyboard. Repaints so
     * the underline affordance appears/disappears immediately.
     */
    public void setTapToOpenLinks(boolean enabled) {
        if (tapToOpenLinks == enabled) return;
        tapToOpenLinks = enabled;
        invalidate();
    }

    /** Scrolls to the previous shell prompt (OSC 133), if any, above the viewport. */
    public void jumpToPrevPrompt() {
        promptNav(-1);
    }

    /** Scrolls to the next shell prompt (OSC 133), if any, below the viewport. */
    public void jumpToNextPrompt() {
        promptNav(1);
    }

    private void promptNav(int dir) {
        if (session == null) return;
        if (session.emulator.promptNav(dir)) {
            pixelScrollOffset = 0; // land on a whole-row boundary
            invalidate();
        }
    }

    /**
     * Fires the scroll-state listener when the viewport crosses between the live
     * bottom and scrolled-into-history. {@code scrollState} is {total, offset,
     * len}; at the bottom the offset plus the viewport length reaches the total.
     * Posted so a listener can safely touch layout from within a draw pass.
     */
    private void notifyScrollStateIfChanged() {
        boolean atBottom = scrollState[1] + scrollState[2] >= scrollState[0];
        if (atBottom == lastAtBottom) return;
        lastAtBottom = atBottom;
        ScrollStateListener l = scrollStateListener;
        if (l != null) post(() -> {
            if (scrollStateListener != null) scrollStateListener.onScrollStateChanged(atBottom);
        });
    }

    /**
     * True when gestures should be reported to the program as mouse events:
     * the user enabled mouse reporting and the program turned on a tracking
     * mode. Reads the last snapshot's mode flags (refreshed every frame), so it
     * needs no JNI round-trip per gesture.
     */
    private boolean mouseReporting() {
        return mouseTrackingEnabled && session != null && snapshot.mouseTracking();
    }

    /**
     * Encodes a swipe as wheel scroll along the gesture's dominant axis (locked
     * on the first move of the gesture). Whole cells crossed emit one wheel
     * report each — vertical buttons 4/5, horizontal buttons 6/7 — matching the
     * sign convention of the scrollback path: {@code dy > 0} is a finger-up
     * swipe that reveals lower content (wheel down), and {@code dx > 0} a
     * finger-left swipe that reveals content to the right (wheel right).
     */
    private void handleMouseWheel(MotionEvent e2, float dx, float dy) {
        if (mouseAxis == 0) {
            mouseAxis = Math.abs(dy) >= Math.abs(dx) ? 1 : 2;
            mouseWheelRemainder = 0;
        }
        float x = e2.getX() - textMarginLeft, y = e2.getY();
        if (mouseAxis == 1) {
            mouseWheelRemainder += dy / cellHeight;
            int steps = (int) mouseWheelRemainder;
            if (steps == 0) return;
            mouseWheelRemainder -= steps;
            int button = steps > 0 ? TerminalNative.MOUSE_WHEEL_DOWN
                                   : TerminalNative.MOUSE_WHEEL_UP;
            emitWheel(button, Math.abs(steps), x, y);
        } else {
            mouseWheelRemainder += dx / cellWidth;
            int steps = (int) mouseWheelRemainder;
            if (steps == 0) return;
            mouseWheelRemainder -= steps;
            int button = steps > 0 ? TerminalNative.MOUSE_WHEEL_RIGHT
                                   : TerminalNative.MOUSE_WHEEL_LEFT;
            emitWheel(button, Math.abs(steps), x, y);
        }
    }

    /** Sends {@code count} wheel-button presses (encoded once, written N times). */
    private void emitWheel(int button, int count, float x, float y) {
        byte[] one = session.emulator.encodeMouse(
                TerminalNative.MOUSE_PRESS, button, x, y, false);
        if (one == null) return;
        for (int i = 0; i < count; i++) session.writeBytes(one);
    }

    /** Reports a left-button click (press then release) at the given pixel. */
    private void sendMouseClick(float px, float py) {
        float x = px - textMarginLeft, y = py;
        byte[] press = session.emulator.encodeMouse(
                TerminalNative.MOUSE_PRESS, TerminalNative.MOUSE_BUTTON_LEFT, x, y, false);
        byte[] release = session.emulator.encodeMouse(
                TerminalNative.MOUSE_RELEASE, TerminalNative.MOUSE_BUTTON_LEFT, x, y, false);
        if (press != null) session.writeBytes(press);
        if (release != null) session.writeBytes(release);
    }

    /**
     * Begins a left-button drag at the long-press point: presses the button
     * (held) and records the start cell. {@link #mouseDragTouch} then carries
     * the motion and release. A short haptic marks the grab, mirroring the
     * feedback the local selection gives.
     */
    private void startMouseDrag(float px, float py) {
        float x = px - textMarginLeft, y = py;
        mouseDragging = true;
        mouseDragCellX = (int) (x / cellWidth);
        mouseDragCellY = (int) (y / cellHeight);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        byte[] press = session.emulator.encodeMouse(
                TerminalNative.MOUSE_PRESS, TerminalNative.MOUSE_BUTTON_LEFT, x, y, true);
        if (press != null) session.writeBytes(press);
    }

    /**
     * Carries an in-progress long-press drag: moves emit a motion report (with
     * the left button held) once per new cell entered, and the lift emits the
     * release. Consumes the whole stream so it bypasses the gesture detectors.
     */
    private boolean mouseDragTouch(MotionEvent event) {
        if (session == null) {
            mouseDragging = false;
            return true;
        }
        float x = event.getX() - textMarginLeft, y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                int cx = (int) (x / cellWidth), cy = (int) (y / cellHeight);
                if (cx == mouseDragCellX && cy == mouseDragCellY) return true;
                mouseDragCellX = cx;
                mouseDragCellY = cy;
                byte[] motion = session.emulator.encodeMouse(
                        TerminalNative.MOUSE_MOTION, TerminalNative.MOUSE_BUTTON_LEFT,
                        x, y, true);
                if (motion != null) session.writeBytes(motion);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                byte[] release = session.emulator.encodeMouse(
                        TerminalNative.MOUSE_RELEASE, TerminalNative.MOUSE_BUTTON_LEFT,
                        x, y, false);
                if (release != null) session.writeBytes(release);
                mouseDragging = false;
                mouseDragCellX = mouseDragCellY = -1;
                return true;
            }
            default:
                return true;
        }
    }

    private void setTextSizePx(float px) {
        textPaint.setTypeface(regularTypeface);
        textPaint.setTextSize(px);
        Paint.FontMetricsInt fm = textPaint.getFontMetricsInt();
        cellWidth = textPaint.measureText("M");
        cellHeight = fm.descent - fm.ascent;
        baseline = -fm.ascent;

        underlineThickness = Math.max(1f, cellHeight / 18f);
        underlinePaint.setStyle(Paint.Style.STROKE);
        underlinePaint.setStrokeWidth(underlineThickness);
        // Short on/off runs read as dots; cell-scaled ones as dashes.
        dottedEffect = new DashPathEffect(
                new float[] {underlineThickness, underlineThickness * 2f}, 0f);
        float dash = Math.max(3f, cellWidth * 0.5f);
        dashedEffect = new DashPathEffect(new float[] {dash, dash * 0.5f}, 0f);
    }

    /**
     * Sets terminal font faces. Missing style-specific faces are synthesized
     * from the closest configured face.
     */
    public void setTerminalFonts(Typeface regular, Typeface bold, Typeface italic,
            Typeface boldItalic) {
        regularTypeface = regular != null ? regular : Typeface.MONOSPACE;
        boldTypeface = bold;
        italicTypeface = italic;
        boldItalicTypeface = boldItalic;
        textPaint.setTypeface(regularTypeface);
        setTextSizePx(spToPx(fontSizeSp));
        if (getWidth() > 0) {
            updateGridSize(getWidth(), getHeight());
        }
        invalidate();
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
            // Announce the new grid only for zoom-driven resizes; layout
            // changes (keyboard show/hide) go through onSizeChanged silently.
            if (session != null) showSizeOverlay();
        }
        invalidate();
    }

    private void persistFontSize() {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(PREF_FONT_SP, fontSizeSp).apply();
    }

    /**
     * Sets left and right text margins in pixels. The grid is narrowed by the
     * combined margin so the terminal never renders text into the margin area.
     * Useful on devices where a few pixels of the screen edge are hidden by the
     * case.
     */
    public void setTextMargins(int leftPx, int rightPx) {
        textMarginLeft = leftPx;
        textMarginRight = rightPx;
        if (getWidth() > 0) {
            updateGridSize(getWidth(), getHeight());
        }
        invalidate();
    }

    public void setStickyModifiers(StickyModifiers mods) {
        sticky = mods;
    }

    /**
     * Sets (or clears, with a null bitmap) the terminal background wallpaper.
     * {@code alpha} is the draw opacity (0–255) over the solid theme
     * background. Takes ownership of {@code bmp}: the previously held bitmap is
     * recycled here, so callers must not reuse it afterward.
     */
    public void setBackgroundImage(Bitmap bmp, int alpha) {
        if (bmp == backgroundImage) {
            backgroundImageAlpha = alpha;
            invalidate();
            return;
        }
        if (backgroundImage != null) backgroundImage.recycle();
        backgroundImage = bmp;
        backgroundImageAlpha = alpha;
        invalidate();
    }

    /** Binds a session; pass null to detach. Resizes it to fit this view. */
    public void attachSession(TerminalSession s) {
        if (s != session) {
            finishSelection(); // also clears the old session's selection
            searchClose(); // the match list belonged to the old session
            clearImageCache(); // image ids belong to the old terminal
            gfxCount = 0;
            resetRichInput(); // the mirror belonged to the old session's line
            scroller.forceFinished(true); // don't coast into the new session
            pixelScrollOffset = 0; // the old session's sub-row offset is meaningless
            mouseDragging = false; // any in-flight drag belonged to the old session
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
        removeCallbacks(cursorBlinkTick);
        cursorBlinkRunning = false;
        removeCallbacks(textBlinkTick);
        textBlinkRunning = false;
        clearImageCache(); // release decoded bitmaps; rebuilt on next draw
        if (backgroundImage != null) {
            backgroundImage.recycle();
            backgroundImage = null; // MainActivity re-pushes it on resume
        }
    }

    private void updateGridSize(int w, int h) {
        cols = Math.max(4, (int) ((w - textMarginLeft - textMarginRight) / cellWidth));
        rows = Math.max(2, h / cellHeight);
        // A new cell height invalidates the sub-row offset (it is measured in
        // the old cell pixels); snap back to a row boundary.
        pixelScrollOffset = 0;
        if (session != null) {
            session.resize(cols, rows, (int) cellWidth, cellHeight);
        }
    }

    /** (Re)arms the in-view grid-size HUD; onDraw renders and fades it. */
    private void showSizeOverlay() {
        sizeOverlayShownAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /**
     * Draws the transient COLSxROWS HUD over everything else. Reads cols/rows
     * live, so the number always matches the current grid. Held opaque for
     * {@link #SIZE_OVERLAY_HOLD_MS} after the last announce, then faded over
     * {@link #SIZE_OVERLAY_FADE_MS}, self-scheduling frames during the fade.
     */
    private void drawSizeOverlay(Canvas canvas) {
        if (sizeOverlayShownAt == 0) return;
        long elapsed = SystemClock.uptimeMillis() - sizeOverlayShownAt;
        if (elapsed >= SIZE_OVERLAY_HOLD_MS + SIZE_OVERLAY_FADE_MS) {
            sizeOverlayShownAt = 0;
            return;
        }
        float fade = elapsed <= SIZE_OVERLAY_HOLD_MS ? 1f
                : 1f - (elapsed - SIZE_OVERLAY_HOLD_MS) / (float) SIZE_OVERLAY_FADE_MS;

        String text = getResources().getString(R.string.grid_size_overlay, cols, rows);
        // Inverse chip: fill with the default fg, paint text in the default bg.
        // Those two are guaranteed to contrast, so the HUD stays legible on
        // any theme and against any cell content underneath it.
        int chip = snapshot.defaultFg();
        int ink = snapshot.defaultBg();

        float tw = overlayTextPaint.measureText(text);
        Paint.FontMetrics fm = overlayTextPaint.getFontMetrics();
        float boxW = tw + overlayPadding * 2;
        float boxH = (fm.descent - fm.ascent) + overlayPadding * 2;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        overlayRect.set(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2);

        overlayBgPaint.setColor(chip);
        overlayBgPaint.setAlpha((int) (0xE6 * fade));
        canvas.drawRoundRect(overlayRect, overlayRadius, overlayRadius, overlayBgPaint);

        overlayTextPaint.setColor(ink);
        overlayTextPaint.setAlpha((int) (0xFF * fade));
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, overlayTextPaint);

        postInvalidateOnAnimation();
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
        // A long-press-initiated mouse drag owns the rest of the gesture: its
        // moves become motion reports and never reach the detectors, so no
        // wheel/scroll fires underneath it.
        if (mouseDragging && mouseDragTouch(event)) return true;
        scaleGestures.onTouchEvent(event);
        // Suppress scrolling (and taps) while a pinch is in progress so the
        // viewport doesn't jump around during zoom.
        if (!scaleGestures.isInProgress()) {
            gestures.onTouchEvent(event);
        }
        return true;
    }

    // --- Selection ---

    /**
     * Routes a (non-mouse) tap by its position in a rapid tap run: 1 = raise
     * the keyboard or open a link, 2 = select the word, 3+ = select the line.
     * Consecutive taps within the platform double-tap window and slop advance
     * the run; anything else restarts it at one. Acts on each tap as it lands
     * (no deferral), so the common single tap keeps its instant response.
     */
    private void handleTap(MotionEvent e) {
        long now = e.getEventTime();
        boolean continues = now - lastTapTime <= tapTimeoutMs
                && Math.abs(e.getX() - lastTapX) <= tapSlopPx
                && Math.abs(e.getY() - lastTapY) <= tapSlopPx;
        tapCount = continues ? tapCount + 1 : 1;
        lastTapTime = now;
        lastTapX = e.getX();
        lastTapY = e.getY();

        if (tapCount == 1) {
            if (selecting) {
                // A lone tap dismisses the selection; don't let it also seed a
                // double-tap run (a following quick tap should be a fresh tap).
                finishSelection();
                tapCount = 0;
                return;
            }
            requestFocus();
            // A tap that lands on an OSC 8 hyperlink opens it (with a preview)
            // instead of raising the keyboard.
            if (tapToOpenLinks && openLinkAt(e.getX(), e.getY())) return;
            if (touchKeyboardEnabled) {
                InputMethodManager imm =
                        getContext().getSystemService(InputMethodManager.class);
                imm.showSoftInput(TerminalView.this, 0);
            }
        } else if (tapCount == 2) {
            selectWordAt(e.getX(), e.getY());
        } else { // 3 or more
            selectLineAt(e.getX(), e.getY());
        }
    }

    private void startSelection(float px, float py) {
        if (session == null) return;
        if (!selectWordAt(px, py)) return;
        // Keep dragging from the long-press to extend: pin the start so the
        // drag moves the end (crossing back over the start flips naturally).
        session.emulator.selectionAnchor(1);
        longPressDragging = true;
    }

    /** Selects the word at viewport pixel (px, py); false if it didn't resolve. */
    private boolean selectWordAt(float px, float py) {
        if (session == null) return false;
        // Snap any smooth-scroll sub-row offset away so the grid is row-aligned
        // for the whole selection: hit-testing here and the handle/drag math all
        // map screen pixels through cellHeight assuming no sub-row translation.
        pixelScrollOffset = 0;
        int cx = clampToGrid((px - textMarginLeft) / cellWidth, cols);
        int cy = clampToGrid(py / (float) cellHeight, rows);
        if (!session.emulator.selectWord(cx, cy)) return false;
        showSelectionUi();
        return true;
    }

    /** Selects the whole line at viewport pixel (px, py); false if it didn't resolve. */
    private boolean selectLineAt(float px, float py) {
        if (session == null) return false;
        pixelScrollOffset = 0;
        int cx = clampToGrid((px - textMarginLeft) / cellWidth, cols);
        int cy = clampToGrid(py / (float) cellHeight, rows);
        if (!session.emulator.selectLine(cx, cy)) return false;
        showSelectionUi();
        return true;
    }

    /** Selects all content (scrollback + screen) and shows the toolbar. */
    private void selectAll() {
        if (session == null || !session.emulator.selectAll()) return;
        showSelectionUi();
    }

    /**
     * Enters (or refreshes) selection mode around whatever selection the
     * emulator now holds: haptic confirm, refresh the mirror so the toolbar's
     * first (synchronous) onGetContentRect sees the new selection, and show or
     * reposition the floating toolbar. Shared by the double-tap (word),
     * triple-tap (line), long-press, and Select-all paths. Any in-progress
     * handle/long-press drag from a prior selection is cleared — these entry
     * points install a brand-new selection.
     */
    private void showSelectionUi() {
        selecting = true;
        longPressDragging = false;
        draggingHandle = -1;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        session.emulator.snapshot(snapshot);
        toolbarSelGeom = selectionGeometryKey();
        if (actionMode == null) {
            actionMode = startActionMode(selectionActions, ActionMode.TYPE_FLOATING);
        } else {
            // A selection kind changed under a live toolbar (word→line, or
            // Select all): reposition it over the new range right away.
            reshowToolbar();
        }
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
                    dragOffsetX = textMarginLeft + (hx + 0.5f) * cellWidth - event.getX();
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
                clampToGrid((px - textMarginLeft) / cellWidth, cols),
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

    /**
     * If viewport pixel (px, py) lands on an OSC 8 hyperlink cell, previews its
     * URI and returns true; false when the cell carries no link (so the caller
     * falls back to raising the keyboard). Mirrors startSelection's pixel→cell
     * math but accounts for — rather than clears — the smooth-scroll sub-row
     * offset, so tapping doesn't jolt the viewport.
     */
    private boolean openLinkAt(float px, float py) {
        if (session == null || cellWidth <= 0 || cellHeight <= 0) return false;
        int col = clampToGrid((px - textMarginLeft) / cellWidth, cols);
        int row = clampToGrid((py - pixelScrollOffset) / cellHeight, rows);
        String url = session.emulator.hyperlinkAt(col, row);
        if (url == null) return false;
        url = url.trim();
        if (url.isEmpty()) return false;
        showLinkDialog(url);
        return true;
    }

    /**
     * Previews a hyperlink URI and offers to open it in the system handler or
     * copy it. Opening is deliberately a second, explicit tap: OSC 8 lets a
     * program's visible link text differ from the real target, so the user
     * sees the actual destination before anything launches.
     */
    private void showLinkDialog(final String url) {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.link_dialog_title)
                .setMessage(url)
                .setPositiveButton(R.string.link_open, (d, w) -> openUrl(url))
                .setNeutralButton(android.R.string.copy, (d, w) -> {
                    ClipboardManager cm =
                            getContext().getSystemService(ClipboardManager.class);
                    if (cm == null) return;
                    cm.setPrimaryClip(ClipData.newPlainText("url", url));
                    Toast.makeText(getContext(), R.string.link_copied,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(getContext(), R.string.link_open_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Snaps the viewport to the live bottom and clears any smooth-scroll
     * sub-row offset. Used by the write paths (typing, paste, line editing)
     * so output always lands row-aligned at the present.
     */
    private void jumpToPresent() {
        if (session != null) session.emulator.scrollToBottom();
        pixelScrollOffset = 0;
    }

    private void pasteClipboard() {
        String text = clipboardText();
        if (text == null || session == null) return;
        byte[] encoded = session.emulator.encodePaste(text);
        if (encoded == null) return;
        jumpToPresent();
        session.writeBytes(encoded);
        invalidate();
    }

    // --- Search ---

    public void setSearchListener(SearchListener l) {
        searchListener = l;
    }

    /**
     * Runs a fresh query: scans the buffer, jumps to and highlights the match
     * nearest the current viewport, and reports the count. An empty query
     * clears the highlight.
     */
    public void searchSetQuery(String query, boolean caseSensitive) {
        if (session == null) {
            notifySearch(0, 0);
            return;
        }
        int total = session.emulator.searchSet(
                query == null ? "" : query, caseSensitive, searchOut);
        invalidate();
        notifySearch(searchOut[0], total);
    }

    /** Moves to the next match (wraps), revealing and highlighting it. */
    public void searchNext() {
        searchStep(1);
    }

    /** Moves to the previous match (wraps), revealing and highlighting it. */
    public void searchPrev() {
        searchStep(-1);
    }

    private void searchStep(int dir) {
        if (session == null) {
            notifySearch(0, 0);
            return;
        }
        int total = session.emulator.searchStep(dir, searchOut);
        invalidate();
        notifySearch(searchOut[0], total);
    }

    /** Ends search mode and clears the match highlight. */
    public void searchClose() {
        if (session != null) session.emulator.searchClear();
        invalidate();
    }

    private void notifySearch(int current, int total) {
        if (searchListener != null) searchListener.onSearchUpdated(current, total);
    }

    private final ActionMode.Callback2 selectionActions = new ActionMode.Callback2() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy);
            menu.add(Menu.NONE, MENU_SELECT_ALL, 1, android.R.string.selectAll);
            if (clipboardHasText()) {
                menu.add(Menu.NONE, MENU_PASTE, 2, android.R.string.paste);
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
            } else if (item.getItemId() == MENU_SELECT_ALL) {
                // Grow the selection to the whole buffer and keep the toolbar
                // up so Copy is one more tap away.
                selectAll();
                return true;
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
                left = textMarginLeft + (int) (snapshot.selectionStartX() * cellWidth);
                right = textMarginLeft + (int) ((snapshot.selectionEndX() + 1) * cellWidth);
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
        // While a sub-row offset is pending, snapshotSmooth grabs the viewport
        // and the single row above it (atomically) so the partial top row the
        // offset exposes can be drawn; otherwise a plain viewport snapshot.
        boolean haveAbove = false;
        if (session != null && smoothScroll && pixelScrollOffset > 0) {
            int rc = session.emulator.snapshotSmooth(snapshot, aboveSnapshot);
            if (rc == 0) { canvas.drawColor(0xFF000000); return; }
            haveAbove = rc == 2;
            if (!haveAbove) pixelScrollOffset = 0; // at the top: nothing above
        } else if (session == null || !session.emulator.snapshot(snapshot)) {
            canvas.drawColor(0xFF000000);
            return;
        }
        float offsetPx = haveAbove ? pixelScrollOffset : 0;
        session.emulator.scrollbar(scrollState); // keep the indicator current
        notifyScrollStateIfChanged();
        updateRichInputActive();
        canvas.drawColor(snapshot.defaultBg());
        drawBackgroundImage(canvas);

        int sc = snapshot.cols, sr = snapshot.rows;
        // The grid (backgrounds, images, cursor, text) is drawn shifted down by
        // the sub-row offset; the exposed strip at the top is filled by the
        // row-above snapshot drawn at logical row -1. Everything is clipped to
        // the view, so the partial top and bottom rows trim for free.
        canvas.save();
        if (offsetPx != 0) canvas.translate(0, offsetPx);

        // Background runs first so glyphs never get painted over.
        if (haveAbove) drawRowBackground(canvas, aboveSnapshot, 0, -cellHeight);
        for (int y = 0; y < sr; y++) {
            drawRowBackground(canvas, snapshot, y, y * cellHeight);
        }
        updateGraphics();
        drawImages(canvas, true); // z < 0: above background, below text
        updateCursorBlink();
        updateTextBlink();
        drawCursor(canvas);
        for (int y = 0; y < sr; y++) {
            drawRowText(canvas, snapshot, y, sc, y * cellHeight);
        }
        if (haveAbove) {
            drawRowText(canvas, aboveSnapshot, 0, aboveSnapshot.cols, -cellHeight);
        }
        drawImages(canvas, false); // z >= 0: above text (the Kitty default)
        canvas.restore();

        drawSizeOverlay(canvas); // grid-size HUD sits above all cell content
        drawSelectionHandles(canvas);
        drawBellFlash(canvas);
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

    private void drawBellFlash(Canvas canvas) {
        long remaining = bellFlashUntil - SystemClock.uptimeMillis();
        if (remaining <= 0) return;
        float phase = Math.min(1f, remaining / (float) BELL_FLASH_MS);
        bellFlashPaint.setColor(0xFFFFFF);
        bellFlashPaint.setAlpha(Math.round(96 * phase));
        canvas.drawRect(0, 0, getWidth(), getHeight(), bellFlashPaint);
        postInvalidateDelayed(16);
    }

    /**
     * Draws the wallpaper to fill the view, center-cropped so its aspect ratio
     * is preserved (the shorter axis fills; the longer axis is trimmed evenly).
     * Drawn at {@link #backgroundImageAlpha} over the already-painted theme
     * background, so a low opacity keeps text readable. A no-op when no image
     * is set or the view has no size yet.
     */
    private void drawBackgroundImage(Canvas canvas) {
        Bitmap bmp = backgroundImage;
        int vw = getWidth(), vh = getHeight();
        if (bmp == null || vw <= 0 || vh <= 0) return;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) return;

        // Pick the largest centered source rect matching the view's aspect.
        if ((long) bw * vh > (long) vw * bh) {
            int cropW = Math.round(bh * (vw / (float) vh));
            int x = (bw - cropW) / 2;
            bgImgSrc.set(x, 0, x + cropW, bh);
        } else {
            int cropH = Math.round(bw * (vh / (float) vw));
            int y = (bh - cropH) / 2;
            bgImgSrc.set(0, y, bw, y + cropH);
        }
        bgImgDst.set(0, 0, vw, vh);
        bgImagePaint.setAlpha(backgroundImageAlpha);
        canvas.drawBitmap(bmp, bgImgSrc, bgImgDst, bgImagePaint);
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
            float left = textMarginLeft + gfx[base + TerminalNative.GFX_COL] * cellWidth
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
            Drawable d = placeHandle(true,
                    textMarginLeft + snapshot.selectionStartX() * cellWidth,
                    (snapshot.selectionStartY() + 1) * cellHeight, startHandleRect);
            if (d != null) d.draw(canvas);
        }
        if (snapshot.selectionEndVisible() && handleRight != null) {
            Drawable d = placeHandle(false,
                    textMarginLeft + (snapshot.selectionEndX() + 1) * cellWidth,
                    (snapshot.selectionEndY() + 1) * cellHeight, endHandleRect);
            if (d != null) d.draw(canvas);
        }
    }

    /**
     * Positions the handle for one selection endpoint so its pointer tip sits on
     * (tipX, tipY) and its bulb hangs just below the line — outward (left for the
     * start endpoint, right for the end), like TextView's. When that would push
     * the bulb off the near screen edge, the mirror-image drawable is used so the
     * bulb hangs *inward* instead: the whole handle stays on-screen with its tip
     * still exactly on the endpoint, rather than being shoved inward off the
     * selected character (the previous behavior near borders). Records an
     * enlarged touch target in {@code touchRect} and returns the drawable to
     * draw, or null if the needed drawable is unavailable.
     */
    private Drawable placeHandle(boolean start, float tipX, float tipY, RectF touchRect) {
        // The two system drawables are mirror images: the left one's tip is on
        // its right (bulb hangs left, hotspot at 3/4 width), the right one's tip
        // is on its left (bulb hangs right, hotspot at 1/4). So the "inward"
        // drawable for either endpoint is simply the other one.
        Drawable outward = start ? handleLeft : handleRight;
        Drawable inward = start ? handleRight : handleLeft;
        float outwardHotspot = start ? 0.75f : 0.25f;
        float inwardHotspot = start ? 0.25f : 0.75f;

        Drawable d = outward;
        float hotspot = outwardHotspot;
        int w = d.getIntrinsicWidth();
        float leftEdge = tipX - hotspot * w;
        boolean spills = start ? leftEdge < 0 : leftEdge + w > getWidth();
        if (spills && inward != null) {
            d = inward;
            hotspot = inwardHotspot;
            w = d.getIntrinsicWidth();
            leftEdge = tipX - hotspot * w;
        }
        int h = d.getIntrinsicHeight();
        int x = Math.round(leftEdge);
        int y = (int) tipY;
        d.setBounds(x, y, x + w, y + h);
        touchRect.set(x, y, x + w, y + h);
        touchRect.inset(-w / 4f, -h / 4f);
        return d;
    }

    private final StringBuilder runText = new StringBuilder(128);

    /**
     * Paints the per-cell background runs of one row of {@code snap} (source row
     * {@code srcRow}) at the given pixel {@code top}. Cells at the default
     * background are skipped (the view is already cleared to it). Split out so
     * the same routine fills the viewport rows and the row-above strip exposed
     * by a smooth-scroll offset.
     */
    private void drawRowBackground(Canvas canvas, ScreenSnapshot snap, int srcRow, float top) {
        int sc = snap.cols;
        float bottom = top + cellHeight;
        int base = srcRow * sc;
        int def = snap.defaultBg();
        int runStart = 0;
        int runBg = snap.bg[base];
        for (int x = 1; x <= sc; x++) {
            int bg = x < sc ? snap.bg[base + x] : 0;
            if (x == sc || bg != runBg) {
                if (runBg != def) {
                    bgPaint.setColor(runBg);
                    canvas.drawRect(textMarginLeft + runStart * cellWidth, top,
                            textMarginLeft + x * cellWidth, bottom, bgPaint);
                }
                runStart = x;
                runBg = bg;
            }
        }
    }

    /**
     * Draws one row of glyphs from {@code snap} (source row {@code srcRow}) at
     * pixel {@code top}. {@code sc} is the row width in cells. Separating source
     * row from destination pixel lets the renderer place the row-above strip
     * (drawn at a negative top) for smooth scrolling.
     */
    private void drawRowText(Canvas canvas, ScreenSnapshot snap, int srcRow, int sc, float top) {
        int rowBase = srcRow * sc;
        int runStart = -1, runFg = 0, runAttr = 0;
        runText.setLength(0);
        for (int x = 0; x <= sc; x++) {
            int i = rowBase + x;
            int cp = x < sc ? snap.codepoints[i] : 0;
            // On the blink "off" phase, treat blinking cells as blank so their
            // background stays but the glyph disappears.
            if (!textBlinkOn && cp != 0 && (snap.attrs[i] & TerminalNative.ATTR_BLINK) != 0) cp = 0;
            int fg = x < sc ? snap.fg[i] : 0;
            int attr = x < sc ? (snap.attrs[i] & ~TerminalNative.ATTR_WIDE) : 0;
            // A grapheme cluster (base + combining/ZWJ marks) is several
            // codepoints rendered as one glyph; draw it whole so the font can
            // shape it, which means keeping it out of batched runs like wides.
            String cluster = x < sc ? snap.graphemeAt(i) : null;
            // Batched runs assume every glyph advances exactly cellWidth, which
            // only holds for the primary monospace font's own (ASCII) glyphs. A
            // non-ASCII codepoint may be drawn via font fallback at a different
            // advance, so keep it out of the batch and pin it below.
            boolean nonAscii = x < sc && cp > 0x7F;
            boolean breakRun = x == sc || cp == 0 || fg != runFg || attr != runAttr
                    // Wide glyphs advance two cells; keep them out of batched runs.
                    || (x < sc && (snap.attrs[i] & TerminalNative.ATTR_WIDE) != 0)
                    || cluster != null
                    || nonAscii;
            if (breakRun && runStart >= 0 && runText.length() > 0) {
                applyStyle(runFg, runAttr);
                canvas.drawText(runText, 0, runText.length(),
                        textMarginLeft + runStart * cellWidth, top + baseline, textPaint);
                // The run covered cells [runStart, x); underline its full width.
                drawUnderline(canvas, runFg, runAttr,
                        textMarginLeft + runStart * cellWidth,
                        textMarginLeft + x * cellWidth, top);
                runStart = -1;
                runText.setLength(0);
            }
            if (x == sc || cp == 0) continue;
            if ((snap.attrs[i] & TerminalNative.ATTR_WIDE) != 0) {
                applyStyle(fg, attr);
                String s = cluster != null ? cluster : new String(Character.toChars(cp));
                canvas.drawText(s, textMarginLeft + x * cellWidth, top + baseline, textPaint);
                drawUnderline(canvas, fg, attr,
                        textMarginLeft + x * cellWidth,
                        textMarginLeft + (x + 2) * cellWidth, top);
                continue;
            }
            if (cluster != null || cp > 0x7F) {
                // Narrow cluster or lone non-ASCII glyph: one cell wide, drawn
                // individually and pinned to its grid column. A cluster's
                // combining marks attach to the base instead of the next cell,
                // and a glyph whose font advance differs from cellWidth (e.g. a
                // block/box element resolved via font fallback) can't push later
                // cells right the way it would inside a batched drawText.
                applyStyle(fg, attr);
                String s = cluster != null ? cluster : new String(Character.toChars(cp));
                canvas.drawText(s, textMarginLeft + x * cellWidth,
                        top + baseline, textPaint);
                drawUnderline(canvas, fg, attr,
                        textMarginLeft + x * cellWidth,
                        textMarginLeft + (x + 1) * cellWidth, top);
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
        boolean italic = (attr & TerminalNative.ATTR_ITALIC) != 0;
        boolean bold = (attr & TerminalNative.ATTR_BOLD) != 0;
        Typeface face = regularTypeface;
        boolean fakeBold = false;
        boolean fakeItalic = false;
        if (bold && italic) {
            if (boldItalicTypeface != null) {
                face = boldItalicTypeface;
            } else if (italicTypeface != null) {
                face = italicTypeface;
                fakeBold = true;
            } else if (boldTypeface != null) {
                face = boldTypeface;
                fakeItalic = true;
            } else {
                fakeBold = true;
                fakeItalic = true;
            }
        } else if (bold) {
            if (boldTypeface != null) {
                face = boldTypeface;
            } else {
                fakeBold = true;
            }
        } else if (italic) {
            if (italicTypeface != null) {
                face = italicTypeface;
            } else {
                fakeItalic = true;
            }
        }
        textPaint.setColor(fg);
        textPaint.setTypeface(face);
        textPaint.setFakeBoldText(fakeBold);
        textPaint.setTextSkewX(fakeItalic ? -0.25f : 0);
        // Underlines are stroked separately by drawUnderline so the engine's
        // 4:2..4:5 styles render; Paint's underline only does a solid line.
        textPaint.setStrikeThruText((attr & TerminalNative.ATTR_STRIKE) != 0);
    }

    /**
     * Strokes the cell underline for a run spanning the pixels [left, right) at
     * row top {@code top}, in the style encoded in the attrs byte. Single is a
     * plain line; double is a stacked pair; curly is a stroked sine; dotted and
     * dashed reuse the cached {@link DashPathEffect}s. No-op when the run has no
     * underline. The line color matches the run's foreground.
     */
    private void drawUnderline(Canvas canvas, int color, int attr,
            float left, float right, float top) {
        int style = (attr & TerminalNative.ATTR_UL_MASK) >> TerminalNative.ATTR_UL_SHIFT;
        if (style == TerminalNative.UNDERLINE_NONE) {
            // Underline OSC 8 hyperlinks that carry no SGR underline of their
            // own — the only tap affordance available without a hover. A link
            // that already sets an underline style keeps it (handled below).
            if (!tapToOpenLinks || (attr & TerminalNative.ATTR_HYPERLINK) == 0) return;
            style = TerminalNative.UNDERLINE_SINGLE;
        }

        // Sit the line within the descent, below the glyph baseline.
        float descent = cellHeight - baseline;
        float y = top + baseline + descent * 0.45f;
        underlinePaint.setColor(color);
        underlinePaint.setPathEffect(null);

        switch (style) {
            case TerminalNative.UNDERLINE_DOUBLE: {
                float half = Math.max(underlineThickness, descent * 0.18f);
                float y1 = y - half;
                float y2 = Math.min(y + half, top + cellHeight - underlineThickness * 0.5f);
                canvas.drawLine(left, y1, right, y1, underlinePaint);
                canvas.drawLine(left, y2, right, y2, underlinePaint);
                break;
            }
            case TerminalNative.UNDERLINE_CURLY:
                drawCurlyUnderline(canvas, left, right, y, top);
                break;
            case TerminalNative.UNDERLINE_DOTTED:
                underlinePaint.setPathEffect(dottedEffect);
                canvas.drawLine(left, y, right, y, underlinePaint);
                break;
            case TerminalNative.UNDERLINE_DASHED:
                underlinePaint.setPathEffect(dashedEffect);
                canvas.drawLine(left, y, right, y, underlinePaint);
                break;
            default: // UNDERLINE_SINGLE
                canvas.drawLine(left, y, right, y, underlinePaint);
                break;
        }
    }

    /**
     * Strokes a sine-like curl centered on {@code y} across [left, right), one
     * wave per cell, with its amplitude clamped so the curl stays between the
     * baseline and the cell bottom. Reuses {@link #underlinePath}.
     */
    private void drawCurlyUnderline(Canvas canvas, float left, float right,
            float y, float top) {
        // A quad bezier peaks at half its control offset, so double the target.
        float peak = Math.min(y - (top + baseline), top + cellHeight - y);
        float ctrl = Math.max(2f, Math.min((cellHeight - baseline) * 0.6f, peak * 2f));
        float half = Math.max(2f, cellWidth * 0.5f); // half-wavelength
        underlinePath.rewind();
        underlinePath.moveTo(left, y);
        boolean up = true;
        for (float x = left; x < right; x += half) {
            float nx = Math.min(x + half, right);
            float cx = (x + nx) * 0.5f;
            underlinePath.quadTo(cx, up ? y - ctrl : y + ctrl, nx, y);
            up = !up;
        }
        canvas.drawPath(underlinePath, underlinePaint);
    }

    /**
     * Starts/stops the blink loop to match the current snapshot and resets the
     * phase to "on" when the cursor moves, so it stays solid during typing and
     * scrolling and blinks only when idle. Called every frame from onDraw.
     */
    private void updateCursorBlink() {
        boolean shouldBlink = snapshot.cursorBlinking()
                && snapshot.cursorVisible()
                && snapshot.cursorInViewport();
        int cx = snapshot.cursorX(), cy = snapshot.cursorY();
        boolean moved = cx != lastCursorX || cy != lastCursorY;
        lastCursorX = cx;
        lastCursorY = cy;

        if (!shouldBlink) {
            if (cursorBlinkRunning) {
                cursorBlinkRunning = false;
                removeCallbacks(cursorBlinkTick);
            }
            cursorBlinkOn = true; // a steady cursor is always shown
            return;
        }
        if (!cursorBlinkRunning || moved) {
            cursorBlinkRunning = true;
            cursorBlinkOn = true; // solid right after (re)start or a move
            removeCallbacks(cursorBlinkTick);
            postDelayed(cursorBlinkTick, CURSOR_BLINK_MS);
        }
    }

    private void updateTextBlink() {
        int[] attrs = snapshot.attrs;
        int n = snapshot.cols * snapshot.rows;
        boolean hasBlinking = false;
        for (int i = 0; i < n; i++) {
            if ((attrs[i] & TerminalNative.ATTR_BLINK) != 0) {
                hasBlinking = true;
                break;
            }
        }
        if (!hasBlinking) {
            if (textBlinkRunning) {
                textBlinkRunning = false;
                removeCallbacks(textBlinkTick);
            }
            textBlinkOn = true;
            return;
        }
        if (!textBlinkRunning) {
            textBlinkRunning = true;
            removeCallbacks(textBlinkTick);
            postDelayed(textBlinkTick, TEXT_BLINK_MS);
        }
    }

    private void drawCursor(Canvas canvas) {
        if (!snapshot.cursorInViewport() || !snapshot.cursorVisible()) return;
        // On the blink "off" phase, skip drawing. For the block cursor this
        // leaves the cell glyph to be drawn normally by the text pass (it is
        // only nulled below when the inverse cursor actually paints).
        if (snapshot.cursorBlinking() && !cursorBlinkOn) return;
        float left = textMarginLeft + snapshot.cursorX() * cellWidth;
        float top = snapshot.cursorY() * cellHeight;
        boolean wide = snapshot.cursorX() < snapshot.cols
                && (snapshot.attrs[snapshot.cursorY() * snapshot.cols + snapshot.cursorX()]
                        & TerminalNative.ATTR_WIDE) != 0;
        float right = left + cellWidth * (wide ? 2 : 1);
        int cursorColor = snapshot.cursorColor();
        bgPaint.setColor(cursorColor != 0 ? cursorColor : snapshot.defaultFg());
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
                String cluster = snapshot.graphemeAt(i);
                if (cp != 0) {
                    applyStyle(snapshot.bg[i], snapshot.attrs[i]);
                    canvas.drawText(cluster != null ? cluster
                                    : new String(Character.toChars(cp)),
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
        dispatchText(text, 0);
    }

    /**
     * Sends printable text with {@code extraMods} baked in (a combo key's mask),
     * OR-ed with any sticky CTRL/ALT. The mask only affects single chars; a
     * multi-char snippet is always sent verbatim.
     */
    public void dispatchText(String text, int extraMods) {
        if (session == null || text.isEmpty()) return;
        if (selecting) finishSelection();
        // This text bypasses the rich-input buffer (hardware key, toolbar, or a
        // modifier combo), so re-sync the mirror from empty afterwards.
        resetRichInput();
        int mods = sticky.consume() | extraMods;
        if (mods == 0 || text.codePointCount(0, text.length()) > 1) {
            jumpToPresent();
            session.write(text);
        } else {
            char ch = text.charAt(0);
            byte[] encoded = session.emulator.encodeKey(
                    keycodeForChar(ch), mods, text, Character.toLowerCase(ch));
            jumpToPresent();
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
        dispatchKey(androidKeyCode, 0);
    }

    /**
     * Sends a non-printable key with {@code extraMods} baked in (a combo key's
     * mask, e.g. Ctrl-→), OR-ed with any sticky CTRL/ALT. The encoder honors
     * the current terminal modes, so modified cursor keys encode correctly.
     */
    public void dispatchKey(int androidKeyCode, int extraMods) {
        if (session == null) return;
        if (selecting) finishSelection();
        // A special key may move the remote cursor or trigger completion, so
        // the mirrored input line no longer matches; drop it.
        resetRichInput();
        session.sendKey(androidKeyCode, sticky.consume() | extraMods, null, 0);
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
    /** Controls whether tapping the terminal raises the soft keyboard. */
    public void setTouchKeyboardEnabled(boolean enabled) {
        touchKeyboardEnabled = enabled;
    }

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
            jumpToPresent();
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
        jumpToPresent();
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

        // Shift was consumed by character production when it changed the
        // output (Shift+; → ':' vs ';'). Keep it as a terminal modifier
        // only when the character is the same with or without shift (e.g.
        // Shift+Tab), otherwise the Ghostty encoder wraps it in a kitty
        // keyboard sequence (\033[59;2u) that terminals at protocol levels
        // 1–2 don't use for plain printable input.
        if ((mods & TerminalNative.MOD_SHIFT) != 0 && utf8 != null
                && unicode != unshifted) {
            mods &= ~TerminalNative.MOD_SHIFT;
        }

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
