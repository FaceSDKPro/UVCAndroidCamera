# Android dual-camera SDK integration

This standalone SDK manages independent RGB/IR USB cameras, previews, controls and bounded software frame pairing.
The Demo consumes real NV21/GRAY8 frames without a HelloFace SDK dependency, models, face recognition or liveness decisions.
For the HiFace Android integration steps and debugging counters, see [the Chinese integration guide](HIFACE_INTEGRATION.zh-CN.md).

The AAR keeps its public API and ships JNI consumer rules. Enable R8 in the host app; for AGP 8.12/8.13, `android.r8.optimizedResourceShrinking=true` enables joint code/resource optimization. The delivered Demo uses this configuration.

## Build and obtain the SDK

Use JDK 17, Android SDK 36 and NDK 29.0.14206865 at the source root:

```sh
./gradlew :UVCCamera:testReleaseUnitTest :UVCCamera:lintRelease :sample:lintDemo prepareSdk
```

This builds locally and creates a local Maven directory. It does not upload artifacts or run CI/CD.
`build/sdk/uvc-android-camera-0.6.5/` and its ZIP contain:

- `sdk/`: four-ABI Release AAR and Java sources JAR.
- `maven-repository/`: fixed-version AAR, POM, Gradle module metadata and sources.
- `demo/`: installable, R8-optimized demonstration APK using the local standard Android debug identity.
- `demo-source/`: standalone sample that consumes the bundled Maven AAR, without a `UVCCamera` source module.
- Bilingual documentation, licenses, `sdk-manifest.json` and `SHA256SUMS`.

Minimum API levels are 21 for the SDK and 23 for the Demo. Use your own application signing for production.
Version 0.6.5 is delivered as a local SDK bundle for HiFace. Use its AAR or bundled Maven repository; it is not published to Maven Central.
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
dependencies { implementation 'com.facesdkpro:uvc-android-camera:0.6.5' }
```

Alternatively, copy the AAR into application `libs/` and use
`implementation files('libs/uvc-android-camera-0.6.5.aar')`.
There are no third-party Java runtime dependencies. JNI consumer rules and native license notices are embedded.
Do not also package upstream copies of the same classes or native libraries.

## Open a paired session

Version 0.6.5 retains Middle as the default. Existing `DualCameraConfig.defaults()` calls continue to work.
Select High by replacing one configuration line:

```java
DualCameraConfig config = DualCameraConfig.defaults(DualCameraConfig.Mode.HIGH);
```

Middle and High prefer 15 and 30 FPS at 640×480 MJPEG. When unavailable, opening selects the nearest advertised geometry, then the nearest FPS, within the requested transport format. Ties prefer fewer pixels and lower FPS. Paired capture remains bounded to portrait/landscape 1080p, with RGB NV21 and IR GRAY8 output.
Existing pairing limits apply, and capture mode takes effect when opening a session.

Resolution preferences are independent of FPS: Medium prefers 640×480 and High prefers 1280×720.
Both select the closest advertised mode. Changing resolution preserves FPS preferences, rotations and pairing limits:

```java
DualCameraConfig config = DualCameraConfig.defaults()
    .withResolution(StreamConfig.Resolution.HIGH); // prefer 720p, still prefer 15 FPS
```

Use `Resolution.MIDDLE` to restore the default geometry preference. `StreamConfig.withResolution(...)` also supports single streams.

Use SDK suggestions to initialize editable device selectors:

```java
CameraSelection selected = CameraSelection.suggest(DualCameraSession.devices(context));
if (!CameraSelection.isPair(selected.rgb, selected.ir)) return; // show manual selectors
UsbDevice rgbDevice = selected.rgb;
UsbDevice irDevice = selected.ir;
```

Current caller-selected devices take priority, followed by verified VID/PID profiles, then case-insensitive RGB / Color /
visible-light and IR / NIR / Infrared name hints. Chinese role names are supported. Equally ranked candidates, combined RGB/IR
names and unknown labels leave the corresponding role null. An unlabelled remaining device is not assumed to be the other role;
monochrome pixels are not used to infer an infrared sensor.
`suggest(devices, rgbDeviceName, irDeviceName)` retains bindings for the current connection. USB device names are not persistent
identities across reconnects; a missing preferred device is not silently replaced by this call. Suggestion does not open devices,
read permission-protected serial numbers or create threads.

Obtain CAMERA runtime permission and construct the session on the main thread.
Bind distinct RGB/IR devices using those suggestions or explicit user selection.
`DualCameraSession.devices(context)` enumerates without starting a resident monitor.
Each session requires two independent USB devices; multiple VideoStreaming interfaces within one device are not supported.

```java
DualCameraSession session = DualCameraSession.create(
    context, selectedRgbDevice, selectedIrDevice,
    DualCameraConfig.defaults(), rgbSurface, null, // headless IR
    new DualCameraSession.Listener() {
        @Override public void onReady(DualCameraSession source) {
            // Main thread: streams are open. Actual pairs still require fresh, sufficiently close arrivals.
            source.withControls(DualCameraSession.Role.RGB, control -> {
                if (control.isBrightnessEnable()) control.setBrightnessPercent(50);
            });
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

session.start(); // Assign the handle before any asynchronous startup failure.

// Stop when hidden, leaving the screen, or changing devices/modes. This does not block.
session.close();
```

Detach, permission denial, start failure, a throwing consumer, five seconds without frames from either opened camera, or five seconds without a usable pair
ends the entire session. Wait for `onClosed`, enumerate again and create a new session to retry.
There is no silent device substitution or single-camera fallback.
`onReady` proves neither simultaneous exposure nor a usable face.

## Resolution, frame rate and rotation

Each `StreamConfig` width/height is the original capture resolution, and `fps` requests a device capture rate.
Explicit `new StreamConfig(...)` requests remain exact. Add `.withClosestSupportedMode()` to opt into the matching used by defaults.
For normal integration use `StreamConfig.preferred(role, resolution, fps, transport)`; the SDK owns RGB NV21 / IR GRAY8 output formats and closest-mode selection. The Demo needs no low-level `UVCCamera` constants.
After `onReady`, `session.actualConfig()` provides the negotiated modes. Low-level callers read `camera.actualConfig()` after `onOpened`; existing `config` fields retain the original requests.
Use actual modes for preview transforms and `CameraFrame.width/height` for pixels. The SDK sizes pair buffers before frame delivery.
Exposure, USB bandwidth and processing can lower the observed rate. `maxPairsPerSecond` limits consumer delivery only.

```java
StreamConfig rgb = StreamConfig.preferred(DualCameraSession.Role.RGB,
    StreamConfig.Resolution.HIGH, 30, StreamConfig.Transport.MJPEG);
StreamConfig ir = StreamConfig.preferred(DualCameraSession.Role.IR,
    StreamConfig.Resolution.MIDDLE, 15, StreamConfig.Transport.YUYV);
DualCameraConfig config = DualCameraConfig.forStreams(rgb, ir)
    .withMaxPairsPerSecond(10)
    .withRotations(90, 270); // Independent clockwise rotations

// Update after the TextureView is laid out and whenever its dimensions change.
rgbTextureView.setOpaque(false);
rgbTextureView.setTransform(PreviewTransform.fitCenter(
    rgbTextureView.getWidth(), rgbTextureView.getHeight(), rgb.width, rgb.height,
    config.rgbRotationDegrees));
// Open DualCameraSession with config. Surface preview remains in source orientation;
// the matrix above handles its display rotation.
```

`forStreams()` starts with the default pair limits. `withPairingLimits()` and `withRotations()`
return new configurations without mutating the original. The existing constructors remain available.
Use `withMaxPairsPerSecond()` to change only delivery rate, preserving timing limits, rotations and capture preferences.

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

Defaults use Middle, preferring 640x480 MJPEG at 15 FPS and selecting the closest supported mode when needed, at most 15 delivered pairs/s,
at most 50 ms between host frame receipts and at most 150 ms age per frame at delivery.
Only RGB NV21 / IR GRAY8 are accepted by the paired API, up to portrait/landscape 1080p; dimensions may differ.
Skew/age limits are adjustable software starting points, not calibrated liveness or sensor synchronization thresholds.

Two reusable direct buffers per role (one in flight, one pending) bound the pairing pool to about 1.46 MiB at 640x480 or 9.89 MiB at dual 1080p,
excluding native capture/decode/display caches. USB callbacks copy into the pool, retaining one latest pending frame per role.
One worker delivers pairs serially, without an unbounded task backlog. After a slow consumer returns, the newest samples
are considered; stale, out-of-order or excessively separated samples are dropped. Copy and rotation run outside the cross-camera lock, serialized per role. Expired inputs are rejected before pixel copy.
Closing clears pool references and waits asynchronously for both the active consumer and in-progress copies.
Limiting pair delivery does not lower camera capture FPS or automatically control IR illumination.

The pair, frames and pixel views are borrowed only until `onFramePair` returns.
For longer ownership, copy into your own bounded pool while inside the callback.
`copyPixels()` / `copyLuma()` allocate arrays; prefer reusable storage on hot paths.
`Nv21Planes` slices views without copying pixels; the complete capture pipeline still includes copies.

`sessionId` identifies a session within the process; `pairId` increases within that session. Neither tracks a person.
`arrivalTimeNanos` now uses native host receipt time before queues/decode, in Android elapsedRealtimeNanos time.
`callbackTimeNanos` is Java delivery after decode; `receivedTimeNanos=0` / `nativeSequence=-1` explicitly indicates
legacy native metadata, in which case arrival time falls back to callback time. `inputLatencyNanos()` measures
receipt-to-Java latency (not pair waiting or algorithm time). `nativeSequence` is unsigned 32-bit and wraps;
the original `sequence` remains a monotonic Java callback counter. `arrivalSkewNanos` compares host receipt times.
It is not an exposure timestamp and proves no hardware synchronization. Independent cameras' clocks and sequence numbers
cannot be compared directly. Processing can itself make results stale; validate result age and current session again.

## Prepared HelloFace integration point

This SDK has no HelloFace algorithm dependency or wrapper. A consumer can call the actual algorithm APIs synchronously from `onFramePair`:

1. RGB: construct `Yuv420Frame` using the direct Y/U/V views and strides from `Nv21Planes`, explicitly selecting the correct
   color range/matrix. No Bitmap, RGB or I420 round trip is required.
2. IR: use genuine GRAY8 IR measurements, an upright/unmirrored image and a valid face box in that IR image's coordinates.
   The published standalone array API requires an explicit copy; the private paired API accepts a synchronous direct view.
3. Validate face correspondence, RGB-to-IR calibration, multiple-face rejection and asynchronous result association.
   RGB box coordinates cannot simply be reused on IR. Final business acceptance must bind the same valid sample;
   missing/timed-out IR and results from previous sessions must never be reused as acceptance.

Published HelloFace 0.8.6's search liveness switch controls RGB liveness. Independent IR evaluation is not automatically part of that search outcome.
HiFace's private development SDK now provides `RgbIrFrame` and `InfraredSearchConfig` for a paired search outcome; see
[HiFace integration](HIFACE_INTEGRATION.zh-CN.md). It requires the matching development SDK, not the published 0.8.6 binaries.
Camera calibration, face correspondence and human/attack effectiveness still require device validation. The standalone camera Demo performs no algorithm acceptance.

Low-level pixels retain source orientation; paired callbacks apply the explicitly configured rotation. Neither API automatically mirrors or detects faces. Keep display transforms separate from algorithm coordinates.
Direct MJPEG GRAY8 uses JPEG intensity range; YUYV GRAY8 preserves source Y. Do not assume a shared threshold across paths.
MJPEG NV21 retains its RGBX conversion; YUYV NV21 preserves source luma. Confirm color metadata for the actual device/path.
NDK, native size budgets and 16 KB ELF/APK alignment gates remain enforced; static alignment does not replace runtime testing on a 16 KB device.

## Pair health and failure completion

`onPairingStateChanged(session, diagnostics)` is sampled at 2 Hz and delivered only on changes: waiting for RGB/IR/both,
paired, skew, expired, consumer busy, or closed. It is not a per-frame callback. Invalidate old business results on
pair loss; a source frame or historic IR pass never substitutes for a current valid pair. Five seconds without a
usable pair closes the session even when both cameras still produce frames. Thresholds are never relaxed to force pairing.

`onFailure(session, role, failure)` preserves a structured code, device, cause and optional native open status.
Role is null for whole-session failures. Its default calls the original `onError` once; override either callback.
The original auto-start constructor remains available. `create()` owns no workers/USB/pixel pools until `start()`;
close-before-start and partial startup failure still deliver one `onClosed`. Release Surfaces only after that callback.

For GRAY8 callbacks, an enabled preview shows the same grayscale decode, expanded directly into Surface RGBA.
No additional JPEG decode or RGBX intermediate is used. MJPEG GRAY8 reports `lumaRange=FULL`; MJPEG NV21/NV12 reports
`VIDEO`; source YUYV luma is `UNSPECIFIED` and needs an explicit device profile. Preview toggling does not normalize
or stretch algorithm pixels. Existing RGB NV21 conversion is retained.
