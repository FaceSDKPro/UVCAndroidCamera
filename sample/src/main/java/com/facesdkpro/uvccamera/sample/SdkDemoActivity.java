package com.facesdkpro.uvccamera.sample;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.facesdkpro.uvccamera.DualCameraConfig;
import com.facesdkpro.uvccamera.DualCameraSession;
import com.facesdkpro.uvccamera.FramePair;
import com.facesdkpro.uvccamera.Nv21Planes;
import com.facesdkpro.uvccamera.PreviewTransform;
import com.facesdkpro.uvccamera.StreamConfig;
import com.serenegiant.usb.UVCCamera;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone SDK consumer: no HelloFace dependency, models, recognition or liveness decision. */
public final class SdkDemoActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Spinner[] selectors = new Spinner[2];
    private final CaptureSettingsView[] settings = new CaptureSettingsView[2];
    private Spinner pairRate;
    private final AtomicLong pairs = new AtomicLong(), skewMicros = new AtomicLong();
    private final AtomicLong pairWidth = new AtomicLong(), pairHeight = new AtomicLong(), irWidth = new AtomicLong(), irHeight = new AtomicLong();
    private final List<Runnable> retiredPreviews = new ArrayList<>();
    private List<UsbDevice> devices = new ArrayList<>();
    private TextView status, counts;
    private Button open;
    private TextureView preview;
    private Surface surface;
    private DualCameraSession session;
    private DualCameraConfig appliedConfig = DualCameraConfig.defaults();
    private boolean visible, failed, restartPending;
    private long previousRgb, previousIr, previousPairs, previousTick;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            DualCameraSession current = session;
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            long rgb = current == null ? 0 : current.receivedFrames(DualCameraSession.Role.RGB);
            long ir = current == null ? 0 : current.receivedFrames(DualCameraSession.Role.IR), n = pairs.get();
            double seconds = previousTick == 0 ? 1 : (now - previousTick) / 1e9;
            counts.setText(current == null ? "RGB：NV21 · IR：GRAY8" : String.format(Locale.ROOT,
                    "RGB %d 帧 · %.1f FPS · 输出 %d×%d\nIR %d 帧 · %.1f FPS · 输出 %d×%d\n交付 %d 对 · %.1f 对/秒 · 到达时间差 %.1f ms",
                    rgb, Math.max(0, rgb - previousRgb) / seconds, pairWidth.get(), pairHeight.get(),
                    ir, Math.max(0, ir - previousIr) / seconds, irWidth.get(), irHeight.get(),
                    n, Math.max(0, n - previousPairs) / seconds, skewMicros.get() / 1000.0));
            previousRgb = rgb; previousIr = ir; previousPairs = n; previousTick = now;
            if (visible) main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 16, 24, 16);
        TextView title = new TextView(this); title.setText("UVC 双目 SDK Demo"); title.setTextSize(24); root.addView(title);
        status = new TextView(this); status.setText("选择 RGB 与 IR 摄像头，演示向算法工作线程交付帧对。"); root.addView(status);
        for (int i = 0; i < 2; i++) {
            selectors[i] = new Spinner(this); selectors[i].setContentDescription(i == 0 ? "RGB 设备" : "IR 设备"); root.addView(selectors[i]);
            settings[i] = new CaptureSettingsView(this, i == 0 ? "RGB" : "IR"); root.addView(settings[i]);
        }
        LinearLayout pairSettings = new LinearLayout(this);
        TextView pairLabel = new TextView(this); pairLabel.setText("帧对交付上限（独立于采集 FPS）"); pairSettings.addView(pairLabel);
        pairRate = new Spinner(this); pairRate.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"5 对/秒", "10 对/秒", "15 对/秒", "30 对/秒"})); pairRate.setSelection(2);
        pairSettings.addView(pairRate, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(pairSettings);
        LinearLayout actions = new LinearLayout(this);
        open = button(actions, "开始采集", () -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) applySettings();
            else requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        });
        button(actions, "停止", this::stopSession);
        button(actions, "刷新设备", this::refreshDevices); root.addView(actions);
        counts = new TextView(this); root.addView(counts);
        TextView note = new TextView(this);
        note.setText("分辨率和 FPS 须为设备支持的组合，不支持时明确报错。\n旋转同时作用于 RGB 预览和双目回调像素；IR 无预览。\n软件配对不代表硬件同步，本示例不输出活体结论。"); root.addView(note);
        preview = new TextureView(this);
        preview.setOpaque(false);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                surface = new Surface(texture); fitPreview();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { fitPreview(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                Surface retired = surface; surface = null;
                Runnable release = () -> { if (retired != null) retired.release(); texture.release(); };
                if (session == null) release.run();
                else { retiredPreviews.add(release); stopSession(); }
                return false; // Caller releases only after native capture has drained.
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        // Keep the settings usable on smaller screens without shrinking the preview to zero height.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.setFillViewport(true);
        LinearLayout diagnostic = new LinearLayout(this);
        button(diagnostic, "设备预览与控制演示", () -> startActivity(new Intent(this, MainActivity.class)));
        scroll.addView(root);
        LinearLayout screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 3));
        android.widget.FrameLayout previewBox = new android.widget.FrameLayout(this); previewBox.setBackgroundColor(0xff000000);
        previewBox.addView(preview, new android.widget.FrameLayout.LayoutParams(-1,-1));
        screen.addView(previewBox, new LinearLayout.LayoutParams(-1,0,2)); screen.addView(diagnostic); setContentView(screen);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        screen.requestApplyInsets();
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button result = new Button(this); result.setText(text); result.setOnClickListener(view -> action.run());
        parent.addView(result, new LinearLayout.LayoutParams(0, -2, 1)); return result;
    }

    private void fitPreview() {
        if (preview.getWidth() == 0 || preview.getHeight() == 0) return;
        preview.setTransform(PreviewTransform.fitCenter(preview.getWidth(), preview.getHeight(),
                appliedConfig.rgb.width, appliedConfig.rgb.height, appliedConfig.rgbRotationDegrees));
    }

    private void refreshDevices() {
        if (session != null) return;
        String[] previous = new String[2];
        for (int role = 0; role < 2; role++) {
            int selected = selectors[role].getSelectedItemPosition() - 1;
            if (selected >= 0 && selected < devices.size()) previous[role] = devices.get(selected).getDeviceName();
        }
        devices = DualCameraSession.devices(this);
        for (int role = 0; role < 2; role++) {
            List<String> labels = new ArrayList<>(); labels.add(role == 0 ? "请选择 RGB 摄像头" : "请选择 IR 摄像头");
            int choice = 0;
            for (int i = 0; i < devices.size(); i++) {
                UsbDevice device = devices.get(i);
                labels.add(String.format(Locale.ROOT, "%s · %04X:%04X", device.getProductName(), device.getVendorId(), device.getProductId()));
                // A known device profile is explicit role binding, not an enumeration-order assumption.
                boolean known = role == 0 ? device.getVendorId() == 0x1187 && device.getProductId() == 0x3a49
                        : device.getVendorId() == 0x1871 && device.getProductId() == 0x3b49;
                if (device.getDeviceName().equals(previous[role]) || previous[role] == null && known) choice = i + 1;
            }
            selectors[role].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            selectors[role].setSelection(choice);
        }
    }

    private void startSession() {
        if (!visible || session != null) return;
        int rgb = selectors[0].getSelectedItemPosition() - 1, ir = selectors[1].getSelectedItemPosition() - 1;
        if (rgb < 0 || ir < 0 || rgb >= devices.size() || ir >= devices.size() || rgb == ir) {
            status.setText("请为 RGB、IR 分别选择不同的摄像头"); return;
        }
        if (surface == null || !surface.isValid()) { status.setText("RGB 预览尚未就绪"); return; }
        StreamConfig rgbConfig = settings[0].config(UVCCamera.PIXEL_FORMAT_NV21), irConfig = settings[1].config(UVCCamera.PIXEL_FORMAT_GRAY8);
        int[] rates = {5, 10, 15, 30};
        appliedConfig = new DualCameraConfig(rgbConfig, irConfig, 50, 150, rates[pairRate.getSelectedItemPosition()],
                settings[0].rotationDegrees(), settings[1].rotationDegrees());
        fitPreview(); pairs.set(0); skewMicros.set(0); pairWidth.set(0); pairHeight.set(0); irWidth.set(0); irHeight.set(0);
        previousRgb = previousIr = previousPairs = previousTick = 0; failed = false; open.setEnabled(false);
        status.setText("正在申请权限并打开两路摄像头…");
        try {
            session = new DualCameraSession(this, devices.get(rgb), devices.get(ir), appliedConfig, surface, null,
                    new DualCameraSession.Listener() {
                        @Override public void onReady(DualCameraSession source) {
                            if (session == source && visible) {
                                status.setText(String.format(Locale.ROOT, "正在采集 · RGB %d×%d @%d / %d° · IR %d×%d @%d / %d°",
                                        rgbConfig.width, rgbConfig.height, rgbConfig.fps, appliedConfig.rgbRotationDegrees,
                                        irConfig.width, irConfig.height, irConfig.fps, appliedConfig.irRotationDegrees));
                                open.setEnabled(true); open.setText("应用并重启");
                            }
                        }
                        @Override public void onError(DualCameraSession source, String message, Throwable cause) {
                            if (session == source) { failed = true; restartPending = false; open.setEnabled(false); }
                            if (session == source && visible) status.setText(message + (cause == null ? "" : " · " + cause.getMessage()));
                        }
                        @Override public void onClosed(DualCameraSession source) {
                            if (session == source) {
                                session = null; open.setEnabled(visible);
                                open.setText("开始采集");
                                if (visible && !failed) status.setText("采集已停止，两路资源已释放");
                                if (restartPending && visible && !failed) {
                                    restartPending = false; main.post(SdkDemoActivity.this::startSession);
                                }
                            }
                            releaseRetiredPreviews();
                        }
                    }, this::consumePair);
        } catch (RuntimeException error) {
            open.setEnabled(true); open.setText("开始采集"); status.setText("打开失败 · " + error.getMessage());
        }
    }

    /** Integration point: call the face SDK synchronously here, or copy into your own bounded pool.
     * This example only validates the pixel contract; it does not simulate an algorithm result. */
    private void consumePair(FramePair pair) {
        Nv21Planes rgb = pair.rgbPlanes();
        if (!rgb.y().isDirect() || pair.ir.luma().remaining() != pair.ir.width * pair.ir.height)
            throw new IllegalStateException("Unexpected pixel contract");
        skewMicros.set(pair.arrivalSkewNanos / 1000); pairs.incrementAndGet();
        pairWidth.set(pair.rgb.width); pairHeight.set(pair.rgb.height); irWidth.set(pair.ir.width); irHeight.set(pair.ir.height);
    }

    private void applySettings() {
        if (session == null) startSession();
        else { restartPending = true; open.setEnabled(false); session.close(); status.setText("正在释放旧会话并应用新设置…"); }
    }

    private void stopSession() {
        restartPending = false;
        if (session != null) { session.close(); if (visible) status.setText("正在停止采集并释放资源…"); }
    }

    private void releaseRetiredPreviews() { for (Runnable release : retiredPreviews) release.run(); retiredPreviews.clear(); }
    @Override protected void onStart() {
        super.onStart(); visible = true; open.setEnabled(session == null); refreshDevices(); main.post(tick);
    }
    @Override protected void onStop() {
        visible = false; main.removeCallbacks(tick); stopSession(); super.onStop();
    }
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) applySettings();
        else status.setText("需要相机权限");
    }
}
