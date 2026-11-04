package com.facesdkpro.uvccamera;

import java.nio.ByteBuffer;

/**
 * Zero-copy Y/U/V views of packed NV21 for a YUV_420_888 consumer. This does not convert pixels,
 * choose a color matrix/range, rotate the image, or extend the source buffer's lifetime.
 */
public final class Nv21Planes {
    public final int width, height;
    public final int yRowStride, uRowStride, vRowStride, uvPixelStride;
    private final ByteBuffer y, u, v;

    private Nv21Planes(ByteBuffer pixels, int width, int height) {
        this.width = width; this.height = height;
        yRowStride = uRowStride = vRowStride = width; uvPixelStride = 2;
        int lumaBytes = width * height;
        y = window(pixels, 0, lumaBytes);
        v = window(pixels, lumaBytes, lumaBytes / 2);
        u = window(pixels, lumaBytes + 1, lumaBytes / 2 - 1);
    }

    /** Input position is the first Y byte; remaining bytes must contain exactly one NV21 frame. */
    public static Nv21Planes from(ByteBuffer pixels, int width, int height) {
        if (pixels == null) throw new NullPointerException("pixels");
        if (width < 2 || height < 2 || width > 4096 || height > 4096
                || (width & 1) != 0 || (height & 1) != 0 || !pixels.isDirect()
                || pixels.remaining() != width * height * 3 / 2) {
            throw new IllegalArgumentException("Expected one direct, packed, even-sized NV21 frame");
        }
        return new Nv21Planes(pixels.slice(), width, height);
    }

    public ByteBuffer y() { return y.asReadOnlyBuffer(); }
    public ByteBuffer u() { return u.asReadOnlyBuffer(); }
    public ByteBuffer v() { return v.asReadOnlyBuffer(); }

    private static ByteBuffer window(ByteBuffer source, int offset, int length) {
        ByteBuffer view = source.duplicate(); view.position(offset); view.limit(offset + length);
        return view.slice().asReadOnlyBuffer();
    }
}
