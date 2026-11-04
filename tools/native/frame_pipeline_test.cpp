// Copyright 2026 FaceSDKPro. Licensed under the Apache License, Version 2.0.
// Device-native checks: production converter/decoder, synthetic input only.
#include "YuyvToNv.h"
#include "ConvertHelper.h"
#include <assert.h>
#include <initializer_list>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int testYuyv() {
    int cases = 0;
    const int widths[] = {2, 4, 14, 16, 18, 30, 32, 34, 640, 1920};
    for (int w : widths) for (int h : {2, 4, 12}) for (int pad : {0, 6}) for (bool vu : {false, true}) {
        const int stride = w * 2 + pad;
        const size_t srcSize = static_cast<size_t>(stride) * (h - 1) + w * 2;
        const size_t dstSize = static_cast<size_t>(w) * h * 3 / 2;
        uint8_t *source = static_cast<uint8_t *>(malloc(srcSize + 2));
        uint8_t *storage = static_cast<uint8_t *>(malloc(dstSize + 2));
        assert(source && storage);
        for (size_t i = 0; i < srcSize + 2; ++i) source[i] = static_cast<uint8_t>(i * 73 + 19);
        memset(storage, 0xcd, dstSize + 2);
        const uint8_t *src = source + 1; uint8_t *dst = storage + 1;
        assert(yuyvToNv(src, srcSize, stride, dst, dstSize, w, h, vu));
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x)
            assert(dst[y * w + x] == src[y * stride + x * 2]);
        for (int y = 0; y < h / 2; ++y) for (int x = 0; x < w / 2; ++x) for (int c = 0; c < 2; ++c) {
            const int component = vu ? 1 - c : c;
            const int a = src[y * 2 * stride + x * 4 + 1 + component * 2];
            const int b = src[(y * 2 + 1) * stride + x * 4 + 1 + component * 2];
            assert(dst[w * h + y * w + x * 2 + c] == (a + b + 1) / 2);
        }
        assert(storage[0] == 0xcd && storage[dstSize + 1] == 0xcd);
        assert(!yuyvToNv(src, srcSize - 1, stride, dst, dstSize, w, h, vu));
        assert(!yuyvToNv(src, srcSize, stride, dst, dstSize - 1, w, h, vu));
        assert(!yuyvToNv(src, srcSize, w * 2 - 1, dst, dstSize, w, h, vu));
        assert(!yuyvToNv(src, srcSize, stride, dst, dstSize, w - 1, h, vu));
        free(source); free(storage); ++cases;
    }
    return cases;
}

static int testJpeg(const char *filename) {
    FILE *file = fopen(filename, "rb"); assert(file);
    fseek(file, 0, SEEK_END); const long size = ftell(file); rewind(file);
    uint8_t *jpeg = static_cast<uint8_t *>(malloc(size)); assert(jpeg);
    assert(fread(jpeg, 1, size, file) == static_cast<size_t>(size)); fclose(file);
    uvc_frame_t input = {}; input.data = jpeg; input.data_bytes = size;
    input.width = 34; input.height = 8; input.frame_format = UVC_FRAME_FORMAT_MJPEG;
    MjpegDecoder decoder;
    uvc_frame_t *packed = uvc_allocate_frame(0); assert(packed);
    assert(decoder.decode(&input, packed) == UVC_SUCCESS);
    const size_t pitch = 48 * 4, bytes = pitch * input.height;
    uint8_t *surface = static_cast<uint8_t *>(malloc(bytes + 2)); assert(surface);
    memset(surface, 0xcd, bytes + 2);
    uvc_frame_t borrowed = {}; borrowed.data = surface + 1; borrowed.step = pitch; borrowed.capacity_bytes = bytes;
    assert(decoder.decode(&input, &borrowed) == UVC_SUCCESS);
    assert(borrowed.step == pitch && borrowed.data_bytes == bytes);
    for (unsigned y = 0; y < input.height; ++y) {
        assert(!memcmp(surface + 1 + y * pitch, static_cast<uint8_t *>(packed->data) + y * packed->step, input.width * 4));
        for (size_t x = input.width * 4; x < pitch; ++x) assert(surface[1 + y * pitch + x] == 0xcd);
    }
    assert(surface[0] == 0xcd && surface[bytes + 1] == 0xcd);
    borrowed.capacity_bytes = bytes - 1;
    assert(decoder.decode(&input, &borrowed) == UVC_ERROR_NO_MEM);
    borrowed.capacity_bytes = bytes; borrowed.step = input.width * 4 - 1;
    assert(decoder.decode(&input, &borrowed) == UVC_ERROR_INVALID_PARAM);
    input.width += 2;
    assert(decoder.decode(&input, packed) == UVC_ERROR_INVALID_PARAM); input.width -= 2;
    const size_t savedSize = input.data_bytes; input.data_bytes = 8;
    assert(decoder.decode(&input, packed) == UVC_ERROR_INVALID_PARAM);
    input.data_bytes = savedSize;
    assert(decoder.decode(&input, packed) == UVC_SUCCESS);
    uvc_free_frame(packed); free(jpeg); free(surface);
    return 6;
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const int yuyv = testYuyv(), jpeg = testJpeg(argv[1]);
    printf("{\"yuyv_cases\":%d,\"jpeg_checks\":%d,\"failed\":0,\"synthetic_only\":true}\n", yuyv, jpeg);
}
