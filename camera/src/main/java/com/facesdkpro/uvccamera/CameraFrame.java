package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;
import java.nio.ByteBuffer;

/**
 * Borrowed decoded pixels: valid only inside FrameListener.onFrame. No pixel copy is made.
 * arrivalTimeNanos uses the host monotonic clock; it is not a sensor timestamp or hardware sync.
 */
public final class CameraFrame {
    public final int width, height, pixelFormat;
    public final long sequence, arrivalTimeNanos;
    private final ByteBuffer pixels;

    CameraFrame(ByteBuffer pixels, StreamConfig config, long sequence, long arrivalTimeNanos) {
        this.pixels = pixels.asReadOnlyBuffer();
        this.width = config.width; this.height = config.height; this.pixelFormat = config.pixelFormat;
        this.sequence = sequence; this.arrivalTimeNanos = arrivalTimeNanos;
    }

    /** Read-only direct view. Its validity ends when the callback returns. */
    public ByteBuffer pixels() { return pixels.asReadOnlyBuffer(); }

    /** Explicit ownership transfer by copying; safe to retain for asynchronous processing. */
    public byte[] copyPixels() {
        ByteBuffer view = pixels(); byte[] copy = new byte[view.remaining()]; view.get(copy); return copy;
    }

    /** Copy the Y plane of an NV21/NV12 callback to packed GRAY8, without an RGB/Bitmap round trip. */
    public byte[] copyLuma() {
        if (pixelFormat != UVCCamera.PIXEL_FORMAT_NV21 && pixelFormat != UVCCamera.PIXEL_FORMAT_NV12) {
            throw new IllegalStateException("copyLuma requires NV21 or NV12");
        }
        ByteBuffer view = pixels(); byte[] gray = new byte[width * height]; view.get(gray); return gray;
    }
}
