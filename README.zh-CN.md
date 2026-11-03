# UVCAndroidCamera

[English](README.md) | 简体中文

由 FaceSDKPro 基于 [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) 裁剪维护的 Android USB 摄像头采集库。

**我们将上游仓库裁剪后，用于 [HelloFace](https://github.com/FaceSDKPro/HelloFace) 的 RGB + 近红外（IR）双目人脸识别功能开发。**
本仓库提供双路摄像头采集、预览、像素送帧和设备生命周期管理，作为 HelloFace 双目功能开发的采集基础。
识别算法、活体策略和双路人脸关联在 HelloFace / SDK 接入层开发。

上游源码基线：`fcdae5f9a194e2111d57019176b53470a120415b`。

保留独立 USB 摄像头并发、单路预览、无预览采集、像素帧回调、UVC 标准设备控制、权限队列及插拔通知。
删除拍照保存、录像、录音、编码器、Java OpenGL 多画面渲染和旧示例。库无第三方 Java 运行时依赖，
仅声明 CAMERA 权限及 USB Host 能力。完整分析见 [UPSTREAM.md](UPSTREAM.md)。

## 构建

JDK 17、Android SDK 36、NDK `27.0.12077973`；Gradle Wrapper 固定为 `8.14.3`。
支持 Android 5.0+，示例要求 Android 6.0+。四种 ABI：arm64-v8a、armeabi-v7a、x86、x86_64。

```sh
./gradlew :camera:assembleRelease :camera:testReleaseUnitTest :sample:assembleRelease
python3 tools/check_artifact.py camera/build/outputs/aar/camera-release.aar
```

必要时可显式指定 Google Maven 镜像：

```sh
./gradlew :camera:assembleRelease -PgoogleMavenMirror=https://maven.aliyun.com/repository/google
```

默认使用 Google/Maven Central；不使用 JCenter、JitPack 或上游发布账号配置。
构建输出位于各模块 `build/`，不提交 AAR、APK、原生二进制或本机签名材料。

## 接入

将 `camera` 模块加入项目，或在本机构建并使用 AAR。
`com.facesdkpro:uvc-android-camera:0.1.0` 是本工程的本地发布坐标，尚未发布到 Maven Central。
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
UvcCameraManager.Camera ir = manager.open(irDevice, StreamConfig.defaults(), null,
        (camera, frame) -> {
            byte[] gray8 = frame.copyLuma(); // 从真实 IR 摄像头的 NV21/NV12 取 Y 平面。
        });

rgb.withControls(control -> {
    if (control.isBrightnessEnable()) control.setBrightnessPercent(50);
});

// 页面离开时停止送帧；原生资源在命令线程释放，onShutdown 表示清理结束。
manager.close();
```

`StreamConfig.defaults()` 明确选择 **640×480、MJPEG、30 FPS、NV21 回调**。
不支持的分辨率/帧率会失败，不会静默改为更大的模式。输入格式也可选择设备提供的 YUYV。
回调支持 YUYV、NV12、NV21、RGB、RGB565、RGBX、BGR；RAW 在此表示解码后的 YUYV，
不是压缩 MJPEG 字节。像素帧不自动旋转、镜像或做人脸检测。

状态回调在主线程；每路帧回调在该摄像头的原生线程，采用只保留最新帧的队列。
回调内不要等待 UI，也不要在保留借用 Buffer 后返回；需要跨线程时自行有界复制/丢帧。
`CameraFrame.arrivalTimeNanos` 是主机单调时钟的回调到达时间，不能当作传感器时间戳或硬件同步证明。

一个会话对应一个独立 USB 设备，可同时开多个会话。单个 USB 设备内多个 VideoStreaming 接口
需要额外适配，不在当前多摄契约内。插拔会通知宿主；拔出关闭对应会话，重新插入后由宿主按角色重新选择。
IR 人脸框、双路同人关联及活体算法由上层 SDK 管理。

## 示例与设备验收

`sample` 提供两个 TextureView、设备选择、三档分辨率、帧计数及亮度控制。
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
