// Copyright 2026 FaceSDKPro. Apache-2.0.
// Production preview worker: USB startup completion, failure rollback and re-open.
#include "utilbase.h"
#include "UVCPreview.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <atomic>

static JNIEnv env = {};
JavaVM *getVM() { return nullptr; }
JNIEnv *getEnv() { return &env; }
static int probeStatus, streamStatus, probes, starts, stops, cases;
static bool failThread, blockProbe, probeEntered, releaseProbe;
static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static std::atomic<bool> returned{false};
extern "C" int __real_pthread_create(pthread_t*, const pthread_attr_t*, void*(*)(void*), void*);
extern "C" int __wrap_pthread_create(pthread_t *t, const pthread_attr_t *a, void*(*fn)(void*), void *p) {
    if (failThread) { failThread = false; return EAGAIN; }
    return __real_pthread_create(t, a, fn, p);
}
extern "C" uvc_error_t uvc_get_stream_ctrl_format_size(uvc_device_handle_t*, uvc_stream_ctrl_t *ctrl, uvc_frame_format, int, int, int) {
    *ctrl = {};
    ++probes;
    pthread_mutex_lock(&gate);
    probeEntered = true; pthread_cond_broadcast(&changed);
    while (blockProbe && !releaseProbe) pthread_cond_wait(&changed, &gate);
    pthread_mutex_unlock(&gate);
    return static_cast<uvc_error_t>(probeStatus);
}
extern "C" uvc_frame_desc_t *uvc_find_frame_desc(uvc_device_handle_t*, uint16_t, uint16_t) { return nullptr; }
extern "C" uvc_error_t uvc_start_streaming(uvc_device_handle_t*, uvc_stream_ctrl_t*, uvc_frame_callback_t*, void*, uint8_t) {
    ++starts; return static_cast<uvc_error_t>(streamStatus);
}
extern "C" void uvc_stop_streaming(uvc_device_handle_t*) { ++stops; }
extern "C" int32_t ANativeWindow_setBuffersGeometry(ANativeWindow*, int32_t, int32_t, int32_t) { return 0; }
extern "C" void ANativeWindow_acquire(ANativeWindow*) {}
extern "C" void ANativeWindow_release(ANativeWindow*) {}
extern "C" int32_t ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer*, ARect*) { return -1; }
extern "C" int32_t ANativeWindow_unlockAndPost(ANativeWindow*) { return 0; }
static void surface(UVCPreview &p) { p.setPreviewDisplay(reinterpret_cast<ANativeWindow*>(1)); }
static void *startBlocked(void *ptr) {
    assert(static_cast<UVCPreview*>(ptr)->startPreview() == 0);
    returned.store(true); return nullptr;
}
int main() {
    alarm(10);
    UVCPreview preview(nullptr);
    assert(preview.startPreview() == UVC_ERROR_INVALID_PARAM); ++cases;
    surface(preview); probeStatus = UVC_ERROR_INVALID_MODE;
    assert(preview.startPreview() == probeStatus && starts == 0); ++cases;
    surface(preview); probeStatus = 0; streamStatus = UVC_ERROR_IO;
    assert(preview.startPreview() == streamStatus && stops == 0); ++cases;
    surface(preview); failThread = true; int before = probes;
    assert(preview.startPreview() == EAGAIN && probes == before); ++cases;
    surface(preview); streamStatus = 0; blockProbe = true; probeEntered = false;
    pthread_t caller; assert(!__real_pthread_create(&caller, nullptr, startBlocked, &preview));
    pthread_mutex_lock(&gate);
    while (!probeEntered) pthread_cond_wait(&changed, &gate);
    assert(!returned.load()); releaseProbe = true; pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&gate);
    pthread_join(caller, nullptr); assert(returned.load());
    assert(preview.startPreview() == UVC_ERROR_BUSY);
    preview.stopPreview(); assert(stops == 1); ++cases;
    blockProbe = false;
    for (int i = 0; i < 20; ++i) {
        surface(preview); assert(preview.startPreview() == 0);
        preview.stopPreview(); preview.stopPreview();
        assert(stops == i + 2); ++cases;
    }
    alarm(0);
    printf("{\"startup_cases\":%d,\"failed\":0,\"usb_simulated\":true}\n", cases);
}
