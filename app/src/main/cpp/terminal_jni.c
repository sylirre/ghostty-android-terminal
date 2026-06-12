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

/* Event bits returned by feed(), see TerminalNative.EVENT_*. */
#define EVENT_BELL 1
#define EVENT_TITLE 2

typedef struct {
    GhosttyTerminal term;
    GhosttyRenderState rs;
    GhosttyRenderStateRowIterator row_iter;
    GhosttyRenderStateRowCells cells;
    GhosttyKeyEncoder encoder;
    GhosttyKeyEvent kev;

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

/* Typed assignments so a callback signature drift fails to compile instead
 * of corrupting the stack at runtime (ghostty_terminal_set takes void*). */
static const GhosttyTerminalWritePtyFn write_pty_fn = on_write_pty;
static const GhosttyTerminalBellFn bell_fn = on_bell;
static const GhosttyTerminalTitleChangedFn title_fn = on_title;

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

    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_USERDATA, c);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_WRITE_PTY, write_pty_fn);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_BELL, bell_fn);
    ghostty_terminal_set(c->term, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, title_fn);
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

/*
 * Copies the current viewport into flat per-cell arrays (row-major).
 * Colors are resolved to ARGB here — including defaults, inverse, and
 * invisible — so the Java renderer just draws what it's given.
 *
 * meta layout: [0] cursor-in-viewport, [1] x, [2] y, [3] style,
 * [4] visible, [5] blinking, [6] wide-tail, [7] default bg, [8] default fg.
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

    jint meta[9] = {0};
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
    (*env)->SetIntArrayRegion(env, jmeta, 0, 9, meta);

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
            row_cp[x] = 0;
            row_fg[x] = meta[8];
            row_bg[x] = meta[7];
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
