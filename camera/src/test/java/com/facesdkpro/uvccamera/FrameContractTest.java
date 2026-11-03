package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameContractTest {
    private StreamConfig nv21() {
        return new StreamConfig(4, 2, 30, UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0);
    }
    @Test public void borrowedViewIsReadOnlyWhileExplicitCopyOwnsItsPixels() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(12);
        for (int i = 0; i < 12; i++) buffer.put((byte)i);
        buffer.flip(); CameraFrame frame = new CameraFrame(buffer, nv21(), 7, 100);
        assertTrue(frame.pixels().isDirect()); assertTrue(frame.pixels().isReadOnly());
        byte[] copy = frame.copyPixels(); buffer.put(0, (byte)99);
        assertEquals(0, copy[0]); assertEquals(99, frame.pixels().get(0));
        assertEquals(7, frame.sequence); assertEquals(100, frame.arrivalTimeNanos);
    }
    @Test public void grayCopyContainsOnlyTheLumaPlaneAndPreservesBufferPosition() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(12);
        for (int i = 0; i < 12; i++) buffer.put((byte)i);
        buffer.flip(); CameraFrame frame = new CameraFrame(buffer, nv21(), 1, 1);
        assertArrayEquals(new byte[]{0,1,2,3,4,5,6,7}, frame.copyLuma());
        frame.pixels().position(5); assertEquals(0, frame.pixels().position());
        assertEquals(0, buffer.position());
    }
    @Test(expected = IllegalStateException.class) public void rgbPixelsCannotBeMislabelledAsInfraredLuma() {
        StreamConfig rgb = new StreamConfig(4, 2, 30, UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_RGBX, 0);
        new CameraFrame(ByteBuffer.allocateDirect(32), rgb, 1, 1).copyLuma();
    }
    @Test public void rejectOddDimensionsAndInvalidRatesBeforeUsbAcquisition() {
        int[][] invalid = {{3,2,30},{4,1,30},{0,2,30},{4098,2,30},{4,2,0},{4,2,121}};
        for (int[] value : invalid) {
            try { new StreamConfig(value[0],value[1],value[2],UVCCamera.UVC_VS_FRAME_MJPEG,UVCCamera.PIXEL_FORMAT_NV21,0); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
