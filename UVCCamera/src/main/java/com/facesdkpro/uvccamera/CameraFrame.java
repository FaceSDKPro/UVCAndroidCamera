package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;
import java.nio.ByteBuffer;

/**
 * Borrowed decoded pixels: valid only inside FrameListener.onFrame. No pixel copy is made.
 * arrivalTimeNanos uses Android elapsedRealtimeNanos, before queueing/decoding when native
 * receipt metadata is available. Neither receipt nor callback time is sensor exposure time.
 */
public final class CameraFrame {
    public enum LumaRange { FULL, VIDEO, UNSPECIFIED }
    public final int width, height, pixelFormat;
    /** Capture geometry and clockwise rotation already applied to pixels. Never rotate them again by this amount. */
    public final int sourceWidth, sourceHeight, appliedRotationDegrees;
    public final long sequence, arrivalTimeNanos;
    /** Zero / -1 means an older native callback did not supply this metadata. */
    public final long receivedTimeNanos, nativeSequence;
    /** Java delivery time, after decode/conversion; useful for measuring input latency. */
    public final long callbackTimeNanos;
    /** No automatic contrast stretching or range normalization is applied to source Y. */
    public final LumaRange lumaRange;
    private final ByteBuffer pixels;

    CameraFrame(ByteBuffer pixels, StreamConfig config, long sequence, long arrivalTimeNanos) {
        this(pixels, config, 0, sequence, arrivalTimeNanos);
    }

    CameraFrame(ByteBuffer pixels, StreamConfig config, int rotationDegrees, long sequence, long arrivalTimeNanos) {
        this(pixels, config, rotationDegrees, sequence, arrivalTimeNanos, 0, -1);
    }

    CameraFrame(ByteBuffer pixels, StreamConfig config, int rotationDegrees, long sequence,
            long callbackTimeNanos, long receivedTimeNanos, long nativeSequence) {
        this.pixels = pixels; // JNI creates a separate borrowed view for each callback.
        this.sourceWidth = config.width; this.sourceHeight = config.height; this.appliedRotationDegrees = rotationDegrees;
        this.width = FrameTransforms.outputWidth(config.width, config.height, rotationDegrees);
        this.height = FrameTransforms.outputHeight(config.width, config.height, rotationDegrees); this.pixelFormat = config.pixelFormat;
        this.sequence = sequence;
        this.callbackTimeNanos = callbackTimeNanos;
        this.receivedTimeNanos = receivedTimeNanos;
        this.nativeSequence = nativeSequence;
        this.arrivalTimeNanos = receivedTimeNanos > 0 ? receivedTimeNanos : callbackTimeNanos;
        this.lumaRange = config.frameType != UVCCamera.UVC_VS_FRAME_MJPEG ? LumaRange.UNSPECIFIED
                : config.pixelFormat == UVCCamera.PIXEL_FORMAT_GRAY8 ? LumaRange.FULL
                : config.pixelFormat == UVCCamera.PIXEL_FORMAT_NV21 || config.pixelFormat == UVCCamera.PIXEL_FORMAT_NV12
                ? LumaRange.VIDEO : LumaRange.UNSPECIFIED;
    }

    /** Includes native queueing/decode/conversion; -1 when native receipt time is unavailable. */
    public long inputLatencyNanos() {
        return receivedTimeNanos > 0 && callbackTimeNanos >= receivedTimeNanos
                ? callbackTimeNanos - receivedTimeNanos : -1;
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
