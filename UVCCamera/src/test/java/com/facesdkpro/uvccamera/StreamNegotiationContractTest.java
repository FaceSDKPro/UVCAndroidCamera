package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamNegotiationContractTest {
    private StreamConfig stream(int width, int height, int pixelFormat) {
        return new StreamConfig(width, height, 30, UVCCamera.UVC_VS_FRAME_MJPEG, pixelFormat, 0);
    }

    private DualCameraConfig request() {
        return DualCameraConfig.forStreams(stream(4, 2, UVCCamera.PIXEL_FORMAT_NV21),
                stream(4, 2, UVCCamera.PIXEL_FORMAT_GRAY8));
    }

    private CameraFrame frame(StreamConfig actual, int bytes) {
        return new CameraFrame(ByteBuffer.allocateDirect(bytes), actual, 1, System.nanoTime());
    }

    @Test public void negotiatedGeometrySizesBothPoolsAndMatchesDeliveredMetadata() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamConfig rgb = stream(8, 4, UVCCamera.PIXEL_FORMAT_NV21);
        StreamConfig ir = stream(6, 4, UVCCamera.PIXEL_FORMAT_GRAY8);
        FramePairDispatcher dispatcher = new FramePairDispatcher(1, request(), System::nanoTime, pair -> {
            assertEquals(8, pair.rgb.width);
            assertEquals(4, pair.rgb.height);
            assertEquals(48, pair.rgb.pixels().remaining());
            assertEquals(6, pair.ir.width);
            assertEquals(4, pair.ir.height);
            assertEquals(24, pair.ir.pixels().remaining());
            assertEquals(CameraFrame.LumaRange.FULL, pair.ir.lumaRange);
            delivered.countDown();
        }, error::set, drained::countDown);
        try {
            dispatcher.configure(true, rgb);
            dispatcher.configure(false, ir);
            dispatcher.offer(true, frame(rgb, 48));
            dispatcher.offer(false, frame(ir, 24));
            assertTrue("No pair: " + error.get(), delivered.await(3, TimeUnit.SECONDS));
            assertNull(error.get());
        } finally {
            dispatcher.close();
            assertTrue(drained.await(3, TimeUnit.SECONDS));
        }
    }

    @Test public void streamCannotBeReconfiguredAfterFramesAndClosedStartupIsIgnored() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        FramePairDispatcher dispatcher = new FramePairDispatcher(1, request(), System::nanoTime,
                pair -> {}, error -> fail(error.toString()), drained::countDown);
        try {
            dispatcher.offer(true, frame(request().rgb, 12));
            assertThrows(IllegalStateException.class,
                    () -> dispatcher.configure(true, stream(8, 4, UVCCamera.PIXEL_FORMAT_NV21)));
        } finally {
            dispatcher.close();
            assertTrue(drained.await(3, TimeUnit.SECONDS));
        }
        dispatcher.configure(false, request().ir);
        assertEquals(0, dispatcher.diagnostics().activeCopies);
    }
}
