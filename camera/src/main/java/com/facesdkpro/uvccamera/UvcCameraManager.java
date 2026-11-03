package com.facesdkpro.uvccamera;

import android.content.Context;
import android.hardware.usb.UsbConstants;
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
    }
    public interface FrameListener { void onFrame(Camera camera, CameraFrame frame); }
    public interface ControlAction { void run(UVCControl control); }

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
        if (listener == null) throw new NullPointerException("listener");
        this.listener = listener;
        Context app = context.getApplicationContext();
        usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        thread.start(); worker = new Handler(thread.getLooper());
        monitor = new USBMonitor(app, new USBMonitor.OnDeviceConnectListener() {
            public void onAttach(UsbDevice device) {
                if (!closed.get() && isCamera(device) && attached.add(device.getDeviceName())) {
                    emit(() -> listener.onAttached(device));
                }
            }
            public void onDetach(UsbDevice device) {
                attached.remove(device.getDeviceName());
                Camera camera = cameras.get(device.getDeviceName());
                if (camera != null) stop(camera);
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
                if (camera != null) stop(camera);
            }
            public void onCancel(UsbDevice device) { permissionFailed(device, "USB access denied", null); }
            public void onError(UsbDevice device, USBMonitor.USBException error) {
                permissionFailed(device, "USB open failed", error);
            }
        }, worker, devicePollMillis);
        worker.post(monitor::register);
    }

    public static boolean isCamera(UsbDevice device) {
        if (device == null) return false;
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    /** Current UVC devices. Match role by VID/PID or user selection, never enumeration order. */
    public List<UsbDevice> devices() {
        List<UsbDevice> result = new ArrayList<>();
        if (!closed.get()) for (UsbDevice device : usb.getDeviceList().values()) {
            if (isCamera(device)) result.add(device);
        }
        Collections.sort(result, (a, b) -> a.getDeviceName().compareTo(b.getDeviceName()));
        return Collections.unmodifiableList(result);
    }

    /** Surface may be null for analysis only. A supplied Surface stays owned by the caller. */
    public Camera open(UsbDevice device, StreamConfig config, Surface preview, FrameListener frames) {
        if (closed.get()) throw new IllegalStateException("Manager closed");
        if (!isCamera(device)) throw new IllegalArgumentException("Device has no UVC interface");
        if (config == null) throw new NullPointerException("config");
        if (frames == null && preview == null) throw new IllegalArgumentException("A frame listener or preview is required");
        Camera camera = new Camera(device, config, preview, frames);
        boolean accepted = worker.post(() -> {
            if (closed.get() || camera.closed.get()) { camera.closed.set(true); return; }
            if (cameras.containsKey(device.getDeviceName())) {
                fail(camera, "Device already has a session", null); return;
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
            monitor.requestPermission(next.device);
            worker.postAtTime(() -> {
                if (requesting == pending) {
                    requesting = null;
                    fail(pending, "USB permission request timed out", null);
                    pumpPermissions();
                }
            }, pending, SystemClock.uptimeMillis() + 30000);
            break;
        }
    }

    private void permissionFailed(UsbDevice device, String message, Throwable cause) {
        Camera camera = requesting;
        if (camera == null || !camera.device.equals(device)) return;
        requesting = null; fail(camera, message, cause); pumpPermissions();
    }

    private void start(Camera session, USBMonitor.UsbControlBlock control) {
        try {
            if (session.preview != null && !session.preview.isValid()) throw new IllegalStateException("Preview Surface is invalid");
            StreamConfig config = session.config;
            UVCCamera camera = new UVCCamera(new UVCParam(config.size(), config.quirks | UVCCamera.getRecommendedPlatformQuirks()));
            session.nativeCamera = camera;
            int status = camera.open(control);
            if (status != 0) throw new IllegalStateException("UVC open/mode negotiation failed: " + status);
            if (session.preview != null) camera.setPreviewDisplay(session.preview);
            if (session.frames != null) camera.setFrameCallback(buffer -> {
                if (closed.get() || session.closed.get()) return;
                long sequence = session.frameCount.incrementAndGet();
                try {
                    session.frames.onFrame(session, new CameraFrame(buffer, config, sequence, SystemClock.elapsedRealtimeNanos()));
                } catch (RuntimeException error) {
                    // Close on the command thread: stopping native capture inside its callback would deadlock.
                    if (session.closed.compareAndSet(false, true))
                        worker.post(() -> fail(session, "Frame listener failed", error));
                }
            }, config.pixelFormat);
            camera.startPreview();
            emit(() -> { if (!session.closed.get()) listener.onOpened(session); });
            worker.postAtTime(() -> {
                if (!session.closed.get() && session.receivedFrames() == 0) fail(session, "Camera produced no frames within 5 seconds", null);
            }, session, SystemClock.uptimeMillis() + 5000);
        } catch (Exception error) { fail(session, "Camera start failed", error); }
    }

    private void fail(Camera camera, String message, Throwable cause) {
        stop(camera); emit(() -> listener.onError(camera.device, message, cause));
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
            catch (RuntimeException error) { emit(() -> listener.onError(camera.device, "Camera release failed", error)); }
            finally { emit(() -> listener.onClosed(camera)); }
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
        public final StreamConfig config;
        private final Surface preview;
        private final FrameListener frames;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong frameCount = new AtomicLong();
        private volatile UVCCamera nativeCamera;
        private Camera(UsbDevice device, StreamConfig config, Surface preview, FrameListener frames) {
            this.device = device; this.config = config; this.preview = preview; this.frames = frames;
        }
        public boolean isClosed() { return closed.get(); }
        public long receivedFrames() {
            UVCCamera camera = nativeCamera;
            long count = frames == null && camera != null ? camera.getPreviewFrameCount() : 0;
            return Math.max(frameCount.get(), count);
        }
        /** Controls are executed on the same command thread as open/close; do not retain the control object. */
        public void withControls(ControlAction action) {
            if (action == null) throw new NullPointerException("action");
            if (closed.get() || UvcCameraManager.this.closed.get()) return;
            worker.post(() -> {
                if (!closed.get() && nativeCamera != null) {
                    try { action.run(nativeCamera.getControl()); }
                    catch (RuntimeException error) { emit(() -> listener.onError(device, "Device control failed", error)); }
                }
            });
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) worker.post(() -> stop(this));
        }
    }
}
