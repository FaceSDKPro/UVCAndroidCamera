# Sample 正式包签名与图标

`sample` 的 Release APK 使用仓库内的 `sample/uvc-camera-release.jks` 签名，别名为 `uvc-camera`，仓库密码和密钥密码均为 `HelloFace`。在源码根目录执行：

```sh
./gradlew :sample:assembleRelease
```

产物为 `sample/build/outputs/apk/release/sample-release.apk`。证书有效期为 **2026-11-05 15:27:59 至 9999-12-30 15:27:59（北京时间）**。X.509 证书没有无限期表示；这里采用当前工具可验证的远期截止日期。证书 SHA-256 指纹：

```text
45:C5:F8:50:8A:6D:C8:32:1B:1A:9E:DE:4D:24:F6:7F:3C:FE:AF:AB:AF:B4:5D:22:B7:8A:A6:ED:CE:CE:15:1C
```

此密钥和密码按项目约定公开，可直接提交 GitHub。任何取得仓库的人都能用相同身份签名，因此仅适合这个公开签名的 sample；如果未来需要受控发布的应用，应使用另一个私有密钥。已发布 APK 的后续更新应保留同一签名证书。

双目摄像头图标原图位于 `docs/images/dual-camera-app-icon-source.png`。APK 使用 `sample/src/main/res/drawable-nodpi/ic_launcher_foreground.png` 作为 Android 8.0 及以上的自适应图标前景，并包含旧版系统所需的各密度圆形和方形图标。
