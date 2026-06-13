/*
 * Kitty graphics Unicode-placeholder (virtual placement) helpers.
 *
 * Virtual placements have no position of their own: the image is shown
 * wherever the program prints the placeholder character U+10EEEE, with the
 * image id encoded in the cell's foreground color and the row/col fragment
 * encoded in combining "rowcolumn" diacritics. This ports the decode and
 * layout math from ghostty's src/terminal/kitty/graphics_unicode.zig so the
 * JNI layer can turn placeholder runs into ordinary placement records.
 */
#ifndef ANDROIDTERM_KITTY_UNICODE_H
#define ANDROIDTERM_KITTY_UNICODE_H

#include <stdbool.h>
#include <stdint.h>

#include <ghostty/vt/style.h>

/** Codepoint of the Kitty Unicode placeholder character. */
#define KITTY_PLACEHOLDER 0x10EEEEu

/** 0-based row/col index for a rowcolumn diacritic, or -1 if cp isn't one. */
int kitty_diacritic_index(uint32_t cp);

/** 24-bit Kitty id encoded in a style color (palette index or RGB; 0 = none). */
uint32_t kitty_color_to_id(GhosttyStyleColor c);

/** Render geometry for one placeholder run; see kitty_virtual_render. */
typedef struct {
    uint32_t source_x, source_y, source_width, source_height;
    uint32_t dest_width, dest_height;
    uint32_t offset_x, offset_y; /* sub-cell pixel offset for aspect centering */
} KittyVirtualRender;

/*
 * Computes the source rectangle and destination size/offset for a single
 * placeholder run (one grid row, vp_width cells wide, starting at fragment
 * cell (vp_row, vp_col) of a grid_rows x grid_cols layout). The image is
 * fit into the grid preserving aspect ratio and centered. grid_rows/cols and
 * the cell size must be non-zero. Returns false when nothing should be drawn.
 */
bool kitty_virtual_render(uint32_t img_width, uint32_t img_height,
                          uint32_t grid_rows, uint32_t grid_cols,
                          uint32_t cell_width, uint32_t cell_height,
                          uint32_t vp_row, uint32_t vp_col,
                          uint32_t vp_width, uint32_t vp_height,
                          KittyVirtualRender *out);

#endif /* ANDROIDTERM_KITTY_UNICODE_H */
