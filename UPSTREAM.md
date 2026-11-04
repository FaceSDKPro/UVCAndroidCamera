# 上游分析与裁剪记录

来源：<https://github.com/shiyinghan/UVCAndroid>，分支 `main`，
提交 `fcdae5f9a194e2111d57019176b53470a120415b`，上游声明版本 1.0.13。
导入的是可追溯的源码快照，未导入上游 Git 历史、发布 APK、本机构建缓存或签名配置。

## 功能分析

| 层 | 上游能力 | 本工程处理 |
|---|---|---|
| USB/JNI | USB 枚举/授权、libusb/libuvc、MJPEG/YUYV、UVC 控制 | 保留，四 ABI 从源码构建 |
| 采集 | UVCCamera、像素转换、帧回调 | 保留，补充无 Surface 出帧 |
| 预览 | 原生 Surface + EGL/RendererHolder 多目标渲染 | 保留单路 Surface，删除 Java EGL/多目标渲染 |
| 拍照 | ImageCapture、ImageReader/OpenGL 抓图、ImageSaver | 删除 |
| 录像/录音 | MediaCodec、MediaMuxer、AudioRecord、编码器及输出文件 | 删除 |
| 管理 | CameraHelper、CameraConnectionService、CameraInternal、多线程单例服务 | 替换为一个监视器、一个命令线程、多个独立设备会话 |
| Android UI | 旧 Dialog、Widget、文件/URI/动画工具、两套示例 | 删除，重写原生双路示例 |
| 依赖/权限 | AndroidX Core、Legacy、AppCompat；录音、存储、前台服务权限 | 无第三方 Java 运行时依赖；只留 CAMERA 和 USB Host |
| 原生附属文件 | JPEG 测试图片、Java 包装、文档图片、RapidJSON 第三方测试等 | 删除不参与 Android 构建的部分，保留实际编解码依赖和许可 |

上游 `CameraInternal.openCameraInternal()` 会为相机创建 ImageCapture 和 VideoCapture 对象，
即使宿主只需要帧。精简管理层直接使用底层 UVCCamera，不再初始化这些对象。
初始裁剪保留上游解码/颜色转换链；后续底层优化见下节，不将裁剪规模或静态检查当作运行性能证明。

## 资源与行为优化

- USB 连接副本关闭时，只移除由本对象拥有的监视器记录，避免原连接丢失后无法统一释放。
- 销毁时遍历设备键的快照，避免边遍历边删除导致剩余连接未关闭。
- USB 广播优先，兼容性轮询由 150 ms 调整为 1000 ms；权限回调限定到应用包，并再次核对系统授权状态。
- 开关、设备控制和清理在命令线程串行执行；多路权限请求排队，停止后拒绝新帧和旧状态回调。
- 流模式必须精确匹配；协商失败立即释放资源，不把默认最大分辨率冒充成功配置。
- 临时 Java Surface 及时释放；帧采用借用的只读 DirectByteBuffer，只有宿主明确复制时才复制像素。
- JNI 使用的三个回调接口及方法明确保留，防止 R8 消除 `onFrame` 等原生反射入口；回调设置和采集启动失败即时返回错误。
- 无预览采集复用原生出帧链，删除了必须有预览窗口才能启动的限制。
- 删除未被调用且含有错误缓冲区处理的实验转换函数，以及误编入库的原生 example.c。

新 API 入口：`UvcCameraManager`、`StreamConfig`、`CameraFrame`。
它不兼容上游拍照/录像 API，也不实现 RGB/IR 硬件同步或单个 USB 设备的多视频接口选择。

## 后续底层优化（2026-11-03）

- TurboJPEG 实例按解码线程复用；解码失败后重建实例，继续验证 JPEG 头尺寸，保留原有解码质量参数。
- 解码前的四帧队列改为单帧覆盖；无预览时先排队压缩帧，消费者忙时丢弃尚未解码的旧帧。
- 新增明确的 GRAY8 回调；无预览 MJPEG 直接解码灰度，带预览时复用已有 RGBX。NV12/NV21 保留原颜色转换。
- 无预览的紧凑 YUYV 回调可直接借用；缓冲池优先选择最小的足够容量，减少大缓冲的扩散。
- 纯预览不再创建像素回调线程和 DirectByteBuffer，用原生成功提交的预览帧计数检查启动。
- 原生运行状态使用原子变量；条件变量按谓词等待；预览线程统一创建/回收捕获线程，防止启动后立即停止时漏等线程。
- 释放帧/状态/按钮回调 JNI 引用，处理重复设置帧回调或 Surface 的额外引用；状态/按钮回调对象在 USB 事件线程退出后销毁。
- 权限和首帧检查按会话标记并及时取消；取消正在授权的会话会继续处理队列；回调异常只安排一次清理。
- 新增只读亮度视图和复制到宿主数组的接口，减少逐帧分配；管理器兜底扫描默认由一秒改为五秒，并允许按设备配置或关闭轮询。

这些优化不会停止仍在运行的 USB 采集，也不提供 RGB/IR 硬件同步。
低功耗接入、颜色范围和性能验收边界见 [PERFORMANCE.zh-CN.md](PERFORMANCE.zh-CN.md)。

## 原生构建与线程继续裁剪（2026-11-04）

- 通过五份 ELF version script 限制跨库符号，保留合计 133 个已审查入口；按函数/数据分段并回收不可达代码。四种 ABI 都校验实际导出、跨库引用、依赖和机器类型，Java/JNI API 继续保留。
- JPEG 只保留本采集链所需的解码入口；编码、变换及 BMP/PPM 文件辅助代码退出构建。libuvc 的旧 MJPEG 转换、libyuv 的第二套 JPEG 入口不再参与构建，后两库不再依赖 JPEG 动态库。
- libyuv 只编译本采集链需要的转换及 SIMD 内核；比较、旋转、缩放等模块退出构建。实际用到的 C++ 包装没有异常/RTTI 使用，明确关闭这些编译支持。
- 管理器的 USB 事件、轮询和命令共用后台 Looper；USBMonitor 的旧构造方式继续自行持有线程，新构造方式不退出调用者拥有的 Looper。
- 删除九个未参与构建的 origin/orgin 副本及示例/测试构建脚本，保留 canonical 源码、许可及版权文本。
- 首次打开在模式协商返回 `-51` 时，对原分辨率、格式和帧率仅重试一次，与已有 `setPreviewSize` 的格式切换兼容处理一致，不选择其他模式。
- 链接脚本通过显式 make 目标依赖和 Gradle 输入跟踪。验证时临时增加一个已存在的 USB 导出，确认四 ABI 都重新链接、制品门禁拒绝额外导出，再恢复并重新检查。
- ELF 元数据检查器与 NDK 的 llvm-nm/llvm-readelf 对全部 20 个共享库交叉核对。

实现采用 [Android NDK 符号可见性说明](https://developer.android.com/ndk/guides/symbol-visibility)中的 version script 方式。裁剪的原生 C/C++ 接口是实现细节；新增跨库调用时需更新导出清单并完成四 ABI 验证。体积、真机和未执行项见 [VALIDATION.md](VALIDATION.md)。

## 2026-11-04 的工具链与交付检查

对齐 HiFace 的 NDK 29.0.14206865、C++17、ThinLTO 与安全链接参数；保留 SIMD 和原生导出边界。
集中管理 NDK/可选 ABI，提供 ARM 双 ABI 构建；保留独立库 API 21 和默认四 ABI。
轻量 C++ 运行时与 HiFace 的差异基于实际体积测量，原因与迁移约束见 [NATIVE_BUILD.md](NATIVE_BUILD.md)。
新增逐库体积预算、全部 ABI 的 ELF LOAD/安全属性检查，以及可选 APK 的 16 KB ZIP 对齐和 native 字节一致性检查。
同时核对实际 C/C++ bitcode、ThinLTO 链接命令，避免只在配置中声明优化。
工作流仍只有手动入口，更新未来手动构建所需 NDK 包版本，本次不触发 CI/CD。

## 规模

按初始裁剪快照统计，Java 主代码由 **74 个文件 / 20,960 行**，减为 **15 个文件 / 6,379 行**
（文件数约减少 80%，行数约减少 70%，不含示例/测试）。
Release AAR 包含四种 ABI 的 20 个共享库；原生编解码与 USB 库仍是主要体积来源。
字节大小受 NDK、编译配置和 ZIP 影响，不用不同构建的压缩大小推断运行性能。

## 来源补充

上游快照缺少 libyuv 根目录的 LICENSE/PATENTS/AUTHORS。本工程于 2026-11-03 从
<https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/> 补充这些文本：

- LICENSE SHA-256 `2b2cc1180c7e6988328ad2033b04b80117419db9c4c584918bbb3cfec7e9364f`
- PATENTS SHA-256 `4da84211f947c6f9f7dd8fbce513e1522e01833407ae732b8c9a2bbb75fed495`
- AUTHORS SHA-256 `9bb006eaa8cc06d145c5234ae6b12f8fed6109e0b744d2c530f9b774c607e931`

这些是许可补充来源，不表示本工程的 libyuv 实现已经更新到该日 main。
原生实现仍来自上述 UVCAndroid 精确源码快照，修改项见本文件及各文件头说明。
