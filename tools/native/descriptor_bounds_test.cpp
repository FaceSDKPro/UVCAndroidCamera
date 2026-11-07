// Copyright 2026 FaceSDKPro. Apache-2.0.
#include "libuvc.h"
extern "C" {
#include "libuvc_internal.h"
uvc_error_t uvc_parse_vs(uvc_device_t *, uvc_device_info_t *, uvc_streaming_interface_t *,
                         const unsigned char *, size_t);
uvc_error_t uvc_parse_vc(uvc_device_t *, uvc_device_info_t *, const unsigned char *, size_t);
uvc_error_t uvc_scan_streaming(uvc_device_t *, uvc_device_info_t *, int);
}
#include <assert.h>

int main() {
    uvc_streaming_interface_t stream = {};
    uvc_format_desc_t format = {};
    uint8_t frame[30] = {30, 0x24, UVC_VS_FRAME_MJPEG};
    frame[25] = 1;
    const uint32_t interval = 333333;
    for (size_t i = 0; i < 4; ++i)
        frame[26 + i] = static_cast<uint8_t>(interval >> (8 * i));
    assert(uvc_parse_vs(nullptr, nullptr, &stream, frame, sizeof(frame)) == UVC_ERROR_INVALID_DEVICE);

    stream.format_descs = &format;
    format.prev = &format;
    assert(uvc_parse_vs(nullptr, nullptr, &stream, frame, 26) == UVC_ERROR_INVALID_DEVICE);
    frame[25] = 0;
    assert(uvc_parse_vs(nullptr, nullptr, &stream, frame, sizeof(frame)) == UVC_ERROR_INVALID_DEVICE);
    frame[25] = 1;
    assert(uvc_parse_vs(nullptr, nullptr, &stream, frame, sizeof(frame)) == UVC_SUCCESS);
    assert(format.frame_descs->intervals[0] == interval && format.frame_descs->intervals[1] == 0);
    free(format.frame_descs->intervals);
    free(format.frame_descs);

    const uint8_t shortFormat[] = {4, 0x24, UVC_VS_FORMAT_MJPEG, 1};
    const uint8_t shortStill[] = {6, 0x24, UVC_VS_STILL_IMAGE_FRAME, 0, 1, 0};
    assert(uvc_parse_vs(nullptr, nullptr, &stream, shortFormat, sizeof(shortFormat)) == UVC_ERROR_INVALID_DEVICE);
    assert(uvc_parse_vs(nullptr, nullptr, &stream, shortStill, sizeof(shortStill)) == UVC_ERROR_INVALID_DEVICE);

    libusb_config_descriptor config = {};
    uvc_device_info_t info = {};
    info.config = &config;
    const uint8_t shortExtension[23] = {23, 0x24, UVC_VC_EXTENSION_UNIT};
    uint8_t shortProcessing[] = {8, 0x24, UVC_VC_PROCESSING_UNIT, 1, 1, 0, 0, 1};
    assert(uvc_parse_vc(nullptr, &info, shortExtension, 22) == UVC_ERROR_INVALID_DEVICE);
    assert(uvc_parse_vc(nullptr, &info, shortProcessing, sizeof(shortProcessing)) == UVC_ERROR_INVALID_DEVICE);
    assert(uvc_scan_streaming(nullptr, &info, 1) == UVC_ERROR_INVALID_DEVICE);
    printf("{\"descriptor_bounds_cases\":9,\"failed\":0}\n");
}
