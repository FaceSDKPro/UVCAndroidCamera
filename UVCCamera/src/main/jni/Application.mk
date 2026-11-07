# Modified by FaceSDKPro, 2026-11-04; see UPSTREAM.md for changes.
#/*
# * UVCCamera
# * library and sample to access to UVC web camera on non-rooted Android device
# * 
# * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
# * 
# * File name: Application.mk
# * 
# * Licensed under the Apache License, Version 2.0 (the "License");
# * you may not use this file except in compliance with the License.
# *  You may obtain a copy of the License at
# * 
# *     http://www.apache.org/licenses/LICENSE-2.0
# * 
# *  Unless required by applicable law or agreed to in writing, software
# *  distributed under the License is distributed on an "AS IS" BASIS,
# *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# *  See the License for the specific language governing permissions and
# *  limitations under the License.
# * 
# * All files in the folder are under this Apache License, Version 2.0.
# * Files in the jni/libjpeg, jni/libusb, jin/libuvc, jni/rapidjson folder may have a different license, see the respective files.
#*/

# Shared with HiFace: NDK r29, C++17, ThinLTO and release hardening.
# Keep API 21 for existing users; HiFace's API 23 app can consume this AAR.
APP_PLATFORM := android-21
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
# Only new/delete are used: avoid embedding a full libc++ in this standalone AAR.
# All cross-library interfaces are C; no STL objects/exceptions cross the boundary.
APP_STL := system
APP_OPTIM := release
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# Preserve codec SIMD and full optimization; reduce size at the reviewed ELF boundary.
uvc_source_root := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../../..)
APP_CFLAGS := -DLOG_NDEBUG -O3 -flto=thin -fstack-protector-strong \
    -ffunction-sections -fdata-sections -ffile-prefix-map=$(uvc_source_root)=/uvc-source
# The system runtime has only unsized delete (C++17 otherwise emits sized delete).
APP_CPPFLAGS := -std=c++17 -fno-sized-deallocation -fvisibility-inlines-hidden
APP_LDFLAGS := -flto=thin -Wl,--gc-sections \
    -Wl,-z,relro,-z,now,-z,noexecstack \
    -Wl,-z,max-page-size=16384,-z,common-page-size=16384
