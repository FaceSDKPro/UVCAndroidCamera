// Copyright 2026 FaceSDKPro. Apache-2.0.
#pragma once
#include <stdint.h>
#include <limits.h>
#include "libuvc.h"

// Only UVCCamera-owned frame copies use capture_time_finished for host receipt.
// The libuvc ABI/layout is unchanged. Preserve it across EVERY decode/conversion.
inline void copyFrameTiming(const uvc_frame_t *source, uvc_frame_t *target) {
    target->sequence = source->sequence;
    target->capture_time = source->capture_time;
    target->capture_time_finished = source->capture_time_finished;
    target->source = source->source;
}

inline int64_t hostReceiptNanos(const uvc_frame_t *frame) {
    const auto &t = frame->capture_time_finished;
    if (t.tv_sec < 0 || t.tv_nsec < 0 || t.tv_nsec >= 1000000000
            || t.tv_sec > (INT64_MAX - t.tv_nsec) / 1000000000) return 0;
    return int64_t(t.tv_sec) * 1000000000 + t.tv_nsec;
}
