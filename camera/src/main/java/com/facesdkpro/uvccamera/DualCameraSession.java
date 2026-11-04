package com.facesdkpro.uvccamera;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One explicitly selected RGB/IR pair. Owns permissions, both cameras and a bounded pair worker.
 * Any camera failure/close ends the entire session. Create a new session to retry or reconnect.
 * State callbacks are main-thread; frame-pair callbacks are serial on UVC-pairs. No face algorithm.
 */
public final class DualCameraSession implements AutoCloseable {
    public enum Role { RGB, IR }
    public interface Listener {
        /** Both streams opened; pair delivery still depends on fresh frames within the skew limit. */
        default void onReady(DualCameraSession session) {}
        default void onError(DualCameraSession session, String message, Throwable cause) {}
        /** Both USB/native resources and an in-flight pair consumer have drained. Safe to release Surfaces. */
        default void onClosed(DualCameraSession session) {}
    }
    public interface FramePairListener { void onFramePair(FramePair pair); }
    private static final AtomicLong NEXT_ID = new AtomicLong();
    public final long sessionId = NEXT_ID.incrementAndGet();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong rgbArrival = new AtomicLong(), irArrival = new AtomicLong();
    private final UvcCameraManager manager;
    private final FramePairDispatcher dispatcher;
    private final UvcCameraManager.Camera rgb, ir;
    private boolean rgbOpened, irOpened, usbDrained, pairsDrained, closedDelivered;
    private long readyAt;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (closed.get()) return;
            long now = SystemClock.elapsedRealtimeNanos();
            if (now - Math.max(readyAt, rgbArrival.get()) > 5_000_000_000L
                    || now - Math.max(readyAt, irArrival.get()) > 5_000_000_000L) {
                fail("A camera stopped producing frames for 5 seconds", null);
            } else main.postDelayed(this, 1000);
        }
    };

    /** Enumerate without starting a monitor. Bind roles by VID/PID or explicit user selection. */
    public static List<UsbDevice> devices(Context context) {
        UsbManager usb = (UsbManager) context.getApplicationContext().getSystemService(Context.USB_SERVICE);
        List<UsbDevice> result = new ArrayList<>();
        if (usb != null) for (UsbDevice device : usb.getDeviceList().values())
            if (UvcCameraManager.isCamera(device)) result.add(device);
        Collections.sort(result, (a, b) -> a.getDeviceName().compareTo(b.getDeviceName()));
        return Collections.unmodifiableList(result);
    }

    /**
     * Call on the main thread after CAMERA permission. Devices must be distinct, independently
     * enumerated USB cameras. Surfaces may be null; the caller owns them until onClosed.
     */
    public DualCameraSession(Context context, UsbDevice rgbDevice, UsbDevice irDevice,
            DualCameraConfig config, Surface rgbPreview, Surface irPreview,
            Listener listener, FramePairListener frames) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Create on the main thread");
        if (context == null || config == null || listener == null || frames == null) throw new NullPointerException("session argument");
        if (!UvcCameraManager.isCamera(rgbDevice) || !UvcCameraManager.isCamera(irDevice)
                || rgbDevice.getDeviceName().equals(irDevice.getDeviceName()))
            throw new IllegalArgumentException("Select two distinct UVC devices for RGB and IR");
        if (rgbPreview != null && !rgbPreview.isValid() || irPreview != null && !irPreview.isValid())
            throw new IllegalArgumentException("Preview Surface is invalid");
        this.listener = listener;
        dispatcher = new FramePairDispatcher(sessionId, config, SystemClock::elapsedRealtimeNanos,
                pair -> { if (!closed.get()) frames.onFramePair(pair); },
                error -> fail("Frame-pair consumer failed", error),
                () -> main.post(() -> { pairsDrained = true; completeClose(); }));
        UvcCameraManager created;
        try {
            created = new UvcCameraManager(context, new UvcCameraManager.Listener() {
                @Override public void onOpened(UvcCameraManager.Camera camera) {
                    if (closed.get()) return;
                    if (camera.device.getDeviceName().equals(rgbDevice.getDeviceName())) rgbOpened = true;
                    if (camera.device.getDeviceName().equals(irDevice.getDeviceName())) irOpened = true;
                    if (rgbOpened && irOpened && readyAt == 0) {
                        readyAt = SystemClock.elapsedRealtimeNanos(); main.postDelayed(watchdog, 1000);
                        listener.onReady(DualCameraSession.this);
                    }
                }
                @Override public void onClosed(UvcCameraManager.Camera camera) {
                    fail(camera.failureMessage == null ? "A camera was disconnected or closed" : camera.failureMessage,
                            camera.failureCause);
                }
                @Override public void onDetached(UsbDevice device) {
                    if (device.getDeviceName().equals(rgbDevice.getDeviceName())
                            || device.getDeviceName().equals(irDevice.getDeviceName()))
                        fail("A selected camera was detached", null);
                }
                @Override public void onError(UsbDevice device, String message, Throwable cause) { fail(message, cause); }
                @Override public void onShutdown() { usbDrained = true; completeClose(); }
            });
        } catch (RuntimeException | Error error) { dispatcher.close(); throw error; }
        manager = created;
        try {
            rgb = manager.open(rgbDevice, config.rgb, rgbPreview, (camera, frame) -> {
                rgbArrival.set(frame.arrivalTimeNanos); dispatcher.offer(true, frame);
            });
            ir = manager.open(irDevice, config.ir, irPreview, (camera, frame) -> {
                irArrival.set(frame.arrivalTimeNanos); dispatcher.offer(false, frame);
            });
        } catch (RuntimeException | Error error) { close(); throw error; }
    }

    public boolean isClosed() { return closed.get(); }
    public long receivedFrames(Role role) { return camera(role).receivedFrames(); }
    public void withControls(Role role, UvcCameraManager.ControlAction action) { camera(role).withControls(action); }
    private UvcCameraManager.Camera camera(Role role) {
        if (role == null) throw new NullPointerException("role");
        return role == Role.RGB ? rgb : ir;
    }

    private void fail(String message, Throwable cause) {
        if (!closed.compareAndSet(false, true)) return;
        main.post(() -> listener.onError(this, message, cause));
        stopResources();
    }

    private void stopResources() {
        main.removeCallbacks(watchdog); dispatcher.close(); manager.close();
    }

    /** Immediate admission stop; asynchronous cleanup, including an active consumer. Never joins a callback. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) stopResources();
    }

    private void completeClose() {
        if (usbDrained && pairsDrained && !closedDelivered) {
            closedDelivered = true; listener.onClosed(this);
        }
    }
}
