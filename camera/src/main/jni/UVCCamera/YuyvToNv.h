// Copyright 2026 FaceSDKPro. Licensed under the Apache License, Version 2.0.
#ifndef UVC_YUYV_TO_NV_H
#define UVC_YUYV_TO_NV_H

#include <stddef.h>
#include <stdint.h>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

// Preserve source Y and vertically average 4:2:2 chroma into 4:2:0.
// No RGB round trip, scratch buffer, heap allocation or range conversion.
inline bool yuyvToNv(const uint8_t *src, size_t srcBytes, int stride,
                     uint8_t *dst, size_t dstBytes, int width, int height, bool nv21) {
    if (!src || !dst || width <= 0 || height <= 0 || width > 4096 || height > 4096
        || (width & 1) || (height & 1) || stride < width * 2) return false;
    const size_t pixels = static_cast<size_t>(width) * height;
    if (srcBytes < static_cast<size_t>(height - 1) * stride + width * 2
        || dstBytes < pixels + pixels / 2) return false;
    for (int y = 0; y < height; y += 2) {
        const uint8_t *a = src + static_cast<size_t>(y) * stride;
        const uint8_t *b = a + stride;
        uint8_t *ya = dst + static_cast<size_t>(y) * width;
        uint8_t *yb = ya + width;
        uint8_t *uv = dst + pixels + static_cast<size_t>(y / 2) * width;
        int x = 0;
#if defined(__ARM_NEON)
        for (; x + 16 <= width; x += 16) {
            const uint8x16x2_t rowA = vld2q_u8(a + x * 2);
            const uint8x16x2_t rowB = vld2q_u8(b + x * 2);
            uint8x16_t chroma = vrhaddq_u8(rowA.val[1], rowB.val[1]);
            if (nv21) chroma = vrev16q_u8(chroma);
            vst1q_u8(ya + x, rowA.val[0]);
            vst1q_u8(yb + x, rowB.val[0]);
            vst1q_u8(uv + x, chroma);
        }
#endif
        for (; x < width; x += 2) {
            ya[x] = a[x * 2]; ya[x + 1] = a[x * 2 + 2];
            yb[x] = b[x * 2]; yb[x + 1] = b[x * 2 + 2];
            const uint8_t u = (static_cast<unsigned>(a[x * 2 + 1]) + b[x * 2 + 1] + 1) / 2;
            const uint8_t v = (static_cast<unsigned>(a[x * 2 + 3]) + b[x * 2 + 3] + 1) / 2;
            uv[x] = nv21 ? v : u; uv[x + 1] = nv21 ? u : v;
        }
    }
    return true;
}

#endif
