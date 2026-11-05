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
import com.facesdkpro.uvccamera.FrameTransforms;
import com.facesdkpro.uvccamera.PreviewTransform;
import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.CameraFailure;
import com.facesdkpro.uvccamera.PairingDiagnostics;
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
    private final AtomicLong latencyTotal = new AtomicLong(), latencyMax = new AtomicLong();
    private UsbDevice rgb, ir;
    private DualCameraSession session;
    private Surface preview;
    private TextureView previewView;
    private SurfaceTexture retiredTexture;
    private TextView text;
    private int cycle, errors, controls;
    private boolean finished, ready, closeReturnedBeforeConsumer, basic;
    private int stateNotifications;
    private PairingDiagnostics.State lastState;
    private long seenSessionId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(128);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        text = new TextView(this); text.setText("双目 SDK 接口验收，不保存摄像头图像"); root.addView(text);
        TextureView view = new TextureView(this); previewView = view; view.setOpaque(false); root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { preview = new Surface(texture); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (session != null) { retiredTexture = texture; session.close(); return false; }
                if (preview != null) preview.release(); preview = null; return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture texture) { updates.incrementAndGet(); }
        }); setContentView(root);
        rotationFixture();
        basic = getIntent().getBooleanExtra("basic", false);
        List<UsbDevice> devices = DualCameraSession.devices(this);
        int rgbMatches = 0, irMatches = 0;
        for (UsbDevice device : devices) {
            if (device.getVendorId() == 0x1187 && device.getProductId() == 0x3a49
                    || device.getVendorId() == 0x1871 && device.getProductId() == 0x1a49) { rgb = device; rgbMatches++; }
            if (device.getVendorId() == 0x1871 && (device.getProductId() == 0x3b49 || device.getProductId() == 0x1b49)) { ir = device; irMatches++; }
        }
        check("explicit_rgb_ir_devices", rgbMatches == 1 && irMatches == 1, "uvc_devices=" + devices.size());
        if (rgbMatches != 1 || irMatches != 1) { finishReport(); return; }
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
        latencyTotal.set(0); latencyMax.set(0); stateNotifications = 0; lastState = null;
        errors = controls = 0; ready = closeReturnedBeforeConsumer = false;
        DualCameraConfig config = DualCameraConfig.defaults();
        if (cycle == 5) config = new DualCameraConfig(new StreamConfig(2, 2, 30,
                UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0), config.ir, 50, 150, 15);
        if (cycle == 8) config = config.withRotations(90,270);
        if (cycle == 9) config = new DualCameraConfig(
                new StreamConfig(640,480,15,UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,UVCCamera.PIXEL_FORMAT_NV21,0),
                new StreamConfig(640,480,15,UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,UVCCamera.PIXEL_FORMAT_GRAY8,0),50,150,15,180,90);
        if (cycle == 10) config = new DualCameraConfig(
                new StreamConfig(1280,720,30,UVCCamera.UVC_VS_FRAME_MJPEG,UVCCamera.PIXEL_FORMAT_NV21,0),
                new StreamConfig(640,480,15,UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,UVCCamera.PIXEL_FORMAT_GRAY8,0),50,150,15,270,180);
        if (cycle == 11) config = new DualCameraConfig(
                new StreamConfig(1920,1080,30,UVCCamera.UVC_VS_FRAME_MJPEG,UVCCamera.PIXEL_FORMAT_NV21,0),
                new StreamConfig(1920,1080,30,UVCCamera.UVC_VS_FRAME_MJPEG,UVCCamera.PIXEL_FORMAT_GRAY8,0),50,150,15,90,180);
        if (cycle == 12) config = new DualCameraConfig(config.rgb,config.ir,50,150,10,270,90);
        final int mode = cycle;
        final DualCameraConfig selected = config;
        boolean showPreview = mode == 1 || mode == 8 || mode == 10;
        if (showPreview) previewView.setTransform(PreviewTransform.fitCenter(previewView.getWidth(),previewView.getHeight(),
                config.rgb.width,config.rgb.height,config.rgbRotationDegrees));
        DualCameraSession.Listener listener = new DualCameraSession.Listener() {
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
                        if (mode <= 2 || mode >= 7) main.postDelayed(() -> sampleAndClose(source, mode), 3000);
                    }
                    @Override public void onError(DualCameraSession source, String message, Throwable cause) {
                        errors++;
                        check("error_policy_" + mode, mode == 4 || mode == 5,
                                message + (cause == null ? "" : " · " + cause.getClass().getSimpleName()));
                        if (mode == 5) check("start_failure_reason_preserved", cause != null && source.failure() != null
                                        && source.failure().code == CameraFailure.Code.OPEN_FAILED
                                        && source.failure().nativeStatus != null && source.failure().nativeStatus != 0,
                                "structured native status=" + (source.failure() == null ? "none" : source.failure().nativeStatus));
                    }
                    @Override public void onPairingStateChanged(DualCameraSession source, PairingDiagnostics diagnostics) {
                        if (Looper.myLooper() != Looper.getMainLooper() || lastState == diagnostics.state) bad.incrementAndGet();
                        lastState = diagnostics.state; stateNotifications++;
                    }
                    @Override public void onClosed(DualCameraSession source) {
                        check("closed_thread_" + mode, Looper.myLooper() == Looper.getMainLooper() && source.isClosed(), "closed on main");
                        check("error_count_" + mode, errors == ((mode == 4 || mode == 5) ? 1 : 0), "errors=" + errors);
                        check("pixel_thread_pair_contract_" + mode, bad.get() == 0, "bad=" + bad.get() + ", pairs=" + pairs.get());
                        check("state_notifications_bounded_" + mode, stateNotifications > 0 && stateNotifications < 10,
                                "state_changes=" + stateNotifications);
                        if (mode == 3) check("close_drains_consumer", closeReturnedBeforeConsumer && consumerEnd.get() > 0,
                                "close_returned_before_consumer=" + closeReturnedBeforeConsumer);
                        if (mode == 6) check("immediate_close_no_delivery", pairs.get() == 0 && !ready, "pairs=" + pairs.get());
                        session = null;
                        if (retiredTexture != null) { if (preview != null) preview.release(); preview = null; retiredTexture.release(); retiredTexture = null; }
                        main.postDelayed(() -> afterClose(mode), 400);
                    }
                };
        DualCameraSession.FramePairListener consumer = pair -> consume(pair, mode, selected);
        session = mode == 6 || mode == 7
                ? DualCameraSession.create(this, rgb, ir, config, showPreview ? preview : null, null, listener, consumer)
                : new DualCameraSession(this, rgb, ir, config, showPreview ? preview : null, null, listener, consumer);
        if (mode == 6) session.close();
        else if (mode == 7) session.start();
    }

    private void consume(FramePair pair, int mode, DualCameraConfig config) {
        int count = pairs.incrementAndGet(); long now = SystemClock.elapsedRealtimeNanos();
        first.compareAndSet(0, now); last.set(now);
        if (Looper.myLooper() == Looper.getMainLooper() || !Thread.currentThread().getName().equals("UVC-pairs")
                || pair.rgb.pixelFormat != UVCCamera.PIXEL_FORMAT_NV21 || pair.ir.pixelFormat != UVCCamera.PIXEL_FORMAT_GRAY8
                || pair.rgb.pixels().remaining() != config.rgb.width*config.rgb.height*3/2
                || pair.ir.luma().remaining() != config.ir.width*config.ir.height
                || pair.rgb.width != FrameTransforms.outputWidth(config.rgb.width,config.rgb.height,config.rgbRotationDegrees)
                || pair.rgb.height != FrameTransforms.outputHeight(config.rgb.width,config.rgb.height,config.rgbRotationDegrees)
                || pair.ir.width != FrameTransforms.outputWidth(config.ir.width,config.ir.height,config.irRotationDegrees)
                || pair.ir.height != FrameTransforms.outputHeight(config.ir.width,config.ir.height,config.irRotationDegrees)
                || pair.rgb.appliedRotationDegrees != config.rgbRotationDegrees || pair.ir.appliedRotationDegrees != config.irRotationDegrees
                || pair.rgb.receivedTimeNanos <= 0 || pair.ir.receivedTimeNanos <= 0
                || pair.rgb.nativeSequence < 0 || pair.ir.nativeSequence < 0
                || pair.rgb.callbackTimeNanos < pair.rgb.receivedTimeNanos || pair.ir.callbackTimeNanos < pair.ir.receivedTimeNanos
                || !pair.rgbPlanes().u().isDirect() || !pair.ir.pixels().isReadOnly()
                || pair.arrivalSkewNanos > 50_000_000L
                || now - pair.rgb.arrivalTimeNanos > 160_000_000L || now - pair.ir.arrivalTimeNanos > 160_000_000L) bad.incrementAndGet();
        long rgbLatency = pair.rgb.inputLatencyNanos(), irLatency = pair.ir.inputLatencyNanos();
        latencyTotal.addAndGet(rgbLatency + irLatency); latencyMax.accumulateAndGet(Math.max(rgbLatency, irLatency), Math::max);
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
        check("pairs_rate_" + mode, pairs.get() > 8 && fps <= source.config.maxPairsPerSecond+.5 && fps > 3,
                "pairs=" + pairs.get() + ", fps=" + fps + ", requested_limit=" + source.config.maxPairsPerSecond
                        + ", input_latency_ms_mean=" + latencyTotal.get()/Math.max(1,pairs.get()*2)/1e6
                        + ", input_latency_ms_max=" + latencyMax.get()/1e6);
        check("two_streams_" + mode, source.receivedFrames(DualCameraSession.Role.RGB) > source.config.rgb.fps
                && source.receivedFrames(DualCameraSession.Role.IR) > source.config.ir.fps,
                "RGB="+source.config.rgb.width+"x"+source.config.rgb.height+"@"+source.config.rgb.fps+"/"+source.config.rgbRotationDegrees
                        +", IR="+source.config.ir.width+"x"+source.config.ir.height+"@"+source.config.ir.fps+"/"+source.config.irRotationDegrees);
        if (mode <= 2) check("controls_" + mode, controls == 2, "reads=" + controls);
        if (mode == 1 || mode == 8 || mode == 10) check("rgb_preview_"+mode, updates.get() > 10, "updates=" + updates.get());
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
        if (cycle == (basic ? 8 : 13)) finishReport(); else openCycle();
    }

    private void rotationFixture() {
        int[][] expected = {{0,1,2,3,4,5,6,7,8,9,10,11},{4,0,5,1,6,2,7,3,8,9,10,11},
                {7,6,5,4,3,2,1,0,10,11,8,9},{3,7,2,6,1,5,0,4,10,11,8,9}};
        for (int format : new int[]{2,3,8}) for (int index=0;index<4;index++) {
            int length = format==8?8:12;
            java.nio.ByteBuffer src=java.nio.ByteBuffer.allocateDirect(length+6), dst=java.nio.ByteBuffer.allocateDirect(length+6);
            for(int i=0;i<length;i++)src.put(i+3,(byte)i);
            for(int i=0;i<length+6;i++)dst.put(i,(byte)99);
            src.position(3);src.limit(3+length);dst.position(3);dst.limit(3+length);
            FrameTransforms.copyRotated(src.asReadOnlyBuffer(),dst,4,2,format,index*90);
            boolean ok=src.position()==3&&dst.position()==3;
            for(int i=0;i<length;i++)if(dst.get(i+3)!=(byte)expected[index][i])ok=false;
            dst.clear();ok=ok&&dst.get(2)==99&&dst.get(length+3)==99;
            check("jni_rotation_"+format+"_"+(index*90),ok,"packed pixels, chroma, windows and guards");
        }
        java.nio.ByteBuffer alias=java.nio.ByteBuffer.allocateDirect(12);
        try {
            FrameTransforms.copyRotated(alias,alias.duplicate(),4,2,3,90);
            check("native_overlap_rejected",false,"accepted alias");
        }catch(IllegalArgumentException expectedFailure){check("native_overlap_rejected",true,"rejected");}
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
