package com.facesdkpro.uvccamera;

/**
 * A software-associated RGB/IR sample, borrowed until onFramePair returns. Buffers are SDK-owned,
 * direct and read-only. Do not retain either frame or its views; use explicit copies if necessary.
 * Arrival-time proximity does not prove simultaneous exposure or correspondence of the faces.
 */
public final class FramePair {
    /** Unique within this process; identifies a session, not a person or a sensor clock. */
    public final long sessionId;
    /** Increasing within this session; never use as a face-tracking ID. */
    public final long pairId;
    public final CameraFrame rgb, ir;
    public final long arrivalSkewNanos;

    FramePair(long sessionId, long pairId, CameraFrame rgb, CameraFrame ir) {
        this.sessionId = sessionId; this.pairId = pairId; this.rgb = rgb; this.ir = ir;
        arrivalSkewNanos = Math.abs(rgb.arrivalTimeNanos - ir.arrivalTimeNanos);
    }

    public Nv21Planes rgbPlanes() { return Nv21Planes.from(rgb.pixels(), rgb.width, rgb.height); }
}
