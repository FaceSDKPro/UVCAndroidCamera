// Copyright 2026 FaceSDKPro. Apache-2.0.
package com.serenegiant.usb;

import java.nio.ByteBuffer;

/**
 * Optional frame metadata without another pixel copy or a per-frame metadata array.
 * receivedTimeNanos is sampled before native queueing/decoding, in Android elapsedRealtimeNanos
 * time. It is host receipt, NOT exposure time. Native sequence is unsigned 32-bit (wraps).
 * Pixels have the same borrowed lifetime as IFrameCallback.
 */
public interface ITimestampedFrameCallback extends IFrameCallback {
    void onFrame(ByteBuffer frame, long receivedTimeNanos, long nativeSequence);

    /** An older native library can still invoke the original callback; missing metadata is explicit. */
    @Override default void onFrame(ByteBuffer frame) { onFrame(frame, 0, -1); }
}
