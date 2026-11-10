package com.facesdkpro.uvccamera;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamConfigTest {
    private Size mjpeg(int width, int height, Integer... rates) {
        return new Size(UVCCamera.UVC_VS_FRAME_MJPEG, width, height, rates[0], Arrays.asList(rates));
    }

    @Test public void middleAdaptsToThirtyOnlyCameraAndStrictRequestStillRejects() {
        Size mode = mjpeg(640, 480, 30);
        StreamConfig request = DualCameraConfig.defaults().rgb;
        StreamConfig actual = request.resolve(Collections.singletonList(mode));
        assertEquals(15, request.fps);
        assertEquals(30, actual.fps);
        assertEquals(640, actual.width);
        assertEquals(UVCCamera.PIXEL_FORMAT_NV21, actual.pixelFormat);
        StreamConfig exact = new StreamConfig(640, 480, 15, mode.type, UVCCamera.PIXEL_FORMAT_NV21, 0);
        assertNull(exact.resolve(Collections.singletonList(mode)));
        assertSame(exact, DualCameraConfig.forStreams(exact, DualCameraConfig.defaults().ir).rgb);
    }

    @Test public void highUsesClosestRateAndEqualDistancePrefersLowerRate() {
        assertEquals(25, DualCameraConfig.defaults(DualCameraConfig.Mode.HIGH).rgb
                .resolve(Collections.singletonList(mjpeg(640, 480, 20, 25))).fps);
        assertEquals(10, DualCameraConfig.defaults().rgb
                .resolve(Collections.singletonList(mjpeg(640, 480, 20, 10))).fps);
    }

    @Test public void matchingResolutionWinsBeforeFrameRate() {
        StreamConfig actual = DualCameraConfig.defaults().rgb.resolve(Arrays.asList(
                mjpeg(800, 600, 15), mjpeg(640, 480, 30)));
        assertEquals(640, actual.width);
        assertEquals(30, actual.fps);
    }

    @Test public void missingResolutionUsesClosestDimensionsWithStableTieBreak() {
        StreamConfig request = new StreamConfig(1280, 720, 30, UVCCamera.UVC_VS_FRAME_MJPEG,
                UVCCamera.PIXEL_FORMAT_NV21, 0).withClosestSupportedMode();
        StreamConfig actual = request.resolve(Arrays.asList(mjpeg(1920, 1080, 30), mjpeg(960, 540, 25)));
        assertEquals(960, actual.width);
        assertEquals(540, actual.height);
        assertEquals(25, actual.fps);
        StreamConfig tie = DualCameraConfig.defaults().rgb.resolve(Arrays.asList(
                mjpeg(800, 480, 30), mjpeg(480, 480, 30)));
        assertEquals(480, tie.width);
    }

    @Test public void skipsInvalidModesAndDoesNotChangeTransportOrPairingBounds() {
        Size wrongFormat = new Size(UVCCamera.UVC_VS_FRAME_UNCOMPRESSED, 640, 480, 15,
                Collections.singletonList(15));
        StreamConfig request = DualCameraConfig.defaults().rgb;
        assertNull(request.resolve(Arrays.asList(wrongFormat, mjpeg(641, 480, 30),
                mjpeg(3840, 2160, 30), mjpeg(640, 480, 0, 121))));
        StreamConfig actual = request.resolve(Arrays.asList(mjpeg(3840, 2160, 30), mjpeg(1280, 720, 30)));
        assertEquals(1280, actual.width);
        assertEquals(720, actual.height);
        assertTrue(DualCameraConfig.defaults(DualCameraConfig.Mode.HIGH).ir.closestSupportedMode);
    }

    @Test public void advertisedDefaultIsUsedWhenRateListIsAbsent() {
        Size mode = mjpeg(640, 480, 25);
        mode.fpsList = null;
        assertEquals(25, DualCameraConfig.defaults().rgb.resolve(Collections.singletonList(mode)).fps);
    }

    @Test public void resolutionPresetsKeepCapturePreferencesAndMatchNearestAdvertisedGeometry() {
        for (DualCameraConfig.Mode fpsMode : DualCameraConfig.Mode.values()) {
            DualCameraConfig original = DualCameraConfig.defaults(fpsMode).withRotations(90, 270)
                    .withPairingLimits(40, 200, 10);
            DualCameraConfig high = original.withResolution(StreamConfig.Resolution.HIGH);
            assertEquals(640, original.rgb.width);
            assertEquals(1280, high.rgb.width); assertEquals(720, high.ir.height);
            assertEquals(fpsMode.fps, high.rgb.fps); assertEquals(fpsMode.fps, high.ir.fps);
            assertTrue(high.rgb.closestSupportedMode); assertTrue(high.ir.closestSupportedMode);
            assertEquals(90, high.rgbRotationDegrees); assertEquals(270, high.irRotationDegrees);
            assertEquals(40, high.maxPairSkewMillis); assertEquals(200, high.maxFrameAgeMillis);
            assertEquals(10, high.maxPairsPerSecond);
            StreamConfig actual = high.rgb.resolve(Arrays.asList(mjpeg(640, 480, 15),
                    mjpeg(1280, 800, 25), mjpeg(1920, 1080, 30)));
            assertEquals(1280, actual.width); assertEquals(800, actual.height); assertEquals(25, actual.fps);
            DualCameraConfig middle = high.withResolution(StreamConfig.Resolution.MIDDLE);
            assertEquals(640, middle.rgb.width); assertEquals(480, middle.ir.height);
            StreamConfig middleActual = middle.rgb.resolve(Arrays.asList(mjpeg(1280, 720, 15), mjpeg(640, 400, 30)));
            assertEquals(640, middleActual.width); assertEquals(400, middleActual.height);
            assertEquals(30, middleActual.fps);
        }
        assertThrows(NullPointerException.class, () -> DualCameraConfig.defaults().withResolution(null));
    }

    @Test public void resolutionPresetEnablesMatchingWithoutChangingFormatOrQuirks() {
        StreamConfig original = new StreamConfig(1920, 1080, 15, UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,
                UVCCamera.PIXEL_FORMAT_GRAY8, 4);
        StreamConfig high = original.withResolution(StreamConfig.Resolution.HIGH);
        assertFalse(original.closestSupportedMode); assertTrue(high.closestSupportedMode);
        assertEquals(original.frameType, high.frameType); assertEquals(original.pixelFormat, high.pixelFormat);
        assertEquals(original.quirks, high.quirks); assertEquals(15, high.fps);
        assertNull(high.resolve(Collections.singletonList(mjpeg(1280, 720, 30))));
    }

    @Test public void preferredStreamsOwnRoleFormatsAndKeepNearestFpsCompatibility() {
        StreamConfig rgb = StreamConfig.preferred(DualCameraSession.Role.RGB,
                StreamConfig.Resolution.MIDDLE, 15, StreamConfig.Transport.MJPEG);
        StreamConfig ir = StreamConfig.preferred(DualCameraSession.Role.IR,
                StreamConfig.Resolution.HIGH, 30, StreamConfig.Transport.YUYV);
        assertEquals(UVCCamera.PIXEL_FORMAT_NV21, rgb.pixelFormat);
        assertEquals(UVCCamera.PIXEL_FORMAT_GRAY8, ir.pixelFormat);
        assertEquals(30, rgb.resolve(Collections.singletonList(mjpeg(640, 480, 30))).fps);
        Size yuyv = new Size(UVCCamera.UVC_VS_FRAME_UNCOMPRESSED, 1280, 800, 25, Collections.singletonList(25));
        StreamConfig actual = ir.resolve(Collections.singletonList(yuyv));
        assertEquals(25, actual.fps); assertEquals(800, actual.height);
        assertEquals(15, DualCameraConfig.forStreams(rgb, ir).rgb.fps);
        assertEquals(30, StreamConfig.defaults().fps);
        assertThrows(NullPointerException.class, () -> StreamConfig.preferred(null,
                StreamConfig.Resolution.MIDDLE, 15, StreamConfig.Transport.MJPEG));
        assertThrows(NullPointerException.class, () -> StreamConfig.preferred(DualCameraSession.Role.RGB,
                null, 15, StreamConfig.Transport.MJPEG));
        assertThrows(NullPointerException.class, () -> StreamConfig.preferred(DualCameraSession.Role.RGB,
                StreamConfig.Resolution.MIDDLE, 15, null));
        assertThrows(IllegalArgumentException.class, () -> StreamConfig.preferred(DualCameraSession.Role.RGB,
                StreamConfig.Resolution.MIDDLE, 0, StreamConfig.Transport.MJPEG));
    }
}
