# Performance and lower-power integration

English | [简体中文](PERFORMANCE.zh-CN.md)

This library supports HelloFace RGB + IR acquisition. The changes below reduce avoidable work;
they do not guarantee a particular device power budget.

## Implementation

- Each decoding thread owns and reuses its TurboJPEG decompressor. Bad JPEGs reset the instance; header dimensions are still checked. Decoder quality flags are unchanged.
- The input queue holds one latest frame instead of four queued frames. Callback delivery also retains only the latest frame.
- Headless capture queues compressed input and decodes only when the consumer is ready. This avoids decoding discarded frames when an SDK callback is busy. Decoding and a blocking callback then run sequentially, which can reduce callback throughput. For throughput-oriented processing, use short callbacks and a bounded asynchronous worker with explicitly owned pixel copies.
- `PIXEL_FORMAT_GRAY8` requests packed grayscale. Headless MJPEG uses TurboJPEG grayscale output directly, avoiding RGBX and chroma output. With a preview, the required RGBX display frame is reused. JPEG grayscale uses full-range values; YUYV grayscale retains source luma. Existing NV12/NV21 conversion stays unchanged. JPEG GRAY8 is therefore not promised to match the previous RGB-to-NV21 Y values byte for byte.
- Compact YUYV input can be delivered directly for a headless YUYV callback. Buffer reuse prefers the smallest fitting allocation.
- Preview-only sessions skip the pixel callback thread and use an atomic native preview-post counter. Startup checks still detect a camera that produces no preview.
- Stop signals are sent under their queue mutexes, waits check predicates, and the preview thread owns capture-thread creation and joining. Running/capture flags are atomic.
- Close releases JNI frame/status/button callback references. USB status callback targets outlive the USB event thread. Repeated frame-callback/Surface assignments release redundant references. Session timeouts and startup checks are cancelled when no longer needed.
- `CameraFrame.luma()` borrows a read-only direct view. `copyLumaTo()` and `copyPixelsTo()` copy into caller-owned reusable arrays, avoiding a new image array on every callback. Borrowed views still expire on callback return.
- The manager's fallback USB scan changes from one second to five seconds. The constructor accepts another interval, or zero for broadcasts plus the initial scan. Missing attach broadcasts then delay discovery until a fallback scan; verify OEM broadcast delivery before disabling polling. This changes scan frequency, not a measured device power percentage.

For HelloFace integration, use a preview only where the UI needs it, use GRAY8 for IR analysis when the SDK accepts that range/layout, choose a supported capture mode, and close unused sessions. Host-side dropping does not reduce the camera's USB traffic, sensor consumption or IR illumination power. This library does not perform RGB/IR hardware synchronization.

## Reproducing measurements

```sh
./gradlew :sample:assembleDeviceTest
python3 tools/run_performance_checks.py --serial YOUR_ADB_SERIAL \
  --output /path/outside/repository --width 640 --height 480 \
  --scenarios headless preview slow gray preview-only
```

The release-optimized harness warms up for five seconds, then collects three 15-second samples per scenario.
It records process CPU time, elapsed time, delivered/preview frame rates, thermal service snapshots,
APK/source hashes and whether library sources stayed unchanged. `slow` adds 80 ms of callback work using sleep.
It saves no images. CPU percentage uses one core as 100%; it is neither total-device utilization nor watts.

Use identical exposure, scene, USB topology, screen state and thermal conditions for an A/B comparison.
Compare achieved frame rates as well as CPU time; fewer delivered frames cannot be counted as a free performance gain.
Use an external power meter to validate electrical consumption of the whole setup. Follow performance checks with
`tools/run_device_checks.py` for functional/resource regression; the benchmark does not replace those checks.

## Evidence on 2026-11-03

An initial experiment used the Xiaomi 24091RPADC / Android 16 tablet with RP26PWD RGB and RP26LD IR cameras,
requesting 640×480 / MJPEG / 30 FPS / NV21. Exposure was not locked, and achieved frame rates varied:

| Scenario | Baseline process CPU, one-core % | Intermediate candidate CPU, one-core % | Total delivered FPS, baseline → candidate |
|---|---:|---:|---:|
| Headless, fast callback | 86.6–97.1 | 87.9–91.1 | 41.7 → 36.6–36.7 |
| Preview plus callback | 89.5–91.5 | 82.1–83.7 | 36.7 → 41.7 |
| Headless, 80 ms callback delay | 90.7–100.6 | 81.4–84.6 | 24.3–24.5 → 21.6–22.0 |

These observations do not establish equal-throughput performance or electrical power savings.
They refer to intermediate library source fingerprint
`8f56941e0c0be8624c75c44e998294530247bb676f04719ed5d0c894f6bfba63`, before subsequent reference/timeout cleanup.
The baseline fingerprint was `3f61d8efe6753bcd96bb562f036126a5aa4be8bafc0b62febb4de10bc94f39ec`.

Wireless ADB disconnected during the grayscale run. The final candidate's full device regression,
GRAY8/preview-only measurements, controlled A/B repetition and electrical power measurements remain pending.
A preliminary 1920×1080 request was rejected by the baseline with `UVC_ERROR_INVALID_MODE`; it is not included as a passed case.
Build/unit/static results and historical validation are recorded separately in [VALIDATION.md](VALIDATION.md) (Chinese).

## Native build and thread trimming on 2026-11-04

The four-ABI native payload changed from **6,895,160 to 1,869,156 bytes (72.9% smaller)** with the same NDK/toolchain.
Five version scripts retain 133 reviewed cross-library entry points. Function/data sections allow unused code to be collected.
JPEG encoding, transforms, BMP/PPM helpers, legacy libuvc/libyuv JPEG paths, and unused YUV compare/rotate/scale modules leave the build.
SIMD conversion kernels remain. The native C/C++ boundary is private; integration uses the Java API.

USB monitoring now shares the manager's command looper. An externally owned looper is preserved when the monitor is destroyed.
The device harness checks for one command thread and no extra USBMonitor thread.
Native export/dependency checks run on all 20 libraries, including the two 64-bit ABIs' 16 KB ELF alignment.
An incremental rebuild test verifies version-script edits across all ABIs and rejects an added export outside the reviewed policy.

First-open negotiation now retries the identical requested mode once on `-51`, matching the existing mode-switch compatibility behavior.
This addresses stateful format probing without selecting another resolution or rate.
Detailed device and format results are in [VALIDATION.md](VALIDATION.md) (Chinese).
The size reduction and removed thread are resource results. Controlled CPU results follow below; electrical consumption remains unmeasured.

## Controlled CPU check on 2026-11-04

The baseline is commit `5c972d9` before this round of trimming. The same local release-optimized measurement driver ran trimmed/baseline/baseline/trimmed rounds, using both cameras at 640×480 / MJPEG / 30 FPS. Manual exposure was set to 10 ms and read back on both devices. Each scenario warmed up for five seconds, then collected three five-second samples per round (six per variant/scenario). Original exposure mode/settings were restored and checked after all 16 scenarios (32 camera restorations). The tablet was left with the verified sample installed and stopped.

CPU below is mean process CPU (sample range), with one core as 100%:

| Scenario | Baseline CPU % | Trimmed CPU % | Baseline FPS, two cameras | Trimmed FPS, two cameras |
|---|---:|---:|---|---|
| Headless NV21 | 91.62 (81.99–100.28) | 92.87 (85.49–98.98) | 29.24 / 29.98 | 29.21 / 29.98 |
| 80 ms callback delay | 84.01 (78.43–89.53) | 82.88 (79.74–85.23) | 11.22 / 11.19 | 11.26 / 11.16 |
| Headless GRAY8 | 89.60 (87.47–90.91) | 88.13 (86.19–91.73) | 29.24 / 29.97 | 29.24 / 29.98 |
| Preview only | 91.66 (89.70–94.46) | 91.53 (90.30–92.80) | 29.20 / 30.00 | 29.20 / 29.96 |

Frame throughput was maintained. CPU sample ranges overlap and do not show a consistent reduction from this round of trimming. These measurements support the binary-size/thread reductions without establishing a steady-state CPU or electrical-power gain. The local measurement driver, input AAR/APK hashes and raw reports are retained outside the source repository.

## NDK 29 alignment on 2026-11-04

The current build uses HiFace's NDK 29.0.14206865 and C++17, with verified ThinLTO, strong stack protection and RELRO/NOW/NX.
Four-ABI native bytes changed from 1,869,156 to **1,844,380** (1.33% smaller); the two-ARM native payload is **840,244** bytes.
AAR ZIP bytes increased slightly from 867,986 to 879,206. The minimal C++ runtime is retained after a static libc++ experiment grew native bytes to 2,583,240.
Per-library budgets and both ELF/APK 16 KB alignment checks now guard the build. Configuration, ABI totals and tradeoffs are in [NATIVE_BUILD.md](NATIVE_BUILD.md).
The fixed-exposure CPU table above belongs to the earlier NDK 27 candidates; this toolchain change has not repeated that CPU comparison or measured electrical consumption.
