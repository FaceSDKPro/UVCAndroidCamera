# UVCAndroidCamera 0.6.0

Android SDK 坐标为 `com.facesdkpro:uvc-android-camera:0.6.0`，供 HiFace 本地接入。

- 修复 USB 设备断开时仅完成一个在途传输的问题。现在逐个排空该设备的全部在途传输，以 `LIBUSB_TRANSFER_NO_DEVICE` 完成回调；保留未提交传输和其他设备的传输。
- 新增直接编译生产 libusb 源码的断开回归测试，覆盖多个传输、回调释放当前传输、无关设备、未提交传输和重复断开。USB 接口为模拟，不代表物理拔插验收。
- 保留 0.5.0 的 Middle / High 接口：默认两路 640×480 MJPEG / 15 FPS，`DualCameraConfig.defaults(DualCameraConfig.Mode.HIGH)` 请求两路 30 FPS。
- RGB NV21 / IR GRAY8、配对限制、旋转和 HiFace 同步送帧方式不变；已有 0.5.0 接入只需更新依赖版本。
- Demo `versionName=0.6.0`、`versionCode=12`。

## 交付文件

- `sdk/uvc-android-camera-0.6.0.aar`：直接放入 HiFace / HelloFace Demo 的 `libs/` 接入。
- `sdk/uvc-android-camera-0.6.0-sources.jar`：Java 源码。
- `maven-repository/`：固定 0.6.0 坐标的本地 Maven 仓库。
- `demo/UvcCameraDemo-0.6.0.apk`：开发演示 APK。
- `demo-source/`：依赖交付 AAR 的独立示例工程。
- `sdk-manifest.json`、`SHA256SUMS` 和中英文文档。

完整包为 `build/sdk/uvc-android-camera-0.6.0.zip`。接入步骤见
[HiFace Android 接入与本地调试](HIFACE_INTEGRATION.zh-CN.md)，本轮实际验收范围见
[验证记录](VALIDATION.md)。CI/CD 继续暂停。

## English

Version 0.6.0 is a local SDK delivery for HiFace under
`com.facesdkpro:uvc-android-camera:0.6.0`. USB disconnect handling now drains every in-flight
transfer belonging to the detached device, completing each with `LIBUSB_TRANSFER_NO_DEVICE`.
Unsubmitted transfers and transfers for other devices remain untouched. A regression test compiles
the production libusb source with simulated USB callbacks, including callbacks that free transfers;
this does not establish physical unplug acceptance.

The 0.5.0 Middle / High API, RGB NV21 / IR GRAY8, pairing limits, rotation and synchronous HiFace
frame delivery are retained. Existing integrations only need to update the dependency version.
The Demo uses `versionCode=12`. The bundle includes the four-ABI AAR, Java sources, local Maven
repository, optimized developer Demo, standalone sample, documentation and SHA-256 checksums.
Validation scope is recorded in `docs/VALIDATION.md`. CI/CD remains paused.
