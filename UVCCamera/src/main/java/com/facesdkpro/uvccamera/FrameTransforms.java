package com.facesdkpro.uvccamera;

import com.serenegiant.usb.UVCCamera;
import java.nio.ByteBuffer;

/** Packed NV21/NV12/GRAY8 pixel rotation, clockwise, without color conversion or temporary frames. */
public final class FrameTransforms {
    private FrameTransforms() {}

    public static void validateRotation(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270)
            throw new IllegalArgumentException("Rotation must be 0, 90, 180 or 270 degrees clockwise");
    }

    public static int outputWidth(int width, int height, int degrees) {
        validateRotation(degrees); return degrees == 90 || degrees == 270 ? height : width;
    }
    public static int outputHeight(int width, int height, int degrees) {
        validateRotation(degrees); return degrees == 90 || degrees == 270 ? width : height;
    }

    /**
     * Copy/rotate the current source window to the current destination window. Both must be direct;
     * destination must be writable and non-overlapping with source. Positions/limits are preserved.
     * Source contains exactly one packed frame; destination has at least that many remaining bytes.
     * GRAY8 permits odd dimensions. NV12/NV21 require even dimensions. No allocation of pixel storage.
     */
    public static void copyRotated(ByteBuffer source, ByteBuffer destination, int width, int height,
            int pixelFormat, int degrees) {
        validateRotation(degrees);
        if (source == null || destination == null) throw new NullPointerException("pixels");
        boolean gray = pixelFormat == UVCCamera.PIXEL_FORMAT_GRAY8;
        if (!gray && pixelFormat != UVCCamera.PIXEL_FORMAT_NV21 && pixelFormat != UVCCamera.PIXEL_FORMAT_NV12)
            throw new IllegalArgumentException("Rotation accepts packed NV21, NV12 or GRAY8 only");
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || !gray && ((width | height) & 1) != 0)
            throw new IllegalArgumentException("Invalid image dimensions");
        int size = gray ? width * height : width * height * 3 / 2;
        if (!source.isDirect() || !destination.isDirect() || destination.isReadOnly()
                || source == destination || source.remaining() != size || destination.remaining() < size)
            throw new IllegalArgumentException("Invalid direct source/destination windows");
        if (degrees == 0) { destination.duplicate().put(source.duplicate()); return; }
        UVCCamera.ensureNativeLoaded();
        if (!nativeRotate(source.slice(), destination.slice(), width, height, pixelFormat, degrees))
            throw new IllegalArgumentException("Invalid or overlapping rotation buffers");
    }

    private static native boolean nativeRotate(ByteBuffer source, ByteBuffer destination,
            int width, int height, int pixelFormat, int degrees);
}
