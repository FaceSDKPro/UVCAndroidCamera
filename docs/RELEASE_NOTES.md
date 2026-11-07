# UVCAndroidCamera 0.3.3

Android SDK 坐标为 `com.facesdkpro:uvc-android-camera:0.3.3`。HelloFace Android Demo 可直接替换本地 AAR 并更新 Gradle 文件引用。

- 在打开摄像头时校验 UVC 控制和流描述符的边界。截断的帧间隔表、无效接口引用等输入通过启动失败回传。
- 关闭相机会话或管理器时，跳过已经排队的 USB 控制操作。在途操作完成后按原顺序释放资源。
- Release 原生诊断路径统一映射到 `/uvc-source`。

现有双目帧回调、软件配对和默认采集模式保持兼容。

## 下载

- `uvc-android-camera-0.3.3.aar` 适用于直接接入。
- `uvc-android-camera-0.3.3.zip` 包含四 ABI AAR、本地 Maven 仓库、开发 Demo、独立示例和文档。
- `uvc-android-camera-0.3.3-sources.jar` 提供 Java 源码。
- `UvcCameraDemo-0.3.3.apk` 为开发演示，`versionCode=8`。
- `SHA256SUMS` 提供附件校验值。

## English

Version 0.3.3 validates UVC control and streaming descriptor bounds during device open and skips pending USB controls during camera or manager shutdown. Release native logs use the normalized `/uvc-source` prefix. The SDK is available as a standalone AAR and as a ZIP with a local Maven repository and sample.
