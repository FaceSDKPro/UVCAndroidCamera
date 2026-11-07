# UVCAndroidCamera 0.5.0

Android SDK 坐标为 `com.facesdkpro:uvc-android-camera:0.5.0`。

- 默认使用 Middle，RGB / IR 均请求 640×480 MJPEG / 15 FPS。
- High 请求两路 30 FPS，通过 `DualCameraConfig.defaults(DualCameraConfig.Mode.HIGH)` 选择。
- 原有 `DualCameraConfig.defaults()` 默认选 Middle。默认接入增加 0 行，选择 High 替换 1 行。
- RGB NV21 / IR GRAY8、软件配对和旋转继续通过现有配置链设置。
- Demo 主页中文顶部标题为“双目相机”，采集参数默认选 Middle。

模式在打开会话时应用，摄像头须支持对应的分辨率、传输格式和帧率。配对交付上限由已有配置决定。

## 下载

- `uvc-android-camera-0.5.0.aar` 供直接接入。
- `uvc-android-camera-0.5.0.zip` 包含四 ABI AAR、本地 Maven 仓库、开发 Demo、独立示例和文档。
- `uvc-android-camera-0.5.0-sources.jar` 提供 Java 源码。
- `UvcCameraDemo-0.5.0.apk` 为开发演示，`versionCode=11`。
- `SHA256SUMS` 提供附件校验值。

验收范围见 `docs/VALIDATION.md`。性能、温度和连续运行证据见 `docs/PERFORMANCE.zh-CN.md`。

SDK 28 项 Java 单测、14 项 Python 检查及 HelloFace Demo 113 项单测通过。发布 AAR 与已验收的
Middle / High 候选逐字节一致，沿用其 84 项双目真机回归记录。
AAR SHA-256 为 `2db5ac66fd84052878023f23ce2d2e76422b77277cf558038252f30cac895a32`。

## English

Version 0.5.0 defaults dual capture to Middle at 15 FPS per camera. Select High at 30 FPS through
`DualCameraConfig.defaults(DualCameraConfig.Mode.HIGH)`. Existing `defaults()` calls select Middle with zero
additional integration lines. High requires replacing one configuration call. Both modes use 640×480 MJPEG,
RGB NV21 and IR GRAY8. Existing pairing and rotation settings apply. The Chinese Demo header reads “双目相机”.

The release AAR is byte-identical to the previously tested Middle/High candidate, whose 84 device checks are retained.
The SDK build has 28 Java tests and 14 Python checks, and the HelloFace Demo has 113 passing unit tests.
