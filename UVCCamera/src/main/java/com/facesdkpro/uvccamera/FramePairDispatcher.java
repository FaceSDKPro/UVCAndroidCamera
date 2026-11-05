package com.facesdkpro.uvccamera;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Latest-only ownership engine: two fixed buffers per role, one serial pair consumer. */
final class FramePairDispatcher implements AutoCloseable {
    interface Consumer { void accept(FramePair pair); }
    interface Failure { void accept(Throwable error); }
    interface Clock { long now(); }
    /** Test seam for slow copies; production uses bulk copy / native rotation. */
    interface Copier { void copy(CameraFrame source, ByteBuffer destination, int rotation); }

    private static final class Slot {
        final ByteBuffer pixels;
        long sequence, arrival, callback, received, nativeSequence;
        Slot(int size) { pixels = ByteBuffer.allocateDirect(size); }
    }
    private final Object lock = new Object(), rgbProducer = new Object(), irProducer = new Object();
    private final ArrayDeque<Slot> rgbPool = new ArrayDeque<>(), irPool = new ArrayDeque<>();
    private final DualCameraConfig config;
    private final long sessionId, maxAge, maxSkew, interval, healthGap;
    private final Clock clock;
    private final Consumer consumer;
    private final Failure failure;
    private final Copier copier;
    private final Runnable drained;
    private final ScheduledThreadPoolExecutor worker;
    private Slot rgb, ir;
    private long pairId, lastStart, lastRgbArrival = Long.MIN_VALUE, lastIrArrival = Long.MIN_VALUE;
    private long lastRgbSequence = -1, lastIrSequence = -1, lastRgbNative = -1, lastIrNative = -1;
    private long rgbFrames, irFrames, replacedRgb, replacedIr, outOfOrderRgb, outOfOrderIr;
    private long expiredRgb, expiredIr, skewRgb, skewIr, rejectedAt;
    private long lastConsumerDuration, maxConsumerDuration;
    private PairingDiagnostics.State rejection;
    private int activeCopies;
    private boolean closed, scheduled, consuming, workerExited, drainedDelivered;

    FramePairDispatcher(long sessionId, DualCameraConfig config, Clock clock,
            Consumer consumer, Failure failure, Runnable drained) {
        this(sessionId, config, clock, consumer, failure, drained, FramePairDispatcher::copyPixels);
    }

    FramePairDispatcher(long sessionId, DualCameraConfig config, Clock clock,
            Consumer consumer, Failure failure, Runnable drained, Copier copier) {
        this.sessionId = sessionId; this.config = config; this.clock = clock;
        this.consumer = consumer; this.failure = failure; this.drained = drained; this.copier = copier;
        maxAge = TimeUnit.MILLISECONDS.toNanos(config.maxFrameAgeMillis);
        maxSkew = TimeUnit.MILLISECONDS.toNanos(config.maxPairSkewMillis);
        interval = TimeUnit.SECONDS.toNanos(1) / config.maxPairsPerSecond;
        healthGap = Math.max(TimeUnit.MILLISECONDS.toNanos(300), maxAge * 2);
        for (int i = 0; i < 2; i++) {
            rgbPool.add(new Slot(config.rgb.width * config.rgb.height * 3 / 2));
            irPool.add(new Slot(config.ir.width * config.ir.height));
        }
        worker = new ScheduledThreadPoolExecutor(1, task -> new Thread(task, "UVC-pairs")) {
            @Override protected void terminated() {
                synchronized (lock) { workerExited = true; }
                notifyDrained();
            }
        };
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setRemoveOnCancelPolicy(true);
    }

    private static void copyPixels(CameraFrame frame, ByteBuffer target, int rotation) {
        ByteBuffer source = frame.pixels();
        if (source.remaining() != target.capacity())
            throw new IllegalArgumentException("Unexpected packed frame capacity");
        target.clear();
        if (rotation == 0) { target.put(source); target.flip(); }
        else FrameTransforms.copyRotated(source, target, frame.width, frame.height, frame.pixelFormat, rotation);
    }

    void offer(boolean isRgb, CameraFrame frame) {
        // Serialize only producers of the SAME role. A slow RGB rotation must not block IR receipt,
        // health snapshots, pair delivery, or close on the other threads.
        synchronized (isRgb ? rgbProducer : irProducer) {
            Slot slot;
            synchronized (lock) {
                if (closed) return;
                StreamConfig stream = isRgb ? config.rgb : config.ir;
                if (frame.width != stream.width || frame.height != stream.height || frame.pixelFormat != stream.pixelFormat)
                    throw new IllegalArgumentException("Frame does not match its role's configured format");
                long previousTime = isRgb ? lastRgbArrival : lastIrArrival;
                long previousSequence = isRgb ? lastRgbSequence : lastIrSequence;
                long previousNative = isRgb ? lastRgbNative : lastIrNative;
                long nativeDelta = (frame.nativeSequence - previousNative) & 0xffffffffL;
                if (frame.arrivalTimeNanos <= previousTime || frame.sequence <= previousSequence
                        || (frame.nativeSequence >= 0 && previousNative >= 0
                            && (nativeDelta == 0 || nativeDelta >= 0x80000000L))) {
                    if (isRgb) outOfOrderRgb++; else outOfOrderIr++;
                    return;
                }
                if (isRgb) {
                    lastRgbArrival = frame.arrivalTimeNanos; lastRgbSequence = frame.sequence;
                    lastRgbNative = frame.nativeSequence;
                } else {
                    lastIrArrival = frame.arrivalTimeNanos; lastIrSequence = frame.sequence;
                    lastIrNative = frame.nativeSequence;
                }
                long now = clock.now();
                // Reject before copying/rotation: decode time must not make an old USB frame fresh.
                if (frame.arrivalTimeNanos < 0 || now < frame.arrivalTimeNanos || now - frame.arrivalTimeNanos > maxAge) {
                    if (isRgb) expiredRgb++; else expiredIr++;
                    rejectLocked(PairingDiagnostics.State.FRAME_EXPIRED, now);
                    return;
                }
                ArrayDeque<Slot> pool = isRgb ? rgbPool : irPool;
                Slot old = isRgb ? rgb : ir;
                if (old != null) {
                    pool.add(old);
                    if (isRgb) replacedRgb++; else replacedIr++;
                }
                if (isRgb) rgb = null; else ir = null;
                slot = pool.removeFirst();
                activeCopies++;
            }
            boolean copied = false;
            try {
                copier.copy(frame, slot.pixels, isRgb ? config.rgbRotationDegrees : config.irRotationDegrees);
                slot.sequence = frame.sequence; slot.arrival = frame.arrivalTimeNanos;
                slot.callback = frame.callbackTimeNanos; slot.received = frame.receivedTimeNanos;
                slot.nativeSequence = frame.nativeSequence;
                copied = true;
            } finally {
                synchronized (lock) {
                    activeCopies--;
                    if (!closed) {
                        if (copied) {
                            if (isRgb) { rgb = slot; rgbFrames++; } else { ir = slot; irFrames++; }
                            scheduleLocked();
                        } else (isRgb ? rgbPool : irPool).add(slot);
                    }
                }
                notifyDrained();
            }
        }
    }

    private void rejectLocked(PairingDiagnostics.State reason, long now) {
        rejection = reason; rejectedAt = now;
    }

    private void scheduleLocked() {
        if (closed || scheduled || rgb == null || ir == null) return;
        scheduled = true;
        long delay = lastStart == 0 ? 0 : Math.max(0, interval - (clock.now() - lastStart));
        worker.schedule(this::deliver, delay, TimeUnit.NANOSECONDS);
    }

    private CameraFrame frame(Slot slot, boolean isRgb) {
        return new CameraFrame(slot.pixels.asReadOnlyBuffer(), isRgb ? config.rgb : config.ir,
                isRgb ? config.rgbRotationDegrees : config.irRotationDegrees, slot.sequence,
                slot.callback, slot.received, slot.nativeSequence);
    }

    private void deliver() {
        Slot a, b;
        long deliveredId;
        synchronized (lock) {
            if (closed) { scheduled = false; return; }
            long now = clock.now();
            if (rgb != null && (now < rgb.arrival || now - rgb.arrival > maxAge)) {
                rgbPool.add(rgb); rgb = null; expiredRgb++;
                rejectLocked(PairingDiagnostics.State.FRAME_EXPIRED, now);
            }
            if (ir != null && (now < ir.arrival || now - ir.arrival > maxAge)) {
                irPool.add(ir); ir = null; expiredIr++;
                rejectLocked(PairingDiagnostics.State.FRAME_EXPIRED, now);
            }
            if (rgb == null || ir == null) { scheduled = false; return; }
            if (Math.abs(rgb.arrival - ir.arrival) > maxSkew) {
                if (rgb.arrival < ir.arrival) { rgbPool.add(rgb); rgb = null; skewRgb++; }
                else { irPool.add(ir); ir = null; skewIr++; }
                rejectLocked(PairingDiagnostics.State.FRAME_SKEW, now);
                scheduled = false; return;
            }
            a = rgb; b = ir; rgb = null; ir = null; consuming = true;
            lastStart = now; deliveredId = ++pairId;
        }
        Throwable error = null;
        try {
            consumer.accept(new FramePair(sessionId, deliveredId, frame(a, true), frame(b, false)));
        } catch (RuntimeException | Error problem) { error = problem; }
        finally {
            synchronized (lock) {
                consuming = false;
                lastConsumerDuration = Math.max(0, clock.now() - lastStart);
                maxConsumerDuration = Math.max(maxConsumerDuration, lastConsumerDuration);
                if (!closed) { rgbPool.add(a); irPool.add(b); }
                scheduled = false;
                if (error == null) scheduleLocked();
            }
        }
        if (error != null) { close(); failure.accept(error); }
    }

    PairingDiagnostics diagnostics() {
        synchronized (lock) {
            long now = clock.now();
            PairingDiagnostics.State state;
            if (closed) state = PairingDiagnostics.State.CLOSED;
            else if (consuming && now - lastStart > maxAge) state = PairingDiagnostics.State.CONSUMER_BUSY;
            else if (rejection != null && rejectedAt > lastStart && now - rejectedAt <= healthGap) state = rejection;
            else if (lastStart != 0 && now - lastStart <= healthGap) state = PairingDiagnostics.State.PAIRED;
            else if (rgb == null && ir == null) state = PairingDiagnostics.State.WAITING_BOTH;
            else state = rgb == null ? PairingDiagnostics.State.WAITING_RGB : PairingDiagnostics.State.WAITING_IR;
            return new PairingDiagnostics(sessionId, rgbFrames, irFrames, pairId,
                    replacedRgb, replacedIr, outOfOrderRgb, outOfOrderIr,
                    expiredRgb, expiredIr, skewRgb, skewIr, rgb != null, ir != null,
                    state, lastStart, lastRgbArrival == Long.MIN_VALUE ? 0 : lastRgbArrival,
                    lastIrArrival == Long.MIN_VALUE ? 0 : lastIrArrival,
                    lastConsumerDuration, maxConsumerDuration, consuming, activeCopies);
        }
    }

    private void notifyDrained() {
        boolean notify;
        synchronized (lock) {
            notify = closed && workerExited && activeCopies == 0 && !drainedDelivered;
            if (notify) drainedDelivered = true;
        }
        if (notify) drained.run();
    }

    /** No joins: both the consumer and any out-of-lock copies drain before notification. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            rgb = null; ir = null; rgbPool.clear(); irPool.clear();
        }
        // shutdown can synchronously invoke terminated(); never hold the pairing lock around it.
        worker.shutdown();
    }
}
