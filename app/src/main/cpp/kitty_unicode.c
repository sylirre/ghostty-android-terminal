#include "kitty_unicode.h"

#include <math.h>
#include <stddef.h>

/* Rowcolumn diacritics from the Kitty graphics protocol, ascending so the
 * lookup can binary-search. Generated from ghostty's graphics_unicode.zig
 * (297 entries); the array index is the encoded row/col value. */
static const uint32_t kitty_diacritics[] = {
    0x0305, 0x030D, 0x030E, 0x0310, 0x0312, 0x033D, 0x033E, 0x033F,
    0x0346, 0x034A, 0x034B, 0x034C, 0x0350, 0x0351, 0x0352, 0x0357,
    0x035B, 0x0363, 0x0364, 0x0365, 0x0366, 0x0367, 0x0368, 0x0369,
    0x036A, 0x036B, 0x036C, 0x036D, 0x036E, 0x036F, 0x0483, 0x0484,
    0x0485, 0x0486, 0x0487, 0x0592, 0x0593, 0x0594, 0x0595, 0x0597,
    0x0598, 0x0599, 0x059C, 0x059D, 0x059E, 0x059F, 0x05A0, 0x05A1,
    0x05A8, 0x05A9, 0x05AB, 0x05AC, 0x05AF, 0x05C4, 0x0610, 0x0611,
    0x0612, 0x0613, 0x0614, 0x0615, 0x0616, 0x0617, 0x0657, 0x0658,
    0x0659, 0x065A, 0x065B, 0x065D, 0x065E, 0x06D6, 0x06D7, 0x06D8,
    0x06D9, 0x06DA, 0x06DB, 0x06DC, 0x06DF, 0x06E0, 0x06E1, 0x06E2,
    0x06E4, 0x06E7, 0x06E8, 0x06EB, 0x06EC, 0x0730, 0x0732, 0x0733,
    0x0735, 0x0736, 0x073A, 0x073D, 0x073F, 0x0740, 0x0741, 0x0743,
    0x0745, 0x0747, 0x0749, 0x074A, 0x07EB, 0x07EC, 0x07ED, 0x07EE,
    0x07EF, 0x07F0, 0x07F1, 0x07F3, 0x0816, 0x0817, 0x0818, 0x0819,
    0x081B, 0x081C, 0x081D, 0x081E, 0x081F, 0x0820, 0x0821, 0x0822,
    0x0823, 0x0825, 0x0826, 0x0827, 0x0829, 0x082A, 0x082B, 0x082C,
    0x082D, 0x0951, 0x0953, 0x0954, 0x0F82, 0x0F83, 0x0F86, 0x0F87,
    0x135D, 0x135E, 0x135F, 0x17DD, 0x193A, 0x1A17, 0x1A75, 0x1A76,
    0x1A77, 0x1A78, 0x1A79, 0x1A7A, 0x1A7B, 0x1A7C, 0x1B6B, 0x1B6D,
    0x1B6E, 0x1B6F, 0x1B70, 0x1B71, 0x1B72, 0x1B73, 0x1CD0, 0x1CD1,
    0x1CD2, 0x1CDA, 0x1CDB, 0x1CE0, 0x1DC0, 0x1DC1, 0x1DC3, 0x1DC4,
    0x1DC5, 0x1DC6, 0x1DC7, 0x1DC8, 0x1DC9, 0x1DCB, 0x1DCC, 0x1DD1,
    0x1DD2, 0x1DD3, 0x1DD4, 0x1DD5, 0x1DD6, 0x1DD7, 0x1DD8, 0x1DD9,
    0x1DDA, 0x1DDB, 0x1DDC, 0x1DDD, 0x1DDE, 0x1DDF, 0x1DE0, 0x1DE1,
    0x1DE2, 0x1DE3, 0x1DE4, 0x1DE5, 0x1DE6, 0x1DFE, 0x20D0, 0x20D1,
    0x20D4, 0x20D5, 0x20D6, 0x20D7, 0x20DB, 0x20DC, 0x20E1, 0x20E7,
    0x20E9, 0x20F0, 0x2CEF, 0x2CF0, 0x2CF1, 0x2DE0, 0x2DE1, 0x2DE2,
    0x2DE3, 0x2DE4, 0x2DE5, 0x2DE6, 0x2DE7, 0x2DE8, 0x2DE9, 0x2DEA,
    0x2DEB, 0x2DEC, 0x2DED, 0x2DEE, 0x2DEF, 0x2DF0, 0x2DF1, 0x2DF2,
    0x2DF3, 0x2DF4, 0x2DF5, 0x2DF6, 0x2DF7, 0x2DF8, 0x2DF9, 0x2DFA,
    0x2DFB, 0x2DFC, 0x2DFD, 0x2DFE, 0x2DFF, 0xA66F, 0xA67C, 0xA67D,
    0xA6F0, 0xA6F1, 0xA8E0, 0xA8E1, 0xA8E2, 0xA8E3, 0xA8E4, 0xA8E5,
    0xA8E6, 0xA8E7, 0xA8E8, 0xA8E9, 0xA8EA, 0xA8EB, 0xA8EC, 0xA8ED,
    0xA8EE, 0xA8EF, 0xA8F0, 0xA8F1, 0xAAB0, 0xAAB2, 0xAAB3, 0xAAB7,
    0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xFE20, 0xFE21, 0xFE22, 0xFE23,
    0xFE24, 0xFE25, 0xFE26, 0x10A0F, 0x10A38, 0x1D185, 0x1D186, 0x1D187,
    0x1D188, 0x1D189, 0x1D1AA, 0x1D1AB, 0x1D1AC, 0x1D1AD, 0x1D242, 0x1D243,
    0x1D244,
};

#define KITTY_DIACRITICS_LEN \
    (sizeof(kitty_diacritics) / sizeof(kitty_diacritics[0]))

int kitty_diacritic_index(uint32_t cp) {
    size_t lo = 0, hi = KITTY_DIACRITICS_LEN;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        uint32_t v = kitty_diacritics[mid];
        if (cp == v) return (int)mid;
        if (cp < v)
            hi = mid;
        else
            lo = mid + 1;
    }
    return -1;
}

uint32_t kitty_color_to_id(GhosttyStyleColor c) {
    switch (c.tag) {
    case GHOSTTY_STYLE_COLOR_PALETTE:
        return (uint32_t)c.value.palette;
    case GHOSTTY_STYLE_COLOR_RGB: {
        uint32_t r = c.value.rgb.r, g = c.value.rgb.g, b = c.value.rgb.b;
        return (r << 16) | (g << 8) | b;
    }
    default:
        return 0;
    }
}

/*
 * Direct port of Placement.renderPlacement from ghostty's
 * graphics_unicode.zig. Variable prefixes follow the original: img_* is the
 * source image space, p_* the placement space, is_* the aspect-padded
 * "scaled image" space, s_* the source rectangle being carved out.
 */
bool kitty_virtual_render(uint32_t img_width, uint32_t img_height,
                          uint32_t grid_rows, uint32_t grid_cols,
                          uint32_t cell_width, uint32_t cell_height,
                          uint32_t vp_row, uint32_t vp_col,
                          uint32_t vp_width, uint32_t vp_height,
                          KittyVirtualRender *out) {
    if (grid_rows == 0 || grid_cols == 0 || cell_width == 0 ||
        cell_height == 0 || img_width == 0 || img_height == 0)
        return false;

    double img_w = (double)img_width;
    double img_h = (double)img_height;

    /* Fit the image into the grid preserving aspect ratio, centering on the
     * shorter axis. */
    double p_rows_px = (double)(grid_rows * cell_height);
    double p_cols_px = (double)(grid_cols * cell_width);
    double x_offset = 0, y_offset = 0, x_scale, y_scale;
    if (img_w * p_rows_px > img_h * p_cols_px) {
        x_scale = p_cols_px / fmax(img_w, 1.0);
        y_scale = x_scale;
        y_offset = (p_rows_px - img_h * y_scale) / 2.0;
    } else {
        y_scale = p_rows_px / fmax(img_h, 1.0);
        x_scale = y_scale;
        x_offset = (p_cols_px - img_w * x_scale) / 2.0;
    }

    /* The aspect padding expressed back in source-image pixels. */
    double is_x_offset = x_offset / x_scale;
    double is_y_offset = y_offset / y_scale;
    double is_width = img_w + is_x_offset * 2.0;
    double is_height = img_h + is_y_offset * 2.0;

    /* The fragment's source rect within the padded image. */
    double s_width = is_width * ((double)vp_width / (double)grid_cols);
    double s_height = is_height * ((double)vp_height / (double)grid_rows);
    double s_x = is_width * ((double)vp_col / (double)grid_cols);
    double s_y = is_height * ((double)vp_row / (double)grid_rows);

    double d_x_offset = 0, d_y_offset = 0;
    double d_width = (double)(vp_width * cell_width);
    double d_height = (double)(vp_height * cell_height);

    if (s_y < is_y_offset) {
        double offset = is_y_offset - s_y;
        s_height -= offset;
        d_y_offset = offset;
        d_height -= offset * y_scale;
        s_y = 0;
        if (s_height > img_h) {
            s_height = img_h;
            d_height = img_h * y_scale;
        }
    } else if (s_y + s_height > is_height - is_y_offset) {
        s_y -= is_y_offset;
        s_height = is_height - is_y_offset - s_y;
        s_height -= is_y_offset;
        d_height = s_height * y_scale;
    } else {
        s_y -= is_y_offset;
    }

    if (s_x < is_x_offset) {
        double offset = is_x_offset - s_x;
        s_width -= offset;
        d_x_offset = offset;
        d_width -= offset * x_scale;
        s_x = 0;
        if (s_width > img_w) {
            s_width = img_w;
            d_width = img_w * x_scale;
        }
    } else if (s_x + s_width > is_width - is_x_offset) {
        s_x -= is_x_offset;
        s_width = is_width - is_x_offset - s_x;
        s_width -= is_x_offset;
        d_width = s_width * x_scale;
    } else {
        s_x -= is_x_offset;
    }

    if (s_width <= 0 || s_height <= 0) return false;

    out->offset_x = (uint32_t)lround(d_x_offset * x_scale);
    out->offset_y = (uint32_t)lround(d_y_offset * y_scale);
    out->source_x = (uint32_t)lround(s_x);
    out->source_y = (uint32_t)lround(s_y);
    out->source_width = (uint32_t)lround(s_width);
    out->source_height = (uint32_t)lround(s_height);
    out->dest_width = (uint32_t)lround(d_width);
    out->dest_height = (uint32_t)lround(d_height);
    return true;
}
