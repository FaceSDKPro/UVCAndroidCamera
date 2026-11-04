package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransformContractTest {
    @Test public void quarterTurnsSwapOutputGeometryAndRetainSourceMetadata() {
        StreamConfig config = StreamConfig.defaults();
        for (int rotation : new int[]{0, 90, 180, 270}) {
            CameraFrame frame = new CameraFrame(ByteBuffer.allocateDirect(460800), config, rotation, 1, 1);
            assertEquals(rotation % 180 == 0 ? 640 : 480, frame.width);
            assertEquals(rotation % 180 == 0 ? 480 : 640, frame.height);
            assertEquals(640, frame.sourceWidth); assertEquals(480, frame.sourceHeight);
            assertEquals(rotation, frame.appliedRotationDegrees);
            assertEquals(frame.width * frame.height, frame.luma().remaining());
        }
        DualCameraConfig rotated = DualCameraConfig.defaults().withRotations(90, 270);
        assertEquals(90, rotated.rgbRotationDegrees); assertEquals(270, rotated.irRotationDegrees);
        assertEquals(0, DualCameraConfig.defaults().rgbRotationDegrees);
        for (int invalid : new int[]{-90, 45, 360}) assertThrows(IllegalArgumentException.class,
                () -> DualCameraConfig.defaults().withRotations(invalid, 0));
    }

    @Test public void zeroRotationCopiesOnlySpecifiedWindowsAndPreservesBufferPositions() {
        ByteBuffer source = ByteBuffer.allocateDirect(20), target = ByteBuffer.allocateDirect(24);
        for (int i = 0; i < 20; i++) source.put(i, (byte)i);
        source.position(3); source.limit(15); target.position(5); target.limit(20);
        FrameTransforms.copyRotated(source, target, 4, 2, UVCCamera.PIXEL_FORMAT_NV21, 0);
        assertEquals(3, source.position()); assertEquals(5, target.position());
        assertEquals(3, target.get(5)); assertEquals(14, target.get(16)); assertEquals(0, target.get(17));
    }

    @Test public void invalidTransformsFailBeforeNativeLoadingOrWriting() {
        ByteBuffer source = ByteBuffer.allocateDirect(12), target = ByteBuffer.allocateDirect(12);
        assertThrows(IllegalArgumentException.class, () -> FrameTransforms.copyRotated(source,target,4,2,3,45));
        assertThrows(IllegalArgumentException.class, () -> FrameTransforms.copyRotated(source,target,4,2,0,90));
        assertThrows(IllegalArgumentException.class, () -> FrameTransforms.copyRotated(source,target,3,2,3,90));
        assertThrows(IllegalArgumentException.class, () -> FrameTransforms.copyRotated(source,target.asReadOnlyBuffer(),4,2,3,90));
        assertThrows(IllegalArgumentException.class, () -> FrameTransforms.copyRotated(source,ByteBuffer.allocate(12),4,2,3,90));
        assertThrows(IllegalArgumentException.class, () -> FrameTransforms.copyRotated(source,source,4,2,3,90));
        assertThrows(IllegalArgumentException.class, () -> FrameTransforms.copyRotated(source,ByteBuffer.allocateDirect(11),4,2,3,90));
    }

    @Test public void previewRotatesClockwiseWithoutStretchingAndHonorsFitOrCrop() {
        for (int rotation : new int[]{0,90,180,270}) for (boolean crop : new boolean[]{false,true}) {
            float[] m = PreviewTransform.values(1000,600,640,480,rotation,crop);
            assertEquals(500, mapX(m,500,300), .001); assertEquals(300,mapY(m,500,300),.001);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int x : new int[]{0,1000}) for (int y : new int[]{0,600}) {
                minX=Math.min(minX,mapX(m,x,y)); maxX=Math.max(maxX,mapX(m,x,y));
                minY=Math.min(minY,mapY(m,x,y)); maxY=Math.max(maxY,mapY(m,x,y));
            }
            assertEquals((double)FrameTransforms.outputWidth(640,480,rotation)/FrameTransforms.outputHeight(640,480,rotation),
                    (maxX-minX)/(maxY-minY), .0001);
            if (crop) { assertTrue(minX <= .001 && minY <= .001 && maxX >= 999.999 && maxY >= 599.999); }
            else { assertTrue(minX >= -.001 && minY >= -.001 && maxX <= 1000.001 && maxY <= 600.001); }
            if (rotation == 90) { assertEquals(mapX(m,0,0),mapX(m,1000,0),.001); assertTrue(mapY(m,1000,0)>mapY(m,0,0)); }
        }
    }
    private float mapX(float[] m,float x,float y) { return m[0]*x+m[1]*y+m[2]; }
    private float mapY(float[] m,float x,float y) { return m[3]*x+m[4]*y+m[5]; }
}
