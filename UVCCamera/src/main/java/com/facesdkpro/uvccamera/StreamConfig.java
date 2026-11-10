package com.facesdkpro.uvccamera;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import java.util.Collections;
import java.util.List;

/** Requested UVC mode. Defaults can select the closest advertised resolution and frame rate. */
public final class StreamConfig {
    /** Resolution preferences, independent from the preferred capture FPS. */
    public enum Resolution {
        MIDDLE(640, 480), HIGH(1280, 720);

        public final int width, height;
        Resolution(int width, int height) { this.width = width; this.height = height; }
    }

    public enum Transport {
        MJPEG(UVCCamera.UVC_VS_FRAME_MJPEG), YUYV(UVCCamera.UVC_VS_FRAME_UNCOMPRESSED);

        private final int frameType;
        Transport(int frameType) { this.frameType = frameType; }
    }

    public final int width, height, fps, frameType, pixelFormat, quirks;
    public final boolean closestSupportedMode;
    private final int maxLongSide, maxShortSide;

    public StreamConfig(int width, int height, int fps, int frameType, int pixelFormat, int quirks) {
        this(width, height, fps, frameType, pixelFormat, quirks, false, 4096, 4096);
    }

    private StreamConfig(int width, int height, int fps, int frameType, int pixelFormat, int quirks,
            boolean closestSupportedMode, int maxLongSide, int maxShortSide) {
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
        this.closestSupportedMode = closestSupportedMode;
        this.maxLongSide = maxLongSide; this.maxShortSide = maxShortSide;
    }

    public static StreamConfig defaults() {
        return preferred(DualCameraSession.Role.RGB, Resolution.MIDDLE, 30, Transport.MJPEG);
    }

    /** Capture preferences with SDK-owned RGB NV21 / IR GRAY8 formats and closest-mode selection. */
    public static StreamConfig preferred(DualCameraSession.Role role, Resolution resolution, int fps, Transport transport) {
        if (role == null || resolution == null || transport == null)
            throw new NullPointerException("role, resolution and transport are required");
        return new StreamConfig(resolution.width, resolution.height, fps, transport.frameType,
                role == DualCameraSession.Role.RGB ? UVCCamera.PIXEL_FORMAT_NV21 : UVCCamera.PIXEL_FORMAT_GRAY8,
                0, true, 4096, 4096);
    }

    /** Choose the nearest advertised resolution/FPS, retaining FPS, transport and callback format. */
    public StreamConfig withResolution(Resolution resolution) {
        if (resolution == null) throw new NullPointerException("resolution");
        return new StreamConfig(resolution.width, resolution.height, fps, frameType, pixelFormat, quirks,
                true, maxLongSide, maxShortSide);
    }

    /** Prefer the requested geometry, then the nearest FPS. Ties prefer fewer pixels and lower FPS. */
    public StreamConfig withClosestSupportedMode() {
        return new StreamConfig(width, height, fps, frameType, pixelFormat, quirks,
                true, maxLongSide, maxShortSide);
    }

    StreamConfig boundedTo(int longSide, int shortSide) {
        if (!closestSupportedMode || maxLongSide <= longSide && maxShortSide <= shortSide) return this;
        return new StreamConfig(width, height, fps, frameType, pixelFormat, quirks,
                closestSupportedMode, Math.min(maxLongSide, longSide), Math.min(maxShortSide, shortSide));
    }

    // Runs once while opening, before any frame callback or pair buffer allocation.
    StreamConfig resolve(List<Size> supported) {
        StreamConfig best = null;
        long bestDistance = Long.MAX_VALUE;
        int bestRateDistance = Integer.MAX_VALUE;
        for (Size mode : supported) {
            if (mode.type != frameType || mode.width <= 0 || mode.height <= 0
                    || (mode.width & 1) != 0 || (mode.height & 1) != 0
                    || Math.max(mode.width, mode.height) > maxLongSide
                    || Math.min(mode.width, mode.height) > maxShortSide) continue;
            long dx = (long) mode.width - width, dy = (long) mode.height - height;
            long distance = dx * dx + dy * dy;
            List<Integer> rates = mode.fpsList == null || mode.fpsList.isEmpty()
                    ? Collections.singletonList(mode.fps) : mode.fpsList;
            for (Integer rate : rates) {
                if (rate == null || rate < 1 || rate > 120) continue;
                int rateDistance = Math.abs(rate - fps);
                if (!closestSupportedMode && (distance != 0 || rateDistance != 0)) continue;
                long area = (long) mode.width * mode.height;
                long bestArea = best == null ? Long.MAX_VALUE : (long) best.width * best.height;
                boolean lowerBandwidth = best == null || area < bestArea
                        || area == bestArea && rate < best.fps;
                boolean closerRate = rateDistance < bestRateDistance
                        || rateDistance == bestRateDistance && lowerBandwidth;
                if (distance < bestDistance || distance == bestDistance && closerRate) {
                    best = new StreamConfig(mode.width, mode.height, rate, frameType, pixelFormat, quirks);
                    bestDistance = distance; bestRateDistance = rateDistance;
                }
            }
        }
        return best;
    }

    Size size() { return new Size(frameType, width, height, fps, Collections.singletonList(fps)); }

    @Override public String toString() {
        return width + "x" + height + " "
                + (frameType == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG" : "YUYV") + " @ " + fps + " FPS";
    }
}
