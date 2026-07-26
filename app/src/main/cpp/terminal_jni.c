/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
/*
 * JNI bindings to libghostty-vt for TerminalEmulator.
 *
 * One TermCtx per terminal bundles the Ghostty handles plus reusable
 * iterator/event objects. Ghostty callbacks (write-pty, bell, title) fire
 * synchronously inside vt_write, so they are buffered in the TermCtx and
 * handed to Java from the same feed() call — no native→Java upcalls.
 *
 * Not thread-safe by design; TerminalEmulator serializes all calls.
 */
#include <android/keycodes.h>
#include <ghostty/vt.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "png_decode.h"
#include "kitty_unicode.h"
#include "case_fold.h"

/* Event bits returned by feed(), see TerminalNative.EVENT_*. */
#define EVENT_BELL 1
#define EVENT_TITLE 2

/* Per-screen Kitty image storage cap. Non-zero enables the protocol; this
 * bounds memory on a phone while leaving room for a few full-screen images. */
#define KITTY_STORAGE_LIMIT_BYTES (64ull * 1024 * 1024)

/* Ints per placement record from terminalGraphics; mirror TerminalNative.GFX_*. */
#define GFX_STRIDE 14

/* Upper bound on stored search matches, to cap memory on a pathological query
 * (e.g. a single common letter against a deep scrollback). Scanning stops once
 * this many hits are found. */
#define MAX_MATCHES 50000

/* One text-search hit, as inclusive screen-coordinate endpoints. Screen y can
 * exceed 65535 with deep scrollback, so it is 32-bit (GhosttyPointCoordinate.y
 * is uint32_t too); x is a column and fits in 16 bits. */
typedef struct {
    uint16_t sx;
    uint32_t sy;
    uint16_t ex;
    uint32_t ey;
} SearchMatch;

typedef struct {
    GhosttyTerminal term;
    GhosttyRenderState rs;
    GhosttyRenderStateRowIterator row_iter;
    GhosttyRenderStateRowCells cells;
    GhosttyKeyEncoder encoder;
    GhosttyKeyEvent kev;
    /* Mouse event encoding (touch gestures → wheel/click reports). Reused
     * across calls; the encoder's tracking mode and output format are re-synced
     * from the terminal on every encode, the geometry from the cached cell
     * size. NULL if allocation failed, which disables mouse encoding. */
    GhosttyMouseEncoder mouse_encoder;
    GhosttyMouseEvent mouse_event;
    /* Reused across frames; re-populated from storage on each terminalGraphics
     * call. NULL when iterator allocation failed (image readback disabled). */
    GhosttyKittyGraphicsPlacementIterator graphics_iter;
    /* Current grid and cell pixel size, kept in sync on resize. Feeds the
     * virtual-placement layout and the XTWINOPS (CSI 14/16/18 t) replies. */
    uint16_t cols, rows;
    uint32_t cell_w, cell_h;

    /* Bytes the terminal wants written back to the PTY (query responses),
     * collected during vt_write. */
    uint8_t *out;
    size_t out_len, out_cap;

    /* Text-search results from run_search. During a scan `matches` is a
     * ring of capacity match_cap that keeps the most recent hits (so the newest
     * content stays navigable even on a huge buffer); afterwards it is rotated
     * into match_count sorted, navigable entries. match_total counts every hit,
     * including those past the cap, for an honest total. Rebuilt on each search;
     * navigation re-runs the scan, so these never outlive intervening output. */
    SearchMatch *matches;
    size_t match_count, match_cap, match_total;
    /* Active query (codepoints) and its case flag, the current navigable index,
     * and a dirty flag set on every feed so navigation re-scans only when the
     * buffer actually changed. */
    uint32_t *search_q;
    size_t search_qlen;
    bool search_cs;
    size_t match_index;
    bool search_dirty;

    int events;

    /* When true, DEC mode 2027 (grapheme clustering) is force-enabled and
     * re-asserted after each feed so it survives a program's RIS reset. Set
     * from Java when the user opts in; off by default (calloc-zeroed). */
    bool want_grapheme_cluster;
} TermCtx;

static void on_write_pty(GhosttyTerminal t, void *ud, const uint8_t *data,
                         size_t len) {
    (void)t;
    TermCtx *c = ud;
    if (c->out_len + len > c->out_cap) {
        size_t cap = c->out_cap ? c->out_cap * 2 : 256;
        while (cap < c->out_len + len) cap *= 2;
        uint8_t *p = realloc(c->out, cap);
        if (!p) return; /* drop response on OOM; terminal state stays valid */
        c->out = p;
        c->out_cap = cap;
    }
    memcpy(c->out + c->out_len, data, len);
    c->out_len += len;
}

static void on_bell(GhosttyTerminal t, void *ud) {
    (void)t;
    ((TermCtx *)ud)->events |= EVENT_BELL;
}

static void on_title(GhosttyTerminal t, void *ud) {
    (void)t;
    ((TermCtx *)ud)->events |= EVENT_TITLE;
}

/* Answers XTWINOPS size queries (CSI 14/16/18 t); ghostty encodes the reply
 * and sends it via the write-pty callback. cell_w/cell_h are 0 until the
 * first resize, which always lands before any program runs. */
static bool on_size(GhosttyTerminal t, void *ud, GhosttySizeReportSize *out) {
    (void)t;
    TermCtx *c = ud;
    out->rows = c->rows;
    out->columns = c->cols;
    out->cell_width = c->cell_w;
    out->cell_height = c->cell_h;
    return true;
}

/* Typed assignments so a callback signature drift fails to compile instead
 * of corrupting the stack at runtime (ghostty_terminal_set takes void*). */
static const GhosttyTerminalWritePtyFn write_pty_fn = on_write_pty;
static const GhosttyTerminalBellFn bell_fn = on_bell;
static const GhosttyTerminalTitleChangedFn title_fn = on_title;
static const GhosttyTerminalSizeFn size_fn = on_size;
static const GhosttySysDecodePngFn decode_png_fn = term_decode_png;

JNIEXPORT jlong JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalNew(
    JNIEnv *env, jclass clazz, jint cols, jint rows, jint scrollbackLines) {
    (void)env; (void)clazz;
    TermCtx *c = calloc(1, sizeof(TermCtx));
    if (!c) return 0;

    /* Ghostty's max_scrollback is a BYTE budget, not a line count (the vt.h
     * comment is misleading; see Screen.zig "amount of scrollback to keep in
     * bytes"). The grid for one row costs Row + cols*Cell = 8*(cols+1) bytes,
     * but each backing page also carries fixed metadata (styles, graphemes,
     * hyperlinks, strings) and is allocated/pruned in whole page-sized chunks,
     * so the real cost per retained row measures ~1.6x the bare grid cost.
     * We double the grid estimate: that clears the per-page overhead, the
     * two-page floor the limit is clamped to internally, and page-granular
     * rounding at small sizes, so the user gets at least the requested depth.
     * Over-provisioning is cheap — the budget is only a cap, and pages are
     * created lazily as output scrolls, not up front. Sized at the spawn
     * width; a later reflow to a wider grid yields fewer lines for the same
     * bytes, as it would in upstream Ghostty. */
    size_t bytes_per_row = (size_t)8 * ((size_t)(uint16_t)cols + 1);
    size_t want_rows = (size_t)(uint16_t)rows + (size_t)(uint32_t)scrollbackLines;
    size_t max_scrollback = want_rows * bytes_per_row * 2;

    GhosttyTerminalOptions opts = {
        .cols = (uint16_t)cols,
        .rows = (uint16_t)rows,
        .max_scrollback = max_scrollback,
    };
    if (ghostty_terminal_new(NULL, &c->term, opts) != GHOSTTY_SUCCESS)
        goto fail;
    if (ghostty_render_state_new(NULL, &c->rs) != GHOSTTY_SUCCESS) goto fail;
    if (ghostty_render_state_row_iterator_new(NULL, &c->row_iter) !=
        GHOSTTY_SUCCESS)
        goto fail;
    if (ghostty_render_state_row_cells_new(NULL, &c->cells) != GHOSTTY_SUCCESS)
        goto fail;
    if (ghostty_key_encoder_new(NULL, &c->encoder) != GHOSTTY_SUCCESS)
        goto fail;
    if (ghostty_key_event_new(NULL, &c->kev) != GHOSTTY_SUCCESS) goto fail;
    if (ghostty_mouse_encoder_new(NULL, &c->mouse_encoder) != GHOSTTY_SUCCESS)
        goto fail;
    if (ghostty_mouse_event_new(NULL, &c->mouse_event) != GHOSTTY_SUCCESS)
        goto fail;

    c->cols = (uint16_t)cols;
    c->rows = (uint16_t)rows;

    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_USERDATA, c);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_WRITE_PTY, write_pty_fn);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_BELL, bell_fn);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, title_fn);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SIZE, size_fn);

    /* Kitty graphics: enable PNG payloads (process-global, idempotent) and
     * image storage on this terminal, then pre-allocate the placement
     * iterator. All are no-ops when Kitty graphics are disabled at build
     * time; a NULL iterator simply leaves image readback disabled. */
    ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, decode_png_fn);
    uint64_t kitty_limit = KITTY_STORAGE_LIMIT_BYTES;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT,
                         &kitty_limit);
    ghostty_kitty_graphics_placement_iterator_new(NULL, &c->graphics_iter);
    return (jlong)(intptr_t)c;

fail:
    ghostty_mouse_event_free(c->mouse_event);
    ghostty_mouse_encoder_free(c->mouse_encoder);
    ghostty_key_event_free(c->kev);
    ghostty_key_encoder_free(c->encoder);
    ghostty_render_state_row_cells_free(c->cells);
    ghostty_render_state_row_iterator_free(c->row_iter);
    ghostty_render_state_free(c->rs);
    ghostty_terminal_free(c->term);
    free(c);
    return 0;
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalFree(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    if (!c) return;
    ghostty_kitty_graphics_placement_iterator_free(c->graphics_iter);
    ghostty_mouse_event_free(c->mouse_event);
    ghostty_mouse_encoder_free(c->mouse_encoder);
    ghostty_key_event_free(c->kev);
    ghostty_key_encoder_free(c->encoder);
    ghostty_render_state_row_cells_free(c->cells);
    ghostty_render_state_row_iterator_free(c->row_iter);
    ghostty_render_state_free(c->rs);
    ghostty_terminal_free(c->term);
    free(c->out);
    free(c->matches);
    free(c->search_q);
    free(c);
}

/* Inverse of pack_rgb: ARGB int (alpha byte ignored) -> GhosttyColorRgb. */
static GhosttyColorRgb unpack_rgb(jint argb) {
    GhosttyColorRgb c;
    c.r = (uint8_t)((argb >> 16) & 0xFF);
    c.g = (uint8_t)((argb >> 8) & 0xFF);
    c.b = (uint8_t)(argb & 0xFF);
    return c;
}

/*
 * Sets the default color theme: foreground, background, cursor, and the full
 * 256-entry palette (built Java-side from the theme's 16 ANSI colors plus the
 * standard 6x6x6 cube and grayscale ramp). Colors are ARGB ints; the alpha
 * byte is ignored. These are *defaults* — a program's OSC 4/10/11/12
 * overrides still win, and the palette set preserves any per-index OSC
 * override. Runs under the TerminalEmulator lock like every other call.
 */
JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSetColors(
    JNIEnv *env, jclass clazz, jlong h, jint fg, jint bg, jint cursor,
    jintArray jpalette) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;

    GhosttyColorRgb fg_rgb = unpack_rgb(fg);
    GhosttyColorRgb bg_rgb = unpack_rgb(bg);
    GhosttyColorRgb cursor_rgb = unpack_rgb(cursor);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg_rgb);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg_rgb);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor_rgb);

    jint argb[256];
    (*env)->GetIntArrayRegion(env, jpalette, 0, 256, argb);
    GhosttyColorRgb pal[256];
    for (int i = 0; i < 256; i++) pal[i] = unpack_rgb(argb[i]);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, pal);
}

/*
 * Sets the default cursor style and blink. style is a GhosttyTerminalCursorStyle
 * (bar=0, block=1, underline=2, block_hollow=3 — same numbering as the CURSOR_*
 * constants and the snapshot's meta[3]). These are *defaults* applied on DECSCUSR
 * reset (CSI 0 q); libghostty-vt also pushes them to the live cursor immediately
 * when no program override is active, so a change at a shell prompt takes effect
 * at once but won't fight a full-screen app's cursor. Under the TerminalEmulator
 * lock like every other call.
 */
JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSetCursorStyle(
    JNIEnv *env, jclass clazz, jlong h, jint style, jboolean blink) {
    (void)env;
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;

    GhosttyTerminalCursorStyle s = (GhosttyTerminalCursorStyle)style;
    bool b = blink == JNI_TRUE;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE, &s);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK, &b);
}

/*
 * Feeds PTY output through the VT parser. Returns bytes the terminal wants
 * written back to the PTY (e.g. DA/DSR responses), or null if none.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalFeed(
    JNIEnv *env, jclass clazz, jlong h, jbyteArray data, jint len) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    /* NUL is an "ignore" control character (ECMA-48). Some producers emit
     * stray NULs inside APC payloads — notably mpv's --vo=kitty, which appends
     * one to each frame's final graphics chunk. The VT engine collects APC
     * bytes verbatim, so a NUL there corrupts the base64 image data and the
     * whole frame is rejected (a black screen). Strip NULs before feeding; the
     * common NUL-free case takes the no-copy fast path. */
    const uint8_t *feed_ptr = (const uint8_t *)bytes;
    size_t feed_len = (size_t)len;
    uint8_t *stripped = NULL;
    if (len > 0 && memchr(bytes, 0, (size_t)len)) {
        stripped = malloc((size_t)len);
        if (stripped) {
            size_t w = 0;
            for (size_t r = 0; r < (size_t)len; r++)
                if (bytes[r] != 0) stripped[w++] = (uint8_t)bytes[r];
            feed_ptr = stripped;
            feed_len = w;
        }
    }
    ghostty_terminal_vt_write(c->term, feed_ptr, feed_len);
    free(stripped);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    /* The buffer changed, so an open search must re-scan before it navigates. */
    c->search_dirty = true;

    /* Keep forced grapheme clustering (mode 2027) sticky: a program's RIS
     * reset clears it, so re-assert after each feed when the user opted in. */
    if (c->want_grapheme_cluster) {
        bool on = false;
        if (ghostty_terminal_mode_get(c->term, GHOSTTY_MODE_GRAPHEME_CLUSTER,
                                      &on) == GHOSTTY_SUCCESS && !on) {
            ghostty_terminal_mode_set(c->term, GHOSTTY_MODE_GRAPHEME_CLUSTER,
                                      true);
        }
    }

    if (c->out_len == 0) return NULL;
    jbyteArray resp = (*env)->NewByteArray(env, (jsize)c->out_len);
    (*env)->SetByteArrayRegion(env, resp, 0, (jsize)c->out_len,
                               (const jbyte *)c->out);
    c->out_len = 0;
    return resp;
}

/* Returns and clears accumulated EVENT_* bits. */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalEvents(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    int e = c->events;
    c->events = 0;
    return e;
}

JNIEXPORT jstring JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalTitle(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyString s = {0};
    if (ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_TITLE, &s) !=
            GHOSTTY_SUCCESS ||
        s.len == 0)
        return NULL;
    /* Title is borrowed and not NUL-terminated; copy before NewStringUTF. */
    char *buf = malloc(s.len + 1);
    if (!buf) return NULL;
    memcpy(buf, s.ptr, s.len);
    buf[s.len] = 0;
    jstring out = (*env)->NewStringUTF(env, buf);
    free(buf);
    return out;
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalResize(
    JNIEnv *env, jclass clazz, jlong h, jint cols, jint rows, jint cell_w,
    jint cell_h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    c->cols = (uint16_t)cols;
    c->rows = (uint16_t)rows;
    c->cell_w = (uint32_t)cell_w;
    c->cell_h = (uint32_t)cell_h;
    ghostty_terminal_resize(c->term, (uint16_t)cols, (uint16_t)rows,
                            (uint32_t)cell_w, (uint32_t)cell_h);
}

/* mode: 0 = top, 1 = bottom, 2 = by delta rows (negative is up). */
JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalScroll(
    JNIEnv *env, jclass clazz, jlong h, jint mode, jint delta) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyTerminalScrollViewport sv = {0};
    switch (mode) {
    case 0: sv.tag = GHOSTTY_SCROLL_VIEWPORT_TOP; break;
    case 1: sv.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM; break;
    default:
        sv.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
        sv.value.delta = delta;
        break;
    }
    ghostty_terminal_scroll_viewport(c->term, sv);
}

/* out: [0]=total rows, [1]=viewport offset, [2]=viewport length. */
JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalScrollbar(
    JNIEnv *env, jclass clazz, jlong h, jintArray jout) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyTerminalScrollbar sb = {0};
    ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
    jint vals[3] = {(jint)sb.total, (jint)sb.offset, (jint)sb.len};
    (*env)->SetIntArrayRegion(env, jout, 0, 3, vals);
}

/* True when screen row y is a primary OSC 133 prompt line (not a
 * continuation). Screen/history lookups walk the page list, so callers should
 * stop at the first hit rather than scan the whole buffer eagerly. */
static bool screen_row_is_prompt(TermCtx *c, uint32_t y) {
    GhosttyPoint p = {
        .tag = GHOSTTY_POINT_TAG_SCREEN,
        .value.coordinate = {.x = 0, .y = y},
    };
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(c->term, p, &ref) != GHOSTTY_SUCCESS)
        return false;
    GhosttyRow row = 0;
    if (ghostty_grid_ref_row(&ref, &row) != GHOSTTY_SUCCESS) return false;
    GhosttyRowSemanticPrompt sp = GHOSTTY_ROW_SEMANTIC_NONE;
    ghostty_row_get(row, GHOSTTY_ROW_DATA_SEMANTIC_PROMPT, &sp);
    return sp == GHOSTTY_ROW_SEMANTIC_PROMPT;
}

/*
 * Scrolls the viewport to the nearest primary shell-prompt line (OSC 133) in
 * the given direction: dir < 0 walks back to the previous prompt above the
 * viewport top, dir > 0 to the next prompt below it. The target prompt lands at
 * the top of the viewport (clamped to the scrollable range). Returns 1 if it
 * moved, 0 when there is no prompt in that direction.
 */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalPromptNav(
    JNIEnv *env, jclass clazz, jlong h, jint dir) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyTerminalScrollbar sb = {0};
    ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
    long total = (long)sb.total;
    long top = (long)sb.offset;
    long len = sb.len ? (long)sb.len : 1;
    if (total == 0) return 0;

    long found = -1;
    if (dir < 0) {
        for (long y = top - 1; y >= 0; y--)
            if (screen_row_is_prompt(c, (uint32_t)y)) { found = y; break; }
    } else {
        for (long y = top + 1; y < total; y++)
            if (screen_row_is_prompt(c, (uint32_t)y)) { found = y; break; }
    }
    if (found < 0) return 0;

    long max_top = total - len;
    if (max_top < 0) max_top = 0;
    long target = found > max_top ? max_top : found;
    long delta = target - top;
    if (delta != 0) {
        GhosttyTerminalScrollViewport sv = {.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA};
        sv.value.delta = (intptr_t)delta;
        ghostty_terminal_scroll_viewport(c->term, sv);
    }
    return 1;
}

/* Force-enables or disables DEC mode 2027 (grapheme clustering). When on, the
 * engine groups multi-codepoint grapheme clusters — combining marks, ZWJ emoji,
 * and Indic conjuncts (consonant-virama-consonant) — into one cell, which the
 * renderer shapes as a unit; terminalFeed re-asserts it after a RIS reset. */
JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSetGraphemeClustering(
    JNIEnv *env, jclass clazz, jlong h, jboolean enable) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    c->want_grapheme_cluster = enable;
    ghostty_terminal_mode_set(c->term, GHOSTTY_MODE_GRAPHEME_CLUSTER, enable);
}

static jint pack_rgb(GhosttyColorRgb c) {
    return (jint)(0xFF000000u | ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) |
                  (uint32_t)c.b);
}

/* Attribute bits in the attrs[] snapshot array (jintArray), mirrored in ScreenSnapshot. */
#define ATTR_BOLD 1
#define ATTR_ITALIC 2
#define ATTR_UNDERLINE 4
#define ATTR_STRIKE 8
#define ATTR_WIDE 16
#define ATTR_BLINK 32
/* Underline shape: a 3-bit field (the GhosttySgrUnderline value, 0..5) packed
   into bits 5-7. ATTR_UNDERLINE flags presence; this field names the style
   (single/double/curly/dotted/dashed). */
#define ATTR_UL_SHIFT 5
#define ATTR_UL_MASK (7 << ATTR_UL_SHIFT)
/* OSC 8 hyperlink presence. Bits 0-7 are taken by the flags above and the
   underline-shape field, so this lands at bit 8. The renderer underlines
   these cells as a tap-to-open affordance. */
#define ATTR_HYPERLINK 256

/* Selection flag bits in meta[9], mirrored in ScreenSnapshot. */
#define SEL_ACTIVE 1
#define SEL_START_VISIBLE 2
#define SEL_END_VISIBLE 4

/* Input-mode flag bits in meta[14], mirrored in ScreenSnapshot / TerminalNative.
 * These tell the IME layer whether the terminal is in a plain line-editing
 * state (none set) or running something that wants raw keys (alt screen /
 * application cursor keys), so rich-keyboard input can disable itself. */
#define INPUT_MODE_ALT_SCREEN 1
#define INPUT_MODE_APP_CURSOR 2
#define INPUT_MODE_BRACKETED_PASTE 4
/* A mouse tracking mode (X10/normal/button/any-event) is active: the view
 * encodes touch gestures as mouse wheel/click reports instead of scrolling. */
#define INPUT_MODE_MOUSE 8

/*
 * Copies the current viewport into flat per-cell arrays (row-major).
 * Colors are resolved to ARGB here — including defaults, inverse,
 * invisible, and the active selection (drawn as inverse video) — so the
 * Java renderer just draws what it's given.
 *
 * meta layout: [0] cursor-in-viewport, [1] x, [2] y, [3] style,
 * [4] visible, [5] blinking, [6] wide-tail, [7] default bg, [8] default fg,
 * [9] SEL_* flags, [10] sel start x, [11] sel start y, [12] sel end x,
 * [13] sel end y, [14] INPUT_MODE_* flags, [15] cursor color (0 = unset).
 * Selection endpoints are viewport coordinates ordered top-left to
 * bottom-right; each is only valid when its visibility bit is set (an
 * endpoint can sit above or below the viewport).
 *
 * Grapheme clusters (a base codepoint plus combining/ZWJ codepoints that
 * render as one glyph) don't fit the one-int-per-cell layout, so they ride
 * in a separate self-describing overflow buffer `jgraphemes`. The base
 * codepoint still lands in the cp array (width/wrap/cursor logic stays
 * codepoint-based); the full cluster is emitted here for the renderer.
 * Layout: jgraphemes[0] is the number of record ints required (excluding
 * slot 0); records follow as [cellIndex, count, cp0, cp1, ...]. If slot 0
 * exceeds the buffer's record capacity the records didn't fit and the caller
 * must grow jgraphemes and retry — the same contract as the cell arrays.
 *
 * Returns (cols << 16) | rows. If the arrays are smaller than cols*rows
 * only meta is written; the caller must re-allocate and retry.
 */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSnapshot(
    JNIEnv *env, jclass clazz, jlong h, jintArray jcp, jintArray jfg,
    jintArray jbg, jintArray jattrs, jintArray jmeta, jintArray jgraphemes) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;

    ghostty_render_state_update(c->rs, c->term);

    uint16_t cols = 0, rows = 0;
    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);

    GhosttyColorRgb bg_default = {0}, fg_default = {255, 255, 255};
    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_COLOR_BACKGROUND,
                             &bg_default);
    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_COLOR_FOREGROUND,
                             &fg_default);

    jint meta[16] = {0};
    bool b = false;
    ghostty_render_state_get(
        c->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE, &b);
    meta[0] = b;
    if (b) {
        uint16_t v16 = 0;
        ghostty_render_state_get(
            c->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &v16);
        meta[1] = v16;
        ghostty_render_state_get(
            c->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &v16);
        meta[2] = v16;
        GhosttyRenderStateCursorVisualStyle style =
            GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;
        ghostty_render_state_get(
            c->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &style);
        meta[3] = (jint)style;
        ghostty_render_state_get(c->rs,
                                 GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE, &b);
        meta[4] = b;
        ghostty_render_state_get(c->rs,
                                 GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING, &b);
        meta[5] = b;
        ghostty_render_state_get(
            c->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL, &b);
        meta[6] = b;
    }
    meta[7] = pack_rgb(bg_default);
    meta[8] = pack_rgb(fg_default);

    /* Effective cursor color (OSC override or the theme default set via
     * terminalSetColors); left 0 when unset, in which case the renderer
     * falls back to the foreground color. */
    bool cursor_has = false;
    ghostty_render_state_get(
        c->rs, GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR_HAS_VALUE, &cursor_has);
    if (cursor_has) {
        GhosttyColorRgb cursor_rgb = {0};
        ghostty_render_state_get(
            c->rs, GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR, &cursor_rgb);
        meta[15] = pack_rgb(cursor_rgb);
    }

    /* Selection endpoints, ordered top-left → bottom-right for handle
     * placement. The untracked refs are valid here because nothing below
     * mutates the terminal. */
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) ==
        GHOSTTY_SUCCESS) {
        GhosttySelection fwd = GHOSTTY_INIT_SIZED(GhosttySelection);
        if (ghostty_terminal_selection_ordered(
                c->term, &sel, GHOSTTY_SELECTION_ORDER_FORWARD, &fwd) ==
            GHOSTTY_SUCCESS) {
            meta[9] = SEL_ACTIVE;
            GhosttyPointCoordinate pc = {0};
            if (ghostty_terminal_point_from_grid_ref(
                    c->term, &fwd.start, GHOSTTY_POINT_TAG_VIEWPORT, &pc) ==
                GHOSTTY_SUCCESS) {
                meta[9] |= SEL_START_VISIBLE;
                meta[10] = pc.x;
                meta[11] = (jint)pc.y;
            }
            if (ghostty_terminal_point_from_grid_ref(
                    c->term, &fwd.end, GHOSTTY_POINT_TAG_VIEWPORT, &pc) ==
                GHOSTTY_SUCCESS) {
                meta[9] |= SEL_END_VISIBLE;
                meta[12] = pc.x;
                meta[13] = (jint)pc.y;
            }
        }
    }

    /* Input-mode flags for the rich-keyboard layer. Any alt-screen variant or
     * application-cursor-keys means a full-screen / raw-key app is running, so
     * the IME should fall back to forwarding raw keys. */
    bool mode = false;
    if ((ghostty_terminal_mode_get(c->term, GHOSTTY_MODE_ALT_SCREEN_SAVE, &mode)
             == GHOSTTY_SUCCESS && mode)
        || (ghostty_terminal_mode_get(c->term, GHOSTTY_MODE_ALT_SCREEN, &mode)
             == GHOSTTY_SUCCESS && mode)
        || (ghostty_terminal_mode_get(c->term, GHOSTTY_MODE_ALT_SCREEN_LEGACY, &mode)
             == GHOSTTY_SUCCESS && mode)) {
        meta[14] |= INPUT_MODE_ALT_SCREEN;
    }
    if (ghostty_terminal_mode_get(c->term, GHOSTTY_MODE_DECCKM, &mode)
            == GHOSTTY_SUCCESS && mode) {
        meta[14] |= INPUT_MODE_APP_CURSOR;
    }
    if (ghostty_terminal_mode_get(c->term, GHOSTTY_MODE_BRACKETED_PASTE, &mode)
            == GHOSTTY_SUCCESS && mode) {
        meta[14] |= INPUT_MODE_BRACKETED_PASTE;
    }
    /* Any mouse tracking mode active → the view reports gestures as mouse
     * events. One cheap query covers X10/normal/button/any-event. */
    bool mouse = false;
    if (ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,
                             &mouse) == GHOSTTY_SUCCESS && mouse) {
        meta[14] |= INPUT_MODE_MOUSE;
    }

    (*env)->SetIntArrayRegion(env, jmeta, 0, 16, meta);

    /* Grapheme overflow buffer. Slot 0 carries the record-int count needed;
     * default it to 0 so the early returns below leave a defined "no clusters"
     * value. Records (written only when cells are produced) occupy slots
     * [1 .. gcap-1], so the record capacity is gcap-1. */
    jsize gcap = jgraphemes ? (*env)->GetArrayLength(env, jgraphemes) : 0;
    if (gcap >= 1) {
        jint zero = 0;
        (*env)->SetIntArrayRegion(env, jgraphemes, 0, 1, &zero);
    }

    jint ret = ((jint)cols << 16) | rows;
    size_t ncells = (size_t)cols * rows;
    if (ncells == 0 || (size_t)(*env)->GetArrayLength(env, jcp) < ncells)
        return ret;

    /* Staging buffer for grapheme records; one bulk copy into jgraphemes at
     * the end beats a JNI region call per cluster. cap_rec is the record-int
     * capacity (gcap-1). On OOM we disable collection and report 0 clusters
     * (rather than leaving slot 0 demanding a grow that can't be satisfied,
     * which would loop the caller forever). */
    size_t cap_rec = gcap >= 1 ? (size_t)(gcap - 1) : 0;
    jint *grec = NULL;
    bool g_disabled = false;
    if (cap_rec > 0) {
        grec = malloc(cap_rec * sizeof(jint));
        if (!grec) {
            g_disabled = true;
            cap_rec = 0;
        }
    }
    size_t g_used = 0, g_needed = 0;
    bool g_overflow = false;

    jint *row_cp = malloc(cols * sizeof(jint));
    jint *row_fg = malloc(cols * sizeof(jint));
    jint *row_bg = malloc(cols * sizeof(jint));
    jint *row_attr = malloc(cols * sizeof(jint));
    if (!row_cp || !row_fg || !row_bg || !row_attr) goto done;

    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                             &c->row_iter);
    int y = 0;
    while (ghostty_render_state_row_iterator_next(c->row_iter) && y < rows) {
        ghostty_render_state_row_get(
            c->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &c->cells);
        /* The row-level grapheme and hyperlink flags let ordinary rows skip
         * the matching per-cell probes entirely — the overwhelmingly common
         * case. Both hang off the same raw row, so fetch it once. */
        bool row_has_graphemes = false;
        bool row_has_links = false;
        {
            GhosttyRow raw_row = 0;
            if (ghostty_render_state_row_get(
                    c->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_RAW, &raw_row) ==
                GHOSTTY_SUCCESS) {
                if (!g_disabled)
                    ghostty_row_get(raw_row, GHOSTTY_ROW_DATA_GRAPHEME,
                                    &row_has_graphemes);
                /* May false-positive; the per-cell probe below confirms. */
                ghostty_row_get(raw_row, GHOSTTY_ROW_DATA_HYPERLINK,
                                &row_has_links);
            }
        }
        GhosttyRenderStateRowSelection rsel =
            GHOSTTY_INIT_SIZED(GhosttyRenderStateRowSelection);
        bool row_selected =
            ghostty_render_state_row_get(
                c->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION, &rsel) ==
            GHOSTTY_SUCCESS;
        int x = 0;
        while (ghostty_render_state_row_cells_next(c->cells) && x < cols) {
            GhosttyCell cell = 0;
            uint32_t cp = 0;
            GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
            ghostty_render_state_row_cells_get(
                c->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &cell);
            ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &cp);
            ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
            if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL ||
                wide == GHOSTTY_CELL_WIDE_SPACER_HEAD)
                cp = 0;

            /* Collect a multi-codepoint grapheme cluster for this cell. Only
             * real cells (cp != 0, i.e. not a spacer) in a grapheme-bearing
             * row are probed. The base codepoint stays in row_cp; the full
             * cluster (base + combining/ZWJ codepoints) is staged for the
             * renderer. g_needed always tracks the true size so the caller can
             * grow on overflow even when nothing fit this pass. */
            if (row_has_graphemes && cp != 0) {
                uint32_t glen = 0;
                ghostty_render_state_row_cells_get(
                    c->cells,
                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
                if (glen > 1) {
                    size_t recsize = (size_t)2 + glen;
                    g_needed += recsize;
                    if (!g_overflow && g_used + recsize <= cap_rec) {
                        uint32_t gstack[32];
                        uint32_t *gbuf = glen <= 32
                                             ? gstack
                                             : malloc((size_t)glen *
                                                      sizeof(uint32_t));
                        if (gbuf) {
                            ghostty_render_state_row_cells_get(
                                c->cells,
                                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                                gbuf);
                            grec[g_used++] = (jint)((size_t)y * cols + x);
                            grec[g_used++] = (jint)glen;
                            for (uint32_t k = 0; k < glen; k++)
                                grec[g_used++] = (jint)gbuf[k];
                            if (gbuf != gstack) free(gbuf);
                        } else {
                            g_overflow = true; /* don't undercount g_needed */
                        }
                    } else {
                        g_overflow = true;
                    }
                }
            }

            GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
            bool has_styling = false;
            ghostty_render_state_row_cells_get(
                c->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING,
                &has_styling);
            if (has_styling)
                ghostty_render_state_row_cells_get(
                    c->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                    &style);

            GhosttyColorRgb rgb;
            jint fg = ghostty_render_state_row_cells_get(
                          c->cells,
                          GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
                          &rgb) == GHOSTTY_SUCCESS
                          ? pack_rgb(rgb)
                          : meta[8];
            jint bg = ghostty_render_state_row_cells_get(
                          c->cells,
                          GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
                          &rgb) == GHOSTTY_SUCCESS
                          ? pack_rgb(rgb)
                          : meta[7];
            if (style.inverse) {
                jint tmp = fg;
                fg = bg;
                bg = tmp;
            }
            if (style.invisible) fg = bg;
            if (style.faint)
                fg = (jint)((0xFF000000u) | (((fg >> 16 & 0xFF) / 2) << 16) |
                            (((fg >> 8 & 0xFF) / 2) << 8) | ((fg & 0xFF) / 2));
            if (row_selected && x >= rsel.start_x && x <= rsel.end_x) {
                jint tmp = fg;
                fg = bg;
                bg = tmp;
            }

            jint attr = 0;
            if (style.bold) attr |= ATTR_BOLD;
            if (style.italic) attr |= ATTR_ITALIC;
            if (style.underline)
                attr |= ATTR_UNDERLINE | ((style.underline & 7) << ATTR_UL_SHIFT);
            if (style.strikethrough) attr |= ATTR_STRIKE;
            if (wide == GHOSTTY_CELL_WIDE_WIDE) attr |= ATTR_WIDE;
            if (style.blink) attr |= ATTR_BLINK;
            if (row_has_links) {
                bool cell_link = false;
                if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_HYPERLINK,
                                     &cell_link) == GHOSTTY_SUCCESS && cell_link)
                    attr |= ATTR_HYPERLINK;
            }

            row_cp[x] = (jint)cp;
            row_fg[x] = fg;
            row_bg[x] = bg;
            row_attr[x] = attr;
            x++;
        }
        for (; x < cols; x++) {
            bool selected = row_selected && x >= rsel.start_x && x <= rsel.end_x;
            row_cp[x] = 0;
            row_fg[x] = selected ? meta[7] : meta[8];
            row_bg[x] = selected ? meta[8] : meta[7];
            row_attr[x] = 0;
        }
        jsize off = (jsize)y * cols;
        (*env)->SetIntArrayRegion(env, jcp, off, cols, row_cp);
        (*env)->SetIntArrayRegion(env, jfg, off, cols, row_fg);
        (*env)->SetIntArrayRegion(env, jbg, off, cols, row_bg);
        (*env)->SetIntArrayRegion(env, jattrs, off, cols, row_attr);
        y++;
    }

done:
    /* Publish the grapheme records: slot 0 = ints needed (0 when disabled,
     * so the caller never demands an unsatisfiable grow), then the staged
     * records. On overflow g_needed > cap_rec and the caller grows + retries. */
    if (gcap >= 1) {
        jint needed = g_disabled ? 0 : (jint)g_needed;
        (*env)->SetIntArrayRegion(env, jgraphemes, 0, 1, &needed);
        if (g_used > 0)
            (*env)->SetIntArrayRegion(env, jgraphemes, 1, (jsize)g_used, grec);
    }
    free(grec);
    free(row_cp);
    free(row_fg);
    free(row_bg);
    free(row_attr);
    return ret;
}

/*
 * Kitty graphics. The VT engine parses and stores images/placements; these
 * calls read them back out for the Canvas renderer.
 *
 * All handles and pixel pointers are borrowed and invalidated by the next
 * mutating terminal call, so each function consumes them within one JNI call
 * with no feed() in between (the same discipline as the selection code).
 */

/* Writes one GFX_STRIDE-wide placement record at index idx, if it fits. The
 * caller counts records regardless of cap and grows/retries on overflow. */
static void gfx_emit(JNIEnv *env, jintArray jout, jint cap, jint idx,
                     jint image_id, jint iw, jint ih, jint col, jint row,
                     jint pw, jint ph, jint sx, jint sy, jint sw, jint sh,
                     jint z, jint ox, jint oy) {
    if (idx >= cap) return;
    jint rec[GFX_STRIDE] = {image_id, iw, ih, col, row, pw,  ph,
                            sx,       sy, sw, sh,  z,   ox, oy};
    (*env)->SetIntArrayRegion(env, jout, idx * GFX_STRIDE, GFX_STRIDE, rec);
}

/* A virtual placement collected in pass 1; positioned later by placeholders. */
typedef struct {
    uint32_t image_id;
    uint32_t placement_id;
    uint32_t rows;
    uint32_t cols;
} VPlace;
#define MAX_VPLACE 32

/* The placement bits decoded from one placeholder cell (U+10EEEE). */
typedef struct {
    uint32_t id_low;
    uint32_t pid, high, row, col;
    bool has_pid, has_high, has_row, has_col;
} PHCell;

/* A run of horizontally adjacent placeholder cells that share an image and
 * continue the same fragment row with increasing columns. */
typedef struct {
    bool active;
    uint32_t id_low, pid, high, row, col, width;
    bool has_pid, has_high;
    int start_x;
} PHRun;

static void decode_placeholder(GhosttyRenderStateRowCells cells, PHCell *out) {
    memset(out, 0, sizeof(*out));

    GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
    bool has_styling = false;
    ghostty_render_state_row_cells_get(
        cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &has_styling);
    if (has_styling) {
        ghostty_render_state_row_cells_get(
            cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
        out->id_low = kitty_color_to_id(style.fg_color);
        uint32_t pid = kitty_color_to_id(style.underline_color);
        if (pid != 0) {
            out->pid = pid;
            out->has_pid = true;
        }
    }

    /* Row, column, and the image-id high byte come from up to three rowcolumn
     * diacritics that follow the base placeholder codepoint. Invalid ones are
     * treated as absent, which lets them continue a previous placement. */
    uint32_t glen = 0;
    ghostty_render_state_row_cells_get(
        cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
    if (glen <= 1) return;
    uint32_t gstack[16];
    uint32_t *gbuf =
        glen <= 16 ? gstack : malloc((size_t)glen * sizeof(uint32_t));
    if (!gbuf) return;
    ghostty_render_state_row_cells_get(
        cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, gbuf);

    int ri = kitty_diacritic_index(gbuf[1]);
    if (ri >= 0) {
        out->row = (uint32_t)ri;
        out->has_row = true;
    }
    if (glen > 2) {
        int ci = kitty_diacritic_index(gbuf[2]);
        if (ci >= 0) {
            out->col = (uint32_t)ci;
            out->has_col = true;
        }
        if (glen > 3) {
            int hi = kitty_diacritic_index(gbuf[3]);
            if (hi >= 0 && hi <= 255) {
                out->high = (uint32_t)hi;
                out->has_high = true;
            }
        }
    }
    if (gbuf != gstack) free(gbuf);
}

static void run_start(PHRun *run, const PHCell *ph, int x) {
    run->active = true;
    run->id_low = ph->id_low;
    run->has_pid = ph->has_pid;
    run->pid = ph->pid;
    run->has_high = ph->has_high;
    run->high = ph->high;
    run->row = ph->has_row ? ph->row : 0;
    run->col = ph->has_col ? ph->col : 0;
    run->width = 1;
    run->start_x = x;
}

static bool run_can_append(const PHRun *run, const PHCell *ph) {
    return run->id_low == ph->id_low &&
           run->has_pid == ph->has_pid && (!run->has_pid || run->pid == ph->pid) &&
           (!ph->has_row || ph->row == run->row) &&
           (!ph->has_col || ph->col == run->col + run->width) &&
           (!ph->has_high || (run->has_high && ph->high == run->high));
}

/* Resolves a completed run to its virtual placement, computes its source rect
 * and destination geometry, and emits a record. Returns 1 if a record was
 * counted (it maps to a known placement and isn't fully clipped), else 0. */
static jint run_emit(JNIEnv *env, jintArray jout, jint cap, jint idx,
                     GhosttyKittyGraphics gfx, const VPlace *vplaces, int nv,
                     uint32_t cell_w, uint32_t cell_h, const PHRun *run, int y) {
    uint32_t image_id = run->id_low | (run->has_high ? (run->high << 24) : 0);
    uint32_t placement_id = run->has_pid ? run->pid : 0;

    const VPlace *vp = NULL;
    for (int i = 0; i < nv; i++) {
        if (vplaces[i].image_id != image_id) continue;
        if (placement_id == 0 || vplaces[i].placement_id == placement_id) {
            vp = &vplaces[i];
            break;
        }
    }
    if (!vp) return 0;

    GhosttyKittyGraphicsImage img = ghostty_kitty_graphics_image(gfx, image_id);
    if (!img) return 0;
    uint32_t iw = 0, ih = 0;
    ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &iw);
    ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &ih);
    if (iw == 0 || ih == 0) return 0;

    uint32_t grid_rows = vp->rows, grid_cols = vp->cols;
    if (grid_rows == 0) grid_rows = (ih + cell_h - 1) / cell_h;
    if (grid_cols == 0) grid_cols = (iw + cell_w - 1) / cell_w;

    KittyVirtualRender r;
    if (!kitty_virtual_render(iw, ih, grid_rows, grid_cols, cell_w, cell_h,
                              run->row, run->col, run->width, 1, &r))
        return 0;

    gfx_emit(env, jout, cap, idx, (jint)image_id, (jint)iw, (jint)ih,
             run->start_x, y, (jint)r.dest_width, (jint)r.dest_height,
             (jint)r.source_x, (jint)r.source_y, (jint)r.source_width,
             (jint)r.source_height, 0, (jint)r.offset_x, (jint)r.offset_y);
    return 1;
}

/*
 * terminalGraphics copies geometry for every visible placement into jout as
 * GFX_STRIDE ints each (see TerminalNative.GFX_*). It returns the placement
 * count; if jout can't hold them all, only those that fit are written and the
 * caller retries with a larger array — same contract as terminalSnapshot.
 *
 * Direct placements come straight from storage. Virtual placements
 * (unicode placeholders) have no position of their own: pass 1 collects them,
 * then pass 2 scans the viewport for placeholder cells, groups them into runs,
 * and emits one record per run with the matching image fragment.
 */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalGraphics(
    JNIEnv *env, jclass clazz, jlong h, jintArray jout) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    if (!c->graphics_iter) return 0;

    /* Reads the terminal into the render state for the pass-2 cell scan; not
     * a mutating call, so the borrowed graphics handle below stays valid. */
    ghostty_render_state_update(c->rs, c->term);

    GhosttyKittyGraphics gfx = NULL;
    if (ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                             &gfx) != GHOSTTY_SUCCESS)
        return 0;

    jsize cap = (*env)->GetArrayLength(env, jout) / GFX_STRIDE;
    jint n = 0;

    /* Pass 1: direct placements emit immediately; virtual ones are stashed. */
    VPlace vplaces[MAX_VPLACE];
    int nv = 0;
    if (ghostty_kitty_graphics_get(
            gfx, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR,
            &c->graphics_iter) == GHOSTTY_SUCCESS) {
        while (ghostty_kitty_graphics_placement_next(c->graphics_iter)) {
            uint32_t image_id = 0;
            if (ghostty_kitty_graphics_placement_get(
                    c->graphics_iter,
                    GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID,
                    &image_id) != GHOSTTY_SUCCESS)
                continue;

            bool is_virtual = false;
            ghostty_kitty_graphics_placement_get(
                c->graphics_iter,
                GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_VIRTUAL, &is_virtual);
            if (is_virtual) {
                if (nv < MAX_VPLACE) {
                    VPlace *vp = &vplaces[nv++];
                    vp->image_id = image_id;
                    vp->placement_id = 0;
                    vp->rows = 0;
                    vp->cols = 0;
                    ghostty_kitty_graphics_placement_get(
                        c->graphics_iter,
                        GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID,
                        &vp->placement_id);
                    ghostty_kitty_graphics_placement_get(
                        c->graphics_iter,
                        GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_ROWS, &vp->rows);
                    ghostty_kitty_graphics_placement_get(
                        c->graphics_iter,
                        GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_COLUMNS,
                        &vp->cols);
                }
                continue;
            }

            GhosttyKittyGraphicsImage img =
                ghostty_kitty_graphics_image(gfx, image_id);
            if (!img) continue;
            GhosttyKittyGraphicsPlacementRenderInfo ri =
                GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsPlacementRenderInfo);
            if (ghostty_kitty_graphics_placement_render_info(
                    c->graphics_iter, img, c->term, &ri) != GHOSTTY_SUCCESS)
                continue;
            if (!ri.viewport_visible) continue;

            uint32_t iw = 0, ih = 0;
            ghostty_kitty_graphics_image_get(
                img, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &iw);
            ghostty_kitty_graphics_image_get(
                img, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &ih);
            int32_t z = 0;
            ghostty_kitty_graphics_placement_get(
                c->graphics_iter, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z, &z);
            uint32_t xo = 0, yo = 0;
            ghostty_kitty_graphics_placement_get(
                c->graphics_iter,
                GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET, &xo);
            ghostty_kitty_graphics_placement_get(
                c->graphics_iter,
                GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET, &yo);

            gfx_emit(env, jout, cap, n, (jint)image_id, (jint)iw, (jint)ih,
                     ri.viewport_col, ri.viewport_row, (jint)ri.pixel_width,
                     (jint)ri.pixel_height, (jint)ri.source_x,
                     (jint)ri.source_y, (jint)ri.source_width,
                     (jint)ri.source_height, (jint)z, (jint)xo, (jint)yo);
            n++;
        }
    }

    /* Pass 2: scan the viewport for placeholder runs of the virtual images. */
    if (nv == 0 || c->cell_w == 0 || c->cell_h == 0) return n;

    uint16_t cols = 0, rows = 0;
    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                             &c->row_iter);
    int y = 0;
    while (ghostty_render_state_row_iterator_next(c->row_iter) && y < rows) {
        ghostty_render_state_row_get(
            c->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &c->cells);
        PHRun run = {0};
        int x = 0;
        while (ghostty_render_state_row_cells_next(c->cells) && x < cols) {
            GhosttyCell cell = 0;
            uint32_t cp = 0;
            ghostty_render_state_row_cells_get(
                c->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &cell);
            ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &cp);

            if (cp != KITTY_PLACEHOLDER) {
                if (run.active) {
                    n += run_emit(env, jout, cap, n, gfx, vplaces, nv,
                                  c->cell_w, c->cell_h, &run, y);
                    run.active = false;
                }
                x++;
                continue;
            }

            PHCell ph;
            decode_placeholder(c->cells, &ph);
            if (!run.active) {
                run_start(&run, &ph, x);
            } else if (run_can_append(&run, &ph)) {
                run.width++;
            } else {
                n += run_emit(env, jout, cap, n, gfx, vplaces, nv, c->cell_w,
                              c->cell_h, &run, y);
                run_start(&run, &ph, x);
            }
            x++;
        }
        if (run.active) {
            n += run_emit(env, jout, cap, n, gfx, vplaces, nv, c->cell_w,
                          c->cell_h, &run, y);
        }
        y++;
    }
    return n;
}

/*
 * Returns a stored Kitty image's pixels as tightly packed RGBA8888 (one byte
 * each R,G,B,A — the in-memory order of Android's ARGB_8888), writing width
 * to wh[0] and height to wh[1]. Null if the image is missing, too large to
 * marshal, or in an unexpected format. Stored images are always decompressed
 * and PNG-decoded by libghostty-vt, so the format is gray/gray+alpha/rgb/rgba.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalImage(
    JNIEnv *env, jclass clazz, jlong h, jint image_id, jintArray jwh) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;

    GhosttyKittyGraphics gfx = NULL;
    if (ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                             &gfx) != GHOSTTY_SUCCESS)
        return NULL;
    GhosttyKittyGraphicsImage img =
        ghostty_kitty_graphics_image(gfx, (uint32_t)image_id);
    if (!img) return NULL;

    uint32_t w = 0, ht = 0;
    GhosttyKittyImageFormat fmt = GHOSTTY_KITTY_IMAGE_FORMAT_RGBA;
    const uint8_t *data = NULL;
    size_t len = 0;
    ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &w);
    ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &ht);
    ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_IMAGE_DATA_FORMAT, &fmt);
    ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR,
                                     &data);
    ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN,
                                     &len);
    if (!data || w == 0 || ht == 0) return NULL;

    size_t bpp;
    switch (fmt) {
    case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY: bpp = 1; break;
    case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA: bpp = 2; break;
    case GHOSTTY_KITTY_IMAGE_FORMAT_RGB: bpp = 3; break;
    case GHOSTTY_KITTY_IMAGE_FORMAT_RGBA: bpp = 4; break;
    default: return NULL; /* PNG should already be decoded; reject the rest */
    }
    size_t npx = (size_t)w * ht;
    if (len < npx * bpp) return NULL;

    size_t out_len = npx * 4;
    if (out_len > (size_t)INT32_MAX) return NULL;
    uint8_t *rgba = malloc(out_len);
    if (!rgba) return NULL;
    const uint8_t *s = data;
    uint8_t *d = rgba;
    for (size_t i = 0; i < npx; i++) {
        uint8_t r, g, b, a;
        switch (bpp) {
        case 1: r = g = b = s[0]; a = 255; break;
        case 2: r = g = b = s[0]; a = s[1]; break;
        case 3: r = s[0]; g = s[1]; b = s[2]; a = 255; break;
        default: r = s[0]; g = s[1]; b = s[2]; a = s[3]; break;
        }
        d[0] = r; d[1] = g; d[2] = b; d[3] = a;
        s += bpp;
        d += 4;
    }

    jbyteArray out = (*env)->NewByteArray(env, (jsize)out_len);
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)out_len,
                                   (const jbyte *)rgba);
        jint wh[2] = {(jint)w, (jint)ht};
        (*env)->SetIntArrayRegion(env, jwh, 0, 2, wh);
    }
    free(rgba);
    return out;
}

/* Maps Android KeyEvent keycodes to GhosttyKey. Unmapped keys return
 * UNIDENTIFIED, which the encoder handles via the event's utf8 text. */
static GhosttyKey map_keycode(jint code) {
    if (code >= AKEYCODE_A && code <= AKEYCODE_Z)
        return GHOSTTY_KEY_A + (code - AKEYCODE_A);
    if (code >= AKEYCODE_0 && code <= AKEYCODE_9)
        return GHOSTTY_KEY_DIGIT_0 + (code - AKEYCODE_0);
    if (code >= AKEYCODE_F1 && code <= AKEYCODE_F12)
        return GHOSTTY_KEY_F1 + (code - AKEYCODE_F1);
    switch (code) {
    case AKEYCODE_DPAD_UP: return GHOSTTY_KEY_ARROW_UP;
    case AKEYCODE_DPAD_DOWN: return GHOSTTY_KEY_ARROW_DOWN;
    case AKEYCODE_DPAD_LEFT: return GHOSTTY_KEY_ARROW_LEFT;
    case AKEYCODE_DPAD_RIGHT: return GHOSTTY_KEY_ARROW_RIGHT;
    case AKEYCODE_ENTER:
    case AKEYCODE_NUMPAD_ENTER: return GHOSTTY_KEY_ENTER;
    case AKEYCODE_DEL: return GHOSTTY_KEY_BACKSPACE;
    case AKEYCODE_FORWARD_DEL: return GHOSTTY_KEY_DELETE;
    case AKEYCODE_ESCAPE: return GHOSTTY_KEY_ESCAPE;
    case AKEYCODE_TAB: return GHOSTTY_KEY_TAB;
    case AKEYCODE_SPACE: return GHOSTTY_KEY_SPACE;
    case AKEYCODE_MOVE_HOME: return GHOSTTY_KEY_HOME;
    case AKEYCODE_MOVE_END: return GHOSTTY_KEY_END;
    case AKEYCODE_PAGE_UP: return GHOSTTY_KEY_PAGE_UP;
    case AKEYCODE_PAGE_DOWN: return GHOSTTY_KEY_PAGE_DOWN;
    case AKEYCODE_INSERT: return GHOSTTY_KEY_INSERT;
    case AKEYCODE_MINUS: return GHOSTTY_KEY_MINUS;
    case AKEYCODE_EQUALS: return GHOSTTY_KEY_EQUAL;
    case AKEYCODE_LEFT_BRACKET: return GHOSTTY_KEY_BRACKET_LEFT;
    case AKEYCODE_RIGHT_BRACKET: return GHOSTTY_KEY_BRACKET_RIGHT;
    case AKEYCODE_BACKSLASH: return GHOSTTY_KEY_BACKSLASH;
    case AKEYCODE_SEMICOLON: return GHOSTTY_KEY_SEMICOLON;
    case AKEYCODE_APOSTROPHE: return GHOSTTY_KEY_QUOTE;
    case AKEYCODE_GRAVE: return GHOSTTY_KEY_BACKQUOTE;
    case AKEYCODE_COMMA: return GHOSTTY_KEY_COMMA;
    case AKEYCODE_PERIOD: return GHOSTTY_KEY_PERIOD;
    case AKEYCODE_SLASH: return GHOSTTY_KEY_SLASH;
    default: return GHOSTTY_KEY_UNIDENTIFIED;
    }
}

/*
 * Encodes a key press into the byte sequence for the PTY, honoring current
 * terminal modes (cursor-key application mode, kitty keyboard, …).
 *
 * mods: GHOSTTY_MODS_* bits (TerminalNative.MOD_*). utf8: text the key
 * produces, or null. Returns null when the key encodes to nothing.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalEncodeKey(
    JNIEnv *env, jclass clazz, jlong h, jint keycode, jint mods, jstring jutf8,
    jint unshifted_cp) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;

    ghostty_key_encoder_setopt_from_terminal(c->encoder, c->term);

    ghostty_key_event_set_action(c->kev, GHOSTTY_KEY_ACTION_PRESS);
    ghostty_key_event_set_key(c->kev, map_keycode(keycode));
    ghostty_key_event_set_mods(c->kev, (GhosttyMods)mods);
    ghostty_key_event_set_consumed_mods(c->kev, 0);
    ghostty_key_event_set_composing(c->kev, false);
    ghostty_key_event_set_unshifted_codepoint(c->kev,
                                              (uint32_t)unshifted_cp);

    const char *utf8 = NULL;
    if (jutf8) {
        utf8 = (*env)->GetStringUTFChars(env, jutf8, NULL);
        ghostty_key_event_set_utf8(c->kev, utf8, strlen(utf8));
    } else {
        ghostty_key_event_set_utf8(c->kev, NULL, 0);
    }

    char buf[128];
    size_t len = 0;
    GhosttyResult res =
        ghostty_key_encoder_encode(c->encoder, c->kev, buf, sizeof(buf), &len);

    if (utf8) (*env)->ReleaseStringUTFChars(env, jutf8, utf8);
    if (res != GHOSTTY_SUCCESS || len == 0) return NULL;

    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)buf);
    return out;
}

/*
 * Encodes a single mouse event into the byte sequence for the PTY, honoring
 * the terminal's active tracking mode and output format (SGR, X10, …). The
 * caller drives gestures: a tap is a PRESS+RELEASE of MOUSE_BUTTON_LEFT; a
 * wheel notch is a PRESS of buttons 4/5 (vertical) or 6/7 (horizontal); a
 * long-press drag is a PRESS, then MOTION events with button_held set, then a
 * RELEASE.
 *
 * action/button are GhosttyMouseAction / GhosttyMouseButton values (mirrored
 * in TerminalNative.MOUSE_*). x/y are surface pixels relative to the cell-grid
 * origin (the view subtracts its left text margin first). button_held tells
 * the encoder a button is down, so MOTION gets reported in button-event
 * tracking mode (1002); it is set per call to stay stateless across the reused
 * encoder. Returns null when the event encodes to nothing — including when no
 * tracking mode is active (so a stale "mouse on" snapshot can't emit stray
 * bytes) and when MOTION wouldn't be reported in the current mode.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalEncodeMouse(
    JNIEnv *env, jclass clazz, jlong h, jint action, jint button, jfloat x,
    jfloat y, jboolean button_held) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    if (!c->mouse_encoder || !c->mouse_event) return NULL;

    /* Tracking mode + format come from the terminal; geometry from the cached
     * cell size kept current on resize. cell_w/h are 0 only before the first
     * resize (which always precedes any program), but guard against the divide
     * either way. */
    ghostty_mouse_encoder_setopt_from_terminal(c->mouse_encoder, c->term);
    GhosttyMouseEncoderSize size = {
        .size = sizeof(GhosttyMouseEncoderSize),
        .screen_width = (uint32_t)c->cols * c->cell_w,
        .screen_height = (uint32_t)c->rows * c->cell_h,
        .cell_width = c->cell_w ? c->cell_w : 1,
        .cell_height = c->cell_h ? c->cell_h : 1,
        .padding_top = 0,
        .padding_bottom = 0,
        .padding_right = 0,
        .padding_left = 0,
    };
    ghostty_mouse_encoder_setopt(c->mouse_encoder,
                                 GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
    bool held = button_held == JNI_TRUE;
    ghostty_mouse_encoder_setopt(c->mouse_encoder,
                                 GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                                 &held);

    GhosttyMousePosition pos = {.x = (float)x, .y = (float)y};
    ghostty_mouse_event_set_action(c->mouse_event, (GhosttyMouseAction)action);
    ghostty_mouse_event_set_button(c->mouse_event, (GhosttyMouseButton)button);
    ghostty_mouse_event_set_position(c->mouse_event, pos);
    ghostty_mouse_event_set_mods(c->mouse_event, 0);

    char buf[64];
    size_t len = 0;
    GhosttyResult res = ghostty_mouse_encoder_encode(
        c->mouse_encoder, c->mouse_event, buf, sizeof(buf), &len);
    if (res != GHOSTTY_SUCCESS || len == 0) return NULL;

    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)buf);
    return out;
}

/*
 * Selection.
 *
 * The selection lives in the terminal (GHOSTTY_TERMINAL_OPT_SELECTION):
 * Ghostty converts the installed snapshot to tracked grid refs, so it
 * stays glued to its text across scrolling, new output, and reflow.
 * The untracked refs handled below are always produced and consumed
 * within one JNI call with no terminal mutation in between, which
 * satisfies their lifetime rules.
 */

/* Resolves a viewport cell to a grid ref; false if out of bounds. */
static bool viewport_ref(TermCtx *c, jint x, jint y, GhosttyGridRef *out) {
    GhosttyPoint p = {
        .tag = GHOSTTY_POINT_TAG_VIEWPORT,
        .value.coordinate = {.x = (uint16_t)x, .y = (uint32_t)y},
    };
    *out = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    return ghostty_terminal_grid_ref(c->term, p, out) == GHOSTTY_SUCCESS;
}

/*
 * Selects the word under viewport cell (x, y) and installs it as the
 * terminal's active selection. A blank cell (no word) selects just that
 * cell so the UI still gets handles and a paste anchor. Returns false
 * only when the coordinates don't resolve.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSelectWord(
    JNIEnv *env, jclass clazz, jlong h, jint x, jint y) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyGridRef ref;
    if (!viewport_ref(c, x, y, &ref)) return JNI_FALSE;

    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyTerminalSelectWordOptions opts =
        GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
    opts.ref = ref;
    if (ghostty_terminal_select_word(c->term, &opts, &sel) != GHOSTTY_SUCCESS) {
        sel = GHOSTTY_INIT_SIZED(GhosttySelection);
        sel.start = ref;
        sel.end = ref;
    }
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
    return JNI_TRUE;
}

/*
 * Selects the whole (soft-wrap-joined) line under viewport cell (x, y) and
 * installs it as the active selection. A blank line (no selectable content)
 * falls back to just that cell, mirroring terminalSelectWord, so the gesture
 * always yields handles and a paste anchor. Returns false only when the
 * coordinates don't resolve.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSelectLine(
    JNIEnv *env, jclass clazz, jlong h, jint x, jint y) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyGridRef ref;
    if (!viewport_ref(c, x, y, &ref)) return JNI_FALSE;

    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyTerminalSelectLineOptions opts =
        GHOSTTY_INIT_SIZED(GhosttyTerminalSelectLineOptions);
    opts.ref = ref;
    if (ghostty_terminal_select_line(c->term, &opts, &sel) != GHOSTTY_SUCCESS) {
        sel = GHOSTTY_INIT_SIZED(GhosttySelection);
        sel.start = ref;
        sel.end = ref;
    }
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
    return JNI_TRUE;
}

/*
 * Selects all selectable terminal content (scrollback + active area) and
 * installs it as the active selection. Returns false when there is nothing
 * to select (an empty terminal).
 */
JNIEXPORT jboolean JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSelectAll(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_select_all(c->term, &sel) != GHOSTTY_SUCCESS)
        return JNI_FALSE;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
    return JNI_TRUE;
}

/*
 * Prepares a handle drag: reorders the active selection so the grabbed
 * visual endpoint (which: 0 = top-left, 1 = bottom-right) becomes the
 * logical end, which is what terminalSelectionDrag moves. The other
 * endpoint stays anchored for the whole drag, and dragging across it
 * flips the selection naturally.
 */
JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSelectionAnchor(
    JNIEnv *env, jclass clazz, jlong h, jint which) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) !=
        GHOSTTY_SUCCESS)
        return;
    GhosttySelection ordered = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttySelectionOrder want = which == 0 ? GHOSTTY_SELECTION_ORDER_REVERSE
                                            : GHOSTTY_SELECTION_ORDER_FORWARD;
    if (ghostty_terminal_selection_ordered(c->term, &sel, want, &ordered) !=
        GHOSTTY_SUCCESS)
        return;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, &ordered);
}

/* Moves the active selection's logical end to viewport cell (x, y). */
JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSelectionDrag(
    JNIEnv *env, jclass clazz, jlong h, jint x, jint y) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) !=
        GHOSTTY_SUCCESS)
        return;
    GhosttyGridRef ref;
    if (!viewport_ref(c, x, y, &ref)) return;
    sel.end = ref;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSelectionClear(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
}

/*
 * Returns the active selection as UTF-8 bytes (soft wraps unwrapped,
 * trailing whitespace trimmed — Ghostty's clipboard semantics), or null
 * when there is no selection. Bytes, not a jstring: NewStringUTF wants
 * modified UTF-8 and would mangle non-BMP characters.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSelectionText(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyTerminalSelectionFormatOptions opts =
        GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
    opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    opts.unwrap = true;
    opts.trim = true;

    uint8_t *buf = NULL;
    size_t len = 0;
    if (ghostty_terminal_selection_format_alloc(c->term, NULL, opts, &buf,
                                                &len) != GHOSTTY_SUCCESS)
        return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out)
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)buf);
    ghostty_free(NULL, buf, len);
    return out;
}

/*
 * Returns the OSC 8 hyperlink URI for viewport cell (x, y) as UTF-8 bytes,
 * or null when the cell has no hyperlink or the coordinates don't resolve.
 * Bytes, not a jstring: the URI is arbitrary UTF-8 and NewStringUTF expects
 * modified UTF-8. The untracked ref is produced and consumed within this one
 * call with no terminal mutation in between, satisfying its lifetime rules.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalHyperlinkAt(
    JNIEnv *env, jclass clazz, jlong h, jint x, jint y) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyGridRef ref;
    if (!viewport_ref(c, x, y, &ref)) return NULL;

    /* A NULL buffer probes the size: SUCCESS with len 0 means the cell has no
       hyperlink; OUT_OF_SPACE reports the byte count needed. */
    size_t len = 0;
    if (ghostty_grid_ref_hyperlink_uri(&ref, NULL, 0, &len) !=
            GHOSTTY_OUT_OF_SPACE ||
        len == 0)
        return NULL;

    uint8_t *buf = malloc(len);
    if (!buf) return NULL;
    size_t got = 0;
    jbyteArray out = NULL;
    if (ghostty_grid_ref_hyperlink_uri(&ref, buf, len, &got) ==
            GHOSTTY_SUCCESS &&
        got > 0) {
        out = (*env)->NewByteArray(env, (jsize)got);
        if (out)
            (*env)->SetByteArrayRegion(env, out, 0, (jsize)got,
                                       (const jbyte *)buf);
    }
    free(buf);
    return out;
}

/*
 * Encodes clipboard text for the PTY per current terminal state: strips
 * unsafe control bytes, wraps in bracketed-paste markers when mode 2004
 * is set, otherwise converts newlines to carriage returns.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalEncodePaste(
    JNIEnv *env, jclass clazz, jlong h, jbyteArray data) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    bool bracketed = false;
    ghostty_terminal_mode_get(c->term, GHOSTTY_MODE_BRACKETED_PASTE,
                              &bracketed);

    jsize len = (*env)->GetArrayLength(env, data);
    /* ghostty_paste_encode scrubs the input in place; work on a copy. */
    char *in = malloc(len ? (size_t)len : 1);
    if (!in) return NULL;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)in);

    size_t cap = (size_t)len + 16; /* room for the bracket markers */
    char *enc = malloc(cap);
    size_t written = 0;
    GhosttyResult res = enc
        ? ghostty_paste_encode(in, (size_t)len, bracketed, enc, cap, &written)
        : GHOSTTY_OUT_OF_MEMORY;
    if (res == GHOSTTY_OUT_OF_SPACE) {
        char *bigger = realloc(enc, written);
        if (bigger) {
            enc = bigger;
            res = ghostty_paste_encode(in, (size_t)len, bracketed, enc,
                                       written, &written);
        }
    }
    free(in);
    jbyteArray out = NULL;
    if (res == GHOSTTY_SUCCESS && written > 0) {
        out = (*env)->NewByteArray(env, (jsize)written);
        if (out)
            (*env)->SetByteArrayRegion(env, out, 0, (jsize)written,
                                       (const jbyte *)enc);
    }
    free(enc);
    return out;
}

/*
 * Text search.
 *
 * libghostty-vt has no built-in search, so we scan the whole active screen
 * (scrollback + active area) here and reuse the selection slot to highlight
 * and reveal the current hit. Matches are stored as screen-coordinate ranges;
 * navigation re-runs the scan from Java, so stale screen points across
 * intervening output are a non-issue and no per-match tracked refs are kept
 * (grid_ref.h flags those as costly per terminal mutation).
 *
 * Reading cells: one untracked row ref is resolved per screen row at column 0,
 * then the column is walked by setting ref.x — the documented grid-ref
 * traverse pattern (node identifies the row, x the column). Untracked refs are
 * produced and consumed with no terminal mutation in between, per their
 * lifetime rules (the same discipline as the selection helpers above).
 */

/* Decodes UTF-8 into codepoints, writing at most len entries (one byte is the
 * worst case). Invalid bytes are skipped. Returns the codepoint count. */
static size_t utf8_to_cp(const uint8_t *b, size_t len, uint32_t *out) {
    size_t n = 0, i = 0;
    while (i < len) {
        uint8_t c = b[i];
        uint32_t cp;
        int extra;
        if (c < 0x80) { cp = c; extra = 0; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; extra = 1; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; extra = 2; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; extra = 3; }
        else { i++; continue; }
        if (i + (size_t)extra >= len) break;
        bool ok = true;
        for (int k = 1; k <= extra; k++) {
            if ((b[i + k] & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (b[i + k] & 0x3F);
        }
        if (ok) { out[n++] = cp; i += (size_t)extra + 1; }
        else { i++; }
    }
    return n;
}

/*
 * Simple (1:1) case fold for case-insensitive matching. ASCII is folded
 * inline; other BMP letters fold via the generated CASE_FOLD table (Latin,
 * Greek, Cyrillic, ...). This is *simple* folding — no multi-character folds
 * (e.g. SHARP S stays as is), which keeps the one-codepoint-per-cell mapping
 * intact — and it folds to lowercase, so case-insensitive search is not
 * accent-insensitive (É == é, but é != e).
 */
static uint32_t fold_cp(uint32_t cp, bool case_sensitive) {
    if (case_sensitive) return cp;
    if (cp < 0x80) return (cp >= 'A' && cp <= 'Z') ? cp + 32 : cp;
    if (cp > 0xFFFF) return cp;
    size_t lo = 0, hi = CASE_FOLD_COUNT;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        uint16_t key = CASE_FOLD[mid][0];
        if (key < cp) lo = mid + 1;
        else if (key > cp) hi = mid;
        else return CASE_FOLD[mid][1];
    }
    return cp;
}

/* Reusable logical-line buffer: one entry per cell across soft-wrapped rows,
 * carrying the codepoint and its screen position. */
typedef struct {
    uint32_t *cp;
    uint16_t *cx;
    uint32_t *cy;
    size_t len, cap;
} LineBuf;

static bool linebuf_push(LineBuf *l, uint32_t cp, uint16_t x, uint32_t y) {
    if (l->len == l->cap) {
        size_t cap = l->cap ? l->cap * 2 : 256;
        uint32_t *ncp = realloc(l->cp, cap * sizeof(uint32_t));
        uint16_t *ncx = realloc(l->cx, cap * sizeof(uint16_t));
        uint32_t *ncy = realloc(l->cy, cap * sizeof(uint32_t));
        if (ncp) l->cp = ncp;
        if (ncx) l->cx = ncx;
        if (ncy) l->cy = ncy;
        if (!ncp || !ncx || !ncy) return false;
        l->cap = cap;
    }
    l->cp[l->len] = cp;
    l->cx[l->len] = x;
    l->cy[l->len] = y;
    l->len++;
    return true;
}

static void match_reverse(SearchMatch *a, size_t lo, size_t hi) {
    while (lo < hi) {
        SearchMatch t = a[lo];
        a[lo] = a[hi];
        a[hi] = t;
        lo++;
        hi--;
    }
}

/* Rotates a[0..n) left by k so a[k] becomes a[0] (three-reversal rotate). */
static void match_rotate_left(SearchMatch *a, size_t n, size_t k) {
    if (n == 0) return;
    k %= n;
    if (k == 0) return;
    match_reverse(a, 0, k - 1);
    match_reverse(a, k, n - 1);
    match_reverse(a, 0, n - 1);
}

/* Records one hit: always counts it, and when there is ring capacity stores it
 * in the slot for the most-recent window. A count is never dropped, so the
 * total stays honest even past the navigable cap. */
static void match_store(TermCtx *c, uint16_t sx, uint32_t sy, uint16_t ex,
                        uint32_t ey) {
    if (c->match_cap)
        c->matches[c->match_total % c->match_cap] = (SearchMatch){sx, sy, ex, ey};
    c->match_total++;
}

/* Searches one finished logical line for the query, recording every
 * non-overlapping hit. Trailing unwritten cells (codepoint 0) are ignored;
 * interior empty cells match as spaces. */
static void search_line(TermCtx *c, LineBuf *l, const uint32_t *q, size_t qlen,
                        bool cs) {
    size_t end = l->len;
    while (end > 0 && l->cp[end - 1] == 0) end--; /* trim trailing blanks */
    if (qlen == 0 || end < qlen) return;
    for (size_t i = 0; i + qlen <= end;) {
        bool hit = true;
        for (size_t j = 0; j < qlen; j++) {
            uint32_t a = l->cp[i + j];
            if (a == 0) a = ' ';
            if (fold_cp(a, cs) != fold_cp(q[j], cs)) { hit = false; break; }
        }
        if (hit) {
            size_t e = i + qlen - 1;
            match_store(c, l->cx[i], l->cy[i], l->cx[e], l->cy[e]);
            i += qlen;
        } else {
            i++;
        }
    }
}

/*
 * Rebuilds the match list for the stored query by scanning the whole active
 * screen. The ring keeps the most-recent hits; match_count/match_total are set
 * and the dirty flag is cleared. Does not touch match_index or the selection.
 */
static void run_search(TermCtx *c) {
    c->match_count = 0;
    c->match_total = 0;
    c->search_dirty = false;
    if (c->search_qlen == 0) return;

    /* Allocate the navigable ring once and reuse it across searches. */
    if (c->match_cap < MAX_MATCHES) {
        SearchMatch *m =
            realloc(c->matches, (size_t)MAX_MATCHES * sizeof(SearchMatch));
        if (m) {
            c->matches = m;
            c->match_cap = MAX_MATCHES;
        }
    }

    size_t total = 0;
    uint16_t cols = 0;
    ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total);
    ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_COLS, &cols);
    if (total == 0 || cols == 0) return;

    LineBuf line = {0};
    for (uint32_t y = 0; y < (uint32_t)total; y++) {
        GhosttyPoint p = {
            .tag = GHOSTTY_POINT_TAG_SCREEN,
            .value.coordinate = {.x = 0, .y = y},
        };
        GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        if (ghostty_terminal_grid_ref(c->term, p, &ref) != GHOSTTY_SUCCESS) {
            search_line(c, &line, c->search_q, c->search_qlen, c->search_cs);
            line.len = 0;
            continue;
        }
        bool wrap = false;
        GhosttyRow row = 0;
        if (ghostty_grid_ref_row(&ref, &row) == GHOSTTY_SUCCESS)
            ghostty_row_get(row, GHOSTTY_ROW_DATA_WRAP, &wrap);
        for (uint16_t x = 0; x < cols; x++) {
            ref.x = x;
            GhosttyCell cell = 0;
            uint32_t cp = 0;
            GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
            if (ghostty_grid_ref_cell(&ref, &cell) == GHOSTTY_SUCCESS) {
                ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &cp);
                ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
            }
            if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL ||
                wide == GHOSTTY_CELL_WIDE_SPACER_HEAD)
                continue;
            if (!linebuf_push(&line, cp, x, y)) { wrap = false; break; }
        }
        if (wrap) continue; /* soft-wrapped: the next row continues it */
        search_line(c, &line, c->search_q, c->search_qlen, c->search_cs);
        line.len = 0;
    }
    /* A trailing wrapped run with no terminating row still searches. */
    if (line.len > 0)
        search_line(c, &line, c->search_q, c->search_qlen, c->search_cs);
    free(line.cp);
    free(line.cx);
    free(line.cy);

    /* The ring holds the most recent min(total, cap) hits. When it wrapped,
     * rotate it so the navigable matches sit at [0, match_count) in screen
     * order; otherwise they are already in order. */
    c->match_count =
        c->match_total < c->match_cap ? c->match_total : c->match_cap;
    if (c->match_cap && c->match_total > c->match_cap)
        match_rotate_left(c->matches, c->match_cap,
                          c->match_total % c->match_cap);
}

/* The navigable hit nearest the viewport: the last one at or above the viewport
 * bottom (so the first jump lands near what the user is looking at), else the
 * first. Matches are sorted top-to-bottom. */
static size_t initial_index(TermCtx *c) {
    size_t idx = 0;
    GhosttyTerminalScrollbar sb = {0};
    ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
    uint64_t bottom = sb.offset + (sb.len ? sb.len - 1 : 0);
    for (size_t i = 0; i < c->match_count; i++) {
        if (c->matches[i].sy <= bottom) idx = i;
        else break;
    }
    return idx;
}

/*
 * Installs match_index as the active selection and scrolls it into view, or
 * clears the selection when there are no matches. Untracked refs are resolved
 * and consumed within this call with no terminal mutation in between.
 */
static void show_match(TermCtx *c) {
    if (c->match_count == 0 || c->match_index >= c->match_count) {
        ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
        return;
    }
    SearchMatch m = c->matches[c->match_index];

    GhosttyPoint ps = {.tag = GHOSTTY_POINT_TAG_SCREEN,
                       .value.coordinate = {.x = m.sx, .y = m.sy}};
    GhosttyPoint pe = {.tag = GHOSTTY_POINT_TAG_SCREEN,
                       .value.coordinate = {.x = m.ex, .y = m.ey}};
    GhosttyGridRef start = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyGridRef end = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(c->term, ps, &start) != GHOSTTY_SUCCESS ||
        ghostty_terminal_grid_ref(c->term, pe, &end) != GHOSTTY_SUCCESS)
        return;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    sel.start = start;
    sel.end = end;
    sel.rectangle = false;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);

    /* Scroll the match into view when it sits outside the viewport, leaving a
     * little context above it. Scrollbar coordinates are screen rows from the
     * top; delta is negative for up (into history). */
    GhosttyTerminalScrollbar sb = {0};
    ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
    long len = sb.len ? (long)sb.len : 1;
    long top = (long)sb.offset;
    long target = top;
    if ((long)m.sy < top || (long)m.sy > top + len - 1)
        target = (long)m.sy - len / 4;
    if (target < 0) target = 0;
    long max_top = (long)sb.total - len;
    if (max_top < 0) max_top = 0;
    if (target > max_top) target = max_top;
    long delta = target - top;
    if (delta != 0) {
        GhosttyTerminalScrollViewport sv = {.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA};
        sv.value.delta = (intptr_t)delta;
        ghostty_terminal_scroll_viewport(c->term, sv);
    }
}

/* 1-based position of the current match within the *total* hits — the navigable
 * window is the most-recent slice, so it is offset by the dropped older hits —
 * or 0 when there are none. */
static jint current_global(TermCtx *c) {
    if (c->match_count == 0) return 0;
    return (jint)((c->match_total - c->match_count) + c->match_index + 1);
}

/* out[0] = current match (1-based, 0 if none), out[1] = navigable count. */
static void write_result(JNIEnv *env, jintArray jout, TermCtx *c) {
    if (!jout) return;
    jint out2[2] = {current_global(c), (jint)c->match_count};
    (*env)->SetIntArrayRegion(env, jout, 0, 2, out2);
}

/*
 * Sets a new query, scans the buffer, and shows the match nearest the viewport.
 * Returns the total hit count (which may exceed the navigable window) and fills
 * out (see write_result). An empty query clears the search. Scan and show
 * happen in this one locked call, so they can't race with PTY output.
 */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSearchSet(
    JNIEnv *env, jclass clazz, jlong h, jbyteArray jquery,
    jboolean caseSensitive, jintArray jout) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;

    free(c->search_q);
    c->search_q = NULL;
    c->search_qlen = 0;
    c->search_cs = caseSensitive == JNI_TRUE;
    c->match_index = 0;

    jsize qbytes = (*env)->GetArrayLength(env, jquery);
    if (qbytes > 0) {
        c->search_q = malloc((size_t)qbytes * sizeof(uint32_t));
        uint8_t *qbuf = c->search_q ? malloc((size_t)qbytes) : NULL;
        if (qbuf) {
            (*env)->GetByteArrayRegion(env, jquery, 0, qbytes, (jbyte *)qbuf);
            c->search_qlen = utf8_to_cp(qbuf, (size_t)qbytes, c->search_q);
            free(qbuf);
        }
    }

    run_search(c);
    c->match_index = initial_index(c);
    show_match(c); /* installs the highlight, or clears it when no matches */
    write_result(env, jout, c);
    return (jint)c->match_total;
}

/*
 * Moves to the next (dir > 0) or previous (dir < 0) match, wrapping, and shows
 * it. Re-scans first only when the buffer changed since the last scan, so idle
 * navigation is cheap. Return value and out are as in terminalSearchSet.
 */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSearchStep(
    JNIEnv *env, jclass clazz, jlong h, jint dir, jintArray jout) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    if (c->search_qlen == 0) {
        write_result(env, jout, c);
        return 0;
    }
    if (c->search_dirty) {
        run_search(c);
        if (c->match_index >= c->match_count)
            c->match_index = c->match_count ? c->match_count - 1 : 0;
    }
    if (c->match_count > 0) {
        long n = (long)c->match_count;
        long i = ((long)c->match_index + dir) % n;
        if (i < 0) i += n;
        c->match_index = (size_t)i;
    }
    show_match(c);
    write_result(env, jout, c);
    return (jint)c->match_total;
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_terminalSearchClear(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    free(c->matches);
    c->matches = NULL;
    c->match_count = 0;
    c->match_cap = 0;
    c->match_total = 0;
    c->match_index = 0;
    free(c->search_q);
    c->search_q = NULL;
    c->search_qlen = 0;
    c->search_dirty = false;
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
}
