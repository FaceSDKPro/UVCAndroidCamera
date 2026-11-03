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
Native 中仍使用上游兼容的解码/颜色转换链；未把本次裁剪宣传为算法或 CPU 性能提升。

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

## 规模

按源码快照统计，Java 主代码由 **74 个文件 / 20,960 行**，减为 **15 个文件 / 6,379 行**
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
