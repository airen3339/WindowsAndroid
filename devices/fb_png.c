/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Framebuffer helpers for hosts without Metal (Linux / Android): a presented-frame
   counter the host's presenter bumps, and PNG screenshots of the guest display
   written with zlib (no image library needed, so it also works headless). */
#include "fb_display.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

void hv_fb_metal_set_layer(HvFbDisplay *fb, void *layer) {
    (void)fb;
    (void)layer;
}

void hv_fb_count_presented(HvFbDisplay *fb) {
    if (fb) __atomic_add_fetch(&fb->presented, 1, __ATOMIC_RELAXED);
}

uint64_t hv_fb_metal_frames(HvFbDisplay *fb) {
    return fb ? __atomic_load_n(&fb->presented, __ATOMIC_RELAXED) : 0;
}

static void put32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static int chunk(FILE *f, const char type[4], const uint8_t *data, uint32_t len) {
    uint8_t hdr[8];
    put32(hdr, len);
    memcpy(hdr + 4, type, 4);
    uLong crc = crc32(0, (const Bytef *)type, 4);
    if (len) crc = crc32(crc, data, len);
    uint8_t tail[4];
    put32(tail, (uint32_t)crc);
    return fwrite(hdr, 1, 8, f) == 8 && (!len || fwrite(data, 1, len, f) == len) && fwrite(tail, 1, 4, f) == 4 ? 0 : -1;
}

int hv_fb_save_png(HvFbDisplay *fb, const char *path) {
    const uint8_t *px;
    uint32_t w, h, stride;
    if (hv_fb_display_get_frame(fb, &px, &w, &h, &stride) != 0) return -1;
    /* Scanlines: a filter byte (0: none) then RGB. The guest framebuffer is XRGB8888 (B, G, R, X in memory). */
    size_t row = 1 + (size_t)w * 3, raw_len = row * h;
    uint8_t *raw = malloc(raw_len);
    if (!raw) return -1;
    for (uint32_t y = 0; y < h; y++) {
        uint8_t *o = raw + row * y;
        const uint8_t *i = px + (size_t)stride * y;
        *o++ = 0;
        for (uint32_t x = 0; x < w; x++, i += 4) {
            *o++ = i[2];
            *o++ = i[1];
            *o++ = i[0];
        }
    }
    uLongf zlen = compressBound((uLong)raw_len);
    uint8_t *z = malloc(zlen);
    int rc = -1;
    if (z && compress2(z, &zlen, raw, (uLong)raw_len, 1) == Z_OK) {
        FILE *f = fopen(path, "wb");
        if (f) {
            static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
            uint8_t ihdr[13];
            put32(ihdr, w);
            put32(ihdr + 4, h);
            ihdr[8] = 8;  /* bit depth */
            ihdr[9] = 2;  /* truecolour */
            ihdr[10] = 0; /* deflate */
            ihdr[11] = 0; /* adaptive filtering */
            ihdr[12] = 0; /* no interlace */
            rc = fwrite(sig, 1, 8, f) == 8 && chunk(f, "IHDR", ihdr, 13) == 0 && chunk(f, "IDAT", z, (uint32_t)zlen) == 0 &&
                         chunk(f, "IEND", NULL, 0) == 0
                     ? 0
                     : -1;
            if (fclose(f) != 0) rc = -1;
        }
    }
    free(z);
    free(raw);
    return rc;
}
