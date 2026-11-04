package com.facesdkpro.uvccamera.sample;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import com.facesdkpro.uvccamera.DualCameraConfig;
import com.facesdkpro.uvccamera.DualCameraSession;
import com.facesdkpro.uvccamera.FramePair;
import com.facesdkpro.uvccamera.StreamConfig;
import com.serenegiant.usb.UVCCamera;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-source-only real-device checks of the standalone public SDK; saves no camera pixels. */
public final class DualSessionProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray checks = new JSONArray();
    private final AtomicInteger pairs = new AtomicInteger(), bad = new AtomicInteger(), updates = new AtomicInteger();
    private final AtomicLong first = new AtomicLong(), last = new AtomicLong(), consumerEnd = new AtomicLong();
    private UsbDevice rgb, ir;
    private DualCameraSession session;
    private Surface preview;
    private SurfaceTexture retiredTexture;
    private TextView text;
    private int cycle, errors, controls;
    private boolean finished, ready, closeReturnedBeforeConsumer;
    private long seenSessionId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(128);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        text = new TextView(this); text.setText("双目 SDK 接口验收，不保存摄像头图像"); root.addView(text);
        TextureView view = new TextureView(this); root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { preview = new Surface(texture); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (session != null) { retiredTexture = texture; session.close(); return false; }
                if (preview != null) preview.release(); preview = null; return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture texture) { updates.incrementAndGet(); }
        }); setContentView(root);
        List<UsbDevice> devices = DualCameraSession.devices(this);
        for (UsbDevice device : devices) {
            if (device.getVendorId() == 0x1187 && device.getProductId() == 0x3a49) rgb = device;
            if (device.getVendorId() == 0x1871 && device.getProductId() == 0x3b49) ir = device;
        }
        check("explicit_rgb_ir_devices", rgb != null && ir != null, "uvc_devices=" + devices.size());
        if (rgb == null || ir == null) { finishReport(); return; }
        try {
            new DualCameraSession(this, rgb, rgb, DualCameraConfig.defaults(), null, null,
                    new DualCameraSession.Listener() {}, pair -> {});
            check("same_device_rejected", false, "accepted");
        } catch (IllegalArgumentException expected) { check("same_device_rejected", true, "rejected before resources"); }
        main.postDelayed(this::openCycle, 300);
        main.postDelayed(() -> { if (!finished) { check("timeout", false, "75 seconds"); if (session != null) session.close(); finishReport(); } }, 75000);
    }

    private void openCycle() {
        if (finished) return;
        if (cycle == 1 && preview == null) { main.postDelayed(this::openCycle, 100); return; }
        pairs.set(0); bad.set(0); first.set(0); last.set(0); consumerEnd.set(0); updates.set(0);
        errors = controls = 0; ready = closeReturnedBeforeConsumer = false;
        DualCameraConfig config = DualCameraConfig.defaults();
        if (cycle == 5) config = new DualCameraConfig(new StreamConfig(640, 480, 120,
                UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0), config.ir, 50, 150, 15);
        final int mode = cycle;
        session = new DualCameraSession(this, rgb, ir, config, mode == 1 ? preview : null, null,
                new DualCameraSession.Listener() {
                    @Override public void onReady(DualCameraSession source) {
                        ready = true;
                        check("state_thread_" + mode, Looper.myLooper() == Looper.getMainLooper(), "ready on main");
                        check("session_id_" + mode, source.sessionId > seenSessionId, "id=" + source.sessionId);
                        seenSessionId = source.sessionId;
                        if (mode <= 2) for (DualCameraSession.Role role : DualCameraSession.Role.values()) {
                            source.withControls(role, control -> {
                                boolean supported = control.isBrightnessEnable();
                                if (supported) control.getBrightnessPercent();
                                main.post(() -> controls++);
                            });
                        }
                        if (mode <= 2 || mode == 7) main.postDelayed(() -> sampleAndClose(source, mode), 2500);
                    }
                    @Override public void onError(DualCameraSession source, String message, Throwable cause) {
                        errors++;
                        check("error_policy_" + mode, mode == 4 || mode == 5,
                                message + (cause == null ? "" : " · " + cause.getClass().getSimpleName()));
                        if (mode == 5) check("start_failure_reason_preserved", cause != null && message.equals("Camera start failed"),
                                message + ", cause=" + (cause != null));
                    }
                    @Override public void onClosed(DualCameraSession source) {
                        check("closed_thread_" + mode, Looper.myLooper() == Looper.getMainLooper() && source.isClosed(), "closed on main");
                        check("error_count_" + mode, errors == ((mode == 4 || mode == 5) ? 1 : 0), "errors=" + errors);
                        check("pixel_thread_pair_contract_" + mode, bad.get() == 0, "bad=" + bad.get() + ", pairs=" + pairs.get());
                        if (mode == 3) check("close_drains_consumer", closeReturnedBeforeConsumer && consumerEnd.get() > 0,
                                "close_returned_before_consumer=" + closeReturnedBeforeConsumer);
                        if (mode == 6) check("immediate_close_no_delivery", pairs.get() == 0 && !ready, "pairs=" + pairs.get());
                        session = null;
                        if (retiredTexture != null) { if (preview != null) preview.release(); preview = null; retiredTexture.release(); retiredTexture = null; }
                        main.postDelayed(() -> afterClose(mode), 400);
                    }
                }, pair -> consume(pair, mode));
        if (mode == 6) session.close();
    }

    private void consume(FramePair pair, int mode) {
        int count = pairs.incrementAndGet(); long now = SystemClock.elapsedRealtimeNanos();
        first.compareAndSet(0, now); last.set(now);
        if (Looper.myLooper() == Looper.getMainLooper() || !Thread.currentThread().getName().equals("UVC-pairs")
                || pair.rgb.pixelFormat != UVCCamera.PIXEL_FORMAT_NV21 || pair.ir.pixelFormat != UVCCamera.PIXEL_FORMAT_GRAY8
                || pair.rgb.pixels().remaining() != 460800 || pair.ir.luma().remaining() != 307200
                || !pair.rgbPlanes().u().isDirect() || !pair.ir.pixels().isReadOnly()
                || pair.arrivalSkewNanos > 50_000_000L
                || now - pair.rgb.arrivalTimeNanos > 160_000_000L || now - pair.ir.arrivalTimeNanos > 160_000_000L) bad.incrementAndGet();
        byte before = pair.rgb.pixels().get(100);
        try {
            if (mode == 2) Thread.sleep(120);
            if (mode == 3 && count == 1) {
                main.post(() -> {
                    if (session != null) { session.close(); closeReturnedBeforeConsumer = consumerEnd.get() == 0; }
                });
                Thread.sleep(300); consumerEnd.set(SystemClock.elapsedRealtimeNanos());
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); bad.incrementAndGet(); }
        if (pair.rgb.pixels().get(100) != before) bad.incrementAndGet();
        if (mode == 4 && count == 3) throw new IllegalStateException("Expected consumer failure fixture");
    }

    private void sampleAndClose(DualCameraSession source, int mode) {
        if (session != source || source.isClosed()) return;
        double fps = pairs.get() > 1 ? (pairs.get() - 1) * 1e9 / (last.get() - first.get()) : 0;
        check("pairs_rate_" + mode, pairs.get() > 8 && fps <= 15.5 && fps > 3, "pairs=" + pairs.get() + ", fps=" + fps);
        check("two_streams_" + mode, source.receivedFrames(DualCameraSession.Role.RGB) > 30
                && source.receivedFrames(DualCameraSession.Role.IR) > 30, "both cameras produced frames");
        if (mode <= 2) check("controls_" + mode, controls == 2, "reads=" + controls);
        if (mode == 1) check("rgb_preview", updates.get() > 10, "updates=" + updates.get());
        source.close();
    }

    private void afterClose(int mode) {
        int usb = 0, workers = 0;
        File[] fds = new File("/proc/self/fd").listFiles();
        if (fds != null) for (File fd : fds) try { if (Os.readlink(fd.getPath()).startsWith("/dev/bus/usb/")) usb++; } catch (Exception ignored) {}
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.getName().equals("UVC-pairs") || thread.getName().equals("UVC-commands")) workers++;
        check("resources_drained_" + mode, usb == 0 && workers == 0, "usb_fds=" + usb + ", java_workers=" + workers);
        cycle++;
        if (cycle == 8) finishReport(); else openCycle();
    }

    private void check(String name, boolean passed, String detail) {
        try { checks.put(new JSONObject().put("name", name).put("passed", passed).put("details", detail)); }
        catch (Exception error) { throw new IllegalStateException(error); }
        Log.i("UvcDualSdkAcceptance", name + " passed=" + passed + " " + detail);
        text.setText(name + ": " + passed);
    }

    private void finishReport() {
        if (finished) return; finished = true;
        try {
            int failed = 0; for (int i = 0; i < checks.length(); i++) if (!checks.getJSONObject(i).getBoolean("passed")) failed++;
            JSONObject report = new JSONObject().put("run_id", getIntent().getStringExtra("runId"))
                    .put("checks", checks).put("failed", failed).put("passed", checks.length() - failed);
            String encoded = android.util.Base64.encodeToString(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            int count = (encoded.length() + 2999) / 3000;
            for (int i = 0; i < count; i++) Log.i("UvcDualSdkAcceptance", "REPORT_PART=" + i + "/" + count + ":" + encoded.substring(i * 3000, Math.min((i + 1) * 3000, encoded.length())));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override protected void onStop() { if (session != null) session.close(); super.onStop(); }
}
