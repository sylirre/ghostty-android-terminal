#include "png_decode.h"

#include <limits.h>
#include <stdint.h>
#include <string.h>

#include <ghostty/vt/allocator.h>

/* stb_image, PNG only, no stdio: a self-contained decoder so PNG support
 * needs no Android/NDK image API (which would strand minSdk 29 anyway). */
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#include "stb_image.h"

bool androidterm_decode_png(void *userdata, const GhosttyAllocator *allocator,
                            const uint8_t *data, size_t data_len,
                            GhosttySysImage *out) {
    (void)userdata;
    if (data == NULL || data_len == 0 || data_len > (size_t)INT_MAX) return false;

    int w = 0, h = 0, channels = 0;
    stbi_uc *pixels =
        stbi_load_from_memory(data, (int)data_len, &w, &h, &channels, 4);
    if (pixels == NULL) return false;
    if (w <= 0 || h <= 0) {
        stbi_image_free(pixels);
        return false;
    }

    /* ghostty takes ownership of out->data and frees it with this same
     * allocator, so it must come from there — not stb's malloc. */
    size_t len = (size_t)w * (size_t)h * 4u;
    uint8_t *buf = ghostty_alloc(allocator, len);
    if (buf == NULL) {
        stbi_image_free(pixels);
        return false;
    }
    memcpy(buf, pixels, len);
    stbi_image_free(pixels);

    out->width = (uint32_t)w;
    out->height = (uint32_t)h;
    out->data = buf;
    out->data_len = len;
    return true;
}
