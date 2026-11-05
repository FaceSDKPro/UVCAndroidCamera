package com.facesdkpro.uvccamera;

import android.hardware.usb.UsbDevice;

/** Structured error produced only on failure, never allocated per frame. No automatic retry policy. */
public final class CameraFailure {
    public enum Code {
        INITIALIZATION, USB_PERMISSION_DENIED, USB_PERMISSION_TIMEOUT, OPEN_FAILED,
        STREAM_TIMEOUT, PAIR_TIMEOUT, FRAME_CONSUMER_FAILED, DEVICE_DETACHED,
        CAMERA_CLOSED, CONTROL_FAILED, RELEASE_FAILED
    }
    public final Code code;
    /** Null for a whole-session failure (for example pair timeout). */
    public final UsbDevice device;
    public final String message;
    public final Throwable cause;
    /** Exact native open/negotiation/stream startup status, or null for other failures. */
    public final Integer nativeStatus;

    CameraFailure(Code code, UsbDevice device, String message, Throwable cause, Integer nativeStatus) {
        this.code = code; this.device = device; this.message = message;
        this.cause = cause; this.nativeStatus = nativeStatus;
    }
}
