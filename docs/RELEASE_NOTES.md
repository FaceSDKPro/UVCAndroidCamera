# UVCAndroidCamera 0.4.0

Android SDK 坐标为 `com.facesdkpro:uvc-android-camera:0.4.0`。HelloFace Android Demo 可使用本地 AAR 接入。

- 带像素回调的预览通过独立 Surface 引用完成绘制，采集队列的锁只覆盖窗口引用获取。Surface 写入期间可以继续入队和切换窗口。
- 采集循环复用已有的原子预览状态，减少一次逐帧队列加锁。
- 预览计数检查 `ANativeWindow_unlockAndPost` 返回值，提交失败会保留失败结果。
- 保留 0.3.3 的 USB 描述符边界检查、启动失败上报及关闭阶段控制操作清理。

性能与连续运行的实测范围见 `docs/PERFORMANCE.zh-CN.md`，发布验收见 `docs/VALIDATION.md`。

Samsung SM-S9210 / Android 16 的完整 10 分钟采集、预览和配对测试保持约 30.0 / 29.2 FPS，
配对约 14.96 对/秒。关闭后 USB 句柄和相机线程归零。手机 SKIN 温度从 34.2°C 升至 35.7°C，
温控采样均为 0。小时级运行、摄像头模组温度和电功耗仍需单独验证。

AAR SHA-256 为 `181da6ca85ff109be97c65dcef44008615ceed270b642bdd6cefe160fc8a12e7`。

## 下载

- `uvc-android-camera-0.4.0.aar` 适用于直接接入。
- `uvc-android-camera-0.4.0.zip` 包含四 ABI AAR、本地 Maven 仓库、开发 Demo、独立示例和文档。
- `uvc-android-camera-0.4.0-sources.jar` 提供 Java 源码。
- `UvcCameraDemo-0.4.0.apk` 为开发演示，`versionCode=9`。
- `SHA256SUMS` 提供附件校验值。

## English

Version 0.4.0 renders pixel-callback previews using an independently acquired Surface reference. The preview queue lock covers reference acquisition, allowing queued capture and Surface changes to proceed during rendering. The capture loop reuses its atomic preview state, and preview counters now check the Surface post result. Version 0.3.3 startup, descriptor validation and shutdown fixes are included.

A complete ten-minute capture, preview and pairing run on a Samsung SM-S9210 maintained approximately 30.0/29.2 source FPS and 14.96 paired FPS. USB descriptors and camera workers reached zero after close. Phone SKIN temperature went from 34.2°C to 35.7°C, with thermal-status samples at zero. Hourly operation, camera-module temperature and electrical power require separate measurements.
