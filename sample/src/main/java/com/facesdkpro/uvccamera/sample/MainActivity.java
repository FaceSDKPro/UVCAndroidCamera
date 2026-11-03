package com.facesdkpro.uvccamera.sample;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.UvcCameraManager;
import com.serenegiant.usb.UVCCamera;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Two independent USB cameras. The sample previews/counts pixels and never saves camera images. */
public final class MainActivity extends Activity {
    private UvcCameraManager manager;
    private final UvcCameraManager.Camera[] cameras = new UvcCameraManager.Camera[2];
    private final Surface[] surfaces = new Surface[2];
    private final Spinner[] devices = new Spinner[2];
    private final SeekBar[] brightness = new SeekBar[2];
    private final TextView[] counters = new TextView[2];
    private final TextureView[] previews = new TextureView[2];
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<UsbDevice> available = new ArrayList<>();
    private TextView status;
    private Spinner resolution;
    private final Runnable tick = new Runnable() {
        public void run() {
            for (int i = 0; i < 2; i++) counters[i].setText(cameras[i] == null ? "未打开" :
                    (cameras[i].isClosed() ? "已关闭" : "已收到 " + cameras[i].receivedFrames() + " 帧"));
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(128);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20, 20, 20, 20);
        TextView title = new TextView(this); title.setText("UVC 双目采集"); title.setTextSize(24); root.addView(title);
        status = new TextView(this); status.setText("分别选择 RGB 和 IR 摄像头"); root.addView(status);
        resolution = new Spinner(this);
        resolution.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"640 × 480 · 30 FPS", "1280 × 720 · 30 FPS", "1920 × 1080 · 30 FPS"})); root.addView(resolution);
        LinearLayout actions = new LinearLayout(this);
        button(actions, "打开双路", () -> { if (checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED) openPair(); else requestPermissions(new String[]{"android.permission.CAMERA"}, 1); });
        button(actions, "停止", this::stopPair); button(actions, "刷新设备", this::refreshDevices); root.addView(actions);
        LinearLayout previews = new LinearLayout(this);
        for (int i = 0; i < 2; i++) {
            final int slot = i;
            LinearLayout pane = new LinearLayout(this); pane.setOrientation(LinearLayout.VERTICAL); pane.setPadding(8, 8, 8, 8);
            TextView role = new TextView(this); role.setText(i == 0 ? "RGB" : "IR"); role.setTextSize(18); pane.addView(role);
            devices[i] = new Spinner(this); pane.addView(devices[i]);
            TextureView view = new TextureView(this); view.setOpaque(false); this.previews[i] = view;
            FrameLayout previewBox = new FrameLayout(this); previewBox.setBackgroundColor(0xff000000);
            previewBox.addView(view, new FrameLayout.LayoutParams(-1, -1));
            pane.addView(previewBox, new LinearLayout.LayoutParams(-1, 0, 1));
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) { surfaces[slot] = new Surface(texture); fitPreview(slot); }
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) { fitPreview(slot); }
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    if (cameras[slot] != null) cameras[slot].close();
                    if (surfaces[slot] != null) surfaces[slot].release(); surfaces[slot] = null; return true;
                }
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
            });
            counters[i] = new TextView(this); pane.addView(counters[i]);
            TextView label = new TextView(this); label.setText("亮度（设备支持时可调）"); pane.addView(label);
            brightness[i] = new SeekBar(this); brightness[i].setMax(100); brightness[i].setEnabled(false); pane.addView(brightness[i]);
            brightness[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean user) {
                    if (user && cameras[slot] != null) cameras[slot].withControls(control -> control.setBrightnessPercent(value));
                }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            });
            previews.addView(pane, new LinearLayout.LayoutParams(0, -1, 1));
        }
        root.addView(previews, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
    }

    private void button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void fitPreview(int slot) {
        TextureView view = previews[slot]; if (view.getWidth() == 0 || view.getHeight() == 0) return;
        int mode = resolution.getSelectedItemPosition();
        float ratio = mode == 0 ? 4f / 3f : 16f / 9f;
        float actual = (float)view.getWidth() / view.getHeight();
        Matrix matrix = new Matrix();
        matrix.setScale(actual > ratio ? ratio / actual : 1f, actual < ratio ? actual / ratio : 1f,
                view.getWidth() / 2f, view.getHeight() / 2f);
        view.setTransform(matrix);
    }

    @Override protected void onStart() {
        super.onStart();
        manager = new UvcCameraManager(this, new UvcCameraManager.Listener() {
            public void onAttached(UsbDevice device) { refreshDevices(); }
            public void onDetached(UsbDevice device) { status.setText("摄像头已拔出，请重新选择并打开"); refreshDevices(); }
            public void onOpened(UvcCameraManager.Camera camera) {
                status.setText("正在采集 · " + camera.config.width + " × " + camera.config.height);
                for (int i = 0; i < 2; i++) if (cameras[i] == camera) {
                    final int slot = i;
                    camera.withControls(control -> {
                        boolean enabled = control.isBrightnessEnable();
                        int value = enabled ? control.getBrightnessPercent() : 0;
                        runOnUiThread(() -> { if (cameras[slot] == camera && !camera.isClosed()) {
                            brightness[slot].setEnabled(enabled); brightness[slot].setProgress(value);
                        } });
                    });
                }
            }
            public void onError(UsbDevice device, String message, Throwable cause) {
                status.setText(message + (cause == null ? "" : " · " + cause.getMessage()));
            }
        });
        refreshDevices(); ui.post(tick);
    }

    private void refreshDevices() {
        if (manager == null) return;
        String[] selected = new String[2];
        for (int i = 0; i < 2; i++) {
            int position = devices[i].getSelectedItemPosition();
            if (position >= 0 && position < available.size()) selected[i] = available.get(position).getDeviceName();
        }
        available = manager.devices(); List<String> labels = new ArrayList<>();
        for (UsbDevice device : available) labels.add(String.format(Locale.ROOT, "%s · %04X:%04X",
                device.getProductName(), device.getVendorId(), device.getProductId()));
        if (labels.isEmpty()) labels.add("未连接 UVC 摄像头");
        for (int i = 0; i < 2; i++) {
            devices[i].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            int choice = Math.min(i, Math.max(0, available.size() - 1));
            for (int j = 0; j < available.size(); j++) {
                UsbDevice device = available.get(j);
                String name = String.valueOf(device.getProductName()).toLowerCase(Locale.ROOT);
                if (device.getDeviceName().equals(selected[i]) || selected[i] == null && name.contains(i == 0 ? "rgb" : "ir")) { choice = j; break; }
            }
            devices[i].setSelection(choice);
        }
    }

    private void openPair() {
        if (manager == null || available.size() < 2) { status.setText("请连接两个独立 UVC 摄像头"); return; }
        int a = devices[0].getSelectedItemPosition(), b = devices[1].getSelectedItemPosition();
        if (a == b) { status.setText("RGB 与 IR 必须选择不同设备"); return; }
        if (surfaces[0] == null || surfaces[1] == null) { status.setText("预览尚未就绪"); return; }
        stopPair(); int mode = resolution.getSelectedItemPosition();
        int width = mode == 0 ? 640 : mode == 1 ? 1280 : 1920, height = mode == 0 ? 480 : mode == 1 ? 720 : 1080;
        StreamConfig config = new StreamConfig(width, height, 30, UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.PIXEL_FORMAT_NV21, 0);
        int[] chosen = {a, b};
        // This screen displays previews and counters only; no unused NV21 pixel conversion.
        // Supply a FrameListener when integrating an actual analysis consumer (see README).
        for (int i = 0; i < 2; i++) {
            fitPreview(i);
            cameras[i] = manager.open(available.get(chosen[i]), config, surfaces[i], null);
        }
        status.setText("正在请求设备权限…");
    }

    private void stopPair() {
        for (int i = 0; i < 2; i++) { if (cameras[i] != null) cameras[i].close(); cameras[i] = null; brightness[i].setEnabled(false); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] values) {
        super.onRequestPermissionsResult(request, names, values);
        if (values.length > 0 && values[0] == PackageManager.PERMISSION_GRANTED) openPair(); else status.setText("需要相机权限");
    }
    @Override protected void onStop() {
        ui.removeCallbacks(tick); stopPair(); if (manager != null) manager.close(); manager = null; super.onStop();
    }
}
