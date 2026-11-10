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
    private void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);
        assertTrue("Timed out waiting for worker state",condition.getAsBoolean());
    }

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

    @Test public void capturePresetsDefaultToMiddleAndKeepPairingLimits() {
        assertEquals(15, DualCameraConfig.defaults().rgb.fps);
        assertEquals(15, DualCameraConfig.defaults().ir.fps);
        for (DualCameraConfig.Mode mode : DualCameraConfig.Mode.values()) {
            DualCameraConfig preset = DualCameraConfig.defaults(mode);
            assertEquals(mode == DualCameraConfig.Mode.MIDDLE ? 15 : 30, preset.rgb.fps);
            assertEquals(preset.rgb.fps, preset.ir.fps);
            assertEquals(640, preset.rgb.width);
            assertEquals(480, preset.ir.height);
            assertEquals(UVCCamera.UVC_VS_FRAME_MJPEG, preset.rgb.frameType);
            assertEquals(preset.rgb.frameType, preset.ir.frameType);
            assertEquals(UVCCamera.PIXEL_FORMAT_NV21, preset.rgb.pixelFormat);
            assertEquals(UVCCamera.PIXEL_FORMAT_GRAY8, preset.ir.pixelFormat);
            assertEquals(15, preset.maxPairsPerSecond);
            assertEquals(50, preset.maxPairSkewMillis);
            assertEquals(150, preset.maxFrameAgeMillis);
        }
        assertThrows(NullPointerException.class, () -> DualCameraConfig.defaults(null));
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
        DualCameraConfig limited = tuned.withMaxPairsPerSecond(10);
        assertEquals(10, limited.maxPairsPerSecond);
        assertEquals(40, limited.maxPairSkewMillis); assertEquals(200, limited.maxFrameAgeMillis);
        assertEquals(90, limited.rgbRotationDegrees); assertEquals(270, limited.irRotationDegrees);
        assertSame(tuned.rgb, limited.rgb); assertSame(tuned.ir, limited.ir);
        assertEquals(20, tuned.maxPairsPerSecond);
        assertThrows(IllegalArgumentException.class, () -> limited.withMaxPairsPerSecond(0));
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
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        FramePairDispatcher dispatcher = new FramePairDispatcher(1, config(), clock::get, pair -> {
            sequence.set(pair.rgb.sequence); delivered.countDown();
        }, errors::add, drained::countDown);
        long now = clock.get();
        dispatcher.offer(true, frame(true, 1, 1, now - TimeUnit.SECONDS.toNanos(1)));
        dispatcher.offer(false, frame(false, 1, 1, now - TimeUnit.SECONDS.toNanos(1)));
        assertEquals(1, delivered.getCount());
        dispatcher.offer(true, frame(true, 2, 2, now - TimeUnit.MILLISECONDS.toNanos(100)));
        dispatcher.offer(false, frame(false, 2, 2, now));
        awaitCondition(()->dispatcher.diagnostics().skewRgb==1);assertEquals(1,delivered.getCount());
        // The old IR remains fresh but is too far from the next RGB. Wait for that rejection,
        // instead of racing a newer IR against the pair worker and asserting a scheduler accident.
        long fresh = clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        dispatcher.offer(true, frame(true, 3, 3, fresh));
        awaitCondition(()->dispatcher.diagnostics().skewIr==1);
        dispatcher.offer(true, frame(true, 4, 4, fresh - 1)); // cannot replace a newer sample
        dispatcher.offer(false, frame(false, 3, 3, fresh)); await(delivered);
        dispatcher.close(); await(drained); assertEquals(3, sequence.get()); assertTrue(errors.toString(), errors.isEmpty());
        PairingDiagnostics stats = dispatcher.diagnostics();
        // Expired input is now discarded BEFORE the pixel copy, not counted as a fresh pooled frame.
        assertEquals(2, stats.rgbFrames); assertEquals(2, stats.irFrames);
        assertEquals(1, stats.expiredRgb); assertEquals(1, stats.expiredIr);
        assertEquals(1, stats.skewRgb); assertEquals(1, stats.outOfOrderRgb);
        assertEquals(0, stats.replacedIr);assertEquals(1,stats.skewIr);
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

    @Test public void nativeReceiptAgeCannotBeRenewedByLateJavaCallbackAndDoesNotCopyPixels() throws Exception {
        CountDownLatch drained=new CountDownLatch(1);AtomicInteger copies=new AtomicInteger();
        long now=TimeUnit.SECONDS.toNanos(5),old=now-TimeUnit.SECONDS.toNanos(1);
        FramePairDispatcher dispatcher=new FramePairDispatcher(1,config(),()->now,pair->fail("Stale pair"),
                error->fail(error.toString()),drained::countDown,(frame,target,rotation)->copies.incrementAndGet());
        try {
            dispatcher.offer(true,new CameraFrame(ByteBuffer.allocateDirect(12),config().rgb,0,1,now,old,1));
            dispatcher.offer(false,new CameraFrame(ByteBuffer.allocateDirect(8),config().ir,0,1,now,old,1));
            PairingDiagnostics stats=dispatcher.diagnostics();
            assertEquals(0,copies.get());assertEquals(0,stats.rgbFrames);assertEquals(0,stats.irFrames);
            assertEquals(1,stats.expiredRgb);assertEquals(1,stats.expiredIr);
            assertEquals(PairingDiagnostics.State.FRAME_EXPIRED,stats.state);
        } finally {dispatcher.close();await(drained);}
    }

    @Test public void slowRgbCopyDoesNotHoldIrOrDiagnosticsLockAndCloseWaitsForCopy() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),drained=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        FramePairDispatcher dispatcher=new FramePairDispatcher(1,config(),System::nanoTime,pair->{},error::set,
                drained::countDown,(frame,target,rotation)->{
                    if(frame.pixelFormat==UVCCamera.PIXEL_FORMAT_NV21){
                        entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}
                        catch(InterruptedException failure){throw new AssertionError(failure);}
                    }
                    target.clear();target.put(frame.pixels());target.flip();
                });
        Thread rgb=new Thread(()->{try{dispatcher.offer(true,frame(true,1,1,System.nanoTime()));}catch(Throwable failure){error.set(failure);}});
        try {
            rgb.start();await(entered);
            dispatcher.offer(false,frame(false,2,1,System.nanoTime()));
            assertTrue(dispatcher.diagnostics().pendingIr);assertEquals(1,dispatcher.diagnostics().activeCopies);
            dispatcher.close();assertEquals(1,drained.getCount());
        } finally {release.countDown();rgb.join(3000);dispatcher.close();await(drained);}
        assertFalse(rgb.isAlive());assertNull(error.get());assertEquals(0,dispatcher.diagnostics().activeCopies);
    }

    @Test public void metadataSurvivesPairOwnershipAndNativeSequenceWrapIsOrdered() throws Exception {
        CountDownLatch delivered=new CountDownLatch(1),drained=new CountDownLatch(1);
        AtomicLong now=new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        java.util.concurrent.atomic.AtomicReference<FramePair> result=new java.util.concurrent.atomic.AtomicReference<>();
        FramePairDispatcher dispatcher=new FramePairDispatcher(1,config(),now::get,pair->{
            result.set(pair);delivered.countDown();
        },error->{throw new AssertionError(error);},drained::countDown);
        try {
            for(int i=0;i<3;i++){
                long time=now.addAndGet(1);
                dispatcher.offer(true,new CameraFrame(ByteBuffer.allocateDirect(12),config().rgb,0,i+1,time,time,
                        (0xfffffffeL+i)&0xffffffffL));
            }
            long time=now.incrementAndGet();
            dispatcher.offer(true,new CameraFrame(ByteBuffer.allocateDirect(12),config().rgb,0,4,time,time,0));
            time=now.incrementAndGet();
            dispatcher.offer(false,new CameraFrame(ByteBuffer.allocateDirect(8),config().ir,0,1,time+10,time,123));
            await(delivered);
            // Numeric metadata only: do not inspect retained borrowed pixels after the callback.
            assertEquals(0,result.get().rgb.nativeSequence);assertEquals(123,result.get().ir.nativeSequence);
            assertEquals(10,result.get().ir.inputLatencyNanos());
            assertEquals(time,result.get().ir.arrivalTimeNanos);
            assertEquals(1,dispatcher.diagnostics().outOfOrderRgb);
        } finally {dispatcher.close();await(drained);}
    }

    @Test public void healthSeparatesSlowConsumerAndRecordsItsDuration() throws Exception {
        AtomicLong now=new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),drained=new CountDownLatch(1);
        FramePairDispatcher dispatcher=new FramePairDispatcher(1,config(),now::get,pair->{
            entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}
            catch(InterruptedException error){throw new AssertionError(error);}
        },error->{throw new AssertionError(error);},drained::countDown);
        try {
            assertEquals(PairingDiagnostics.State.WAITING_BOTH,dispatcher.diagnostics().state);
            dispatcher.offer(true,frame(true,1,1,now.get()));
            assertEquals(PairingDiagnostics.State.WAITING_IR,dispatcher.diagnostics().state);
            dispatcher.offer(false,frame(false,1,1,now.get()));await(entered);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(151));
            assertEquals(PairingDiagnostics.State.CONSUMER_BUSY,dispatcher.diagnostics().state);
        } finally {release.countDown();dispatcher.close();await(drained);}
        PairingDiagnostics stats=dispatcher.diagnostics();
        assertEquals(PairingDiagnostics.State.CLOSED,stats.state);assertFalse(stats.consumerActive);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(151),stats.maxConsumerDurationNanos);
    }

    @Test public void healthKeepsLatestInputTimesAfterDeliveryAndRejectsOlderInput() throws Exception {
        long time=TimeUnit.SECONDS.toNanos(5);
        AtomicLong now=new AtomicLong(time);
        CountDownLatch delivered=new CountDownLatch(1),drained=new CountDownLatch(1);
        FramePairDispatcher dispatcher=new FramePairDispatcher(1,config(),now::get,
                pair->delivered.countDown(),error->{throw new AssertionError(error);},drained::countDown);
        try {
            dispatcher.offer(true,frame(true,1,1,time-2));
            dispatcher.offer(false,frame(false,1,1,time-1));await(delivered);
            dispatcher.offer(true,frame(true,2,2,time-3));
            PairingDiagnostics stats=dispatcher.diagnostics();
            assertEquals(time-2,stats.lastRgbTimeNanos);assertEquals(time-1,stats.lastIrTimeNanos);
            assertEquals(1,stats.outOfOrderRgb);
        } finally {dispatcher.close();await(drained);}
        assertEquals(time-2,dispatcher.diagnostics().lastRgbTimeNanos);
        assertEquals(time-1,dispatcher.diagnostics().lastIrTimeNanos);
    }

    @Test public void closeDuringFailedCopyDrainsExactlyOnce() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),drained=new CountDownLatch(1);
        AtomicInteger notifications=new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        FramePairDispatcher dispatcher=new FramePairDispatcher(1,config(),System::nanoTime,pair->{},
                error->{throw new AssertionError(error);},()->{notifications.incrementAndGet();drained.countDown();},
                (frame,target,rotation)->{
                    entered.countDown();
                    try {assertTrue(release.await(3,TimeUnit.SECONDS));}
                    catch(InterruptedException error){throw new AssertionError(error);}
                    throw new IllegalArgumentException("copy failed");
                });
        Thread producer=new Thread(()->{
            try {dispatcher.offer(true,frame(true,1,1,System.nanoTime()));}
            catch(Throwable error){failure.set(error);}
        });
        try {
            producer.start();await(entered);dispatcher.close();assertEquals(1,drained.getCount());
        } finally {release.countDown();producer.join(3000);dispatcher.close();await(drained);}
        assertFalse(producer.isAlive());assertTrue(failure.get() instanceof IllegalArgumentException);
        assertEquals(1,notifications.get());assertEquals(0,dispatcher.diagnostics().activeCopies);
    }
}
