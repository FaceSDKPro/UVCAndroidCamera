package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;

/** Exact RGB NV21 / IR GRAY8 modes and bounded software pairing limits. No liveness policy. */
public final class DualCameraConfig {
    private static final int DEFAULT_SKEW_MILLIS = 50;
    private static final int DEFAULT_AGE_MILLIS = 150;
    private static final int DEFAULT_PAIRS_PER_SECOND = 15;
    public final StreamConfig rgb, ir;
    public final int maxPairSkewMillis, maxFrameAgeMillis, maxPairsPerSecond;
    public final int rgbRotationDegrees, irRotationDegrees;

    public DualCameraConfig(StreamConfig rgb, StreamConfig ir, int maxPairSkewMillis,
            int maxFrameAgeMillis, int maxPairsPerSecond) {
        this(rgb, ir, maxPairSkewMillis, maxFrameAgeMillis, maxPairsPerSecond, 0, 0);
    }

    public DualCameraConfig(StreamConfig rgb, StreamConfig ir, int maxPairSkewMillis,
            int maxFrameAgeMillis, int maxPairsPerSecond, int rgbRotationDegrees, int irRotationDegrees) {
        FrameTransforms.validateRotation(rgbRotationDegrees); FrameTransforms.validateRotation(irRotationDegrees);
        if (rgb == null || ir == null) throw new NullPointerException("stream config");
        if (rgb.pixelFormat != UVCCamera.PIXEL_FORMAT_NV21
                || ir.pixelFormat != UVCCamera.PIXEL_FORMAT_GRAY8) {
            throw new IllegalArgumentException("Dual capture requires RGB NV21 and IR GRAY8");
        }
        // One in-flight and one latest buffer per role: at most approximately 10 MiB.
        if (Math.max(rgb.width, rgb.height) > 1920 || Math.min(rgb.width, rgb.height) > 1080
                || Math.max(ir.width, ir.height) > 1920 || Math.min(ir.width, ir.height) > 1080) {
            throw new IllegalArgumentException("Paired capture supports at most portrait/landscape 1080p");
        }
        if (maxPairSkewMillis < 1 || maxPairSkewMillis > 200 || maxFrameAgeMillis < maxPairSkewMillis
                || maxFrameAgeMillis > 1000 || maxPairsPerSecond < 1 || maxPairsPerSecond > 120) {
            throw new IllegalArgumentException("Invalid pairing skew, age or delivery rate");
        }
        this.rgb = rgb; this.ir = ir; this.maxPairSkewMillis = maxPairSkewMillis;
        this.maxFrameAgeMillis = maxFrameAgeMillis; this.maxPairsPerSecond = maxPairsPerSecond;
        this.rgbRotationDegrees = rgbRotationDegrees; this.irRotationDegrees = irRotationDegrees;
    }

    /** Return an immutable copy with independent clockwise pixel rotations. Reopen the session to apply. */
    public DualCameraConfig withRotations(int rgbDegrees, int irDegrees) {
        return new DualCameraConfig(rgb, ir, maxPairSkewMillis, maxFrameAgeMillis, maxPairsPerSecond, rgbDegrees, irDegrees);
    }

    /** Return an immutable copy with new software pairing limits, preserving both rotations. */
    public DualCameraConfig withPairingLimits(int skewMillis, int ageMillis, int pairsPerSecond) {
        return new DualCameraConfig(rgb, ir, skewMillis, ageMillis, pairsPerSecond,
                rgbRotationDegrees, irRotationDegrees);
    }

    /** Pair caller-selected RGB NV21 and IR GRAY8 streams with the default software limits. */
    public static DualCameraConfig forStreams(StreamConfig rgb, StreamConfig ir) {
        return new DualCameraConfig(rgb, ir, DEFAULT_SKEW_MILLIS, DEFAULT_AGE_MILLIS,
                DEFAULT_PAIRS_PER_SECOND);
    }

    /** 640x480 MJPEG, capture 30 FPS, deliver at most 15 pairs/s, 50 ms arrival skew, 150 ms age.
     * These software limits are starting points, not calibrated sensor synchronization limits. */
    public static DualCameraConfig defaults() {
        return forStreams(StreamConfig.defaults(),
                new StreamConfig(640, 480, 30, UVCCamera.UVC_VS_FRAME_MJPEG,
                        UVCCamera.PIXEL_FORMAT_GRAY8, 0));
    }
}
