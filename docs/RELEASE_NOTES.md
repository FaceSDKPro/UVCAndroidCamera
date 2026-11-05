# UVCAndroidCamera 0.3.2

SDK 坐标：`com.facesdkpro:uvc-android-camera:0.3.2`。

## 修复与优化

- 相机启动等待模式协商、USB 视频传输及初始线程创建完成后才报告成功；真实启动错误保留原生错误码，失败时回收线程和资源。
- 关闭设备前取消并排空 USB 状态中断传输，禁止关闭中的回调再次提交，修复句柄关闭时仍存在活跃状态传输的问题。
- 双目会话复用已有输入时间戳，复制完成后复用已有锁判断关闭完成，减少逐帧重复原子写入和额外锁获取。保留 2 Hz 健康检查、5 秒超时、帧时效/顺序约束和可靠排空，不新增线程或高频轮询。
- 保持 RGB NV21 / IR GRAY8、每路双缓冲、最新帧优先、独立旋转和原有公开 API；新增低层 `startPreviewWithStatus()` 返回启动状态。

性能观测没有证明明确 CPU 或整机功耗下降，本版不以降低出帧量换取性能数据。详细范围见[性能说明](PERFORMANCE.zh-CN.md)。

## 下载与接入

- `uvc-android-camera-0.3.2.zip`：完整 SDK，包含四 ABI AAR、本地 Maven 仓库、源码、开发 Demo、独立示例工程、文档及校验文件。
- `uvc-android-camera-0.3.2.aar`：独立 Android 库。
- `uvc-android-camera-0.3.2-sources.jar`：Java 源码。
- `UvcCameraDemo-0.3.2.apk`：采用标准 Android Debug 签名的开发演示，`versionCode=7`；正式应用使用自己的签名。
- `SHA256SUMS`：上述下载文件的 SHA-256 校验值。

支持 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`；SDK 最低 API 21，Demo 最低 API 23。原生 ELF 与 APK ZIP 均按 16 KB 对齐。库仅声明 CAMERA 权限，使用 Android USB Host。

此版本通过 GitHub Release 手动发布，未发布 Maven Central。使用 ZIP 内 `maven-repository` 作为本地仓库，或直接依赖 AAR，具体步骤见[SDK 接入说明](SDK_INTEGRATION.zh-CN.md)。

## 验证范围

发布构建执行 27 项 Java 单元测试、14 项 Python 检查、库与 Demo Lint、四 ABI 产物及独立 AAR 消费检查。相同库字节已完成 143 项真机检查：59 项底层采集与 84 项双目会话，包括预览、17 轮开关、慢/失败消费者和资源排空；正常采集约 29–30 FPS，配对约 14.9 对/秒。

原始启动 `-1` 的具体内核诱因没有再次稳定复现，不认定为已证实的硬件故障。未追加真实物理拔插、长稳、固定曝光功耗或 16 KB 页设备运行。完整记录见[验证记录](VALIDATION.md)。CI/CD 继续暂停，本次手动发布不恢复自动触发或部署。

## English

Version 0.3.2 fixes native stream startup reporting and drains USB status transfers before closing their handle. It removes duplicate per-frame timestamp writes and an extra pairing-lock acquisition while retaining bounded buffers, freshness checks, low-frequency health monitoring and safe shutdown.

The complete ZIP includes all four ABIs, a local Maven repository, sources, documentation and a standalone developer Demo. SDK minimum API is 21; Demo minimum API is 23. The Demo uses the standard Android debug signing identity. Maven Central publication is not available.

The release build runs 27 Java unit tests, 14 Python checks, Lint and artifact/standalone-consumer audits. The same library bytes have passed 143 device checks. CPU or electrical-power savings are not established. CI/CD remains paused; this release is published manually.
