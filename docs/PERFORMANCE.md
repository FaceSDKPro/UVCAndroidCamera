# Performance and lower-power integration

English | [简体中文](PERFORMANCE.zh-CN.md)

This library supports HelloFace RGB + IR acquisition. The changes below reduce avoidable work;
they do not guarantee a particular device power budget.

## Implementation

- Each decoding thread owns and reuses its TurboJPEG decompressor. Bad JPEGs reset the instance; header dimensions are still checked. Decoder quality flags are unchanged.
- The input queue holds one latest frame instead of four queued frames. Callback delivery also retains only the latest frame.
- Headless capture queues compressed input and decodes only when the consumer is ready. This avoids decoding discarded frames when an SDK callback is busy. Decoding and a blocking callback then run sequentially, which can reduce callback throughput. For throughput-oriented processing, use short callbacks and a bounded asynchronous worker with explicitly owned pixel copies.
- `PIXEL_FORMAT_GRAY8` requests packed grayscale. MJPEG uses TurboJPEG grayscale output directly, avoiding RGBX and chroma output; an optional preview expands the same gray pixels into the Surface. JPEG grayscale uses full-range values; YUYV grayscale retains source luma with or without a preview. MJPEG NV12/NV21 retains its RGBX matrix conversion; the direct YUYV path is described below. JPEG GRAY8 is therefore not promised to match the previous RGB-to-NV21 Y values byte for byte.
- Compact YUYV input can be delivered directly for a YUYV callback, including with a preview. Buffer reuse prefers the smallest fitting allocation.
- MJPEG preview-only sessions decode into the actual Surface stride, avoiding an intermediate RGBX copy. Preview-only sessions skip the pixel callback thread and use an atomic native preview-post counter. Startup checks still detect a camera that produces no preview.
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

## Capture path changes on 2026-11-04

- Headless USB frames go directly to the latest capture queue. The preview worker owns stream lifetime and sleeps instead of forwarding every frame. Atomic readiness/Surface state supports path transitions; modulo-32-bit sequence comparison prevents an old preview-queue frame from replacing newer headless input. Replacing pending data does not notify the consumer again.
- MJPEG preview-only decoding writes directly into ANativeWindow's actual stride, avoiding an intermediate RGBX frame and full-frame copy. An independent Surface reference covers decoding without holding the preview-queue lock. A bad JPEG clears the target buffer and resets the decoder. Pixel callbacks retain independent frame storage and their existing borrowed lifetime.
- YUYV→NV12/NV21 directly preserves source Y and averages vertically adjacent chroma samples with upward rounding. ARM uses NEON; other targets use the scalar path. No RGB conversion, scratch buffer, heap allocation or range rescaling is required. With a preview, original YUYV also feeds RAW/YUV/NV12/NV21/GRAY8 callbacks while RGBX is used for display.
- Frame growth now preserves the original pointer/capacity when realloc fails, and records data length for borrowed buffers.

YUYV NV12/NV21 intentionally differs from the old YUYV→RGBX→NV output: RGB clipping, matrix conversion and a second quantization are removed. YUYV GRAY8 consistently preserves source Y even with a preview. MJPEG NV12/NV21 retains the prior matrix path and JPEG quality flags. Consumers must use the camera's actual range rather than assume byte equality with the old round trip. This does not provide hardware synchronization or reduce camera USB/sensor/illumination consumption.

The four-ABI native payload is **1,869,372 bytes**, up 24,992 (1.36%) from 1,844,380, within the unchanged 1,900,000-byte ceiling. The two ARM ABIs total **856,080 bytes**. Export and 16 KB ELF/APK alignment checks remain enforced.

Native numerical tests cover 120 dimension/stride/NV12/NV21 combinations, SIMD blocks/tails and buffer bounds. Synthetic JPEG checks compare packed/strided active pixels and cover short buffers, bad stride/dimensions and recovery after truncated input. Run with host cjpeg installed:

```sh
python3 tools/run_native_checks.py --serial YOUR_ADB_SERIAL --output /path/outside/repository
```

### Fixed-exposure A/B results for this change

The baseline is the NDK 29 AAR from `2159e68`. Both Release/R8 benchmark APKs have byte-identical driver DEX; only the capture AAR changes.
The same tablet and RGB/IR cameras use 640×480, MJPEG requested at 30 FPS or YUYV at 15 FPS, with manual 10 ms exposure read back.
Candidate/baseline/baseline/candidate rounds each warm up for five seconds and take three five-second samples per scenario: six samples per variant/scenario, 60 total.
All 40 exposure restorations were read back successfully. CPU is process mean (sample range), with one core as 100%; FPS lists both cameras.

| Scenario | Baseline CPU % | Optimized CPU % | Baseline FPS | Optimized FPS |
|---|---:|---:|---|---|
| MJPEG / headless NV21 | 93.39 (86.72–99.44) | 90.85 (81.88–96.70) | 29.28 / 30.04 | 29.34 / 30.01 |
| MJPEG / 80 ms callback delay | 83.44 (80.36–85.86) | 78.87 (74.59–82.93) | 11.29 / 11.19 | 11.19 / 11.12 |
| MJPEG / headless GRAY8 | 87.61 (86.29–89.51) | 83.26 (79.86–87.19) | 29.37 / 30.01 | 29.30 / 30.07 |
| MJPEG / preview only | 91.33 (89.80–93.06) | 90.19 (87.37–93.21) | 29.26 / 30.06 | 29.22 / 30.02 |
| YUYV / headless NV21 | 83.10 (79.48–86.12) | 75.61 (70.75–81.54) | 14.52 / 14.85 | 14.68 / 15.02 |

YUYV process CPU per delivered frame changed from **28.30 ms to 25.46 ms (about 10.0% less)**, with slightly higher achieved throughput; mean process CPU changed from 83.10% to 75.61%.
MJPEG mean differences are smaller and sample ranges overlap. Slow-callback throughput also varies slightly; CPU percentage alone does not establish equal-cost throughput or stable power savings.
Direct preview removes an intermediate frame/copy, but this run does not establish a stable CPU gain for that scenario. Electrical current/watts were not measured.

The initial attempt with extra thread statistics exceeded Android's single-log limit and is excluded; measurements were repeated using complete chunked reports.
Thread names can be inherited, so name-aggregated context-switch records are diagnostic only and are not used to quantify an individual preview worker's wake reduction.
Raw inputs, driver, reports and failed attempts remain outside the repository. The accepted sample is restored and capture stopped; post-performance resource regression is recorded separately in VALIDATION.md.

## Details following global review

Stable frame geometry/stride is now initialized at stream start instead of looking up descriptors for every frame. The review also fixes JNI callback reentrancy/reference ownership, metadata growth/release, USB startup failures and small-width RGBX conversion bounds. Thread names are assigned at their own entry points to avoid inherited-name ambiguity in later diagnostics.

Current native bytes are **1,888,028 across four ABIs** or **866,444 across the two ARM ABIs**; the AAR is **902,427 bytes**. Existing size and 16 KB alignment gates remain enforced. This round validates correctness, fault recovery and cleanup; it does not repeat the historical `c0d095c` 60-sample CPU comparison or establish a new electrical-power result.

## RGB + IR pairing refinements on 2026-11-05

- Pairing still owns two fixed direct buffers per role: **1,536,000 bytes at 640×480**, excluding native capture/decode/display storage. The latest pending frame replaces its predecessor; a slow algorithm cannot grow the queue. No per-frame metadata array or additional pixel pool is introduced.
- Full-frame copy/rotation runs outside the shared pairing lock. Only producers of the same role serialize. A blocked RGB copy therefore permits IR receipt, health snapshots and close; a deterministic concurrency test covers this. Close completion waits for both in-flight copies and the consumer.
- Native host receipt time is recorded before queueing/decoding and carried as primitive callback arguments. Expired input is rejected before copying/rotation and checked again before delivery. Pair age is no longer renewed by decode time. This is host timing, not sensor exposure synchronization.
- A GRAY8 preview expands the same decoded grayscale pixels directly into the Surface, using NEON on ARM and a scalar tail. It avoids a second JPEG decode and a 1,228,800-byte RGBX scratch frame at 640×480. Actual retained native capacity depends on stream history; this is not a measured process-memory reduction.
- Pair health is sampled at 2 Hz and notified only on state changes. Counters and durations are primitives; status does not retain images or log every frame. `create()` defers USB/workers/pixel-pool ownership until `start()`.

The updated four-ABI native payload is **1,898,012 bytes**, including **871,244 bytes** across the ARM ABIs. The unchanged 1,900,000-byte ceiling, 133 exports per ABI and 16 KB alignment gates still pass. This leaves little native-size headroom; future additions must keep the same audit.

A short host-only 640×480 / two 30-FPS producers / 80-ms consumer comparison against `720e186` uses the same 1,536,000-byte pool. Each approximately two-second window accepts 120 inputs and delivers 24 pairs. Producer plus pair-worker CPU totals are 19.70/19.83 ms for baseline and 21.48/21.60 ms for the update: about 1.8 ms more per window. It checks scheduling/copy overhead, without USB, JPEG or face models. It does not establish phone CPU or power savings. Final device capture and validation scope are recorded in [VALIDATION.md](VALIDATION.md); fixed-exposure CPU/power comparison, camera calibration and human/attack liveness testing remain separate work.

## State management and performance balance (2026-11-05)

Startup confirmation and draining USB status transfers keep their synchronization at open/close. During capture, the session watchdog reuses input timestamps already maintained by the pairing engine. Copy completion checks for final shutdown while holding the existing ownership lock. This removes one duplicate atomic timestamp write and one extra shared-lock acquisition per input frame, about 60 of each per second with two 30-FPS streams. Health checks remain at 2 Hz with notifications only on state changes. Five-second timeouts, two buffers per role, age/order checks and safe shutdown remain in place. No extra configuration, persistent worker or per-frame logging was added; the repository guidance records this balance.

A host stress check uses the production `FramePairDispatcher`, 640×480 pixels, two producers and a blocked consumer, in baseline/update/update/baseline order. Each variant has ten samples of 10,000 input frames. Median producer CPU is **82.177 → 82.736 ms**, with overlapping ranges and no clear gain; USB, decoding and Android are excluded. The low-level two-stream MJPEG/NV21 headless device check records three five-second samples per variant: **88.79–96.72% → 94.98–100.88%** of one core, with streams near **29.17 / 29.97 FPS**. Exposure was not locked and this scenario does not exercise pairing, so it cannot attribute CPU changes to this edit or establish CPU/power savings. Reduced duplicate state maintenance is the established result; capture, pairing throughput and shutdown checks are recorded in [VALIDATION.md](VALIDATION.md).


## Preview and sustained capture on 2026-11-07

Version 0.4.0 renders pixel-callback previews outside the capture queue mutex while holding an independent
window reference. It reuses the atomic preview state and checks Surface post failures before counting a preview.
Native regression cases cover a Surface switch during blocked rendering, failed posts and balanced window references.

A Samsung SM-S9210 on Android 16 ran RP26PWD RGB and RP26LD IR cameras for 600.056 seconds after five seconds
of warmup. Both streams requested 640×480 MJPEG at 30 FPS. RGB delivered NV21 with a preview, IR delivered GRAY8,
and software pairing was limited to 15 pairs per second. Diagnostic window brightness was 35%. HiFace processing
was outside this test.

RGB and IR averaged 29.997 and 29.207 FPS, pairing averaged 14.959 FPS, and mean input latency was 3.549 ms.
Process CPU averaged 58.09% of one core. Native heap allocation stayed within 15,297–15,535 KiB.
The six active USB descriptors and seven camera workers reached zero after close, which took approximately 519 ms.
Java heap grew from approximately 5.2 MiB to 27.0 MiB and returned to 2,108 KiB after the shutdown GC.
Native heap after close was 10,387 KiB. PSS included the live Activity and graphics caches and was 71,401 KiB after close.

Phone SKIN temperature went from 34.2°C to 35.7°C. All thermal-status samples were zero.
A two-minute 0.3.3 baseline averaged 61.43% CPU, 29.995/29.204 source FPS, 14.960 paired FPS and 3.677 ms mean input latency.
Exposure was unlocked and run durations and starting temperatures differed, so these observations do not establish
a repeatable CPU or cooling benefit. Camera-module temperature and electrical power were unmeasured.
Hourly and multi-day stability remain separate validation work. Two earlier runs interrupted by Activity backgrounding
are retained as interrupted runs.

The isolated diagnostic package contained all 20 exact native libraries from the 0.4.0 AAR.
Raw metadata is outside the source tree at `/tmp/uvc-endurance-o3XcJ4/`. No camera images were saved.


A subsequent two-minute 15 FPS run averaged 15.001/14.602 source FPS, 14.610 paired FPS, 4.928 ms mean input latency,
and 53.36% process CPU relative to one core. Phone SKIN temperature went from 35.2°C to 35.8°C.
Close took approximately 586 ms and drained USB descriptors and camera workers.
This mode reduced input and paired throughput alongside CPU use. Different durations and starting temperatures
also limit comparison, so it does not establish a same-throughput optimization or camera-module cooling.
