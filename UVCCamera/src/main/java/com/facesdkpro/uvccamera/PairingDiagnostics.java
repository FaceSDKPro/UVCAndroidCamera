package com.facesdkpro.uvccamera;

/** Immutable, on-demand snapshot. No per-frame status callbacks or image/identity logging. */
public final class PairingDiagnostics {
    static PairingDiagnostics empty(long sessionId, boolean closed) {
        return new PairingDiagnostics(sessionId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false,
                closed ? State.CLOSED : State.WAITING_BOTH, 0, 0, 0, 0, 0, false, 0);
    }
    public enum State {
        WAITING_BOTH, WAITING_RGB, WAITING_IR, PAIRED, FRAME_SKEW, FRAME_EXPIRED, CONSUMER_BUSY, CLOSED
    }
    public final long sessionId;
    /** Fresh, ordered frames copied into the fixed pairing pool. */
    public final long rgbFrames, irFrames;
    /** Callbacks started, including an active callback or one that subsequently fails. */
    public final long dispatchedPairs;
    public final long replacedRgb, replacedIr, outOfOrderRgb, outOfOrderIr;
    public final long expiredRgb, expiredIr, skewRgb, skewIr;
    public final boolean pendingRgb, pendingIr;
    public final State state;
    /** All times share the pairing clock. Zero pair time means none dispatched yet. */
    public final long lastPairTimeNanos, lastRgbTimeNanos, lastIrTimeNanos;
    public final long lastConsumerDurationNanos, maxConsumerDurationNanos;
    public final boolean consumerActive;
    public final int activeCopies;

    PairingDiagnostics(long sessionId, long rgbFrames, long irFrames, long dispatchedPairs,
            long replacedRgb, long replacedIr, long outOfOrderRgb, long outOfOrderIr,
            long expiredRgb, long expiredIr, long skewRgb, long skewIr,
            boolean pendingRgb, boolean pendingIr) {
        this(sessionId, rgbFrames, irFrames, dispatchedPairs, replacedRgb, replacedIr,
                outOfOrderRgb, outOfOrderIr, expiredRgb, expiredIr, skewRgb, skewIr, pendingRgb, pendingIr,
                State.WAITING_BOTH, 0, 0, 0, 0, 0, false, 0);
    }

    PairingDiagnostics(long sessionId, long rgbFrames, long irFrames, long dispatchedPairs,
            long replacedRgb, long replacedIr, long outOfOrderRgb, long outOfOrderIr,
            long expiredRgb, long expiredIr, long skewRgb, long skewIr,
            boolean pendingRgb, boolean pendingIr, State state, long lastPair, long lastRgb, long lastIr,
            long lastConsumer, long maxConsumer, boolean consumerActive, int activeCopies) {
        this.sessionId = sessionId;
        this.rgbFrames = rgbFrames; this.irFrames = irFrames; this.dispatchedPairs = dispatchedPairs;
        this.replacedRgb = replacedRgb; this.replacedIr = replacedIr;
        this.outOfOrderRgb = outOfOrderRgb; this.outOfOrderIr = outOfOrderIr;
        this.expiredRgb = expiredRgb; this.expiredIr = expiredIr;
        this.skewRgb = skewRgb; this.skewIr = skewIr;
        this.pendingRgb = pendingRgb; this.pendingIr = pendingIr;
        this.state = state; this.lastPairTimeNanos = lastPair;
        this.lastRgbTimeNanos = lastRgb; this.lastIrTimeNanos = lastIr;
        this.lastConsumerDurationNanos = lastConsumer; this.maxConsumerDurationNanos = maxConsumer;
        this.consumerActive = consumerActive; this.activeCopies = activeCopies;
    }

    public long discardedFrames() {
        return replacedRgb + replacedIr + outOfOrderRgb + outOfOrderIr
                + expiredRgb + expiredIr + skewRgb + skewIr;
    }
}
