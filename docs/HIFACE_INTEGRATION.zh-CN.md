# HiFace Android 接入与本地调试

摄像头库保持独立：UVCAndroidCamera 负责采集、像素格式、软件配对和设备状态；
HiFace 负责人脸检测、RGB 身份搜索、IR 活体及最终业务判断。

双目示例使用提供 `RgbIrFrame` 和 `InfraredSearchConfig` 的 HiFace SDK。
Android Demo 的接入入口为 `UVCCameraFaceSearchActivity.java`。

## 本地 AAR 接入

在 UVCAndroidCamera 根目录执行（JDK 17、Android SDK 36、NDK 29.0.14206865）：

```sh
./gradlew :UVCCamera:publishReleasePublicationToSdkRepository
```

本地 Maven 目录为 `build/sdk-maven`，相机坐标为
`com.facesdkpro:uvc-android-camera:0.4.0`。包含 USB 描述符边界检查和关闭阶段待执行控制操作的清理。

对于使用本地 Maven 的项目，在 Demo 的 Gradle 文件中更新坐标：

```kotlin
implementation("com.facesdkpro:uvc-android-camera:0.4.0")
```

HelloFace Android Demo 使用本地文件。把 Release 中的 `uvc-android-camera-0.4.0.aar`
放入 `platforms/Android/demo/libs/` 并同步更新 `implementation(files(...))`。
UVC AAR 进入 Demo，相机实现与 HiFace 算法 SDK 分开交付。

## 双目送帧与终态

页面直接持有 `HiFaceSdk.builder(activity).lifecycleOwner(activity).build()`。
先启动 HiFace 红外搜索，再在主线程创建并启动双目相机：

```java
// 仅为开发起点：实际设备需标定 IR 阈值、归一化 RGB→IR 映射和适用距离。
InfraredSearchConfig irConfig = new InfraredSearchConfig(
    .50f, new float[]{1,0,0,0,1,0,0,0,1}, .25f, 80, 50, 150);
FaceSearchSession search = sdk.search().startCameraSearch(
    FaceSearchSessionConfig.builder().infraredLiveness(irConfig).build(),
    searchListener);

DualCameraSession cameras = DualCameraSession.create(
    context, rgbDevice, irDevice, DualCameraConfig.defaults(), rgbSurface, null,
    cameraListener, pair -> {
        Nv21Planes rgb = pair.rgbPlanes();
        // 默认 MJPEG→NV21 为 VIDEO / BT601；切换传输格式时重新确认颜色元数据。
        Yuv420Frame input = new Yuv420Frame(
            rgb.y(), rgb.u(), rgb.v(), rgb.width, rgb.height,
            rgb.yRowStride, rgb.uRowStride, rgb.vRowStride, rgb.uvPixelStride,
            YuvColorRange.VIDEO, YuvColorMatrix.BT601, 0, false);
        sdk.processFrame(new RgbIrFrame(
            input, pair.ir.luma(), pair.ir.width, pair.ir.height, pair.ir.width,
            pair.sessionId, pair.pairId, pair.rgb.sequence, pair.ir.sequence,
            pair.rgb.arrivalTimeNanos / 1_000_000L,
            pair.ir.arrivalTimeNanos / 1_000_000L));
    });
cameras.start();
```

`processFrame` 同步借用输入，必须在 `onFramePair` 返回前完成；不能保留 pair 或把原始缓冲排到另一个线程。
相机池为每路两个固定缓冲，消费者慢时保留最新待处理帧；不创建逐帧像素数组。
两路的配置旋转已应用到像素，传给 `Yuv420Frame` 的 rotation 为 0，预览矩阵不改变算法坐标。

`infraredLiveness` 明确选择单人 RGB 搜索＋IR 活体，关闭默认 RGB S/M 活体；不能同时显式开启 RGB 活体或多人模式。
身份结果来自 search listener，最终仅在 `MATCH_FOUND` 且同帧 IR 通过时接受；相似度达标也可能出现在
`SPOOF_DETECTED` 的诊断结果中。IR 分数读取 `result.getFrame().getInfrared()`，不写入原 RGB 活体分数。
缺失/过期/不对应的 IR、旧会话或旧帧通过不能沿用为当前通过。

IR 仍须来自真实红外设备。软件配对及单应矩阵几何重叠不证明同步曝光或跨光谱身份相同；
真人、假体材料、阈值和设备几何标定后续单独验收。

## 生命周期与故障

`create()` 先返回句柄，`start()` 再启动，便于保存所有者后接收部分启动失败；原自动启动构造函数继续可用。
页面退出时先停止送帧并关闭搜索，再调用 `cameras.close()`，收到 `onClosed` 后释放 Surface。
关闭不会在主线程等待 native 帧或算法结束；在途消费者和锁外拷贝全部排空后才通知完成。

`onPairingStateChanged` 最多每秒检查两次，仅状态变化通知。丢失帧对时清除旧框/旧业务结果，
保持 HiFace 会话代次校验。任一路断流，或两路有帧却连续 5 秒没有有效帧对，都会结束整组相机。
`onFailure` 包含 RGB/IR 角色、错误码、原因和可选原生状态；覆盖它或原 `onError` 中一个即可。
重试须等待关闭完成，重新枚举、重新确认角色，创建新会话；不静默换设备或降级单目。

## 性能与排查

- `arrivalTimeNanos` 优先使用原生收帧时间，解码延迟不会把旧帧伪装成新帧；它不等于曝光时间。
- `callbackTimeNanos` 记录 Java 回调时间，`inputLatencyNanos()` 仅统计 native 排队/解码/转换至 Java 的延迟。
  `receivedTimeNanos=0` / `nativeSequence=-1` 表示旧 native 未提供元数据；`sequence` 继续用作 Java 回调计数。
- 过期帧在拷贝前退出；拷贝/原生旋转按角色串行、位于两路共享锁外。每路一帧在途、一帧待处理，不累积算法任务。
- IR 无预览时直接解码 GRAY8；开启预览复用同一灰度并直接展开到 Surface，不再解码第二次 JPEG。
- 每秒读取一次 `pairingDiagnostics()` 即可诊断，不逐帧写日志；只保存计数和耗时，不保存人脸图像、特征或身份。
- 降低 `maxPairsPerSecond` 只限制软件交付，不降低相机 USB/传感器 FPS 或 IR 补光功耗。根据实测选择采集 FPS 和处理频率。

| 现象 | 查看 |
|---|---|
| 单路没有回调 | USB 授权、角色、模式协商、`STREAM_TIMEOUT` 及错误角色 |
| 两路有帧但没帧对 | `FRAME_SKEW` / `FRAME_EXPIRED`、skew/expired 计数及输入延迟；不自动放宽门槛 |
| replaced 持续增长 | 消费耗时、交付限速；正常最新帧覆盖不代表故障 |
| outOfOrder 增长 | 时间/回调序号未递增，或原生 32 位序号重复/倒退；正常回绕可通过 |
| 消费长时间阻塞 | `CONSUMER_BUSY`、`maxConsumerDurationNanos`；使用 SDK 背压，不排原始图像队列 |
| 帧对正常但无识别 | 记录 SDK 错误码、模式、颜色和旋转；`NOT_READY` 时等下一对新帧 |

原始设备报告在仓库外；验收范围、失败和未执行项见 [验证记录](VALIDATION.md)。
