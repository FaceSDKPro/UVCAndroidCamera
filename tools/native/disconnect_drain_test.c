// Copyright 2026 FaceSDKPro. Apache-2.0.
// Exercise production libusb disconnect traversal with callbacks that free transfers.
#include "libusbi.h"
#include <stdio.h>

static int completed, cleared;
static void clear_transfer(struct usbi_transfer *transfer) {
    assert(transfer->state_flags & USBI_TRANSFER_IN_FLIGHT);
    ++cleared;
}
const struct usbi_os_backend usbi_backend = { .clear_transfer_priv = clear_transfer };
void usbi_log(struct libusb_context *ctx, enum libusb_log_level level,
              const char *function, const char *format, ...) {}

int __wrap_usbi_handle_transfer_completion(struct usbi_transfer *transfer,
                                          enum libusb_transfer_status status) {
    assert(status == LIBUSB_TRANSFER_NO_DEVICE);
    assert(transfer->state_flags & USBI_TRANSFER_IN_FLIGHT);
    // The completion callback can remove and free the current transfer.
    list_del(&transfer->list);
    usbi_mutex_destroy(&transfer->lock);
    free(transfer);
    ++completed;
    return 0;
}

static struct usbi_transfer *add(struct libusb_context *ctx,
                                 struct libusb_device_handle *device, int in_flight) {
    struct usbi_transfer *transfer = calloc(1, sizeof(*transfer) + sizeof(struct libusb_transfer));
    assert(transfer);
    usbi_mutex_init(&transfer->lock);
    transfer->state_flags = in_flight ? USBI_TRANSFER_IN_FLIGHT : 0;
    USBI_TRANSFER_TO_LIBUSB_TRANSFER(transfer)->dev_handle = device;
    list_add_tail(&transfer->list, &ctx->flying_transfers);
    return transfer;
}

int main(void) {
    struct libusb_context ctx = {0};
    struct libusb_device detached_device = {0}, other_device = {0};
    struct libusb_device_handle detached = { .dev = &detached_device };
    struct libusb_device_handle other = { .dev = &other_device };
    detached_device.ctx = other_device.ctx = &ctx;
    list_init(&ctx.flying_transfers);
    usbi_mutex_init(&ctx.flying_transfers_lock);
    struct usbi_transfer *unsubmitted = add(&ctx, &detached, 0);
    add(&ctx, &detached, 1);
    struct usbi_transfer *unrelated = add(&ctx, &other, 1);
    add(&ctx, &detached, 1);
    add(&ctx, &detached, 1);
    usbi_handle_disconnect(&detached);
    assert(completed == 3 && cleared == 3);
    assert(unrelated->state_flags & USBI_TRANSFER_IN_FLIGHT);
    assert(!unsubmitted->state_flags);
    usbi_handle_disconnect(&detached);
    assert(completed == 3 && cleared == 3);
    list_del(&unsubmitted->list); list_del(&unrelated->list);
    usbi_mutex_destroy(&unsubmitted->lock); usbi_mutex_destroy(&unrelated->lock);
    free(unsubmitted); free(unrelated);
    assert(list_empty(&ctx.flying_transfers));
    usbi_mutex_destroy(&ctx.flying_transfers_lock);
    puts("{\"disconnect_drain_cases\":4,\"failed\":0,\"usb_simulated\":true}");
    return 0;
}
