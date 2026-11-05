package com.facesdkpro.uvccamera;

/** Immutable counters for one dual-camera session. Safe to read from any thread. */
public final class PairingDiagnostics {
    public final long sessionId;
    /** Valid, ordered frames accepted from each camera. */
    public final long rgbFrames, irFrames;
    /** Pair callbacks started, including a callback that subsequently throws. */
    public final long dispatchedPairs;
    /** Newer frames replaced these waiting frames while the consumer was busy or rate limited. */
    public final long replacedRgb, replacedIr;
    public final long outOfOrderRgb, outOfOrderIr;
    public final long expiredRgb, expiredIr;
    /** Older frames discarded because the two arrivals exceeded maxPairSkewMillis. */
    public final long skewRgb, skewIr;
    public final boolean pendingRgb, pendingIr;

    PairingDiagnostics(long sessionId, long rgbFrames, long irFrames, long dispatchedPairs,
            long replacedRgb, long replacedIr, long outOfOrderRgb, long outOfOrderIr,
            long expiredRgb, long expiredIr, long skewRgb, long skewIr,
            boolean pendingRgb, boolean pendingIr) {
        this.sessionId = sessionId;
        this.rgbFrames = rgbFrames; this.irFrames = irFrames; this.dispatchedPairs = dispatchedPairs;
        this.replacedRgb = replacedRgb; this.replacedIr = replacedIr;
        this.outOfOrderRgb = outOfOrderRgb; this.outOfOrderIr = outOfOrderIr;
        this.expiredRgb = expiredRgb; this.expiredIr = expiredIr;
        this.skewRgb = skewRgb; this.skewIr = skewIr;
        this.pendingRgb = pendingRgb; this.pendingIr = pendingIr;
    }

    public long discardedFrames() {
        return replacedRgb + replacedIr + outOfOrderRgb + outOfOrderIr
                + expiredRgb + expiredIr + skewRgb + skewIr;
    }
}
