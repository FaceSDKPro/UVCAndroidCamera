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
        this.pixels = pixels; // JNI creates a separate borrowed view for each callback.
        this.width = config.width; this.height = config.height; this.pixelFormat = config.pixelFormat;
        this.sequence = sequence; this.arrivalTimeNanos = arrivalTimeNanos;
    }

    /** Read-only direct view. Its validity ends when the callback returns. */
    public ByteBuffer pixels() { return pixels.asReadOnlyBuffer(); }

    /** Explicit ownership transfer by copying; safe to retain for asynchronous processing. */
    public byte[] copyPixels() {
        ByteBuffer view = pixels(); byte[] copy = new byte[view.remaining()]; view.get(copy); return copy;
    }

    /** Copy pixels into caller-owned reusable storage. Returns bytes written. */
    public int copyPixelsTo(byte[] destination, int offset) {
        return copyTo(destination, offset, pixels.remaining());
    }

    /** Borrow the packed Y plane of NV21/NV12, or all pixels of GRAY8. */
    public ByteBuffer luma() {
        requireLuma();
        ByteBuffer view = pixels(); view.limit(width * height); return view;
    }

    /** Copy luma into caller-owned reusable storage. Returns bytes written. */
    public int copyLumaTo(byte[] destination, int offset) {
        requireLuma();
        return copyTo(destination, offset, width * height);
    }

    /** Explicit owned packed GRAY8 copy; luma()/copyLumaTo() avoid allocating an image array. */
    public byte[] copyLuma() {
        requireLuma();
        byte[] gray = new byte[width * height]; copyLumaTo(gray, 0); return gray;
    }

    private void requireLuma() {
        if (pixelFormat != UVCCamera.PIXEL_FORMAT_NV21 && pixelFormat != UVCCamera.PIXEL_FORMAT_NV12
                && pixelFormat != UVCCamera.PIXEL_FORMAT_GRAY8) {
            throw new IllegalStateException("Luma requires NV21, NV12 or GRAY8");
        }
    }

    private int copyTo(byte[] destination, int offset, int length) {
        if (destination == null) throw new NullPointerException("destination");
        if (offset < 0 || offset > destination.length - length) throw new IndexOutOfBoundsException("Destination too small");
        ByteBuffer view = pixels(); view.limit(length); view.get(destination, offset, length); return length;
    }
}
