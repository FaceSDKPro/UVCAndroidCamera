# Android 双目摄像头 SDK 接入

本 SDK 提供独立 USB RGB/IR 摄像头管理、预览、设备控制和有界软件帧配对。
Demo 展示真实的 NV21 / GRAY8 采集与消费接口，不包含 HelloFace SDK、模型、人脸识别或活体判断。
本次准备没有修改或接入 HelloFace 工程。

## 获取 SDK 与 Demo

在源码根目录，使用 JDK 17、Android SDK 36、NDK 29.0.14206865：

```sh
./gradlew :camera:testReleaseUnitTest :camera:lintRelease :sample:lintDemo prepareSdk
```

这只执行本地构建和本地 Maven 目录生成，不上传到远端、不运行 CI/CD。
输出 `build/sdk/uvc-android-camera-0.2.0/` 及同名 ZIP，包含：

- `sdk/`：四 ABI Release AAR 与 Java 源码 JAR。
- `maven-repository/`：固定版本的 AAR、POM、Gradle module 元数据和源码。
- `demo/`：启用 R8 的可安装演示 APK，采用本机标准 Android Debug 签名。
- `demo-source/`：独立示例工程，只依赖交付包内的 Maven AAR，不包含 `camera` 源码模块。
- 中英文说明、许可、`sdk-manifest.json` 和 `SHA256SUMS`。

SDK 最低 API 21；Demo 最低 API 23。Demo APK 仅供开发演示，正式应用使用自己的签名。
没有发布 Maven Central、GitHub Release 或 tag。ABI 可通过 `-PuvcAbis=arm64-v8a,armeabi-v7a` 选择；
同一坐标的不同 ABI 构建应使用不同交付目录，不要混放到同一个依赖缓存。

在解压目录执行 `shasum -a 256 -c SHA256SUMS` 校验交付文件。独立 Demo：

```sh
cd demo-source
# 配置 ANDROID_HOME，或仅在本机创建 local.properties 指向 Android SDK。
./gradlew :sample:assembleDemo
```

集成方在 `settings.gradle` 添加本地仓库，路径指向解压后的 `maven-repository`：

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url = uri('/absolute/path/to/maven-repository') }
        google()
        mavenCentral()
    }
}
```

在应用模块添加：

```groovy
dependencies { implementation 'com.facesdkpro:uvc-android-camera:0.2.0' }
```

也可把 `sdk/*.aar` 放入应用 `libs/`，使用 `implementation files('libs/uvc-android-camera-0.2.0.aar')`。
库没有第三方 Java 运行时依赖；JNI 保留规则和原生许可随 AAR 打包。避免同时引入上游同名底层类或 `.so`。

## 打开双目会话

先取得 CAMERA 运行时权限，再在主线程调用。设备角色由 VID/PID、端口或用户明确选择绑定；不能使用枚举顺序。
`DualCameraSession.devices(context)` 只枚举，不创建常驻监控线程。一个会话管理两个独立 USB 设备，
同一 USB 设备的多个 VideoStreaming 接口暂不支持。

```java
DualCameraSession session = new DualCameraSession(
    context, selectedRgbDevice, selectedIrDevice,
    DualCameraConfig.defaults(), rgbSurface, null, // IR 默认无预览
    new DualCameraSession.Listener() {
        @Override public void onReady(DualCameraSession source) {
            // 主线程：两路流已打开；实际帧对交付仍受新鲜度及时间差门禁约束。
        }
        @Override public void onError(DualCameraSession source, String message, Throwable error) {
            // 主线程：本组采集已停止，作废旧业务结果，待 onClosed 后重新创建会话。
        }
        @Override public void onClosed(DualCameraSession source) {
            // 主线程：两路 native/USB 和在途帧消费者均已结束，现在可以释放 Surface。
        }
    }, pair -> {
        // UVC-pairs 专用线程，串行调用。这里是算法 SDK 的同步接入位置。
        Nv21Planes rgb = pair.rgbPlanes();
        ByteBuffer infrared = pair.ir.luma();
        // rgb.y()/u()/v() 是 DirectByteBuffer 视图；infrared 是紧凑 GRAY8。
        // 对接方同步处理完再返回；不要把 pair 或 Buffer 保存到异步队列。
    });

session.withControls(DualCameraSession.Role.RGB, control -> {
    if (control.isBrightnessEnable()) control.setBrightnessPercent(50);
});

// 页面隐藏、退出或准备改变设备/模式时调用；不阻塞调用线程。
session.close();
```

一路拔出、权限失败、打开失败、消费者抛出异常，或已打开的任一路持续 5 秒不出帧，都会结束整个会话。
重试或重连必须等待 `onClosed`，重新枚举并创建新会话；不会静默换设备或退回单目。
`onReady` 不表示两路在同一时刻曝光，也不表示有一张可用于活体的脸。

## 帧格式、所有权与背压

| 数据 | 格式 | 布局与步长 | 640×480 字节数 |
|---|---|---|---:|
| RGB | NV21 | 紧凑 Y + VU；Y/U/V rowStride=width，UV pixelStride=2 | 460800 |
| IR | GRAY8 | 紧凑单通道；rowStride=width | 307200 |

默认采集 640×480、MJPEG、各 30 FPS；最多交付 15 对/秒。默认回调到达时间差不超过 50 ms，
交付前每路数据不超过 150 ms。配置只接受 RGB NV21、IR GRAY8，分辨率最高为横/竖 1080p，
两路可以具有不同分辨率。时间差/新鲜度是需要实测调整的起始设置，不是活体安全阈值。

固定复用每路 2 个 DirectByteBuffer（一帧在处理、一帧待处理），640×480 合计约 1.46 MiB、双路 1080p 约 9.89 MiB，
不包含底层采集/解码/显示缓存。USB 回调把像素复制到池中，保留每路最新一帧；算法消费者串行执行，
不会积压无限任务。慢消费者返回后再选最新帧，过时、乱序或时间差过大的样本丢弃。关闭时清掉池引用，在途消费者自然结束。
交付频率限制不等于降低摄像头采集 FPS，不会自动关闭 IR 补光。

`FramePair` 与其 `CameraFrame`、像素视图只在 `onFramePair` 调用期间有效。需要跨回调持有数据时，
在调用内复制到宿主自己的有界缓冲池；`copyPixels()` / `copyLuma()` 会分配数组，热路径优先复用存储。
`Nv21Planes` 只切视图，不做像素复制；这个局部行为不意味着整条链路零拷贝。

`sessionId` 标识本进程中的一次会话，`pairId` 是会话内递增的配对序号，均不是人脸跟踪 ID。
`arrivalTimeNanos` 来自主机单调时钟，记录解码后回调到达时间；`arrivalSkewNanos` 仅描述这两个回调的间隔。
它不等于传感器曝光时间，不能证明硬件同步；分离 USB 摄像头的设备时钟或帧序号不能直接互相比较。
消费者内部的长时间处理仍会增加结果延迟，宿主应再次检查结果所属会话和有效期。

## 为 HelloFace 接入预留的位置

本 SDK 不依赖或封装 HelloFace 的算法。集成方可在 `onFramePair` 同步调用现有算法接口：

1. RGB：用 `Nv21Planes` 的 Y/U/V DirectByteBuffer、行步长与 UV pixelStride 构造 `Yuv420Frame`。
   不需要转 Bitmap/RGB/I420；必须显式声明实际颜色范围和矩阵。
2. IR：从真实 IR 流取得 GRAY8，提供已归正、未镜像的图像，以及该 IR 图像中的有效人脸框，
   再调用红外评估接口。当前算法数组入口需显式复制，本摄像头 SDK 不改变其所有权语义。
3. 校验同人对应、标定 RGB→IR 几何关系、处理多人和异步结果关联；不能直接复用 RGB 框坐标。
   最终业务通过必须绑定同一次有效采样，IR 缺失、超时或旧会话结果不可沿用为通过。

现有 HelloFace 0.8.6 的搜索活体开关是 RGB 活体；独立 IR 评估没有自动加入搜索终态。
此 Demo 不伪造统一双目算法接口或最终通过结果。后续接入时还需补充并验证该关联契约。

原始像素不自动旋转、镜像或做人脸检测。显示缩放与算法坐标分开处理。
MJPEG 直接 GRAY8 使用 JPEG 灰度范围；YUYV→GRAY8 保留传感器原始 Y，二者阈值不能假定通用。
MJPEG→NV21 使用现有 RGBX 转换路径；YUYV→NV21 保留源 Y，颜色元数据应根据实际路径与设备确认。
NDK、原生体积预算和 16 KB ELF/APK 对齐保持原门禁；静态对齐通过不能替代 16 KB 页设备运行。
