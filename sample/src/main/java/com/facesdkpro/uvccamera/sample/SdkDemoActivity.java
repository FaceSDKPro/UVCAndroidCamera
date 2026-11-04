package com.facesdkpro.uvccamera.sample;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone SDK consumer: no HelloFace dependency, models, recognition or liveness decision. */
public final class SdkDemoActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Spinner[] selectors = new Spinner[2];
    private final AtomicLong pairs = new AtomicLong(), skewMicros = new AtomicLong();
    private final List<Runnable> retiredPreviews = new ArrayList<>();
    private List<UsbDevice> devices = new ArrayList<>();
    private TextView status, counts;
    private Button open;
    private TextureView preview;
    private Surface surface;
    private DualCameraSession session;
    private boolean visible, failed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            DualCameraSession current = session;
            counts.setText(current == null ? "RGB：NV21 · IR：GRAY8" : String.format(Locale.ROOT,
                    "RGB %d 帧 · IR %d 帧\n交付 %d 对 · 最近回调到达间隔 %.1f ms",
                    current.receivedFrames(DualCameraSession.Role.RGB), current.receivedFrames(DualCameraSession.Role.IR),
                    pairs.get(), skewMicros.get() / 1000.0));
            if (visible) main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 16, 24, 16);
        TextView title = new TextView(this); title.setText("UVC 双目 SDK Demo"); title.setTextSize(24); root.addView(title);
        status = new TextView(this); status.setText("选择 RGB 与 IR 摄像头，演示向算法工作线程交付帧对。"); root.addView(status);
        for (int i = 0; i < 2; i++) { selectors[i] = new Spinner(this); root.addView(selectors[i]); }
        LinearLayout actions = new LinearLayout(this);
        open = button(actions, "开始采集", () -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startSession();
            else requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        });
        button(actions, "停止", this::stopSession);
        button(actions, "刷新设备", this::refreshDevices); root.addView(actions);
        counts = new TextView(this); root.addView(counts);
        TextView note = new TextView(this);
        note.setText("640×480 · MJPEG 30 FPS · 最多 15 对/秒\n仅预览 RGB，IR 无预览。软件配对不代表硬件同步。\n本示例验证采集接口，不输出人脸识别或活体结论。"); root.addView(note);
        preview = new TextureView(this);
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
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout diagnostic = new LinearLayout(this);
        button(diagnostic, "设备预览与控制演示", () -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(diagnostic); setContentView(root);
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button result = new Button(this); result.setText(text); result.setOnClickListener(view -> action.run());
        parent.addView(result, new LinearLayout.LayoutParams(0, -2, 1)); return result;
    }

    private void fitPreview() {
        if (preview.getWidth() == 0 || preview.getHeight() == 0) return;
        float actual = (float)preview.getWidth() / preview.getHeight(), ratio = 4f / 3f;
        Matrix transform = new Matrix();
        transform.setScale(actual > ratio ? ratio / actual : 1f, actual < ratio ? actual / ratio : 1f,
                preview.getWidth() / 2f, preview.getHeight() / 2f);
        preview.setTransform(transform);
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
        pairs.set(0); skewMicros.set(0); failed = false; open.setEnabled(false);
        status.setText("正在申请权限并打开两路摄像头…");
        try {
            session = new DualCameraSession(this, devices.get(rgb), devices.get(ir), DualCameraConfig.defaults(), surface, null,
                    new DualCameraSession.Listener() {
                        @Override public void onReady(DualCameraSession source) {
                            if (session == source && visible) status.setText("双路已打开，正在交付 NV21 / GRAY8 帧对");
                        }
                        @Override public void onError(DualCameraSession source, String message, Throwable cause) {
                            if (session == source) failed = true;
                            if (session == source && visible) status.setText(message + (cause == null ? "" : " · " + cause.getMessage()));
                        }
                        @Override public void onClosed(DualCameraSession source) {
                            if (session == source) {
                                session = null; open.setEnabled(visible);
                                if (visible && !failed) status.setText("采集已停止，两路资源已释放");
                            }
                            releaseRetiredPreviews();
                        }
                    }, this::consumePair);
        } catch (RuntimeException error) {
            open.setEnabled(true); status.setText("打开失败 · " + error.getMessage());
        }
    }

    /** Integration point: call the face SDK synchronously here, or copy into your own bounded pool.
     * This example only validates the pixel contract; it does not simulate an algorithm result. */
    private void consumePair(FramePair pair) {
        Nv21Planes rgb = pair.rgbPlanes();
        if (!rgb.y().isDirect() || pair.ir.luma().remaining() != pair.ir.width * pair.ir.height)
            throw new IllegalStateException("Unexpected pixel contract");
        skewMicros.set(pair.arrivalSkewNanos / 1000); pairs.incrementAndGet();
    }

    private void stopSession() {
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
        if (request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startSession();
        else status.setText("需要相机权限");
    }
}
