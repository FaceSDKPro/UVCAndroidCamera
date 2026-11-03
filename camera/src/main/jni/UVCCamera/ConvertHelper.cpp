// Modified by FaceSDKPro, 2026-11-03; see UPSTREAM.md for changes.
#include "ConvertHelper.h"

MjpegDecoder::~MjpegDecoder() {
    if (handle) tjDestroy(handle);
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
    if (uvc_ensure_frame_size(out, static_cast<size_t>(width) * height * bytes) < 0)
        return UVC_ERROR_NO_MEM;
    if (tjDecompress2(handle, static_cast<unsigned char *>(in->data), in->data_bytes,
                      static_cast<unsigned char *>(out->data), width, width * bytes, height,
                      gray ? TJPF_GRAY : TJPF_RGBX, 0) < 0) {
        // A malformed camera frame must not poison subsequent frames on the reused instance.
        tjDestroy(handle); handle = nullptr;
        return UVC_ERROR_INVALID_PARAM;
    }
    out->width = width; out->height = height;
    out->frame_format = gray ? UVC_FRAME_FORMAT_GRAY8 : UVC_FRAME_FORMAT_RGBX;
    out->step = width * bytes;
    out->sequence = in->sequence; out->capture_time = in->capture_time;
    out->capture_time_finished = in->capture_time_finished; out->source = in->source;
    return UVC_SUCCESS;
}

int uvc_rgbx_to_gray8(uvc_frame_t *in, uvc_frame_t *out) {
    if (in->frame_format != UVC_FRAME_FORMAT_RGBX) return UVC_ERROR_INVALID_PARAM;
    if (uvc_ensure_frame_size(out, static_cast<size_t>(in->width) * in->height) < 0)
        return UVC_ERROR_NO_MEM;
    out->width = in->width; out->height = in->height;
    out->frame_format = UVC_FRAME_FORMAT_GRAY8; out->step = in->width;
    out->sequence = in->sequence; out->capture_time = in->capture_time;
    out->capture_time_finished = in->capture_time_finished; out->source = in->source;
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
    out->sequence = in->sequence;
    out->capture_time = in->capture_time;
    out->source = in->source;

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
    out->sequence = in->sequence;
    out->capture_time = in->capture_time;
    out->source = in->source;

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
    out->sequence = in->sequence;
    out->capture_time = in->capture_time;
    out->source = in->source;

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
    out->sequence = in->sequence;
    out->capture_time = in->capture_time;
    out->source = in->source;

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
    out->sequence = in->sequence;
    out->capture_time = in->capture_time;
    out->source = in->source;

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
    out->sequence = in->sequence;
    out->capture_time = in->capture_time;
    out->source = in->source;

    uint8_t *out_data = (uint8_t *) out->data;
    int ret = UVC_SUCCESS;

    ret = libyuv::ABGRToRGB24(in_data, in->step, out_data, out->step,
                              out->width, out->height);

    return  ret;
}