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

/* Event bits returned by feed(), see TerminalNative.EVENT_*. */
#define EVENT_BELL 1
#define EVENT_TITLE 2

/* Per-screen Kitty image storage cap. Non-zero enables the protocol; this
 * bounds memory on a phone while leaving room for a few full-screen images. */
#define KITTY_STORAGE_LIMIT_BYTES (64ull * 1024 * 1024)

/* Ints per placement record from terminalGraphics; mirror TerminalNative.GFX_*. */
#define GFX_STRIDE 14

typedef struct {
    GhosttyTerminal term;
    GhosttyRenderState rs;
    GhosttyRenderStateRowIterator row_iter;
    GhosttyRenderStateRowCells cells;
    GhosttyKeyEncoder encoder;
    GhosttyKeyEvent kev;
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

    int events;
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
static const GhosttySysDecodePngFn decode_png_fn = androidterm_decode_png;

JNIEXPORT jlong JNICALL
Java_dev_androidterm_term_TerminalNative_terminalNew(
    JNIEnv *env, jclass clazz, jint cols, jint rows, jint scrollback) {
    (void)env; (void)clazz;
    TermCtx *c = calloc(1, sizeof(TermCtx));
    if (!c) return 0;

    GhosttyTerminalOptions opts = {
        .cols = (uint16_t)cols,
        .rows = (uint16_t)rows,
        .max_scrollback = (size_t)scrollback,
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
Java_dev_androidterm_term_TerminalNative_terminalFree(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    if (!c) return;
    ghostty_kitty_graphics_placement_iterator_free(c->graphics_iter);
    ghostty_key_event_free(c->kev);
    ghostty_key_encoder_free(c->encoder);
    ghostty_render_state_row_cells_free(c->cells);
    ghostty_render_state_row_iterator_free(c->row_iter);
    ghostty_render_state_free(c->rs);
    ghostty_terminal_free(c->term);
    free(c->out);
    free(c);
}

/*
 * Feeds PTY output through the VT parser. Returns bytes the terminal wants
 * written back to the PTY (e.g. DA/DSR responses), or null if none.
 */
JNIEXPORT jbyteArray JNICALL
Java_dev_androidterm_term_TerminalNative_terminalFeed(
    JNIEnv *env, jclass clazz, jlong h, jbyteArray data, jint len) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    ghostty_terminal_vt_write(c->term, (const uint8_t *)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    if (c->out_len == 0) return NULL;
    jbyteArray resp = (*env)->NewByteArray(env, (jsize)c->out_len);
    (*env)->SetByteArrayRegion(env, resp, 0, (jsize)c->out_len,
                               (const jbyte *)c->out);
    c->out_len = 0;
    return resp;
}

/* Returns and clears accumulated EVENT_* bits. */
JNIEXPORT jint JNICALL
Java_dev_androidterm_term_TerminalNative_terminalEvents(
    JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    int e = c->events;
    c->events = 0;
    return e;
}

JNIEXPORT jstring JNICALL
Java_dev_androidterm_term_TerminalNative_terminalTitle(
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
Java_dev_androidterm_term_TerminalNative_terminalResize(
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
Java_dev_androidterm_term_TerminalNative_terminalScroll(
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
Java_dev_androidterm_term_TerminalNative_terminalScrollbar(
    JNIEnv *env, jclass clazz, jlong h, jintArray jout) {
    (void)clazz;
    TermCtx *c = (TermCtx *)(intptr_t)h;
    GhosttyTerminalScrollbar sb = {0};
    ghostty_terminal_get(c->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
    jint vals[3] = {(jint)sb.total, (jint)sb.offset, (jint)sb.len};
    (*env)->SetIntArrayRegion(env, jout, 0, 3, vals);
}

static jint pack_rgb(GhosttyColorRgb c) {
    return (jint)(0xFF000000u | ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) |
                  (uint32_t)c.b);
}

/* Attribute bits in the attrs[] snapshot array, mirrored in ScreenSnapshot. */
#define ATTR_BOLD 1
#define ATTR_ITALIC 2
#define ATTR_UNDERLINE 4
#define ATTR_STRIKE 8
#define ATTR_WIDE 16

/* Selection flag bits in meta[9], mirrored in ScreenSnapshot. */
#define SEL_ACTIVE 1
#define SEL_START_VISIBLE 2
#define SEL_END_VISIBLE 4

/*
 * Copies the current viewport into flat per-cell arrays (row-major).
 * Colors are resolved to ARGB here — including defaults, inverse,
 * invisible, and the active selection (drawn as inverse video) — so the
 * Java renderer just draws what it's given.
 *
 * meta layout: [0] cursor-in-viewport, [1] x, [2] y, [3] style,
 * [4] visible, [5] blinking, [6] wide-tail, [7] default bg, [8] default fg,
 * [9] SEL_* flags, [10] sel start x, [11] sel start y, [12] sel end x,
 * [13] sel end y. Selection endpoints are viewport coordinates ordered
 * top-left to bottom-right; each is only valid when its visibility bit is
 * set (an endpoint can sit above or below the viewport).
 *
 * Returns (cols << 16) | rows. If the arrays are smaller than cols*rows
 * only meta is written; the caller must re-allocate and retry.
 */
JNIEXPORT jint JNICALL
Java_dev_androidterm_term_TerminalNative_terminalSnapshot(
    JNIEnv *env, jclass clazz, jlong h, jintArray jcp, jintArray jfg,
    jintArray jbg, jbyteArray jattrs, jintArray jmeta) {
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

    jint meta[14] = {0};
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
    (*env)->SetIntArrayRegion(env, jmeta, 0, 14, meta);

    jint ret = ((jint)cols << 16) | rows;
    size_t ncells = (size_t)cols * rows;
    if (ncells == 0 || (size_t)(*env)->GetArrayLength(env, jcp) < ncells)
        return ret;

    jint *row_cp = malloc(cols * sizeof(jint));
    jint *row_fg = malloc(cols * sizeof(jint));
    jint *row_bg = malloc(cols * sizeof(jint));
    jbyte *row_attr = malloc(cols);
    if (!row_cp || !row_fg || !row_bg || !row_attr) goto done;

    ghostty_render_state_get(c->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                             &c->row_iter);
    int y = 0;
    while (ghostty_render_state_row_iterator_next(c->row_iter) && y < rows) {
        ghostty_render_state_row_get(
            c->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &c->cells);
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

            jbyte attr = 0;
            if (style.bold) attr |= ATTR_BOLD;
            if (style.italic) attr |= ATTR_ITALIC;
            if (style.underline) attr |= ATTR_UNDERLINE;
            if (style.strikethrough) attr |= ATTR_STRIKE;
            if (wide == GHOSTTY_CELL_WIDE_WIDE) attr |= ATTR_WIDE;

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
        (*env)->SetByteArrayRegion(env, jattrs, off, cols, row_attr);
        y++;
    }

done:
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
Java_dev_androidterm_term_TerminalNative_terminalGraphics(
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
Java_dev_androidterm_term_TerminalNative_terminalImage(
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
Java_dev_androidterm_term_TerminalNative_terminalEncodeKey(
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
Java_dev_androidterm_term_TerminalNative_terminalSelectWord(
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
 * Prepares a handle drag: reorders the active selection so the grabbed
 * visual endpoint (which: 0 = top-left, 1 = bottom-right) becomes the
 * logical end, which is what terminalSelectionDrag moves. The other
 * endpoint stays anchored for the whole drag, and dragging across it
 * flips the selection naturally.
 */
JNIEXPORT void JNICALL
Java_dev_androidterm_term_TerminalNative_terminalSelectionAnchor(
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
Java_dev_androidterm_term_TerminalNative_terminalSelectionDrag(
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
Java_dev_androidterm_term_TerminalNative_terminalSelectionClear(
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
Java_dev_androidterm_term_TerminalNative_terminalSelectionText(
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
 * Encodes clipboard text for the PTY per current terminal state: strips
 * unsafe control bytes, wraps in bracketed-paste markers when mode 2004
 * is set, otherwise converts newlines to carriage returns.
 */
JNIEXPORT jbyteArray JNICALL
Java_dev_androidterm_term_TerminalNative_terminalEncodePaste(
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
