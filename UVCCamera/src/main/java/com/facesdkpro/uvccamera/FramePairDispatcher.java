package com.facesdkpro.uvccamera;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Package-private pairing/ownership engine; no USB, UI or face SDK dependency. */
final class FramePairDispatcher implements AutoCloseable {
    interface Consumer { void accept(FramePair pair); }
    interface Failure { void accept(Throwable error); }
    interface Clock { long now(); }
    private static final class Slot {
        final ByteBuffer pixels;
        long sequence, arrival;
        Slot(int size) { pixels = ByteBuffer.allocateDirect(size); }
    }
    private final Object lock = new Object();
    private final ArrayDeque<Slot> rgbPool = new ArrayDeque<>(), irPool = new ArrayDeque<>();
    private final DualCameraConfig config;
    private final long sessionId;
    private final Clock clock;
    private final Consumer consumer;
    private final Failure failure;
    private final ScheduledThreadPoolExecutor worker;
    private Slot rgb, ir;
    private long pairId, lastStart, lastRgbArrival = Long.MIN_VALUE, lastIrArrival = Long.MIN_VALUE;
    private boolean closed, scheduled;

    FramePairDispatcher(long sessionId, DualCameraConfig config, Clock clock,
            Consumer consumer, Failure failure, Runnable drained) {
        this.sessionId = sessionId; this.config = config; this.clock = clock;
        this.consumer = consumer; this.failure = failure;
        for (int i = 0; i < 2; i++) {
            rgbPool.add(new Slot(config.rgb.width * config.rgb.height * 3 / 2));
            irPool.add(new Slot(config.ir.width * config.ir.height));
        }
        worker = new ScheduledThreadPoolExecutor(1, task -> new Thread(task, "UVC-pairs")) {
            @Override protected void terminated() { drained.run(); }
        };
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setRemoveOnCancelPolicy(true);
    }

    void offer(boolean isRgb, CameraFrame frame) {
        synchronized (lock) {
            if (closed) return;
            StreamConfig stream = isRgb ? config.rgb : config.ir;
            if (frame.width != stream.width || frame.height != stream.height || frame.pixelFormat != stream.pixelFormat)
                throw new IllegalArgumentException("Frame does not match its role's configured format");
            long previous = isRgb ? lastRgbArrival : lastIrArrival;
            if (frame.arrivalTimeNanos <= previous) return;
            if (isRgb) lastRgbArrival = frame.arrivalTimeNanos; else lastIrArrival = frame.arrivalTimeNanos;
            ArrayDeque<Slot> pool = isRgb ? rgbPool : irPool;
            Slot old = isRgb ? rgb : ir;
            if (old != null) pool.add(old);
            if (isRgb) rgb = null; else ir = null;
            Slot slot = pool.removeFirst();
            ByteBuffer source = frame.pixels();
            if (source.remaining() != slot.pixels.capacity()) {
                pool.add(slot); throw new IllegalArgumentException("Unexpected packed frame capacity");
            }
            slot.pixels.clear();
            int rotation = isRgb ? config.rgbRotationDegrees : config.irRotationDegrees;
            try {
                if (rotation == 0) { slot.pixels.put(source); slot.pixels.flip(); }
                else FrameTransforms.copyRotated(source, slot.pixels, stream.width, stream.height, stream.pixelFormat, rotation);
            }
            catch (RuntimeException | Error error) { pool.add(slot); throw error; }
            slot.sequence = frame.sequence; slot.arrival = frame.arrivalTimeNanos;
            if (isRgb) rgb = slot; else ir = slot;
            scheduleLocked();
        }
    }

    private void scheduleLocked() {
        if (closed || scheduled || rgb == null || ir == null) return;
        scheduled = true;
        long interval = TimeUnit.SECONDS.toNanos(1) / config.maxPairsPerSecond;
        long delay = lastStart == 0 ? 0 : Math.max(0, interval - (clock.now() - lastStart));
        worker.schedule(this::deliver, delay, TimeUnit.NANOSECONDS);
    }

    private void deliver() {
        Slot a, b;
        synchronized (lock) {
            if (closed) { scheduled = false; return; }
            long now = clock.now(), maxAge = TimeUnit.MILLISECONDS.toNanos(config.maxFrameAgeMillis);
            if (rgb != null && (now < rgb.arrival || now - rgb.arrival > maxAge)) { rgbPool.add(rgb); rgb = null; }
            if (ir != null && (now < ir.arrival || now - ir.arrival > maxAge)) { irPool.add(ir); ir = null; }
            if (rgb == null || ir == null) { scheduled = false; return; }
            if (Math.abs(rgb.arrival - ir.arrival) > TimeUnit.MILLISECONDS.toNanos(config.maxPairSkewMillis)) {
                if (rgb.arrival < ir.arrival) { rgbPool.add(rgb); rgb = null; }
                else { irPool.add(ir); ir = null; }
                scheduled = false; return;
            }
            a = rgb; b = ir; rgb = null; ir = null; lastStart = now;
        }
        Throwable error = null;
        try {
            consumer.accept(new FramePair(sessionId, ++pairId,
                    new CameraFrame(a.pixels.asReadOnlyBuffer(), config.rgb, config.rgbRotationDegrees, a.sequence, a.arrival),
                    new CameraFrame(b.pixels.asReadOnlyBuffer(), config.ir, config.irRotationDegrees, b.sequence, b.arrival)));
        } catch (RuntimeException | Error problem) { error = problem; }
        finally {
            synchronized (lock) {
                if (!closed) { rgbPool.add(a); irPool.add(b); }
                scheduled = false;
                if (error == null) scheduleLocked();
            }
        }
        if (error != null) {
            close(); failure.accept(error);
        }
    }

    /** Reject immediately; an active consumer completes before drained is delivered. Never waits. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            rgb = null; ir = null; rgbPool.clear(); irPool.clear();
            worker.shutdown();
        }
    }
}
