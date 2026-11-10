package com.facesdkpro.uvccamera;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one explicitly selected RGB/IR pair, serialized USB permissions and bounded pair delivery.
 * State callbacks run on main; pixels are borrowed on UVC-pairs. Never joins a frame callback.
 * A failure ends the WHOLE session; create a fresh session to retry/reconnect.
 */
public final class DualCameraSession implements AutoCloseable {
    public enum Role { RGB, IR }
    public interface Listener {
        /** Streams opened, not a promise of fresh pairs or stabilized exposure. */
        default void onReady(DualCameraSession session) {}
        default void onError(DualCameraSession session, String message, Throwable cause) {}
        /** role is null for whole-session failures; default preserves the original onError callback. */
        default void onFailure(DualCameraSession session, Role role, CameraFailure failure) {
            onError(session, failure.message, failure.cause);
        }
        /** Sampled at 2 Hz, delivered ONLY when state changes. No per-frame status allocation/notification. */
        default void onPairingStateChanged(DualCameraSession session, PairingDiagnostics diagnostics) {}
        /** USB/native, pair consumer AND out-of-lock copies have drained. Safe to release Surfaces. */
        default void onClosed(DualCameraSession session) {}
    }
    public interface FramePairListener { void onFramePair(FramePair pair); }
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final long STALL_NANOS = 5_000_000_000L;
    public final long sessionId = NEXT_ID.incrementAndGet();
    public final DualCameraConfig config;
    private final Context context;
    private final UsbDevice rgbDevice, irDevice;
    private final Surface rgbPreview, irPreview;
    private final FramePairListener frames;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Object lifecycle = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile UvcCameraManager manager;
    private volatile FramePairDispatcher dispatcher;
    private volatile UvcCameraManager.Camera rgb, ir;
    // Main thread / start(), except the atomic admission stop.
    private boolean started, rgbOpened, irOpened, usbDrained = true, pairsDrained = true, closedDelivered;
    private long readyAt;
    private volatile CameraFailure lastFailure;
    private PairingDiagnostics.State publishedState;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (closed.get()) return;
            long now = SystemClock.elapsedRealtimeNanos();
            PairingDiagnostics diagnostics = pairingDiagnostics();
            if (now - Math.max(readyAt, diagnostics.lastRgbTimeNanos) > STALL_NANOS) {
                fail(new CameraFailure(CameraFailure.Code.STREAM_TIMEOUT, rgbDevice,
                        "RGB camera stopped producing usable frames for 5 seconds", null, null));
            } else if (now - Math.max(readyAt, diagnostics.lastIrTimeNanos) > STALL_NANOS) {
                fail(new CameraFailure(CameraFailure.Code.STREAM_TIMEOUT, irDevice,
                        "IR camera stopped producing usable frames for 5 seconds", null, null));
            } else if (now - Math.max(readyAt, diagnostics.lastPairTimeNanos) > STALL_NANOS) {
                fail(new CameraFailure(CameraFailure.Code.PAIR_TIMEOUT, null,
                        diagnostics.consumerActive ? "Frame-pair consumer stalled for 5 seconds"
                                : "Both streams are active but no usable pair was delivered for 5 seconds", null, null));
            } else {
                publishState(diagnostics);
                main.postDelayed(this, 500);
            }
        }
    };

    /** Enumerates without allocating workers or requesting USB permission. */
    public static List<UsbDevice> devices(Context context) {
        if (context == null) throw new NullPointerException("context");
        UsbManager usb = (UsbManager) context.getApplicationContext().getSystemService(Context.USB_SERVICE);
        return UvcDevices.list(usb);
    }

    /**
     * Backward-compatible auto-start. Pure argument errors throw before owning resources;
     * all subsequent startup failures report onFailure + onClosed asynchronously.
     */
    public DualCameraSession(Context context, UsbDevice rgbDevice, UsbDevice irDevice,
            DualCameraConfig config, Surface rgbPreview, Surface irPreview,
            Listener listener, FramePairListener frames) {
        this(context, rgbDevice, irDevice, config, rgbPreview, irPreview, listener, frames, true);
    }

    /** Two-phase startup: assign the handle first, then call start(). No USB/thread/pixel-pool allocation here. */
    public static DualCameraSession create(Context context, UsbDevice rgbDevice, UsbDevice irDevice,
            DualCameraConfig config, Surface rgbPreview, Surface irPreview,
            Listener listener, FramePairListener frames) {
        return new DualCameraSession(context, rgbDevice, irDevice, config, rgbPreview, irPreview, listener, frames, false);
    }

    private DualCameraSession(Context context, UsbDevice rgbDevice, UsbDevice irDevice,
            DualCameraConfig config, Surface rgbPreview, Surface irPreview,
            Listener listener, FramePairListener frames, boolean autoStart) {
        requireMain();
        if (context == null || config == null || listener == null || frames == null)
            throw new NullPointerException("session argument");
        if (!CameraSelection.isPair(rgbDevice, irDevice))
            throw new IllegalArgumentException("Select two distinct UVC devices for RGB and IR");
        if (rgbPreview != null && !rgbPreview.isValid() || irPreview != null && !irPreview.isValid())
            throw new IllegalArgumentException("Preview Surface is invalid");
        this.context = context.getApplicationContext();
        this.rgbDevice = rgbDevice; this.irDevice = irDevice; this.config = config;
        this.rgbPreview = rgbPreview; this.irPreview = irPreview;
        this.listener = listener; this.frames = frames;
        if (autoStart) start();
    }

    /** Main-thread, idempotent while active. Closed sessions cannot restart or reuse old pair IDs. */
    public void start() {
        requireMain();
        synchronized (lifecycle) {
            if (closed.get()) throw new IllegalStateException("Session closed");
            if (started) return;
            started = true;
            UsbDevice openingDevice = null;
            try {
                dispatcher = new FramePairDispatcher(sessionId, config, SystemClock::elapsedRealtimeNanos,
                        pair -> { if (!closed.get()) frames.onFramePair(pair); },
                        error -> fail(new CameraFailure(CameraFailure.Code.FRAME_CONSUMER_FAILED, null,
                                "Frame-pair consumer failed", error, null)),
                        () -> main.post(() -> { pairsDrained = true; completeClose(); }));
                pairsDrained = false;
                manager = new UvcCameraManager(context, cameraListener());
                usbDrained = false;
                openingDevice = rgbDevice;
                rgb = manager.open(rgbDevice, config.rgb, rgbPreview,
                        (camera, frame) -> dispatcher.offer(true, frame), actual -> dispatcher.configure(true, actual));
                openingDevice = irDevice;
                ir = manager.open(irDevice, config.ir, irPreview,
                        (camera, frame) -> dispatcher.offer(false, frame), actual -> dispatcher.configure(false, actual));
            } catch (RuntimeException | LinkageError error) {
                // Including the first/second open failing: callers already own a handle and always
                // receive completion. No lost partially-constructed session or premature Surface release.
                fail(new CameraFailure(CameraFailure.Code.INITIALIZATION, openingDevice,
                        "Dual-camera startup failed", error, null));
            } catch (Error fatal) {
                close();
                throw fatal;
            }
        }
    }

    private UvcCameraManager.Listener cameraListener() {
        return new UvcCameraManager.Listener() {
            @Override public void onOpened(UvcCameraManager.Camera camera) {
                if (closed.get()) return;
                if (camera.device.getDeviceName().equals(rgbDevice.getDeviceName())) rgbOpened = true;
                if (camera.device.getDeviceName().equals(irDevice.getDeviceName())) irOpened = true;
                if (rgbOpened && irOpened && readyAt == 0) {
                    readyAt = SystemClock.elapsedRealtimeNanos();
                    listener.onReady(DualCameraSession.this);
                    if (!closed.get()) {
                        publishState(pairingDiagnostics());
                        main.postDelayed(watchdog, 500);
                    }
                }
            }
            @Override public void onClosed(UvcCameraManager.Camera camera) {
                fail(camera.failure != null ? camera.failure : new CameraFailure(CameraFailure.Code.CAMERA_CLOSED,
                        camera.device, "A camera was disconnected or closed", null, null));
            }
            @Override public void onDetached(UsbDevice device) {
                if (role(device) != null) fail(new CameraFailure(CameraFailure.Code.DEVICE_DETACHED,
                        device, "A selected camera was detached", null, null));
            }
            @Override public void onFailure(CameraFailure failure) { fail(failure); }
            @Override public void onShutdown() { usbDrained = true; completeClose(); }
        };
    }

    public boolean isClosed() { return closed.get(); }
    /** Negotiated modes after onReady. Requests and rotations remain available in config. */
    public DualCameraConfig actualConfig() {
        return new DualCameraConfig(rgb == null ? config.rgb : rgb.actualConfig(),
                ir == null ? config.ir : ir.actualConfig(), config.maxPairSkewMillis,
                config.maxFrameAgeMillis, config.maxPairsPerSecond,
                config.rgbRotationDegrees, config.irRotationDegrees);
    }
    /** Null for an explicit normal close. Available after onClosed as well. */
    public CameraFailure failure() { return lastFailure; }
    public long receivedFrames(Role role) {
        UvcCameraManager.Camera camera = camera(role);
        return camera == null ? 0 : camera.receivedFrames();
    }
    /** Counters and primitive timing values only; never retains image buffers. */
    public PairingDiagnostics pairingDiagnostics() {
        FramePairDispatcher current = dispatcher;
        return current == null ? PairingDiagnostics.empty(sessionId, closed.get()) : current.diagnostics();
    }
    public void withControls(Role role, UvcCameraManager.ControlAction action) {
        UvcCameraManager.Camera camera = camera(role);
        if (camera == null) throw new IllegalStateException("Start the session before applying controls");
        camera.withControls(action);
    }
    private UvcCameraManager.Camera camera(Role role) {
        if (role == null) throw new NullPointerException("role");
        return role == Role.RGB ? rgb : ir;
    }
    private Role role(UsbDevice device) {
        if (device == null) return null;
        if (device.getDeviceName().equals(rgbDevice.getDeviceName())) return Role.RGB;
        return device.getDeviceName().equals(irDevice.getDeviceName()) ? Role.IR : null;
    }

    private void publishState(PairingDiagnostics diagnostics) {
        if (publishedState == diagnostics.state) return;
        publishedState = diagnostics.state;
        listener.onPairingStateChanged(this, diagnostics);
    }

    private void fail(CameraFailure failure) {
        if (!closed.compareAndSet(false, true)) return;
        lastFailure = failure;
        main.post(() -> listener.onFailure(this, role(failure.device), failure));
        stopResources();
    }

    private void stopResources() {
        main.removeCallbacks(watchdog);
        synchronized (lifecycle) {
            if (dispatcher != null) dispatcher.close();
            if (manager != null) manager.close();
        }
        main.post(this::completeClose);
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) stopResources();
    }

    private void completeClose() {
        if (closed.get() && usbDrained && pairsDrained && !closedDelivered) {
            closedDelivered = true;
            publishState(pairingDiagnostics());
            listener.onClosed(this);
        }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Create/start on the main thread");
    }
}
