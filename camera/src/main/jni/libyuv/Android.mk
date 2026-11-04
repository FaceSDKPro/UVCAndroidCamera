# Modified by FaceSDKPro, 2026-11-04; see UPSTREAM.md.
# This is the Android makefile for libyuv for NDK.
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_CPP_EXTENSION := .cc

LOCAL_SRC_FILES := \
    source/convert_from_argb.cc \
    source/cpu_id.cc \
    source/planar_functions.cc \
    source/row_any.cc \
    source/row_common.cc \
    source/row_gcc.cc \
    source/row_msa.cc \
    source/row_neon.cc \
    source/row_neon64.cc \
    source/row_win.cc

LOCAL_CFLAGS += -Wall -fno-exceptions -fno-rtti
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_C_INCLUDES += $(LOCAL_PATH)/include
LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)/include

LOCAL_MODULE := libyuv_static
LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

LOCAL_WHOLE_STATIC_LIBRARIES := libyuv_static
LOCAL_MODULE := libyuv
LOCAL_LDFLAGS += -Wl,--version-script=$(LOCAL_PATH)/../exports/libyuv.so.map
include $(BUILD_SHARED_LIBRARY)
# Keep map edits as link dependencies for incremental ndk-build.
$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/../exports/libyuv.so.map
