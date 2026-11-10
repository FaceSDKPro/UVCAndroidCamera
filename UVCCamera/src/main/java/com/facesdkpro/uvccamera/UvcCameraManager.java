package com.facesdkpro.uvccamera;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCControl;
import com.serenegiant.usb.UVCParam;
import com.serenegiant.usb.ITimestampedFrameCallback;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One USB monitor and one command thread for multiple independent UVC USB devices.
 * State callbacks run on the main thread; frames run on each camera's native callback thread.
 * Call close when the owning screen stops. No global service, Activity reference, or encoder is used.
 */
public final class UvcCameraManager implements AutoCloseable {
    public interface Listener {
        default void onAttached(UsbDevice device) {}
        default void onDetached(UsbDevice device) {}
        default void onOpened(Camera camera) {}
        default void onClosed(Camera camera) {}
        /** Final callback, after USB/native cleanup. Delivered once even though ordinary callbacks are suppressed. */
        default void onShutdown() {}
        default void onError(UsbDevice device, String message, Throwable cause) {}
        /** Override this OR onError. Default dispatch preserves existing listeners without double delivery. */
        default void onFailure(CameraFailure failure) { onError(failure.device, failure.message, failure.cause); }
    }
    public interface FrameListener { void onFrame(Camera camera, CameraFrame frame); }
    public interface ControlAction { void run(UVCControl control); }
    interface ConfigurationListener { void onConfigured(StreamConfig actual); }

    private final Listener listener;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("UVC-commands");
    private final Handler worker;
    private final USBMonitor monitor;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Confined to worker.
    private final Map<String, Camera> cameras = new HashMap<>();
    private final Set<String> attached = new HashSet<>();
    private final ArrayDeque<Camera> permissions = new ArrayDeque<>();
    private Camera requesting;

    public UvcCameraManager(Context context, Listener listener) {
        this(context, listener, 5000);
    }

    /** Fallback device scan interval: 0 disables polling; broadcasts and the initial scan remain active. */
    public UvcCameraManager(Context context, Listener listener, long devicePollMillis) {
        if (devicePollMillis != 0 && devicePollMillis < 1000) throw new IllegalArgumentException("Device polling must be 0 or >= 1000 ms");
        if (context == null) throw new NullPointerException("context");
        if (listener == null) throw new NullPointerException("listener");
        this.listener = listener;
        Context app = context.getApplicationContext();
        usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (usb == null) throw new IllegalStateException("USB service unavailable");
        thread.start(); worker = new Handler(thread.getLooper());
        USBMonitor created;
        try {
        created = new USBMonitor(app, new USBMonitor.OnDeviceConnectListener() {
            public void onAttach(UsbDevice device) {
                if (!closed.get() && isCamera(device) && attached.add(device.getDeviceName())) {
                    emit(() -> listener.onAttached(device));
                }
            }
            public void onDetach(UsbDevice device) {
                attached.remove(device.getDeviceName());
                Camera camera = cameras.get(device.getDeviceName());
                if (camera != null) fail(camera, CameraFailure.Code.DEVICE_DETACHED, "Camera was detached", null);
                emit(() -> listener.onDetached(device));
            }
            public void onDeviceOpen(UsbDevice device, USBMonitor.UsbControlBlock control, boolean fresh) {
                Camera camera = requesting;
                if (camera == null || !camera.device.equals(device)) return;
                requesting = null;
                worker.removeCallbacksAndMessages(camera);
                if (!camera.closed.get() && !closed.get()) start(camera, control);
                pumpPermissions();
            }
            public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock control) {
                Camera camera = cameras.get(device.getDeviceName());
                if (camera != null) fail(camera, CameraFailure.Code.CAMERA_CLOSED, "USB camera was closed", null);
            }
            public void onCancel(UsbDevice device) { permissionFailed(device, CameraFailure.Code.USB_PERMISSION_DENIED, "USB access denied", null); }
            public void onError(UsbDevice device, USBMonitor.USBException error) {
                permissionFailed(device, CameraFailure.Code.OPEN_FAILED, "USB open failed", error);
            }
        }, worker, worker, devicePollMillis);
        } catch (RuntimeException | LinkageError error) {
            thread.quitSafely();
            throw error;
        }
        monitor = created;
        worker.post(() -> {
            try { if (!closed.get()) monitor.register(); }
            catch (RuntimeException error) {
                CameraFailure failure = new CameraFailure(CameraFailure.Code.INITIALIZATION, null,
                        "USB monitor initialization failed", error, null);
                main.post(() -> listener.onFailure(failure));
                close();
            }
        });
    }

    public static boolean isCamera(UsbDevice device) {
        return UvcDevices.isCamera(device);
    }

    /** Current UVC devices. Match role by VID/PID or user selection, never enumeration order. */
    public List<UsbDevice> devices() {
        return closed.get() ? Collections.emptyList() : UvcDevices.list(usb);
    }

    /** Surface may be null for analysis only. Invalid Surfaces fail before enqueueing; ownership stays with the caller. */
    public Camera open(UsbDevice device, StreamConfig config, Surface preview, FrameListener frames) {
        return open(device, config, preview, frames, null);
    }

    Camera open(UsbDevice device, StreamConfig config, Surface preview, FrameListener frames,
            ConfigurationListener configured) {
        if (closed.get()) throw new IllegalStateException("Manager closed");
        if (!isCamera(device)) throw new IllegalArgumentException("Device has no UVC interface");
        if (config == null) throw new NullPointerException("config");
        if (frames == null && preview == null) throw new IllegalArgumentException("A frame listener or preview is required");
        if (preview != null && !preview.isValid()) throw new IllegalArgumentException("Preview Surface is invalid");
        Camera camera = new Camera(device, config, preview, frames, configured);
        boolean accepted = worker.post(() -> {
            if (closed.get() || camera.closed.get()) { camera.closed.set(true); return; }
            if (cameras.containsKey(device.getDeviceName())) {
                fail(camera, CameraFailure.Code.OPEN_FAILED, "Device already has a session", null); return;
            }
            cameras.put(device.getDeviceName(), camera);
            permissions.add(camera); pumpPermissions();
        });
        if (!accepted) camera.closed.set(true);
        return camera;
    }

    private void pumpPermissions() {
        if (closed.get() || requesting != null) return;
        Camera next;
        while ((next = permissions.poll()) != null) {
            if (next.closed.get()) continue;
            requesting = next;
            final Camera pending = next;
            try { monitor.requestPermission(next.device); }
            catch (RuntimeException error) {
                fail(pending, CameraFailure.Code.USB_PERMISSION_DENIED, "USB permission request failed", error);
                return; // stop() advances the serialized permission queue.
            }
            worker.postAtTime(() -> {
                if (requesting == pending) {
                    requesting = null;
                    fail(pending, CameraFailure.Code.USB_PERMISSION_TIMEOUT, "USB permission request timed out", null);
                    pumpPermissions();
                }
            }, pending, SystemClock.uptimeMillis() + 30000);
            break;
        }
    }

    private void permissionFailed(UsbDevice device, CameraFailure.Code code, String message, Throwable cause) {
        Camera camera = requesting;
        if (camera == null || !camera.device.equals(device)) return;
        requesting = null; fail(camera, code, message, cause); pumpPermissions();
    }

    private void start(Camera session, USBMonitor.UsbControlBlock control) {
        try {
            if (session.preview != null && !session.preview.isValid()) throw new IllegalStateException("Preview Surface is invalid");
            StreamConfig requested = session.config;
            UVCCamera camera = new UVCCamera(new UVCParam(requested.size(),
                    requested.quirks | UVCCamera.getRecommendedPlatformQuirks()));
            session.nativeCamera = camera;
            int status = camera.open(control, supported -> {
                StreamConfig actual = requested.resolve(supported);
                if (actual == null) return null;
                session.actualConfig = actual;
                return actual.size();
            });
            if (status != 0) {
                String message = "UVC open failed for " + requested + ": " + status;
                fail(session, CameraFailure.openFailed(session.device, message, status));
                return;
            }
            StreamConfig config = session.actualConfig;
            if (session.configured != null) session.configured.onConfigured(config);
            if (session.preview != null) camera.setPreviewDisplay(session.preview);
            if (session.frames != null) camera.setFrameCallback(new ITimestampedFrameCallback() {
              @Override public void onFrame(ByteBuffer buffer, long receivedTime, long nativeSequence) {
                if (closed.get() || session.closed.get()) return;
                long sequence = session.frameCount.incrementAndGet();
                try {
                    session.frames.onFrame(session, new CameraFrame(buffer, config, 0, sequence,
                            SystemClock.elapsedRealtimeNanos(), receivedTime, nativeSequence));
                } catch (RuntimeException | LinkageError error) {
                    // Close on the command thread: stopping native capture inside its callback would deadlock.
                    if (session.closed.compareAndSet(false, true))
                        worker.post(() -> fail(session, CameraFailure.Code.FRAME_CONSUMER_FAILED, "Frame listener failed", error));
                }
              }
            }, config.pixelFormat);
            status = camera.startPreviewWithStatus();
            if (status != 0) {
                String message = "UVC stream startup failed: " + status;
                fail(session, CameraFailure.openFailed(session.device, message, status));
                return;
            }
            emit(() -> { if (!session.closed.get()) listener.onOpened(session); });
            worker.postAtTime(() -> {
                if (!session.closed.get() && session.receivedFrames() == 0)
                    fail(session, CameraFailure.Code.STREAM_TIMEOUT, "Camera produced no frames within 5 seconds", null);
            }, session, SystemClock.uptimeMillis() + 5000);
        } catch (Exception | LinkageError error) { fail(session, CameraFailure.Code.OPEN_FAILED, "Camera start failed", error); }
    }

    private void fail(Camera camera, CameraFailure.Code code, String message, Throwable cause) {
        fail(camera, new CameraFailure(code, camera.device, message, cause, null));
    }

    private void fail(Camera camera, CameraFailure failure) {
        // Publish the reason before onClosed: a pair owner must stop its peer immediately,
        // without losing the detailed error while that cleanup suppresses ordinary callbacks.
        camera.failure = failure;
        camera.failureCause = failure.cause; camera.failureMessage = failure.message;
        stop(camera); emit(() -> listener.onFailure(failure));
    }

    private void stop(Camera camera) {
        camera.closed.set(true);
        worker.removeCallbacksAndMessages(camera);
        boolean wasRequesting = requesting == camera;
        if (wasRequesting) requesting = null;
        permissions.remove(camera);
        if (cameras.get(camera.device.getDeviceName()) == camera) cameras.remove(camera.device.getDeviceName());
        camera.frameCount.set(camera.receivedFrames());
        UVCCamera nativeCamera = camera.nativeCamera; camera.nativeCamera = null;
        if (nativeCamera != null) {
            try { nativeCamera.destroy(true); }
            catch (RuntimeException error) { emit(() -> listener.onFailure(new CameraFailure(
                    CameraFailure.Code.RELEASE_FAILED, camera.device, "Camera release failed", error, null))); }
        }
        // A permission-cancelled or never-opened camera also has one terminal notification.
        if (!camera.closedDelivered) {
            camera.closedDelivered = true;
            emit(() -> listener.onClosed(camera));
        }
        if (wasRequesting) pumpPermissions();
    }

    private void emit(Runnable action) {
        if (!closed.get()) main.postAtTime(() -> { if (!closed.get()) action.run(); }, this, SystemClock.uptimeMillis());
    }

    /** Rejects new work immediately; native threads and USB connections drain asynchronously. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        main.removeCallbacksAndMessages(this);
        worker.post(() -> {
            for (Camera camera : new ArrayList<>(cameras.values())) stop(camera);
            permissions.clear(); requesting = null;
            try { monitor.destroy(); }
            finally {
                worker.removeCallbacksAndMessages(null); thread.quitSafely();
                main.post(listener::onShutdown);
            }
        });
    }

    public final class Camera implements AutoCloseable {
        public final UsbDevice device;
        /** Original request. Use actualConfig() after onOpened for negotiated capture metadata. */
        public final StreamConfig config;
        private volatile StreamConfig actualConfig;
        private final Surface preview;
        private final FrameListener frames;
        private final ConfigurationListener configured;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong frameCount = new AtomicLong();
        private volatile UVCCamera nativeCamera;
        volatile String failureMessage;
        Throwable failureCause;
        volatile CameraFailure failure;
        private boolean closedDelivered; // command thread only
        private Camera(UsbDevice device, StreamConfig config, Surface preview, FrameListener frames,
                ConfigurationListener configured) {
            this.device = device; this.config = config; this.actualConfig = config;
            this.preview = preview; this.frames = frames; this.configured = configured;
        }
        public boolean isClosed() { return closed.get(); }
        /** Actual capture mode after onOpened; initially the request. */
        public StreamConfig actualConfig() { return actualConfig; }
        public CameraFailure failure() { return failure; }
        public long receivedFrames() {
            UVCCamera camera = nativeCamera;
            long count = frames == null && camera != null ? camera.getPreviewFrameCount() : 0;
            return Math.max(frameCount.get(), count);
        }
        /** Controls are executed on the same command thread as open/close; do not retain the control object. */
        public void withControls(ControlAction action) {
            if (action == null) throw new NullPointerException("action");
            if (closed.get() || UvcCameraManager.this.closed.get()) return;
            worker.postAtTime(() -> {
                if (!closed.get() && !UvcCameraManager.this.closed.get() && nativeCamera != null) {
                    try { action.run(nativeCamera.getControl()); }
                    catch (RuntimeException | LinkageError error) { emit(() -> listener.onFailure(new CameraFailure(
                            CameraFailure.Code.CONTROL_FAILED, device, "Device control failed", error, null))); }
                }
            }, this, SystemClock.uptimeMillis());
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) worker.post(() -> stop(this));
        }
    }
}
