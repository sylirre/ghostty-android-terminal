/*
 * PNG decoder shim for libghostty-vt's Kitty graphics protocol.
 *
 * Ghostty stores Kitty images in raw RGB/RGBA/grayscale form but needs an
 * embedder-supplied callback to turn PNG payloads (f=100) into RGBA. This
 * wraps the vendored stb_image (PNG-only) and is installed process-globally
 * via ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, ...). Keeping it in its
 * own translation unit lets the heavy third-party header compile with
 * warnings off, away from libterm.so's -Wall -Werror.
 */
#ifndef TERM_PNG_DECODE_H
#define TERM_PNG_DECODE_H

#include <ghostty/vt/sys.h>

/* Matches GhosttySysDecodePngFn. Decodes data/data_len into out as RGBA,
 * allocating out->data through the supplied allocator (ghostty frees it).
 * Returns false on any decode/allocation failure. */
bool term_decode_png(void *userdata, const GhosttyAllocator *allocator,
                            const uint8_t *data, size_t data_len,
                            GhosttySysImage *out);

#endif /* TERM_PNG_DECODE_H */
