package com.facesdkpro.uvccamera;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import java.util.Collections;

/** Exact UVC mode. Unsupported modes fail; opening never selects a larger resolution silently. */
public final class StreamConfig {
    public final int width, height, fps, frameType, pixelFormat, quirks;

    public StreamConfig(int width, int height, int fps, int frameType, int pixelFormat, int quirks) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("Dimensions must be positive even values up to 4096");
        }
        if (fps < 1 || fps > 120) throw new IllegalArgumentException("FPS must be 1..120");
        if (frameType != UVCCamera.UVC_VS_FRAME_MJPEG && frameType != UVCCamera.UVC_VS_FRAME_UNCOMPRESSED) {
            throw new IllegalArgumentException("Only MJPEG and uncompressed YUYV modes are supported");
        }
        if (pixelFormat < UVCCamera.PIXEL_FORMAT_RAW || pixelFormat > UVCCamera.PIXEL_FORMAT_GRAY8) {
            throw new IllegalArgumentException("Unsupported callback pixel format");
        }
        this.width = width; this.height = height; this.fps = fps;
        this.frameType = frameType; this.pixelFormat = pixelFormat; this.quirks = quirks;
    }

    public static StreamConfig defaults() {
        return new StreamConfig(640, 480, 30, UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0);
    }

    Size size() { return new Size(frameType, width, height, fps, Collections.singletonList(fps)); }
}
