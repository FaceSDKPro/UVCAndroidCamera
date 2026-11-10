package com.facesdkpro.uvccamera;

import android.hardware.usb.UsbDevice;

/** Structured error produced only on failure, never allocated per frame. No automatic retry policy. */
public final class CameraFailure {
    public enum Code {
        INITIALIZATION, USB_PERMISSION_DENIED, USB_PERMISSION_TIMEOUT, OPEN_FAILED,
        STREAM_TIMEOUT, PAIR_TIMEOUT, FRAME_CONSUMER_FAILED, DEVICE_DETACHED,
        CAMERA_CLOSED, CONTROL_FAILED, RELEASE_FAILED, UNSUPPORTED_MODE, DEVICE_BUSY, OPEN_TIMEOUT
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

    static CameraFailure openFailed(UsbDevice device, String message, int status) {
        Code code;
        switch (status) {
            case -2: case -51: code = Code.UNSUPPORTED_MODE; break;
            case -3: code = Code.USB_PERMISSION_DENIED; break;
            case -4: code = Code.DEVICE_DETACHED; break;
            case -6: code = Code.DEVICE_BUSY; break;
            case -7: code = Code.OPEN_TIMEOUT; break;
            default: code = Code.OPEN_FAILED; break;
        }
        return new CameraFailure(code, device, message, new IllegalStateException(message), status);
    }
}
