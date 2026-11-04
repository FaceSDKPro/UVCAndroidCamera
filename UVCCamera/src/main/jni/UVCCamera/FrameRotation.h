// Copyright 2026 FaceSDKPro. Apache-2.0.
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>

// Rotate units rather than bytes: NV chroma's VU/UV pairs must remain intact.
// Share the tiled kernel instead of inlining/specializing it for every luma/chroma call site.
static __attribute__((noinline, minsize)) void rotatePackedPlane(const uint8_t *src, uint8_t *dst, int w, int h, int unit, int degrees) {
    const int outWidth = degrees == 180 ? w : h;
    // A tile bounds strided writes on quarter turns, without allocating a scratch image.
    for (int by = 0; by < h; by += 32) for (int bx = 0; bx < w; bx += 32) {
        const int endY = by + 32 < h ? by + 32 : h, endX = bx + 32 < w ? bx + 32 : w;
        for (int y = by; y < endY; ++y) {
            int dx = degrees == 90 ? h - 1 - y : degrees == 180 ? w - 1 - bx : y;
            int dy = degrees == 90 ? bx : degrees == 180 ? h - 1 - y : w - 1 - bx;
            ptrdiff_t at = (static_cast<ptrdiff_t>(dy) * outWidth + dx) * unit;
            const ptrdiff_t step = (degrees == 90 ? outWidth : degrees == 180 ? -1 : -outWidth) * unit;
            const uint8_t *row = src + (static_cast<size_t>(y) * w + bx) * unit;
            for (int x = bx; x < endX; ++x, row += unit, at += step) {
                dst[at] = row[0]; if (unit == 2) dst[at + 1] = row[1];
            }
        }
    }
}

static inline bool rotatePackedFrame(const uint8_t *src, size_t srcSize, uint8_t *dst, size_t dstSize,
        int w, int h, int format, int degrees) {
    if (!src || !dst || w < 1 || h < 1 || w > 4096 || h > 4096
            || (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270)
            || (format != 2 && format != 3 && format != 8) || (format != 8 && ((w | h) & 1))) return false;
    const size_t ySize = static_cast<size_t>(w) * h, size = format == 8 ? ySize : ySize * 3 / 2;
    if (srcSize != size || dstSize < size) return false;
    const uintptr_t a = reinterpret_cast<uintptr_t>(src), b = reinterpret_cast<uintptr_t>(dst);
    if (a < b ? b - a < size : a - b < size) return false;
    if (degrees == 0) memcpy(dst, src, size);
    else {
        rotatePackedPlane(src, dst, w, h, 1, degrees);
        if (format != 8) rotatePackedPlane(src + ySize, dst + ySize, w / 2, h / 2, 2, degrees);
    }
    return true;
}
