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
static bool surfaceCopy;
static int postStatus;
static std::atomic<int> windowReferences{0};
static uint8_t surfacePixels[16];
static uvc_frame_callback_t *frameCallback;
static void *frameCallbackUser;
static bool blockSurface, surfaceEntered, releaseSurface;
static std::atomic<bool> surfaceChanged{false};
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
extern "C" uvc_error_t uvc_start_streaming(uvc_device_handle_t*, uvc_stream_ctrl_t*, uvc_frame_callback_t *callback, void *user, uint8_t) {
    frameCallback = callback; frameCallbackUser = user;
    ++starts; return static_cast<uvc_error_t>(streamStatus);
}
extern "C" void uvc_stop_streaming(uvc_device_handle_t*) { ++stops; }
extern "C" int32_t ANativeWindow_setBuffersGeometry(ANativeWindow*, int32_t, int32_t, int32_t) { return 0; }
extern "C" void ANativeWindow_acquire(ANativeWindow*) { ++windowReferences; }
extern "C" void ANativeWindow_release(ANativeWindow*) { assert(--windowReferences >= 0); }
extern "C" int32_t ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer *buffer, ARect*) {
    if (!surfaceCopy) return -1;
    pthread_mutex_lock(&gate);
    if (blockSurface) {
        surfaceEntered = true; pthread_cond_broadcast(&changed);
        while (!releaseSurface) pthread_cond_wait(&changed, &gate);
    }
    pthread_mutex_unlock(&gate);
    *buffer = {};
    buffer->width = buffer->height = buffer->stride = 2;
    buffer->bits = surfacePixels;
    return 0;
}
extern "C" int32_t ANativeWindow_unlockAndPost(ANativeWindow*) { return postStatus; }
extern int copyToSurface(uvc_frame_t *, ANativeWindow **);
static void surface(UVCPreview &p) {
    ++windowReferences; // Models the owned reference returned by ANativeWindow_fromSurface.
    p.setPreviewDisplay(reinterpret_cast<ANativeWindow*>(1));
}
static void *startBlocked(void *ptr) {
    assert(static_cast<UVCPreview*>(ptr)->startPreview() == 0);
    returned.store(true); return nullptr;
}
static void *changeSurface(void *ptr) {
    surface(*static_cast<UVCPreview*>(ptr));
    surfaceChanged.store(true);
    return nullptr;
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
    uint8_t pixels[16] = {};
    uvc_frame_t frame = {};
    frame.data = pixels; frame.width = frame.height = 2; frame.step = 8;
    ANativeWindow *window = reinterpret_cast<ANativeWindow*>(1);
    surfaceCopy = true; postStatus = -ENODEV;
    assert(copyToSurface(&frame, &window) == -ENODEV); ++cases;
    postStatus = 0;
    assert(copyToSurface(&frame, &window) == 0); ++cases;
    surfaceCopy = false;
    assert(copyToSurface(&frame, &window) != 0); ++cases;
    assert(preview.setPreviewSize(2, 2, UVC_VS_FRAME_UNCOMPRESSED, 30) == 0);
    surface(preview); surfaceCopy = true; blockSurface = true;
    assert(preview.startPreview() == 0);
    uint8_t yuyv[8] = {};
    frame.data = yuyv; frame.data_bytes = sizeof(yuyv); frame.step = 4;
    frame.frame_format = UVC_FRAME_FORMAT_YUYV;
    frameCallback(&frame, frameCallbackUser);
    pthread_mutex_lock(&gate);
    while (!surfaceEntered) pthread_cond_wait(&changed, &gate);
    pthread_mutex_unlock(&gate);
    pthread_t changer;
    assert(!__real_pthread_create(&changer, nullptr, changeSurface, &preview));
    for (int i = 0; i < 100 && !surfaceChanged.load(); ++i) usleep(1000);
    bool changedDuringRender = surfaceChanged.load();
    pthread_mutex_lock(&gate);
    releaseSurface = true; pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&gate);
    pthread_join(changer, nullptr);
    preview.stopPreview();
    assert(changedDuringRender); ++cases;
    assert(windowReferences.load() == 0);
    alarm(0);
    printf("{\"startup_cases\":%d,\"failed\":0,\"usb_simulated\":true}\n", cases);
}
