package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class DualCameraContractTest {
    private DualCameraConfig config() {
        return new DualCameraConfig(
                new StreamConfig(4, 2, 30, UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0),
                new StreamConfig(4, 2, 30, UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_GRAY8, 0),
                50, 150, 120);
    }
    private CameraFrame frame(boolean rgb, int value, long sequence, long arrival) {
        StreamConfig stream = rgb ? config().rgb : config().ir;
        ByteBuffer pixels = ByteBuffer.allocateDirect(rgb ? 12 : 8);
        while (pixels.hasRemaining()) pixels.put((byte)value);
        pixels.flip(); return new CameraFrame(pixels, stream, sequence, arrival);
    }
    private void await(CountDownLatch latch) throws Exception { assertTrue("Timed out", latch.await(3, TimeUnit.SECONDS)); }

    @Test public void nv21SlicesRespectInputWindowAndChromaOrderWithoutCopying() {
        ByteBuffer storage = ByteBuffer.allocateDirect(20);
        for (int i = 0; i < 20; i++) storage.put((byte)i);
        storage.position(3); storage.limit(15);
        Nv21Planes planes = Nv21Planes.from(storage, 4, 2);
        assertEquals(3, planes.y().get(0)); assertEquals(11, planes.v().get(0)); assertEquals(12, planes.u().get(0));
        assertEquals(3, planes.u().capacity()); assertEquals(4, planes.v().capacity());
        assertEquals(4, planes.yRowStride); assertEquals(4, planes.uRowStride); assertEquals(2, planes.uvPixelStride);
        assertTrue(planes.u().isDirect()); assertTrue(planes.u().isReadOnly());
        storage.put(12, (byte)99); assertEquals(99, planes.u().get(0));
        planes.y().position(4); assertEquals(0, planes.y().position()); assertEquals(3, storage.position());
    }

    @Test public void nv21RejectsHeapWrongCapacityAndOddGeometry() {
        for (ByteBuffer buffer : new ByteBuffer[]{ByteBuffer.allocate(12), ByteBuffer.allocateDirect(11), ByteBuffer.allocateDirect(13)}) {
            assertThrows(IllegalArgumentException.class, () -> Nv21Planes.from(buffer, 4, 2));
        }
        assertThrows(IllegalArgumentException.class, () -> Nv21Planes.from(ByteBuffer.allocateDirect(9), 3, 2));
    }

    @Test public void pairedConfigRejectsWrongFormatsAndUnboundedSettings() {
        DualCameraConfig valid = config();
        assertThrows(IllegalArgumentException.class, () -> new DualCameraConfig(valid.ir, valid.rgb, 50, 150, 15));
        assertThrows(IllegalArgumentException.class, () -> new DualCameraConfig(valid.rgb, valid.ir, 50, 49, 15));
        assertThrows(IllegalArgumentException.class, () -> new DualCameraConfig(valid.rgb, valid.ir, 50, 150, 0));
        StreamConfig large = new StreamConfig(3840, 2160, 30, UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0);
        assertThrows(IllegalArgumentException.class, () -> new DualCameraConfig(large, valid.ir, 50, 150, 15));
    }

    @Test public void namedConfigStepsKeepDefaultsAndPreserveImmutableRotations() {
        DualCameraConfig original = config();
        DualCameraConfig defaults = DualCameraConfig.forStreams(original.rgb, original.ir);
        assertEquals(50, defaults.maxPairSkewMillis);
        assertEquals(150, defaults.maxFrameAgeMillis);
        assertEquals(15, defaults.maxPairsPerSecond);
        DualCameraConfig rotated = defaults.withRotations(90, 270);
        DualCameraConfig tuned = rotated.withPairingLimits(40, 200, 20);
        assertEquals(0, defaults.rgbRotationDegrees);
        assertEquals(15, defaults.maxPairsPerSecond);
        assertEquals(90, rotated.rgbRotationDegrees);
        assertEquals(270, tuned.irRotationDegrees);
        assertEquals(40, tuned.maxPairSkewMillis);
        assertEquals(200, tuned.maxFrameAgeMillis);
        assertEquals(20, tuned.maxPairsPerSecond);
        assertThrows(IllegalArgumentException.class, () -> tuned.withPairingLimits(40, 39, 20));
    }

    @Test public void dispatcherCopiesBorrowedInputAndDrainsOnlyAfterConsumerReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicInteger pixel = new AtomicInteger(); List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        FramePairDispatcher dispatcher = new FramePairDispatcher(17, config(), System::nanoTime, pair -> {
            entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
            assertEquals(17, pair.sessionId); assertEquals(1, pair.pairId); assertTrue(pair.rgb.pixels().isReadOnly());
            pixel.set(pair.rgb.pixels().get(0));
        }, errors::add, drained::countDown);
        ByteBuffer original = ByteBuffer.allocateDirect(12); original.put(0, (byte)11);
        long now = System.nanoTime(); CameraFrame rgb = new CameraFrame(original, config().rgb, 1, now);
        dispatcher.offer(true, rgb); dispatcher.offer(false, frame(false, 22, 2, now)); await(entered);
        // The source belongs to the camera; subsequent writes must not affect this owned pair slot.
        original.put(0, (byte)99);
        dispatcher.close(); assertEquals(1, drained.getCount()); release.countDown(); await(drained);
        assertEquals(11, pixel.get()); assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test public void slowConsumerReceivesLatestPairAndNeverConcurrentCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        FramePairDispatcher dispatcher = new FramePairDispatcher(1, config(), System::nanoTime, pair -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                sequences.add(pair.rgb.sequence);
                if (pair.pairId == 1) {
                    entered.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
                } else second.countDown();
            } finally { active.decrementAndGet(); }
        }, errors::add, drained::countDown);
        long now = System.nanoTime(); dispatcher.offer(true, frame(true, 1, 1, now)); dispatcher.offer(false, frame(false, 1, 1, now)); await(entered);
        for (int i = 2; i <= 100; i++) {
            now = System.nanoTime(); dispatcher.offer(true, frame(true, i, i, now)); dispatcher.offer(false, frame(false, i, i, now));
        }
        release.countDown(); await(second); dispatcher.close(); await(drained);
        assertEquals(java.util.Arrays.asList(1L, 100L), sequences); assertEquals(1, maximum.get()); assertTrue(errors.toString(), errors.isEmpty());
        PairingDiagnostics stats = dispatcher.diagnostics();
        assertEquals(100, stats.rgbFrames); assertEquals(100, stats.irFrames);
        assertEquals(2, stats.dispatchedPairs);
        assertEquals(98, stats.replacedRgb); assertEquals(98, stats.replacedIr);
        assertEquals(196, stats.discardedFrames());
    }

    @Test public void staleSkewedAndOutOfOrderFramesCannotBeDeliveredAsCurrentPair() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicLong sequence = new AtomicLong(); List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        FramePairDispatcher dispatcher = new FramePairDispatcher(1, config(), System::nanoTime, pair -> {
            sequence.set(pair.rgb.sequence); delivered.countDown();
        }, errors::add, drained::countDown);
        long now = System.nanoTime();
        dispatcher.offer(true, frame(true, 1, 1, now - TimeUnit.SECONDS.toNanos(1)));
        dispatcher.offer(false, frame(false, 1, 1, now - TimeUnit.SECONDS.toNanos(1)));
        Thread.sleep(30); assertEquals(1, delivered.getCount());
        now = System.nanoTime(); dispatcher.offer(true, frame(true, 2, 2, now - TimeUnit.MILLISECONDS.toNanos(100)));
        dispatcher.offer(false, frame(false, 2, 2, now)); Thread.sleep(30); assertEquals(1, delivered.getCount());
        long fresh = System.nanoTime(); dispatcher.offer(true, frame(true, 3, 3, fresh));
        dispatcher.offer(true, frame(true, 4, 4, fresh - 1)); // cannot replace a newer sample
        dispatcher.offer(false, frame(false, 3, 3, fresh)); await(delivered);
        dispatcher.close(); await(drained); assertEquals(3, sequence.get()); assertTrue(errors.toString(), errors.isEmpty());
        PairingDiagnostics stats = dispatcher.diagnostics();
        assertEquals(3, stats.rgbFrames); assertEquals(3, stats.irFrames);
        assertEquals(1, stats.expiredRgb); assertEquals(1, stats.expiredIr);
        assertEquals(1, stats.skewRgb); assertEquals(1, stats.outOfOrderRgb);
        assertEquals(1, stats.replacedIr);
        assertEquals(1, stats.dispatchedPairs); assertEquals(5, stats.discardedFrames());
    }

    @Test public void consumerFailureStopsDeliveryAndReportsOnce() throws Exception {
        CountDownLatch failed = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), failures = new AtomicInteger();
        FramePairDispatcher dispatcher = new FramePairDispatcher(1, config(), System::nanoTime, pair -> {
            calls.incrementAndGet(); throw new IllegalStateException("fixture");
        }, error -> { failures.incrementAndGet(); failed.countDown(); }, drained::countDown);
        long now = System.nanoTime(); dispatcher.offer(true, frame(true, 1, 1, now)); dispatcher.offer(false, frame(false, 1, 1, now));
        await(failed); await(drained);
        dispatcher.offer(true, frame(true, 2, 2, now + 1)); dispatcher.offer(false, frame(false, 2, 2, now + 1));
        dispatcher.close(); assertEquals(1, calls.get()); assertEquals(1, failures.get());
    }
}
