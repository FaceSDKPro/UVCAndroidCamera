# HiFace Android 接入与本地调试

本文对照本机 HiFace Android Demo 与 SDK 0.8.6 的接口编写。摄像头库继续独立：HiFace 应用持有算法会话、USB 设备选择及最终业务状态。当前 HiFace 的 `UVCCameraFaceSearchActivity` 只是页面占位，尚未接入 UVC。

## 先让 HiFace Demo 消费本地 UVC AAR

在本仓库构建 ARM 双 ABI 本地交付包（JDK 17、Android SDK 36、NDK 29.0.14206865）：

```sh
./gradlew prepareSdk -PuvcAbis=arm64-v8a,armeabi-v7a
```

这与 HiFace Demo 的 `abiFilters` 一致。输出位于 `build/sdk/uvc-android-camera-0.3.0/maven-repository`，只在本机生成，不上传。HiFace 的 `platforms/android/settings.gradle.kts` 在 `dependencyResolutionManagement.repositories` 中添加：

```kotlin
maven {
    name = "LocalUvcCamera"
    url = uri("/Users/zlb/code/UVCAndroidCamera/build/sdk/uvc-android-camera-0.3.0/maven-repository")
    content { includeModule("com.facesdkpro", "uvc-android-camera") }
}
```

HiFace 的 `platforms/android/demo/build.gradle.kts` 添加 `implementation("com.facesdkpro:uvc-android-camera:0.3.0")`。调试 HiFace 算法仍可用原有 `debugImplementation(project(":sdk"))`；UVC 独立以 AAR 接入，可验证交付包中的 JNI、资源和 consumer ProGuard 规则。HiFace Demo 已有 CAMERA 权限；实际启动仍须先取得运行时权限和 USB 访问授权。调试本地 UVC 源码时，可先在此仓库运行 `:sample:assembleDemo` 和 `:sample:assembleDeviceTest`，再重新生成本地 AAR 供 HiFace 消费。不要把本机绝对路径写入对外提交的 Gradle 文件；用项目本地 Gradle 属性传入目录更合适。

## 双目帧送入 HiFace 搜索

HiFace 的 `FaceSearchSession` 管搜索状态；相机帧仍要调用同一个 `HiFaceSdk.processFrame()`。先创建 HiFace SDK 和搜索会话，再在主线程创建 `DualCameraSession`；两个 USB 设备须由 VID/PID、端口或用户选择明确绑定为 RGB 和 IR。不要按设备枚举顺序绑定。

```java
// 以下代码放在 HiFace 应用的会话控制器中；颜色参数应来自设备实测配置。
YuvColorRange range = YuvColorRange.VIDEO;
YuvColorMatrix matrix = YuvColorMatrix.BT601;
DualCameraSession cameras = new DualCameraSession(
    context, rgbDevice, irDevice, DualCameraConfig.defaults(), rgbSurface, null,
    cameraListener, pair -> {
        Nv21Planes rgb = pair.rgbPlanes();
        Yuv420Frame input = new Yuv420Frame(
            rgb.y(), rgb.u(), rgb.v(), pair.rgb.width, pair.rgb.height,
            rgb.yRowStride, rgb.uRowStride, rgb.vRowStride, rgb.uvPixelStride,
            range, matrix, 0, false);
        hiFaceSdk.processFrame(input);
        // IR 帧在同一回调内有效；另行完成 IR 人脸定位与 RGB/IR 同人关联。
    });
```

`Nv21Planes` 的 Y/U/V 是只读 DirectByteBuffer 视图；`processFrame()` 同步借用输入，返回后不能再保存这些视图。`DualCameraSession` 已串行交付、限速并只保留最新待处理帧。像素在 `DualCameraConfig.withRotations()` 指定后已经实际旋转，因此传给 `Yuv420Frame` 的旋转参数为 **0**。预览矩阵不代表算法像素旋转。这里的 `VIDEO/BT601` 仅示例：HiFace 要求显式传入真实采集路径的颜色范围与矩阵，不能靠 UVC 自动推断；换 MJPEG/YUYV 或相机时重新确认。HiFace 算法最终结果来自其 listener；异步结果要再核对当前摄像头 `sessionId` 和业务会话，避免旧会话结果回填。

IR 输入必须来自真实红外流。`InfraredFrame` 要求正向、未镜像 GRAY8 像素，以及 **IR 图像坐标**中的人脸框；构造函数会复制数组。可在回调内用 `pair.ir.copyLumaTo(reusableGray, 0)` 获得紧凑灰度，但在完成 IR 检测、双目几何标定和同人校验前，不应调用 `evaluateInfrared()` 给出双目活体结论。HiFace 0.8.6 的搜索活体开关目前只管 RGB；独立 IR 结果不会自动合入搜索终态。帧对仅按主机回调到达时间配对，不证明同步曝光或同一张脸。

页面停止时先停止送帧并关闭搜索会话，再 `cameras.close()`；等待 `onClosed` 后释放预览 Surface 或重新选择设备并创建新会话。拔出、权限失败、任一路断流会结束整组摄像头，不自动降级为单目。

## 排查“有预览、没有识别”

`DualCameraSession.pairingDiagnostics()` 可从任意线程读取一次一致快照。Demo 的帧对卡片显示累计交付对数和丢弃帧数；排查时每秒记录一次快照及 `receivedFrames(RGB/IR)`，只记计数和设备 VID/PID，避免保存人脸图像。

| 现象 | 首先查看 |
|---|---|
| 某路 `receivedFrames` 为 0 | USB 授权、设备角色、分辨率/格式/FPS 协商、该路断流错误。 |
| 两路有帧、`dispatchedPairs` 为 0 | `expiredRgb/Ir`、`skewRgb/Ir`、`pendingRgb/Ir`；结合 `maxFrameAgeMillis` 与 `maxPairSkewMillis` 调整。 |
| `replacedRgb/Ir` 持续增长 | 算法消费耗时或交付限速；比较采集 FPS、`maxPairsPerSecond` 和业务处理耗时。 |
| `outOfOrderRgb/Ir` 增长 | 同一路到达时间没有严格递增；检查上游回调时钟与重入。 |
| 帧对正常但 HiFace 没结果 | 记录 `processFrame()` 的错误码、搜索 listener 状态、颜色参数和已应用旋转；`NOT_READY` 时继续送后续新帧。 |

这些丢弃数是软件配对计数，不包含 USB/解码器在回调前丢失的帧。`dispatchedPairs` 包含已开始但消费者抛异常的回调。需要完整设备验收时使用 `tools/run_device_checks.py`，它与日常页面诊断用途不同；本地验证命令和历史结果见 [验证记录](VALIDATION.md)。
