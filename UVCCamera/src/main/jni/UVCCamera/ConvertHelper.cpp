// Modified by FaceSDKPro, 2026-11-04; see UPSTREAM.md for changes.
#include "ConvertHelper.h"
#include "YuyvToNv.h"
#include "FrameTiming.h"
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

MjpegDecoder::~MjpegDecoder() {
    if (handle) tjDestroy(handle);
}

int gray8ToPreview(const uvc_frame_t *in, uint8_t *destination, int stride) {
    if (!in || !in->data || !destination || in->frame_format != UVC_FRAME_FORMAT_GRAY8
            || !in->width || !in->height || in->width > 4096 || in->height > 4096
            || in->step < in->width || stride < int(in->width * 4)
            || in->data_bytes < (in->height - 1) * in->step + in->width) return UVC_ERROR_INVALID_PARAM;
    // A byte expansion, not a color conversion. Keep the native dependency/export set unchanged.
    for (unsigned y = 0; y < in->height; ++y) {
        const auto *source = static_cast<const uint8_t *>(in->data) + y * in->step;
        auto *target = destination + y * stride;
        unsigned x = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
        const uint8x16_t alpha = vdupq_n_u8(255);
        for (; x + 16 <= in->width; x += 16) {
            const uint8x16_t gray = vld1q_u8(source + x);
            const uint8x16x4_t rgba = {{gray, gray, gray, alpha}};
            vst4q_u8(target + x * 4, rgba);
        }
#endif
        for (; x < in->width; ++x) {
            target[x * 4] = target[x * 4 + 1] = target[x * 4 + 2] = source[x];
            target[x * 4 + 3] = 255;
        }
    }
    return UVC_SUCCESS;
}

int MjpegDecoder::decode(uvc_frame_t *in, uvc_frame_t *out, bool gray) {
    if (!in || !in->data || !in->data_bytes || in->frame_format != UVC_FRAME_FORMAT_MJPEG)
        return UVC_ERROR_INVALID_PARAM;
    if (!handle) handle = tjInitDecompress();
    if (!handle) return UVC_ERROR_NO_MEM;
    int width, height, subsamp, colorspace;
    if (tjDecompressHeader3(handle, static_cast<unsigned char *>(in->data), in->data_bytes,
                            &width, &height, &subsamp, &colorspace) < 0
        || width != in->width || height != in->height) {
        tjDestroy(handle); handle = nullptr;
        return UVC_ERROR_INVALID_PARAM;
    }
    const int bytes = gray ? 1 : 4;
    const int pitch = out->library_owns_data ? width * bytes : static_cast<int>(out->step);
    if (pitch < width * bytes) return UVC_ERROR_INVALID_PARAM;
    if (uvc_ensure_frame_size(out, static_cast<size_t>(pitch) * height) < 0)
        return UVC_ERROR_NO_MEM;
    if (tjDecompress2(handle, static_cast<unsigned char *>(in->data), in->data_bytes,
                      static_cast<unsigned char *>(out->data), width, pitch, height,
                      gray ? TJPF_GRAY : TJPF_RGBX, 0) < 0) {
        // A malformed camera frame must not poison subsequent frames on the reused instance.
        tjDestroy(handle); handle = nullptr;
        return UVC_ERROR_INVALID_PARAM;
    }
    out->width = width; out->height = height;
    out->frame_format = gray ? UVC_FRAME_FORMAT_GRAY8 : UVC_FRAME_FORMAT_RGBX;
    out->step = pitch;
    copyFrameTiming(in, out);
    return UVC_SUCCESS;
}

int uvc_yuyv_to_nv(uvc_frame_t *in, uvc_frame_t *out, bool nv21) {
    if (in->frame_format != UVC_FRAME_FORMAT_YUYV) return UVC_ERROR_INVALID_PARAM;
    const size_t bytes = static_cast<size_t>(in->width) * in->height * 3 / 2;
    if (uvc_ensure_frame_size(out, bytes) < 0) return UVC_ERROR_NO_MEM;
    if (!yuyvToNv(static_cast<const uint8_t *>(in->data), in->data_bytes, in->step,
                  static_cast<uint8_t *>(out->data), out->data_bytes, in->width, in->height, nv21))
        return UVC_ERROR_INVALID_PARAM;
    out->width = in->width; out->height = in->height; out->step = in->width;
    out->frame_format = nv21 ? UVC_FRAME_FORMAT_NV21 : UVC_FRAME_FORMAT_NV12;
    copyFrameTiming(in, out);
    return UVC_SUCCESS;
}

int uvc_rgbx_to_gray8(uvc_frame_t *in, uvc_frame_t *out) {
    if (in->frame_format != UVC_FRAME_FORMAT_RGBX) return UVC_ERROR_INVALID_PARAM;
    if (uvc_ensure_frame_size(out, static_cast<size_t>(in->width) * in->height) < 0)
        return UVC_ERROR_NO_MEM;
    out->width = in->width; out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_GRAY8; out->step = in->width;
    copyFrameTiming(in, out);
    return libyuv::ABGRToJ400(static_cast<uint8_t *>(in->data), in->step,
                              static_cast<uint8_t *>(out->data), out->step, out->width, out->height);
}

/** @brief Convert a frame from RGBX8888 to YUYV
* @ingroup frame
* @param ini RGBX8888 frame
* @param out YUYV frame
*/
int uvc_rgbx_to_yuyv(uvc_frame_t *in, uvc_frame_t *out) {
    uint8_t *in_data = (uint8_t *) in->data;

    if (in->frame_format != UVC_FRAME_FORMAT_RGBX)
        return UVC_ERROR_INVALID_PARAM;

    if (uvc_ensure_frame_size(out, in->width * in->height * PIXEL_YUYV) < 0)
        return UVC_ERROR_NO_MEM;

    out->width = in->width;
    out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_YUYV;
    if (out->library_owns_data)
        out->step = in->width * PIXEL_YUYV;
    copyFrameTiming(in, out);

    uint8_t *out_data = (uint8_t *) out->data;
    int ret = UVC_SUCCESS;

    ret = libyuv::ABGRToYUY2(in_data, in->step,
                             out_data, out->step, out->width, out->height);

    return  ret;
}

/** @brief Convert a frame from RGBX8888 to NV12
* @ingroup frame
* @param ini RGBX8888 frame
* @param out NV12 frame
*/
 int uvc_rgbx_to_nv12(uvc_frame_t *in, uvc_frame_t *out) {
    uint8_t *in_data = (uint8_t *) in->data;

    if (in->frame_format != UVC_FRAME_FORMAT_RGBX)
        return UVC_ERROR_INVALID_PARAM;

    if (uvc_ensure_frame_size(out, (in->width * in->height * 3) / 2) < 0)
        return UVC_ERROR_NO_MEM;

    out->width = in->width;
    out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_NV12;
    if (out->library_owns_data)
        out->step = in->width;
    copyFrameTiming(in, out);

    uint8_t *out_data = (uint8_t *) out->data;
    int ret = UVC_SUCCESS;

    ret = libyuv::ABGRToNV12(in_data, in->step, out_data, out->step,
                             out_data + out->width * out->height,
                             out->step, out->width, out->height);

    return  ret;
}

/** @brief Convert a frame from RGBX8888 to NV21
* @ingroup frame
* @param ini RGBX8888 frame
* @param out NV21 frame
*/
 int uvc_rgbx_to_nv21(uvc_frame_t *in, uvc_frame_t *out) {
    uint8_t *in_data = (uint8_t *) in->data;

    if (in->frame_format != UVC_FRAME_FORMAT_RGBX)
        return UVC_ERROR_INVALID_PARAM;

    if (uvc_ensure_frame_size(out, (in->width * in->height * 3) / 2) < 0)
        return UVC_ERROR_NO_MEM;

    out->width = in->width;
    out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_NV21;
    if (out->library_owns_data)
        out->step = in->width;
    copyFrameTiming(in, out);

    uint8_t *out_data = (uint8_t *) out->data;
    int ret = UVC_SUCCESS;

    ret = libyuv::ABGRToNV21(in_data, in->step, out_data, out->step,
                             out_data + out->width * out->height,
                             out->step, out->width, out->height);

    return  ret;
}

/** @brief Convert a frame from RGBX8888 to RGB
* @ingroup frame
* @param ini RGBX8888 frame
* @param out RGB frame
*/
 int uvc_rgbx_to_rgb(uvc_frame_t *in, uvc_frame_t *out) {
    uint8_t *in_data = (uint8_t *) in->data;

    if (in->frame_format != UVC_FRAME_FORMAT_RGBX)
        return UVC_ERROR_INVALID_PARAM;

    if (uvc_ensure_frame_size(out, in->width * in->height * PIXEL_RGB) < 0)
        return UVC_ERROR_NO_MEM;

    out->width = in->width;
    out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_RGB;
    if (out->library_owns_data)
        out->step = in->width * PIXEL_RGB;
    copyFrameTiming(in, out);

    uint8_t *out_data = (uint8_t *) out->data;
    int ret = UVC_SUCCESS;

    ret = libyuv::ABGRToRAW(in_data, in->step, out_data, out->step,
                            out->width, out->height);

    return  ret;
}

/** @brief Convert a frame from RGBX8888 to RGB565
* @ingroup frame
* @param ini RGBX8888 frame
* @param out RGB565 frame
*/
 int uvc_rgbx_to_rgb565(uvc_frame_t *in, uvc_frame_t *out) {
    uint8_t *in_data = (uint8_t *) in->data;

    if (in->frame_format != UVC_FRAME_FORMAT_RGBX)
        return UVC_ERROR_INVALID_PARAM;

    if (uvc_ensure_frame_size(out, in->width * in->height * PIXEL_RGB565) < 0)
        return UVC_ERROR_NO_MEM;

    out->width = in->width;
    out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_RGB565;
    if (out->library_owns_data)
        out->step = in->width * PIXEL_RGB565;
    copyFrameTiming(in, out);

    uint8_t *out_data = (uint8_t *) out->data;
    int ret = UVC_SUCCESS;

    ret = libyuv::ABGRToRGB565(in_data, in->step, out_data, out->step,
                               out->width, out->height);

    return  ret;
}

/** @brief Convert a frame from RGBX8888 to BGR
* @ingroup frame
* @param ini RGBX8888 frame
* @param out BGR frame
*/
 int uvc_rgbx_to_bgr(uvc_frame_t *in, uvc_frame_t *out) {
    uint8_t *in_data = (uint8_t *) in->data;

    if (in->frame_format != UVC_FRAME_FORMAT_RGBX)
        return UVC_ERROR_INVALID_PARAM;

    if (uvc_ensure_frame_size(out, in->width * in->height * PIXEL_BGR) < 0)
        return UVC_ERROR_NO_MEM;

    out->width = in->width;
    out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_BGR;
    if (out->library_owns_data)
        out->step = in->width * PIXEL_BGR;
    copyFrameTiming(in, out);

    uint8_t *out_data = (uint8_t *) out->data;
    int ret = UVC_SUCCESS;

    ret = libyuv::ABGRToRGB24(in_data, in->step, out_data, out->step,
                              out->width, out->height);

    return  ret;
}
