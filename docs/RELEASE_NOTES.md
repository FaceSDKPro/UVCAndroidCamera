# UVCAndroidCamera 0.6.5

[GitHub Release / SDK 下载](https://github.com/FaceSDKPro/UVCAndroidCamera/releases/tag/v0.6.5)：完整 SDK ZIP、四 ABI AAR、Java 源码、开发 Demo APK 和 SHA-256 校验文件。

The release includes the complete SDK ZIP, four-ABI AAR, Java sources, developer Demo APK and SHA-256 checksums.

- 启用 R8 代码与资源联合优化，保留 JNI 所需规则；四 ABI 原生库继续按 16 KB ELF 与 APK ZIP 对齐。
- 包含 0.6.1–0.6.3 的模式就近匹配、RGB/IR 推荐和预览布局改进；复杂逻辑继续封装在 SDK 内。
- 优化 App 中英文文案，默认英文，简体中文本地化；新增系统的应用语言声明，其他语言回退英文。
- Google Play 文案、宣传图与截图按中英文分别准备；商店图标复用 App 图形，使用浅色背景。
- Demo `versionName=0.6.5`、`versionCode=17`。本地验证和实际送审状态见验证与 Play 记录。

Includes nearest-mode negotiation, RGB/IR role suggestions and larger responsive previews.
English is the default, with Chinese localization and English fallback for other languages.
Store assets use the launcher artwork on a light background. Demo version code is 17.

## UVCAndroidCamera 0.6.3 本地候选

- SDK 提供 `StreamConfig.preferred(role, resolution, fps, transport)`，集中生成角色对应的 NV21 / GRAY8 输出与就近匹配配置；中配/高配分辨率及 15/30 FPS 兼容行为保留。
- 新增 `withMaxPairsPerSecond(...)`，调整交付频率时保留时间限制、旋转和采集配置；双设备有效性检查由 SDK 共用。
- 两个 Demo 页面只读取控件、调用 SDK 并显示状态，移除底层 `UVCCamera` 常量、默认时间限制和重复设备匹配循环。
- 首页竖屏 RGB 预览区域加高 20%；预览标题、参数和状态使用半透明渐变叠在视频上，不另占画面高度。
- 手机横屏采用紧凑顶栏、左侧视频及右侧可滚动设置，实时数据与采集按钮固定在底部；双路预览横屏并排显示，参数入口叠在画面上。
- Demo `versionName=0.6.3`、`versionCode=15`。仅本地候选，实际验证范围见 [验证记录](VALIDATION.md)。

Typed capture preferences now let the SDK own role output formats and nearest-mode behavior.
The Demo passes UI choices directly and can change pair delivery rate without repeating timing defaults.
Existing resolution/FPS fallback and lifecycle checks are retained. Demo version code is 15.
The portrait home preview is 20% taller, with translucent text over the video. Short landscape
layouts put settings beside the video and keep metrics/actions visible; dual previews remain side by side.

## UVCAndroidCamera 0.6.2 本地候选

- 新增 SDK `CameraSelection`：保留当前手动选择，优先已验证 VID/PID，其次使用 RGB / IR / NIR 等明确名称推荐角色。无标识、同级多候选或名称含两种角色时保留手动选择，不按枚举顺序猜测。
- 新增独立分辨率偏好：中配就近 640×480，高配就近 1280×720；`withResolution(...)` 保留 FPS、旋转和配对限制。Middle / High 的 15 / 30 FPS 偏好继续自动就近匹配。
- Demo 分辨率选择收敛为中配 / 高配；旧 1080p 选项恢复为高配，既有 FPS 选项索引不变。无名称设备显示通用 USB 摄像头标签。
- Demo `versionName=0.6.2`、`versionCode=14`。仅本地候选，实际验证范围见 [验证记录](VALIDATION.md)。

SDK role suggestions preserve caller selections, then use verified model IDs and explicit RGB/IR/NIR name hints.
Unknown or ambiguous roles remain manually selectable. Medium/High resolution preferences target 640×480 and
1280×720 independently of FPS; nearest-supported FPS behavior is retained. Demo version code is 14.

## UVCAndroidCamera 0.6.1 本地候选

- 默认配置及 Middle / High 在打开设备时匹配实际能力，优先请求分辨率，再选择最接近的帧率；同等距离优先更小画面和更低帧率。双目回退限制在横/竖 1080p 内，传输格式保持请求值。
- 显式 `new StreamConfig(...)` 保留精确模式；`.withClosestSupportedMode()` 可启用匹配。原 `config` 字段保留请求，新增 `actualConfig()` 获取协商结果。
- 帧元数据、配对缓冲和预览使用实际尺寸。匹配与缓冲配置只在启动阶段执行。
- App 档位不再固定标注 15/30 FPS，显示实际采集参数，失败使用简短中英文提示；原始错误码继续保留在日志和 `CameraFailure` 中。
- Demo `versionName=0.6.1`、`versionCode=13`。本地构建与回归记录见 [验证记录](VALIDATION.md)。

Defaults and Middle / High now select the closest advertised resolution and FPS during startup.
Explicit `StreamConfig` constructors remain exact; opt in with `withClosestSupportedMode()`.
Read negotiated modes through `actualConfig()` and frame geometry from `CameraFrame`.
Pair buffers and previews use actual dimensions. Demo errors use concise localized messages.
The local candidate passed 84 basic dual-camera device checks. Mode negotiation succeeded on
the tested KP26BLD pair; observed RGB throughput was about 16.4–16.6 FPS despite a negotiated
30 FPS mode. See the validation record for measured rates and remaining coverage.

## UVCAndroidCamera 0.6.0

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
