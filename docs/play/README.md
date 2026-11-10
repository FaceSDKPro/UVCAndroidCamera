# Google Play 上架准备

## 构建与签名

Play 和本地 APK 的应用 ID 均为 `com.helloface.uvccamera`。旧包名的下载 APK 可以与新版并存。新版若需覆盖安装，前后两次安装的签名须一致。首次在 Play Console 创建应用时核对包名注册状态。当前交付 `versionName` 为 0.6.5，`versionCode` 为 17，`targetSdk` 为 36。每次上传新版本前递增 Play 已使用的 `versionCode`。

本地审查构建：

```sh
./gradlew :sample:lintPlay :sample:bundlePlay :sample:assemblePlay
```

AAB 在 `sample/build/outputs/bundle/play/sample-play.aab`。没有配置上传密钥时，Gradle 生成未签名审查包。已签名的本机候选及公开上传证书归档到 `build/play-submission/`。Play 构建从以下环境变量读取仓库外的私有 RSA 上传密钥。

| 环境变量 | 用途 |
| --- | --- |
| `UVC_PLAY_UPLOAD_KEYSTORE` | 私有上传 keystore 的绝对路径 |
| `UVC_PLAY_UPLOAD_STORE_PASSWORD` | keystore 密码 |
| `UVC_PLAY_UPLOAD_KEY_ALIAS` | 上传密钥别名 |
| `UVC_PLAY_UPLOAD_KEY_PASSWORD` | 上传密钥密码 |

App 内提供离线隐私政策。Play 版也提供官网政策链接，可用 `uvcPlayPrivacyUrl` 覆盖地址。用本机生成的上传密钥重建时，在子 shell 中读取私有凭据：

```sh
(
  play_upload_dir="$HOME/.uvc-android-camera/play-upload"
  export UVC_PLAY_UPLOAD_KEYSTORE="$play_upload_dir/uvc-camera-upload.jks"
  export UVC_PLAY_UPLOAD_KEY_ALIAS=uvc-camera-upload
  export UVC_PLAY_UPLOAD_STORE_PASSWORD="$(cat "$play_upload_dir/keystore.password")"
  export UVC_PLAY_UPLOAD_KEY_PASSWORD="$UVC_PLAY_UPLOAD_STORE_PASSWORD"
  ./gradlew :sample:bundlePlay
)
jarsigner -verify -verbose -certs sample/build/outputs/bundle/play/sample-play.aab
bundletool validate --bundle=sample/build/outputs/bundle/play/sample-play.aab
bundletool dump config --bundle=sample/build/outputs/bundle/play/sample-play.aab
```

确认输出包含 `PAGE_ALIGNMENT_16K`。保留 R8 映射文件和原生符号 ZIP，供 Play Console 解码崩溃栈。首次上传时使用 Play App Signing 的 Google 生成应用签名密钥选项。上传密钥用于签 AAB，Google 的应用签名密钥用于签分发 APK。将本机私有目录纳入受保护的备份，后续更新继续使用同一上传密钥。

应用最低支持 Android 6（API 23）。Play 的可选安装程序检查要求 API 24，当前已为本应用关闭该检查，以保留 Android 6 支持和离线使用。Play App Signing 仍启用。

## 商店素材和隐私

- [中英文商店文案](STORE_LISTING.md)
- [商店图标 512×512 PNG](assets/icon-512.png)
- [英文宣传图 1024×500](assets/feature-en-US.jpg) · [简体中文宣传图 1024×500](assets/feature-zh-CN.jpg)
- [UVC 双目相机中英文隐私政策](https://www.helloface.dev/en/uvc-camera-privacy)已发布到 HelloFace 官网，无需登录。在 Play Console 填写该地址。[本地政策源文件](privacy.html)与官网页面同步维护。
- 真机截图在 `assets/screenshots/`，英文和简体中文各 4 张，展示 0.6.5 无外接摄像头时的真实界面，含首页、调参与横屏双路预览。浅色商店图标直接复用 App 前景图形；其余语言使用英文资源。`assets/manifest.json` 记录素材摘要。使用 `tools/prepare_play_assets.mjs` 和 sharp 可重建排版，原始截图在本机 `build/play-submission-0.6.5-v17/screenshots/raw/`。

## Play Console 声明草稿

- Data safety 按最终签名包核验。当前应用在本机内存处理画面，无账号、广告、统计或帧上传。最终分发包行为一致时，填写不收集、不共享用户数据。
- App 类型选择应用，类别选择工具，定价为免费，默认语言 English (United States)，添加简体中文。目标受众选 18 岁及以上，面向需要配置外接双目摄像头的用户。应用无广告、内购、账号或用户生成内容，按这些事实完成内容分级问卷。支持邮箱为 FaceAISDK.Service@gmail.com。
- 审核操作说明可填下方英文。选择有 USB Host 的 Android 手机或平板，并接入两个独立的 UVC 摄像头。无外接设备时，审核者仍可打开界面、查看设置及隐私政策。

> This utility requires two independently detected UVC cameras and an Android device with USB host support. Connect both cameras using a compatible hub if needed. Open the app, tap Refresh devices, assign the RGB and IR cameras, then tap Start preview. Allow camera and USB access when Android asks. Open Dual view to view each camera or adjust its settings. No account or login is required. Without connected cameras, the app shows the connection prompt and capture settings.

## 送审前检查

1. 英文、简体中文和其它系统语言分别启动，确认英文回退，并检查横竖屏、长文案及权限拒绝状态。
2. 安装 AAB 生成的分包，检查 USB Host 仍为必需，内置后摄和自动对焦为可选。验证授权、双路采集、拔出、重开和退后台释放，补做 16 KB 页设备上的运行验证。
3. 确认隐私网页可公开打开，商店截图与最终版本一致。如果配置了 App 内在线入口，核对其地址。
4. 核对开发者身份和包名注册。适用的新个人账号需至少 12 人连续加入封闭测试 14 天，再申请正式发布权限。

## 本地验证记录

2026-11-10 15:24（北京时间），**0.6.5（17）已上传并确认送审**，正式版设置为全部 178 个投放项、100% 推出，自管式发布关闭，审核通过后自动发布。中英文标题、说明、浅色 App 同款图标、两张宣传图和各 4 张真实截图共 12 项变更一并提交。Play 页面显示“正在审核中的更改”，送审确认时快速检查仍在进行，通过后自动进入后续审核；不能视为已审核通过。图标与两张宣传图按既有 AI 图形来源作资源声明，截图保留真实 App 界面。

发布源码为 `d827e281117a529ac64bb91bbd5f367f56a958f9`。AAB SHA-256：`bddff9e275b177697de8eae9da3dc48650417d59c02ac439984266447cb12bdc`；完整 SDK ZIP SHA-256：`cc796465d88955b96d8853c8d268682db8794eac3f8e388bf6bd4725cff3526e`。SDK 清单绑定干净源码快照，65 项校验和通过；AAR 为 955,695 字节，SDK ZIP 为 4,478,253 字节。Play 确认 R8 映射及原生符号均已附加，原设备支持范围没有减少。归档与送审凭据位于本机 `build/play-submission-0.6.5-v17/`，送审时的原始 SDK ZIP 归档在 `build/sdk-archive/uvc-android-camera-0.6.5-source-d827e28.zip`。本段送审记录随后补充，不改变发布产物绑定的源码快照。CI/CD 保持暂停。


2026-11-09，0.6.0（`versionCode=12`）Play AAB/APK 构建、28 项 SDK Java 单测和 Play Lint 通过，Lint 有 9 条已有警告、无错误。AAB 与原上传证书匹配，最终包仅请求 CAMERA 权限，含中英文及官网隐私入口，未包含诊断 Activity。四 ABI 共 20 个原生库的产物审计、16 KB ELF/ZIP 对齐及官方 `zipalign` 复核通过。保留 SDK 0.6.0 的既有真机验收记录，本次发布构建时没有已连接设备，未重跑双目采集或物理拔插。

同日，0.6.0（12）已上传并提交 Google Play 正式版审核，设置为向全部 178 个投放项（含“其他国家/地区”）100% 推出，审核通过后自动发布。构建源码为 `383d3b6`，AAB SHA-256 为 `b5a702dbf713e51623adfdfb39745b6a5346c979b4462e8d8682c68c3f246250`。签名包、映射和原生符号保存在本机 `build/play-submission-0.6.0-v12/`。

2026-11-06，Play AAB 构建通过，Play Lint 无错误，保留已有告警。已生成独立的本机 RSA 4096 上传密钥，AAB 签名与公开上传证书匹配。

`bundletool` 验证 AAB 可生成分包，配置为 `PAGE_ALIGNMENT_16K`。通用 APK 包含默认英文和简体中文应用名，20 个原生库与 AAR 一致并通过对齐检查。真机从 AAB 生成的本地测试 APK 验证英文、简体中文及法语系统的英文回退。实际双路 USB 采集和 16 KB 页设备运行待验证。

参考 [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)、[应用内容](https://support.google.com/googleplay/android-developer/answer/9859455)、[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)、[商店素材](https://support.google.com/googleplay/android-developer/answer/9866151)、[封闭测试](https://support.google.com/googleplay/android-developer/answer/14151465)和 [16 KB 兼容性](https://developer.android.com/guide/practices/page-sizes)。
