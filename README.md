# UVCAndroidCamera

English | [简体中文](README.zh-CN.md)

A trimmed Android USB camera acquisition library maintained by FaceSDKPro, based on
[UVCAndroid](https://github.com/shiyinghan/UVCAndroid).

**We trimmed the upstream repository to support development of [HelloFace](https://github.com/FaceSDKPro/HelloFace)'s RGB + near-infrared (IR) dual-camera face recognition feature.**
This repository provides the acquisition foundation: concurrent camera capture, preview, pixel delivery and device lifecycle management.
Face recognition algorithms, liveness policies and cross-camera face association are developed in the HelloFace / SDK integration layer.

Upstream source baseline: `fcdae5f9a194e2111d57019176b53470a120415b`.

The library retains concurrent independent USB devices, one preview surface per camera, headless capture,
pixel callbacks, standard UVC controls, queued permission requests and attach/detach notifications.
Photo storage, video/audio recording, encoders, Java OpenGL multi-output rendering and legacy demos have been removed.
There are no third-party Java runtime dependencies. The manifest declares only CAMERA permission and USB Host capability.
See [UPSTREAM.md](UPSTREAM.md) for the trimming analysis (Chinese).

## Build

Use JDK 17, Android SDK 36 and NDK `27.0.12077973`. The Gradle Wrapper is pinned to `8.14.3`.
The library supports Android 5.0+; the sample requires Android 6.0+.
Four ABIs are included: arm64-v8a, armeabi-v7a, x86 and x86_64.

```sh
./gradlew :camera:assembleRelease :camera:testReleaseUnitTest :sample:assembleRelease
python3 tools/check_artifact.py camera/build/outputs/aar/camera-release.aar
```

An explicit Google Maven mirror can be supplied when needed:

```sh
./gradlew :camera:assembleRelease -PgoogleMavenMirror=https://maven.aliyun.com/repository/google
```

The default repositories are Google Maven and Maven Central. JCenter, JitPack and upstream publishing account configuration are not used.
Build outputs stay in each module's `build/` directory. AARs, APKs, native binaries and local signing material are excluded from source control.

## Integration

Add the `camera` module to your project, or build and consume the AAR locally.
`com.facesdkpro:uvc-android-camera:0.1.0` is this project's local publication coordinate; it has not been published to Maven Central.
To publish to your local Maven repository, run `./gradlew :camera:publishReleasePublicationToMavenLocal`.

```java
UvcCameraManager manager = new UvcCameraManager(context, new UvcCameraManager.Listener() {
    @Override public void onOpened(UvcCameraManager.Camera camera) { }
    @Override public void onError(UsbDevice device, String message, Throwable cause) { }
});

// Obtain CAMERA runtime permission first, then select devices from manager.devices().
// Bind RGB/IR roles by VID/PID or user selection, never by enumeration order.
UvcCameraManager.Camera rgb = manager.open(rgbDevice, StreamConfig.defaults(), rgbSurface,
        (camera, frame) -> {
            ByteBuffer pixels = frame.pixels(); // Borrowed, read-only direct NV21 buffer.
            // Consume synchronously here; call frame.copyPixels() to retain an owned copy.
        });
UvcCameraManager.Camera ir = manager.open(irDevice, StreamConfig.defaults(), null,
        (camera, frame) -> {
            ByteBuffer gray8 = frame.luma(); // Borrow Y synchronously; use a bounded copyLumaTo pool for async work.
        });

rgb.withControls(control -> {
    if (control.isBrightnessEnable()) control.setBrightnessPercent(50);
});

// Stop delivery when leaving the screen. Native cleanup runs on the command thread.
// onShutdown signals that cleanup has completed.
manager.close();
```

`StreamConfig.defaults()` explicitly selects **640×480, MJPEG, 30 FPS and NV21 callbacks**.
Unsupported resolutions or frame rates fail instead of silently selecting a larger mode.
Device-provided YUYV input can also be selected.
Callbacks support YUYV, NV12, NV21, RGB, RGB565, RGBX, BGR and GRAY8. RAW means decoded YUYV here,
not compressed MJPEG bytes. Frames are not automatically rotated, mirrored or processed for face detection.

State callbacks run on the main thread. Each camera's frame callbacks run on its native callback thread,
using a queue that retains only the latest frame.
Do not wait for the UI inside a frame callback or retain a borrowed buffer after returning.
Use bounded copying/dropping when passing frames to another thread.
`CameraFrame.arrivalTimeNanos` is the host monotonic callback arrival time, not a sensor timestamp or proof of hardware synchronization.

Each session corresponds to one independent USB device, and multiple sessions can run concurrently.
Multiple VideoStreaming interfaces within a single USB device require additional adaptation and are outside the current multi-camera contract.
Attach/detach events notify the host. Detaching closes the corresponding session; after reattachment, the host selects and reopens the appropriate role.
IR face boxes, cross-camera face association and liveness algorithms belong to the upper SDK layer.

## Performance and lower-power integration

MJPEG decoders are reused per decoding thread. Both input and callback queues retain only the latest frame.
Headless sessions decode when the consumer is ready, so a busy SDK does not force obsolete JPEGs through RGB conversion.
Preview-only sessions omit the pixel callback thread and use a native preview counter for startup checks.
Native callback references and per-session delayed tasks are released/cancelled at close.
USB monitoring shares the manager's command looper, avoiding a second Java background thread.
The native build excludes JPEG encoding/image-file helpers and unused YUV scaling, rotation and comparison modules.
SIMD conversions remain enabled. The five native libraries expose a reviewed internal interface; Java is the supported integration API.
Across the four ABIs, native library bytes decreased from 6,895,160 to 1,869,156 (72.9%) with the same toolchain.
This is a binary size result; device power requires separate measurement.

For IR analysis without a preview, explicitly select `UVCCamera.PIXEL_FORMAT_GRAY8` in `StreamConfig`.
MJPEG is then decoded directly into packed, full-range grayscale (`width * height` bytes), without RGBX or chroma output.
With a preview, grayscale is derived from the RGBX frame already needed for display.
YUYV-to-GRAY8 preserves the camera's luma values. Existing NV12/NV21 color conversion remains unchanged;
GRAY8 JPEG values can differ from the Y plane obtained through the previous RGB-to-NV21 conversion.
Use `frame.luma()` for a borrowed read-only view, or `copyLumaTo(byte[], offset)` / `copyPixelsTo(byte[], offset)`
with caller-owned reusable arrays. All borrowed views expire when the callback returns.

`UvcCameraManager` uses USB broadcasts plus a five-second fallback device scan.
The overload `new UvcCameraManager(context, listener, devicePollMillis)` accepts `0` for broadcasts plus the initial scan,
or an interval of at least 1000 ms. Disabling polling requires reliable attach broadcasts on the target device;
when broadcasts are missing, discovery latency follows the configured interval.
Choose a supported capture resolution/FPS for the application and close sessions when unused.
See [PERFORMANCE.md](PERFORMANCE.md) for implementation details, the measurement procedure and current evidence limits.

## Sample and device validation

`sample` provides two TextureViews, device selection, three resolution presets, frame counters and brightness controls.
The sample uses preview-only sessions by default; add a FrameListener as shown above when consuming pixels.
`deviceTest` is a separate acceptance build using Release optimization and JNI keep rules, signed with the local Debug identity.
Its diagnostic Activity is excluded from the production `release` build.

```sh
./gradlew :sample:assembleDeviceTest
python3 tools/run_device_checks.py --serial YOUR_ADB_SERIAL --output /path/outside/repository
```

The device must allow installation, CAMERA permission and access to both USB cameras.
The script checks simultaneous headless capture, repeated open/close, error recovery, buffer/GRAY8 contracts,
device parameter reads, both preview updates and resource cleanup. It saves statistics and logs, not camera images.
File descriptors are recorded by category, including USB, events, pipes and graphics, so Android graphics buffer caches are not mistaken for capture connection leaks.
Physical hotplug, long-duration reliability, hardware synchronization and operation on a 16 KB page-size device require separate validation.
Short capture tests and ELF alignment checks do not replace those tests. See [VALIDATION.md](VALIDATION.md) for the recorded validation scope (Chinese).

## Origin and licenses

The Java/JNI adapter retains Apache-2.0 licensing, original copyright notices and modification notes.
libusb, libuvc, libjpeg-turbo, libyuv and RapidJSON retain their respective licenses; see [NOTICE](NOTICE),
the license files in the source tree and `META-INF/uvccamera/LICENSES/` inside the AAR.
This project uses a new, trimmed management API. Remove the old upstream dependency when migrating to avoid packaging duplicate JNI libraries and low-level classes.
