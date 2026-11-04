# UVCAndroidCamera

English | [简体中文](docs/README.zh-CN.md)

An Android USB camera SDK and Demo trimmed from [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) for development of [HelloFace](https://github.com/FaceSDKPro/HelloFace)'s RGB + near-infrared (IR) dual-camera face recognition feature. Face recognition and liveness decisions are handled by the upper-layer algorithm SDK.

## Supported cameras

Designed for **two independent UVC devices (RGB and IR), two USB device identifiers and two independent video streams**. RGB/IR roles are assigned by VID/PID or user configuration.

Modules that combine or alternate RGB/IR images in a single stream are not supported; multiple video interfaces within one USB device are not yet supported. Software frame pairing does not imply hardware-synchronized exposure.

## Dual-camera preview

![Landscape RGB and IR dual-stream preview](docs/images/dual-preview-landscape.png)

Visible-light RGB is on the left and near-infrared (IR) on the right. Each stream supports independent resolution, frame rate, MJPEG/YUYV and 0°/90°/180°/270° rotation settings, subject to device capabilities.

## Features

- Dual previews, raw frame callbacks and paired RGB NV21 / IR GRAY8 delivery.
- USB permissions, hotplug, device controls and session lifecycle management.
- Bounded buffers that retain only the latest frames; failure of either stream ends both captures.

## Integration

The SDK supports Android 5.0+ (API 21); the Demo requires Android 6.0+ (API 23). Add the `UVCCamera` module to your project or use the built AAR:

```gradle
implementation project(':UVCCamera')
```

See [SDK integration](docs/SDK_INTEGRATION.md) for usage and build steps, and [build notes](docs/NATIVE_BUILD.md) for native configuration.
