# 验证范围（2026-11-03）

## 构建与内容

- JDK 17 / Gradle 8.14.3 / AGP 8.13.2 / Android SDK 36 / NDK 27.0.12077973。
- arm64-v8a、armeabi-v7a、x86、x86_64 四种 ABI 从源码构建通过。
- 4 项帧所有权、GRAY8、参数边界单元测试通过，失败/跳过均为 0。
- camera/sample 的 Release Lint 无错误；示例 Release 和真实启用 R8 的 deviceTest 构建通过。
- AAR 只声明 CAMERA 权限，无第三方 Java 运行时依赖；不含拍照、录像、录音或 Java EGL 模块。
- 两个 64 位 ABI 的全部 10 个共享库通过 16 KB ELF LOAD 段对齐检查。
- 原生第三方许可随源码和 AAR 保留；源码上传检查排除构建产物、本机配置和凭据形态内容。

## 已连接设备

Xiaomi 24091RPADC，Android 16 / API 36 / arm64-v8a，系统页大小 4096 字节。
摄像头为 ASPIRE RP26PWD RGB（1187:3A49）和 RP26LD IR（1871:3B49）。

裁剪版本身的 Release 优化构建完成 **29 项检查，29 通过，0 失败**：

- 三轮两路同时无预览采集，每轮约 8 秒，640×480 / MJPEG 输入 / NV21 回调。
- 120 FPS 不支持模式被明确拒绝，随后两路正常配置可再次打开。
- 第四轮同时预览和回调，两路 TextureView 各收到 231 次更新。
- 各轮 Buffer 长度正确，8 次 GRAY8 复制符合宽高契约。
- 8 次设备亮度能力及当前值读取成功。
- 多轮关闭后 USB、eventfd、eventpoll、socket、pipe 数量保持稳定。
- 管理器最终关闭后 USB 文件描述符为 0，采集/USB/命令工作线程为 0。

另已验证示例主界面的打开/停止操作，两路实时帧计数均持续增长。

本轮观测帧率约为 IR 28.9–29.2 FPS、RGB 29.9–30.0 FPS；这是该设备和场景的短时观测，
不表示性能对比、硬件同步或所有相机的帧率保证。
进程总 FD 中图形缓冲缓存会增长，因此验收分别记录各类 FD，不以总数替代 USB/事件资源检查。

本次采集库源码指纹（由 `tools/run_device_checks.py` 计算）：
`3f61d8efe6753bcd96bb562f036126a5aa4be8bafc0b62febb4de10bc94f39ec`。
已验收 deviceTest APK SHA-256：
`98d52dce52bb2ffa49b465859321554b8bfaabfa615dbb03e0f7f3a556831587`。
原始结构化报告、构建日志和失败尝试留在本地，未随代码上传；未保存摄像头图像。

## 未执行

物理拔插、长时间稳定性、硬件同步、16 KB 页大小真机，以及其他 Android 版本/ABI 的设备运行。
这些项目不记为通过；构建、ELF 静态检查和本机短测不能替代它们。
