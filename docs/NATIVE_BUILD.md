# Native build / 原生构建

## 中文

与 HiFace Android 共用 NDK `29.0.14206865`、JDK 17、Gradle 8.14.3 和 AGP 8.13.2。
NDK 版本及可选 ABI 集中在根 `build.gradle`，UVCCamera 与 sample 读取同一配置。

- C++17；C/C++ 使用 `-O3 -flto=thin -fstack-protector-strong`，按函数/数据分段，链接时回收不可达代码。
- 保留 JPEG/libyuv SIMD；五份 version script 保持已审查的 133 个跨库导出入口。
- 所有共享库显式设置 `max-page-size=16384`、`common-page-size=16384`、RELRO/NOW 和不可执行栈。
- HiFace 应用最低 API 23；此独立库保留 API 21，sample 为 API 23。默认保留四 ABI，`-PuvcAbis=arm64-v8a,armeabi-v7a` 可选择与 HiFace 相同的 ARM 双 ABI。
- 该库只使用基础 new/delete 与 placement new，不使用 STL 容器、异常或 RTTI；继续使用轻量 `APP_STL=system`，显式 `-fno-sized-deallocation`，避免 C++17 生成系统运行时没有的 sized delete。跨库只使用 C 接口。

HiFace 的 SDK 使用 `c++_static`。本库同配置实验的四 ABI so 合计为 **2,583,240 字节**；轻量运行时为 **1,844,380 字节**，因此没有引入完整静态 libc++ 或额外的 libc++_shared.so。
[NDK 官方说明](https://developer.android.com/ndk/guides/cpp-support)将 system 运行时标为已弃用，未来 NDK 升级或引入 STL/异常/RTTI 时必须重新选择运行时、检查跨库边界与体积，不能只改版本号。

`tools/native_size_budget.json` 固定逐 ABI/逐库上限，原生总字节上限为 1,900,000。
增加导出或依赖、调整预算前需检查实际增长原因；不通过删 ABI 冒充同配置体积下降。
本次四 ABI 相比 NDK 27 版本减少 24,776 字节（1.33%）；不同 ABI/库的变化并不相同，详见下表。
AAR 压缩字节由 867,986 变为 879,206，虽然原生总量下降，压缩包仍略增；不将两者混用。

## English

The project shares HiFace Android's NDK `29.0.14206865`, JDK 17, Gradle 8.14.3 and AGP 8.13.2.
The root `build.gradle` owns the NDK pin and optional ABI selection for both modules.
C++17, `-O3`, ThinLTO, strong stack protection, section garbage collection and RELRO/NOW/NX are enabled.
Codec SIMD and the reviewed 133-symbol native boundary remain intact. Every shared library explicitly requests 16 KB maximum/common page alignment.

The standalone library retains API 21 and four ABIs; the sample requires API 23.
`-PuvcAbis=arm64-v8a,armeabi-v7a` selects the same ARM ABI set as HiFace without changing the default compatibility range.

Unlike HiFace's `c++_static`, UVC retains the minimal `system` runtime for basic new/delete and placement new.
It uses no STL containers, exceptions or RTTI, passes only C interfaces between libraries, and disables sized deallocation for this runtime.
The static libc++ experiment produced **2,583,240 native bytes**, compared with **1,844,380 bytes** using the minimal runtime.
[The NDK documentation](https://developer.android.com/ndk/guides/cpp-support) deprecates `system`; a future toolchain update or use of STL/exceptions/RTTI requires a reviewed runtime migration and fresh size/boundary checks.

`tools/native_size_budget.json` enforces per-ABI/library limits and a 1,900,000-byte total native ceiling.
Review actual growth before changing budgets. Native bytes decreased by 24,776 (1.33%) from the previous NDK 27 configuration; AAR ZIP bytes increased slightly from 867,986 to 879,206. Neither result establishes a CPU or electrical-power improvement.

## Size / 体积

Stripped ELF bytes from the same four-ABI source boundary / 相同四 ABI 源码边界的已剥离 ELF 字节：

| ABI | NDK 27 | NDK 29 + ThinLTO |
|---|---:|---:|
| arm64-v8a | 446,720 | 429,168 |
| armeabi-v7a | 406,868 | 411,076 |
| x86 | 502,128 | 498,056 |
| x86_64 | 513,440 | 506,080 |
| Total / 合计 | 1,869,156 | 1,844,380 |

The two-ARM AAR is 476,986 bytes, containing 840,244 native bytes; each ARM library is byte-identical to its four-ABI counterpart.
ARM 双 ABI AAR 为 476,986 字节，内含 840,244 字节原生库；每个 ARM 库与四 ABI AAR 内对应库字节相同。

## Alignment checks / 对齐检查

[Android's 16 KB guide](https://developer.android.com/guide/practices/page-sizes) requires checking ELF segments and APK packaging separately.
`tools/check_artifact.py` validates all 20 ELF libraries, permissions, exports, dependencies, size and memory protection.
With `--apk`, it also requires matching uncompressed native libraries with ZIP data offsets divisible by 16,384.

```sh
python3 tools/check_artifact.py UVCCamera/build/outputs/aar/UVCCamera-release.aar --apk sample/build/outputs/apk/release/sample-release.apk
"$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign" -c -P 16 4 sample/build/outputs/apk/release/sample-release.apk
```

本地检查同时使用 NDK llvm-readelf/llvm-nm 交叉核对 ELF，并使用官方 zipalign 核对 APK。
错误的 ELF 对齐、APK ZIP 对齐和超体积候选均验证会被拒绝。
已连接平板为 4 KB 页；16 KB 真机运行仍未执行，静态对齐通过不替代运行验收。
Local checks also cross-check ELF metadata with NDK llvm-readelf/llvm-nm and APK packaging with zipalign.
Misaligned ELF/APK and oversized candidates are rejected. The connected tablet uses 4 KB pages; runtime validation on a 16 KB device remains outstanding.

CI/CD remains paused; all checks in this change run locally. / CI/CD 继续暂停，本次检查均在本地执行。

## Subsequent capture-path changes / 后续采集路径变化

The NDK 29 size table above records commit `2159e68`. Direct YUYV conversion, direct MJPEG preview decoding and headless routing subsequently change the four-ABI payload to **1,869,372 bytes** (AAR **892,396 bytes**), and the two-ARM native payload to **856,080 bytes**. The same 1,900,000-byte ceiling, per-library budgets, toolchain and alignment/hardening flags apply. See [performance details](PERFORMANCE.md) and [validation](VALIDATION.md).

上方 NDK 29 表格对应 `2159e68`。后续直转/预览/队列优化的四 ABI 原生字节为 **1,869,372**，AAR **892,396**，ARM 双 ABI 原生字节为 **856,080**。沿用原体积门禁、工具链、对齐与安全参数，增长原因及实测结果见性能说明，不将字节变化换算成电功耗。

## Global review candidate / 全局复核候选

The subsequent lifecycle/buffer/error-path fixes produce **1,888,028 native bytes across four ABIs**, **866,444 across the two ARM ABIs**, and a **902,427-byte four-ABI AAR**. Budgets, the 133-symbol boundary per ABI, NDK 29 and 16 KB alignment/hardening remain unchanged. The `c0d095c` CPU comparison remains a historical measurement of that version; this round does not claim a new CPU/power result.

后续生命周期、缓冲区和错误处理修复的四 ABI 原生字节为 **1,888,028**，ARM 双 ABI **866,444**，四 ABI AAR **902,427**。没有放宽体积或导出门禁，保留原 NDK 29 与 16 KB 对齐/安全参数；先前 CPU 对照对应 `c0d095c`，不作为本轮候选新测量结果。

## SDK 0.3.0 pixel rotation

NV21/NV12/GRAY8 rotation uses a small shared tiled kernel in `FrameRotation.h`, registered through JNI without adding public native exports.
The paired API writes into its existing destination pool; zero rotation keeps the original fast copy path. Preview uses the host TextureView transform.
The shared kernel is kept out of line with Clang's `minsize` attribute to avoid duplicate luma/chroma specializations; SIMD codec settings and the rest of the `-O3` / ThinLTO build remain unchanged.
Four-ABI native bytes are **1,893,020**, two-ARM-ABI bytes **868,816**: **4,992 bytes** above the preceding capture SDK.
All existing library/total budgets, 133 exported symbols per ABI, NDK 29 and 16 KB ELF/APK alignment gates remain unchanged.
This is not an electrical-power comparison.

0.3.0 使用小型分块原生内核旋转 NV21/NV12/GRAY8，直接写入原有配对池；0 度保留快速复制，预览由 TextureView 变换处理。
旋转内核单独使用 Clang `minsize` 并禁止内联，避免为 Y/UV 调用点重复展开；其余 `-O3`、ThinLTO 和编解码 SIMD 配置保持原样。
四 ABI 原生总量 **1,893,020 字节**、ARM 双 ABI **868,816 字节**，比上一采集 SDK 增加 **4,992 字节**。
未放宽任一体积或导出门禁，继续核对每 ABI 133 个导出入口、NDK 29 和 16 KB 对齐；不据此宣称整机节电比例。
