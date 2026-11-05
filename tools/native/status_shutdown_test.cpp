// Copyright 2026 FaceSDKPro. Apache-2.0.
// Production status callback/close with cancellation and callback-in-progress races.
#include "libuvc.h"
extern "C" {
#include "libuvc_internal.h"
void LIBUSB_CALL _uvc_status_callback(libusb_transfer*);
}
#include <assert.h>
#include <stdio.h>
#include <unistd.h>
#include <initializer_list>
static pthread_t callbackThread;
static bool threadStarted, processing, callbackBlocked, releaseCallback;
static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static int submissions, submitStatus, cancellations, releases, closes, cases;
static void *callback(void *p) { _uvc_status_callback(static_cast<libusb_transfer*>(p)); return nullptr; }
extern "C" int libusb_submit_transfer(libusb_transfer*) { ++submissions; return submitStatus; }
extern "C" int libusb_cancel_transfer(libusb_transfer *t) {
    ++cancellations;
    if (callbackBlocked) {
        pthread_mutex_lock(&gate); releaseCallback = true; pthread_cond_broadcast(&changed); pthread_mutex_unlock(&gate);
        return LIBUSB_ERROR_NOT_FOUND; // completion already entered its callback
    }
    t->status = LIBUSB_TRANSFER_CANCELLED;
    assert(!pthread_create(&callbackThread, nullptr, callback, t)); threadStarted = true;
    return 0;
}
extern "C" void __wrap_uvc_process_status_xfer(uvc_device_handle_t*, libusb_transfer*) {
    pthread_mutex_lock(&gate); processing = true; pthread_cond_broadcast(&changed);
    while (callbackBlocked && !releaseCallback) pthread_cond_wait(&changed, &gate);
    pthread_mutex_unlock(&gate);
}
extern "C" uvc_error_t __wrap_uvc_release_if(uvc_device_handle_t *d, int) {
    assert(!d->status_active && d->status_closing); ++releases; return UVC_SUCCESS;
}
extern "C" void libusb_close(libusb_device_handle*) {
    if (threadStarted) { pthread_join(callbackThread, nullptr); threadStarted = false; }
    ++closes;
}
extern "C" void __wrap_uvc_unref_device(uvc_device_t*) {}
extern "C" void uvc_stop_streaming(uvc_device_handle_t*) { assert(false); }
extern "C" void __wrap_uvc_free_devh(uvc_device_handle_t *d) { assert(!d->status_active); }
struct Fixture {
    uvc_device_handle_t handle = {}; uvc_device_t device = {}; uvc_context_t context = {};
    uvc_device_info_t info = {}; libusb_transfer transfer = {};
    Fixture() {
        handle.dev = &device; device.ctx = &context; context.open_devices = &handle;
        handle.prev = &handle; // DL_APPEND's single-item list invariant
        handle.info = &info; handle.status_xfer = &transfer; handle.status_active = 1;
        transfer.user_data = &handle;
        pthread_mutex_init(&handle.status_mutex, nullptr); pthread_cond_init(&handle.status_cond, nullptr);
        submissions = cancellations = releases = closes = 0; submitStatus = 0;
        processing = callbackBlocked = releaseCallback = false;
    }
    ~Fixture() { pthread_cond_destroy(&handle.status_cond); pthread_mutex_destroy(&handle.status_mutex); }
    void close() { uvc_close(&handle); assert(releases == 1 && closes == 1 && !context.open_devices); }
};
int main() {
    alarm(10);
    { Fixture f; f.close(); assert(cancellations == 1 && submissions == 0); ++cases; }
    { Fixture f; callbackBlocked = true; f.transfer.status = LIBUSB_TRANSFER_COMPLETED;
      assert(!pthread_create(&callbackThread, nullptr, callback, &f.transfer)); threadStarted = true;
      pthread_mutex_lock(&gate); while (!processing) pthread_cond_wait(&changed, &gate); pthread_mutex_unlock(&gate);
      f.close(); assert(cancellations == 1 && submissions == 0); ++cases; }
    for (auto status : {LIBUSB_TRANSFER_ERROR, LIBUSB_TRANSFER_CANCELLED, LIBUSB_TRANSFER_NO_DEVICE}) {
        Fixture f; f.transfer.status = status; _uvc_status_callback(&f.transfer);
        assert(!f.handle.status_active && !submissions); f.close(); assert(!cancellations); ++cases;
    }
    { Fixture f; f.transfer.status = LIBUSB_TRANSFER_COMPLETED; submitStatus = LIBUSB_ERROR_IO;
      _uvc_status_callback(&f.transfer); assert(!f.handle.status_active && submissions == 1);
      f.close(); assert(!cancellations); ++cases; }
    { Fixture f; f.transfer.status = LIBUSB_TRANSFER_COMPLETED; _uvc_status_callback(&f.transfer);
      assert(f.handle.status_active && submissions == 1); f.close(); assert(cancellations == 1); ++cases; }
    alarm(0);
    printf("{\"status_shutdown_cases\":%d,\"failed\":0,\"usb_simulated\":true}\n", cases);
}
