# Android dual-camera SDK integration

This standalone SDK manages independent RGB/IR USB cameras, previews, controls and bounded software frame pairing.
The Demo consumes real NV21/GRAY8 frames without a HelloFace SDK dependency, models, face recognition or liveness decisions.
HelloFace has not been modified or linked as part of this preparation.

## Build and obtain the SDK

Use JDK 17, Android SDK 36 and NDK 29.0.14206865 at the source root:

```sh
./gradlew :camera:testReleaseUnitTest :camera:lintRelease :sample:lintDemo prepareSdk
```

This builds locally and creates a local Maven directory. It does not upload artifacts or run CI/CD.
`build/sdk/uvc-android-camera-0.3.0/` and its ZIP contain:

- `sdk/`: four-ABI Release AAR and Java sources JAR.
- `maven-repository/`: fixed-version AAR, POM, Gradle module metadata and sources.
- `demo/`: installable, R8-optimized demonstration APK using the local standard Android debug identity.
- `demo-source/`: standalone sample that consumes the bundled Maven AAR, without a `camera` source module.
- Bilingual documentation, licenses, `sdk-manifest.json` and `SHA256SUMS`.

Minimum API levels are 21 for the SDK and 23 for the Demo. Use your own application signing for production.
Nothing is published to Maven Central or GitHub Releases; no tags are created.
Select ABIs with `-PuvcAbis=arm64-v8a,armeabi-v7a` if needed. Keep builds with different ABI sets in separate delivery directories;
do not mix different bytes under the same coordinates in a dependency cache.

Run `shasum -a 256 -c SHA256SUMS` in the extracted directory. To build the independent Demo:

```sh
cd demo-source
# Set ANDROID_HOME, or create a local local.properties pointing to your Android SDK.
./gradlew :sample:assembleDemo
```

Add the extracted repository in your consumer's `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url = uri('/absolute/path/to/maven-repository') }
        google()
        mavenCentral()
    }
}
```

Then add the application dependency:

```groovy
dependencies { implementation 'com.facesdkpro:uvc-android-camera:0.3.0' }
```

Alternatively, copy the AAR into application `libs/` and use
`implementation files('libs/uvc-android-camera-0.3.0.aar')`.
There are no third-party Java runtime dependencies. JNI consumer rules and native license notices are embedded.
Do not also package upstream copies of the same classes or native libraries.

## Open a paired session

Obtain CAMERA runtime permission and construct the session on the main thread.
Bind RGB/IR roles using VID/PID, a port profile or explicit user selection, never enumeration order.
`DualCameraSession.devices(context)` enumerates without starting a resident monitor.
Each session requires two independent USB devices; multiple VideoStreaming interfaces within one device are not supported.

```java
DualCameraSession session = new DualCameraSession(
    context, selectedRgbDevice, selectedIrDevice,
    DualCameraConfig.defaults(), rgbSurface, null, // headless IR
    new DualCameraSession.Listener() {
        @Override public void onReady(DualCameraSession source) {
            // Main thread: streams are open. Actual pairs still require fresh, sufficiently close arrivals.
        }
        @Override public void onError(DualCameraSession source, String message, Throwable error) {
            // Main thread: both cameras are stopping. Invalidate previous business results.
        }
        @Override public void onClosed(DualCameraSession source) {
            // Main thread: native/USB resources and the active consumer have drained; release Surfaces now.
        }
    }, pair -> {
        // Serial UVC-pairs worker: synchronous algorithm integration point.
        Nv21Planes rgb = pair.rgbPlanes();
        ByteBuffer infrared = pair.ir.luma();
        // rgb.y()/u()/v() are direct views; infrared is packed GRAY8.
        // Complete synchronous processing before returning. Do not retain pair or these buffers.
    });

session.withControls(DualCameraSession.Role.RGB, control -> {
    if (control.isBrightnessEnable()) control.setBrightnessPercent(50);
});

// Stop when hidden, leaving the screen, or changing devices/modes. This does not block.
session.close();
```

Detach, permission denial, start failure, a throwing consumer, or five seconds without frames from either opened camera
ends the entire session. Wait for `onClosed`, enumerate again and create a new session to retry.
There is no silent device substitution or single-camera fallback.
`onReady` proves neither simultaneous exposure nor a usable face.

## Resolution, frame rate and rotation

Each `StreamConfig` width/height is the original capture resolution, and `fps` requests a device capture rate.
The camera must support the full resolution/transport/FPS combination; unsupported requests fail explicitly.
Exposure, USB bandwidth and processing can lower the observed rate. `maxPairsPerSecond` limits consumer delivery only.

```java
StreamConfig rgb = new StreamConfig(1280, 720, 30,
    UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0);
StreamConfig ir = new StreamConfig(640, 480, 15,
    UVCCamera.UVC_VS_FRAME_UNCOMPRESSED, UVCCamera.PIXEL_FORMAT_GRAY8, 0);
DualCameraConfig config = new DualCameraConfig(rgb, ir, 50, 150, 15)
    .withRotations(90, 270); // Independent clockwise rotations

// Update after the TextureView is laid out and whenever its dimensions change.
rgbTextureView.setOpaque(false);
rgbTextureView.setTransform(PreviewTransform.fitCenter(
    rgbTextureView.getWidth(), rgbTextureView.getHeight(), rgb.width, rgb.height,
    config.rgbRotationDegrees));
// Open DualCameraSession with config. Surface preview remains in source orientation;
// the matrix above handles its display rotation.
```

Only 0/90/180/270 degrees are accepted. Preview uses a TextureView content transform without rotating a CPU display frame;
see [Android TextureView](https://developer.android.com/reference/android/view/TextureView).
Paired delivery physically rotates RGB NV21 and IR GRAY8 directly into existing destination pool buffers, without a full-frame scratch buffer or color conversion.
Quarter turns swap `CameraFrame.width/height`. `sourceWidth/sourceHeight` retain capture dimensions, and
`appliedRotationDegrees` records the angle already applied to pixels. NV21 VU pairs retain their order.
Use rotation **0** when submitting those rotated pixels to an algorithm SDK; face boxes are in the output orientation.
Rotation alone is not RGB/IR geometric calibration or face correspondence.

Low-level `UvcCameraManager` callbacks retain source orientation. For custom processing, preallocate an independent direct destination and call
`FrameTransforms.copyRotated(source, destination, width, height, pixelFormat, degrees)`.
It accepts packed NV21/NV12/GRAY8, including odd-sized GRAY8. Buffers must not overlap, destination must be writable,
and positions/limits are preserved.

Close the old session and wait for `onClosed` before applying new resolution, capture FPS, transport or rotation settings.
The Demo apply/restart action follows this order, renegotiates capture and cancels a pending restart when hidden.
With the lower-level `UvcCameraManager`, `close()` also drains asynchronously: wait for `onShutdown` before releasing its Surfaces/SurfaceTextures or creating a replacement manager. The dual-preview Demo follows this order for stop, failure and apply/restart, and checks callback ownership.
Demo choices are common candidate settings, not a capability guarantee for the selected camera. Open errors and observed FPS are shown separately.

## Pixels, ownership and backpressure

| Data | Format | Layout and strides | Bytes at 640x480 |
|---|---|---|---:|
| RGB | NV21 | Packed Y + VU; Y/U/V rowStride=width, UV pixelStride=2 | 460800 |
| IR | GRAY8 | Packed intensity, rowStride=width | 307200 |

Defaults are 640x480 MJPEG capture at 30 FPS per camera, at most 15 delivered pairs/s,
at most 50 ms between callback arrivals and at most 150 ms age per frame at delivery.
Only RGB NV21 / IR GRAY8 are accepted by the paired API, up to portrait/landscape 1080p; dimensions may differ.
Skew/age limits are adjustable software starting points, not calibrated liveness or sensor synchronization thresholds.

Two reusable direct buffers per role (one in flight, one pending) bound the pairing pool to about 1.46 MiB at 640x480 or 9.89 MiB at dual 1080p,
excluding native capture/decode/display caches. USB callbacks copy into the pool, retaining one latest pending frame per role.
One worker delivers pairs serially, without an unbounded task backlog. After a slow consumer returns, the newest samples
are considered; stale, out-of-order or excessively separated samples are dropped. Closing clears pool references and lets the active consumer finish.
Limiting pair delivery does not lower camera capture FPS or automatically control IR illumination.

The pair, frames and pixel views are borrowed only until `onFramePair` returns.
For longer ownership, copy into your own bounded pool while inside the callback.
`copyPixels()` / `copyLuma()` allocate arrays; prefer reusable storage on hot paths.
`Nv21Planes` slices views without copying pixels; the complete capture pipeline still includes copies.

`sessionId` identifies a session within the process; `pairId` increases within that session. Neither tracks a person.
`arrivalTimeNanos` is a host monotonic timestamp at the decoded callback, and `arrivalSkewNanos` describes those arrivals only.
It is not an exposure timestamp and proves no hardware synchronization. Independent cameras' clocks and sequence numbers
cannot be compared directly. Processing can itself make results stale; validate result age and current session again.

## Prepared HelloFace integration point

This SDK has no HelloFace algorithm dependency or wrapper. A consumer can call the actual algorithm APIs synchronously from `onFramePair`:

1. RGB: construct `Yuv420Frame` using the direct Y/U/V views and strides from `Nv21Planes`, explicitly selecting the correct
   color range/matrix. No Bitmap, RGB or I420 round trip is required.
2. IR: use genuine GRAY8 IR measurements, an upright/unmirrored image and a valid face box in that IR image's coordinates.
   The current algorithm's array API requires an explicit copy; this camera SDK does not change that ownership contract.
3. Validate face correspondence, RGB-to-IR calibration, multiple-face rejection and asynchronous result association.
   RGB box coordinates cannot simply be reused on IR. Final business acceptance must bind the same valid sample;
   missing/timed-out IR and results from previous sessions must never be reused as acceptance.

HelloFace 0.8.6's search liveness switch controls RGB liveness. Independent IR evaluation is not automatically part of the search outcome.
This Demo does not invent a unified binocular algorithm API or simulate an acceptance result. That association contract still needs implementation and validation during integration.

Low-level pixels retain source orientation; paired callbacks apply the explicitly configured rotation. Neither API automatically mirrors or detects faces. Keep display transforms separate from algorithm coordinates.
Direct MJPEG GRAY8 uses JPEG intensity range; YUYV GRAY8 preserves source Y. Do not assume a shared threshold across paths.
MJPEG NV21 retains its RGBX conversion; YUYV NV21 preserves source luma. Confirm color metadata for the actual device/path.
NDK, native size budgets and 16 KB ELF/APK alignment gates remain enforced; static alignment does not replace runtime testing on a 16 KB device.
