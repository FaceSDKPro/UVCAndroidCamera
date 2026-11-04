# UVCAndroidCamera

[English](README.md) | 简体中文

由 FaceSDKPro 基于 [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) 裁剪维护的 Android USB 摄像头采集库。

**我们将上游仓库裁剪后，用于 [HelloFace](https://github.com/FaceSDKPro/HelloFace) 的 RGB + 近红外（IR）双目人脸识别功能开发。**
本仓库提供可独立引入的 Android 摄像头 SDK 与配套 Demo：双路采集、预览、像素送帧和设备生命周期管理。
识别算法、活体策略和双路人脸关联在 HelloFace / SDK 接入层开发。

## 支持的摄像头类型

本项目的双目采集接口面向 **RGB + 近红外（NIR/IR）双光谱双摄方案：两个独立 UVC 设备、两路独立视频流**。
主机分别枚举可见光 RGB 摄像头与近红外摄像头，每路具有独立的设备标识，分别打开、配置和采集：

- **RGB 路**：一个 UVC 设备，输出一路可见光画面，用于预览及上层人脸检测、识别。
- **IR 路**：另一个 UVC 设备，输出一路真实近红外画面，用于上层红外活体评估。

这里通常所说的“两个 Camera ID”，准确地说是 **Android USB Host 枚举出的两个 `UsbDevice` 标识**。
本库直接通过 USB Host / libusb 访问 UVC 设备，不依赖 Camera2 的 `cameraId`，也不要求设备被系统 Camera2 枚举。
RGB/IR 角色按 VID/PID 或用户配置绑定；插拔后重新枚举，不能依赖设备列表顺序或假定设备标识永久不变。

**当前双目接口不支持单视频流输出的 RGB/IR 模组**，包括将两幅画面左右/上下拼接到同一帧、
或在同一路流中交替输出 RGB/IR 的设备；同一 USB 设备内的多个 VideoStreaming 接口也尚未适配。
两路独立视频流的帧配对属于软件时间关联，不代表硬件同步曝光。

## SDK 与 Demo 交付

当前本地 SDK 版本 **0.3.0**，供 HelloFace SDK 集成开发人员引入；当前没有接入或修改 HelloFace。

```sh
./gradlew prepareSdk
```

本地构建、单元测试、Lint 和制品审计后，生成 `build/sdk/uvc-android-camera-0.3.0.zip`：
包含 AAR、源码 JAR、本地 Maven 仓库、可安装 Demo、只依赖二进制 SDK 的独立 Demo 源码及校验清单。
不执行远端发布或 CI/CD。详细引入方式及完整示例见 [SDK 接入说明](docs/SDK_INTEGRATION.zh-CN.md)。

- `DualCameraSession`：RGB NV21 + IR GRAY8、RGB 单路预览、有界缓冲和串行帧对回调。
- `Nv21Planes`：直接切出 Y/U/V Buffer，便于构造算法 SDK 的 YUV 帧，无 Bitmap/I420 绕行。
- RGB/IR 可分别配置采集分辨率、FPS 和顺时针 0°/90°/180°/270° 旋转；Demo 支持修改后“应用并重启”。
- `FrameTransforms` 在原有池缓冲中旋转 NV21/NV12/GRAY8；`PreviewTransform` 提供 TextureView 的等比预览旋转。
- 一路失败或断开即结束双路，等待清理完成后显式重试；不会自动降级单目。
- 配对使用回调到达时间，仅为软件关联，不代表同步曝光、同一张脸或活体通过。

上游源码基线：`fcdae5f9a194e2111d57019176b53470a120415b`。

保留独立 USB 摄像头并发、单路预览、无预览采集、像素帧回调、UVC 标准设备控制、权限队列及插拔通知。
删除拍照保存、录像、录音、编码器、Java OpenGL 多画面渲染和旧示例。库无第三方 Java 运行时依赖，
仅声明 CAMERA 权限及 USB Host 能力。完整分析见 [UPSTREAM.md](UPSTREAM.md)。

## 优化与实测

围绕 HelloFace 的 RGB + IR 连续采集，主要减少解码、内存复制和线程转发：

- **YUYV 直接转 NV12/NV21**：ARM 使用 NEON，保留源亮度，不再绕经 RGBX；转换内核无需临时缓冲。
- **纯 MJPEG 预览直接解码到 Surface**：按实际步幅写入，省去 RGBX 中间帧及整帧复制。
- **无预览采集减少一次线程转发**：消费者就绪后才解码，只保留最新帧，避免处理过时 JPEG。
- **减少常驻开销**：复用解码器和帧缓冲，USB 监控共用命令线程；原生构建保留 SIMD，裁掉未使用的编码、缩放等模块。像素旋转使用独立的小型原生内核。

采集路径版本 `c0d095c` 曾在 Xiaomi 24091RPADC（Android 16）和两台 RGB/IR 摄像头上，以 640×480、双路 YUYV 请求各 15 FPS、固定 10 ms 曝光进行对照，
**平均每交付一帧的进程 CPU 时间由 28.30 ms 降至 25.46 ms，约减少 10%**，实际吞吐略高于基线。
该版本的完整对照共 60 段样本；MJPEG 各场景的 CPU 区间有重叠，整机电功耗尚未测量。条件、帧率和数据见 [性能记录](PERFORMANCE.zh-CN.md)。

当前 NDK 29 构建体积：

| 项目 | 字节数 |
|---|---:|
| 四 ABI 的 20 个 `.so`，未压缩合计 | 1,893,020 |
| ARM 双 ABI 的 10 个 `.so`，未压缩合计 | 868,816 |
| SDK 0.3.0 四 ABI Release AAR，ZIP 包 | 926,034 |

逐库体积预算和 **1,900,000 字节**原生总量上限持续检查；全部 20 个 `.so` 的 **16 KB ELF 对齐**及示例 APK 的 **16 KB ZIP 对齐**已通过。
16 KB 页真机运行仍待验证。工具链、C++ 运行时取舍及检查方法见 [NATIVE_BUILD.md](NATIVE_BUILD.md)。

## 构建

JDK 17、Android SDK 36、NDK `29.0.14206865`；Gradle Wrapper 固定为 `8.14.3`。
支持 Android 5.0+，示例要求 Android 6.0+。四种 ABI：arm64-v8a、armeabi-v7a、x86、x86_64。

```sh
./gradlew :camera:assembleRelease :camera:testReleaseUnitTest :sample:assembleRelease
python3 tools/check_artifact.py camera/build/outputs/aar/camera-release.aar --apk sample/build/outputs/apk/release/sample-release-unsigned.apk
```

原生配置与 HiFace 对齐到 NDK 29、C++17、ThinLTO、栈保护及 RELRO/NOW/NX；保留编解码 SIMD。
库继续兼容 API 21，示例为 API 23；默认不移除 x86 模拟器支持。HelloFace/HiFace 只需 ARM 时可构建双 ABI：

```sh
./gradlew :camera:assembleRelease -PuvcAbis=arm64-v8a,armeabi-v7a
python3 tools/check_artifact.py camera/build/outputs/aar/camera-release.aar --abis arm64-v8a armeabi-v7a
```

使用轻量 `system` C++ 运行时满足基础 new/delete，不传递 STL 对象或异常；原因和后续迁移约束见 [原生构建说明](NATIVE_BUILD.md)。
制品检查同时核对权限、原生导出、依赖、体积、ELF/APK 对齐，以及 APK 与 AAR 中的原生库字节一致性。

必要时可显式指定 Google Maven 镜像：

```sh
./gradlew :camera:assembleRelease -PgoogleMavenMirror=https://maven.aliyun.com/repository/google
```

默认使用 Google/Maven Central；不使用 JCenter、JitPack 或上游发布账号配置。
构建输出位于各模块 `build/`，不提交 AAR、APK、原生二进制或本机签名材料。

## 接入

将 `camera` 模块加入项目，或在本机构建并使用 AAR。
`com.facesdkpro:uvc-android-camera:0.3.0` 是本工程的本地发布坐标，尚未发布到 Maven Central。
需要本地 Maven 时运行 `./gradlew :camera:publishReleasePublicationToMavenLocal`。

```java
UvcCameraManager manager = new UvcCameraManager(context, new UvcCameraManager.Listener() {
    @Override public void onOpened(UvcCameraManager.Camera camera) { }
    @Override public void onError(UsbDevice device, String message, Throwable cause) { }
});

// 在应用中先取得 CAMERA 运行时权限，再从 manager.devices() 选择设备。
// RGB/IR 的角色由 VID/PID 或用户选择绑定，不能依赖列表顺序。
UvcCameraManager.Camera rgb = manager.open(rgbDevice, StreamConfig.defaults(), rgbSurface,
        (camera, frame) -> {
            ByteBuffer pixels = frame.pixels(); // 只在回调期间有效的 DirectByteBuffer，只读 NV21。
            // 在这里同步消费；若要异步保留，显式调用 frame.copyPixels()。
        });
StreamConfig irConfig = new StreamConfig(640, 480, 30,
        UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_GRAY8, 0);
UvcCameraManager.Camera ir = manager.open(irDevice, irConfig, null,
        (camera, frame) -> {
            ByteBuffer gray8 = frame.luma(); // 紧凑 GRAY8；异步处理时用有界数组池配合 copyLumaTo。
        });

rgb.withControls(control -> {
    if (control.isBrightnessEnable()) control.setBrightnessPercent(50);
});

// 页面离开时停止送帧；原生资源在命令线程释放，onShutdown 表示清理结束。
manager.close();
```

`StreamConfig.defaults()` 明确选择 **640×480、MJPEG、30 FPS、NV21 回调**。
不支持的分辨率/帧率会失败，不会静默改为更大的模式。输入格式也可选择设备提供的 YUYV。
回调支持 YUYV、NV12、NV21、RGB、RGB565、RGBX、BGR、GRAY8；RAW 在此表示解码后的 YUYV，
不是压缩 MJPEG 字节。低层 `UvcCameraManager` 回调保留采集方向；双目 `FramePair` 按配置实际旋转像素，
90°/270° 时输出宽高交换。均不自动镜像或做人脸检测。

### 分辨率、帧率与旋转

`StreamConfig(width, height, fps, frameType, pixelFormat, quirks)` 设置每路实际 UVC 采集请求；
`DualCameraConfig.withRotations(rgbDegrees, irDegrees)` 设置双目回调的独立像素旋转。
预览 Surface 保持采集方向，宿主使用 `PreviewTransform.fitCenter(...)` / `centerCrop(...)` 对 TextureView 做显示变换。
应用新配置时先关闭旧会话，等待 `onClosed` 再重新打开；Demo 的“应用并重启”已执行这一步骤。
设备不支持的分辨率/格式/FPS 组合会明确失败，不会静默降级。采集 FPS、实际观测 FPS 与帧对交付上限分别显示。
完整调用和输出坐标约定见 [SDK 接入说明](docs/SDK_INTEGRATION.zh-CN.md)。

状态回调在主线程；每路帧回调在该摄像头的原生线程，采用只保留最新帧的队列。
回调内不要等待 UI，也不要在保留借用 Buffer 后返回；需要跨线程时自行有界复制/丢帧。
`CameraFrame.arrivalTimeNanos` 是主机单调时钟的回调到达时间，不能当作传感器时间戳或硬件同步证明。

一个会话对应一个独立 USB 设备，可同时开多个会话。单个 USB 设备内多个 VideoStreaming 接口
需要额外适配，不在当前多摄契约内。插拔会通知宿主；拔出关闭对应会话，重新插入后由宿主按角色重新选择。
IR 人脸框、双路同人关联及活体算法由上层 SDK 管理。

## 低功耗接入与像素约定

- 只需要显示时使用纯预览会话；需要 SDK 像素时再传入 FrameListener，只给需要显示的摄像头设置 Surface。
- IR 不需要预览且 SDK 接受对应灰度范围时，选择 `PIXEL_FORMAT_GRAY8`。无预览 MJPEG 直接输出 `width * height` 字节的全范围灰度；有预览时复用显示所需的 RGBX 帧生成灰度。
- 用 `frame.luma()` 同步借用亮度，或用 `copyLumaTo(byte[], offset)`、`copyPixelsTo(byte[], offset)` 复制到复用数组；异步队列保持有界，借用视图只在回调期间有效。
- 按业务选择设备支持的分辨率/帧率，不使用时关闭会话。主机丢帧不会降低相机 USB 流量、传感器或红外补光功耗。

**YUYV 的像素值约定**：NV12/NV21 直转保留源 Y，并对相邻两行色度平均；GRAY8 无论有无预览都保留源 Y。
这些数值与旧的 YUYV→RGBX→NV 绕行结果可能不同。MJPEG 的 NV12/NV21 仍使用原颜色转换；JPEG 直接 GRAY8 也不保证等于该 NV21 的 Y。
接入 SDK 时按实际输入范围使用像素；详细说明见 [PERFORMANCE.zh-CN.md](PERFORMANCE.zh-CN.md)。

`UvcCameraManager` 默认使用 USB 广播与五秒一次的兜底扫描。
`new UvcCameraManager(context, listener, devicePollMillis)` 可设为 `0`（仅广播及首次扫描），或至少 1000 毫秒的间隔。
关闭轮询前应确认目标系统插入广播可靠；缺少广播时，发现延迟取决于轮询间隔。

## 示例与设备验收

2026-11-04 在上述平板及 RGB/IR 摄像头上，59 项设备检查、24 组双路模式及 10 个切换阶段通过；
另有 120 组 YUYV 数值组合、6 组 JPEG 步幅/异常恢复检查通过。性能测量后，59 项资源回归再次通过。
后续全局复核增加了 JNI 重入/引用、分配失败、USB 提交及 RGBX 步幅边界检查；完整证据范围见 [VALIDATION.md](VALIDATION.md)。

`sample` 默认进入 SDK Demo：明确选择 RGB/IR，每路独立设置分辨率、采集 FPS、MJPEG/YUYV 和旋转角度；
深色面板显示 RGB 预览、旋转后的 NV21/GRAY8 宽高、实际采集 FPS、帧对速率及回调到达时间差，IR 无预览。
横屏采用预览与设置并排布局，窄屏采用上下布局；空间充足时参数区独立滚动，紧凑竖屏支持整页滚动，开始/停止按钮固定在底部。RGB 蓝色、IR 琥珀色用于区分通道，连接状态和错误独立显示。
`SdkDemoActivity.consumePair()` 是算法同步接入位置；当前只检查像素契约，不模拟识别或活体结果。
顶部“双路预览”进入两个 TextureView 的预览页；每路“参数”弹窗提供设备选择、独立分辨率/FPS/传输格式/预览旋转及设备支持的亮度控制。该页面只预览，并显示实际帧率和累计帧数。
窄屏或紧凑横屏可滚动浏览预览卡片，保证两路画面均有完整的显示区域。
页面旋转后保留已选参数，采集按原生命周期停止，需要手动重新打开。界面使用 Android 原生 View 和矢量资源，没有增加 UI 框架依赖。
`demo` 为使用本机标准 Debug 签名、启用 R8 且不带诊断 Activity 的可安装演示构建。
`deviceTest` 是使用 Release 优化和 JNI 保留规则、以本机 Debug 身份签名的独立验收构建；
其中的诊断 Activity 不进入正式 `release`。

```sh
./gradlew :sample:assembleDeviceTest
python3 tools/run_device_checks.py --serial YOUR_ADB_SERIAL --output /path/outside/repository
```

设备需要允许安装、CAMERA 和两路 USB 访问。脚本验证双路无预览出帧、反复开关、错误恢复、
Buffer/GRAY8 契约、设备参数读取、两路预览更新及资源释放，只保存统计和日志，不保存图像。
文件描述符按 USB/事件/管道/图形等类型分别记录，避免将 Android 图形缓冲缓存误判为采集连接泄漏。
物理拔插、长期稳定性、硬件同步及 16 KB 真机运行需分别验收，不能由短时采集或 ELF 对齐检查代替。验收记录见 [VALIDATION.md](VALIDATION.md)。

## 来源与许可

Java/JNI 适配层沿用 Apache-2.0；原文件版权和修改说明保留。
libusb、libuvc、libjpeg-turbo、libyuv、RapidJSON 保留各自许可，详见 [NOTICE](NOTICE)、
源码内的许可文件及 AAR 内 `META-INF/uvccamera/LICENSES/`。
此工程采用新的精简管理 API；替换上游时请移除旧依赖，避免同名 JNI/底层类重复打包。
