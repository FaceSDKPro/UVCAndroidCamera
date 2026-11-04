package com.facesdkpro.uvccamera.sample;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.facesdkpro.uvccamera.PreviewTransform;
import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.UvcCameraManager;
import com.serenegiant.usb.UVCCamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Independent preview/control screen. Uses preview-only sessions and never saves images. */
public final class MainActivity extends Activity {
    private UvcCameraManager manager;
    private final UvcCameraManager.Camera[] cameras = new UvcCameraManager.Camera[2];
    private final Surface[] surfaces = new Surface[2];
    private final Spinner[] devices = new Spinner[2];
    private final SeekBar[] brightness = new SeekBar[2];
    private final TextView[] brightnessText = new TextView[2],
            deviceNames = new TextView[2],
            counters = new TextView[2];
    private final DemoUi.PreviewCard[] previews = new DemoUi.PreviewCard[2];
    private final Handler uiThread = new Handler(Looper.getMainLooper());
    private final List<Runnable> retiredPreviews = new ArrayList<>();
    private final boolean[] opened = new boolean[2];
    private DeviceSelectors deviceSelectors;
    private ImageButton refresh;
    private boolean visible, closing, restartPending, failed;
    private DemoUi ui;
    private TextView status;
    private Button open, stop;
    private final CaptureSettingsView[] settings = new CaptureSettingsView[2];
    private final StreamConfig[] applied = new StreamConfig[2];
    private final int[] rotations = new int[2];
    private final long[] previousCounts = new long[2];
    private String[] restoredDevices;
    private long previousTick;
    private AlertDialog settingsDialog;
    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    if (!visible || closing || !hasCameras()) return;
                    for (UvcCameraManager.Camera camera : cameras) {
                        if (camera != null && camera.isClosed()) {
                            failPair("摄像头已关闭 · 请重新打开双路预览", null);
                            return;
                        }
                    }
                    long now = android.os.SystemClock.elapsedRealtimeNanos();
                    double seconds = previousTick == 0 ? 1 : (now - previousTick) / 1e9;
                    for (int i = 0; i < 2; i++) {
                        long count = cameras[i] == null ? 0 : cameras[i].receivedFrames();
                        DemoUi.setText(
                                counters[i],
                                String.format(
                                        Locale.ROOT,
                                        "%.1f FPS   ·   已收到 %d 帧",
                                        Math.max(0, count - previousCounts[i]) / seconds,
                                        count));
                        previousCounts[i] = count;
                    }
                    previousTick = now;
                    uiThread.postDelayed(this, 1000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new DemoUi(this);
        LinearLayout screen = ui.column();
        screen.addView(
                ui.header(
                        "双路视频预览",
                        "RGB / IR · 独立设备控制",
                        ui.button("返回", R.drawable.ic_back, false, this::finish)));
        status = ui.status("设备连接后可分别调整预览参数");
        screen.addView(status, ui.margin(-1, -2, 20, 0, 20, 14));
        int width = getResources().getConfiguration().screenWidthDp,
                height = getResources().getConfiguration().screenHeightDp;
        boolean compact = height < 520 || width < 600;
        boolean sideBySide = width >= 600;
        LinearLayout streams = sideBySide ? ui.row() : ui.column();
        for (int i = 0; i < 2; i++) {
            final int slot = i;
            String role = i == 0 ? "RGB" : "IR";
            int color = i == 0 ? ui.rgb : ui.ir;
            devices[i] = ui.spinner(role + " 设备");
            settings[i] = new CaptureSettingsView(this, role);
            if (state != null) settings[i].restore(state.getIntArray("capture_" + i));
            brightness[i] = new SeekBar(this);
            brightness[i].setContentDescription(role + " 设备亮度");
            brightness[i].setMax(100);
            brightness[i].setEnabled(false);
            brightness[i].setProgressTintList(ColorStateList.valueOf(color));
            brightness[i].setThumbTintList(ColorStateList.valueOf(color));
            brightnessText[i] = ui.label("打开摄像头后可调节设备亮度", 12, ui.muted);
            brightness[i].setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar bar, int value, boolean user) {
                            if (user && cameras[slot] != null) {
                                brightnessText[slot].setText("设备亮度 · " + value + "%");
                                cameras[slot].withControls(
                                        control -> control.setBrightnessPercent(value));
                            }
                        }

                        public void onStartTrackingTouch(SeekBar bar) {}

                        public void onStopTrackingTouch(SeekBar bar) {}
                    });
            LinearLayout channel = ui.column();
            LinearLayout title = ui.row();
            TextView roleMark = ui.strong(role, 13, color);
            roleMark.setPadding(ui.dp(9), ui.dp(6), ui.dp(9), ui.dp(6));
            roleMark.setBackground(ui.shape(ui.tint(color, 20), 8, 0));
            title.addView(roleMark);
            ui.gap(title, 10);
            deviceNames[i] = ui.label("未选择设备", 12, ui.muted);
            deviceNames[i].setSingleLine();
            deviceNames[i].setEllipsize(TextUtils.TruncateAt.END);
            title.addView(deviceNames[i], new LinearLayout.LayoutParams(0, -2, 1));
            ui.gap(title, 8);
            Button parameters =
                    ui.button("参数", R.drawable.ic_tune, false, () -> showSettings(slot));
            parameters.setContentDescription(role + " 参数");
            title.addView(parameters);
            channel.addView(title);
            ui.gap(channel, 10);
            previews[i] = ui.new PreviewCard(i == 0 ? "RGB 可见光" : "IR 近红外", "独立 UVC 视频流", color);
            channel.addView(
                    previews[i],
                    compact
                            ? new LinearLayout.LayoutParams(-1, ui.dp(260))
                            : new LinearLayout.LayoutParams(-1, 0, 1));
            counters[i] = ui.label("未打开 · 等待采集", 12, ui.muted);
            counters[i].setContentDescription(role + " 预览统计");
            counters[i].setPadding(ui.dp(4), ui.dp(10), 0, 0);
            channel.addView(counters[i]);
            bindSurface(i);
            if (sideBySide) {
                if (i > 0) ui.gap(streams, 16);
                streams.addView(channel, new LinearLayout.LayoutParams(0, compact ? -2 : -1, 1));
            } else {
                if (i > 0) ui.gap(streams, 12);
                streams.addView(
                        channel,
                        new LinearLayout.LayoutParams(-1, compact ? -2 : 0, compact ? 0 : 1));
            }
        }
        deviceSelectors = new DeviceSelectors(ui, devices);
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(-1, 0, 1);
        content.setMargins(ui.dp(20), 0, ui.dp(20), 0);
        if (compact) {
            ScrollView page = new ScrollView(this);
            page.addView(streams);
            screen.addView(page, content);
        } else screen.addView(streams, content);
        LinearLayout footer = ui.row();
        footer.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
        open = ui.button("打开双路", 0, true, this::requestOpen);
        open.setEnabled(false);
        if (width >= 900 || width >= 640 && width > height) {
            footer.addView(
                    ui.label("两路预览独立旋转 · 参数按设备能力设置", 12, ui.muted),
                    new LinearLayout.LayoutParams(0, -2, 1));
            footer.addView(open, new LinearLayout.LayoutParams(ui.dp(200), -2));
        } else footer.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        ui.gap(footer, 10);
        stop = ui.button("停止", R.drawable.ic_stop, false, this::stopPair);
        stop.setEnabled(false);
        footer.addView(stop, new LinearLayout.LayoutParams(ui.dp(100), -2));
        ui.gap(footer, 10);
        refresh = ui.iconButton("刷新设备", R.drawable.ic_refresh, this::refreshDevices);
        footer.addView(refresh, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        screen.addView(footer);
        if (state != null) restoredDevices = state.getStringArray("devices");
        ui.install(this, screen);
    }

    private void bindSurface(int slot) {
        previews[slot].texture.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(
                            SurfaceTexture texture, int width, int height) {
                        surfaces[slot] = new Surface(texture);
                        fitPreview(slot);
                    }

                    public void onSurfaceTextureSizeChanged(
                            SurfaceTexture texture, int width, int height) {
                        fitPreview(slot);
                    }

                    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                        Surface retired = surfaces[slot];
                        surfaces[slot] = null;
                        Runnable release =
                                () -> {
                                    if (retired != null) retired.release();
                                    texture.release();
                                };
                        if (manager == null) release.run();
                        else {
                            retiredPreviews.add(release);
                            restartPending = false;
                            closeManager();
                        }
                        return false;
                    }

                    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
                });
    }

    private void showSettings(int slot) {
        if (!visible || closing || settingsDialog != null) return;
        String role = slot == 0 ? "RGB" : "IR";
        int[] previous = settings[slot].selection();
        String selected = deviceSelectors.selectedNames()[slot];
        LinearLayout panel = ui.column();
        panel.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
        detach(devices[slot]);
        detach(settings[slot]);
        detach(brightness[slot]);
        detach(brightnessText[slot]);
        panel.addView(ui.field("USB 设备", devices[slot]));
        ui.gap(panel, 16);
        panel.addView(settings[slot]);
        ui.gap(panel, 18);
        panel.addView(brightnessText[slot]);
        ui.gap(panel, 6);
        panel.addView(brightness[slot], new LinearLayout.LayoutParams(-1, ui.dp(44)));
        ui.gap(panel, 10);
        panel.addView(ui.label("不支持的采集组合会明确报错。应用后，正在运行的两路预览将重新打开。", 12, ui.muted));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        Runnable restore =
                () -> {
                    settings[slot].restore(previous);
                    deviceSelectors.restore(slot, selected);
                    updateDeviceNames();
                };
        settingsDialog =
                new AlertDialog.Builder(this)
                        .setTitle(role + " 采集设置")
                        .setView(scroll)
                        .setNegativeButton("取消", (dialog, which) -> restore.run())
                        .setPositiveButton(
                                "应用设置",
                                (dialog, which) -> {
                                    updateDeviceNames();
                                    if (hasCameras()) requestOpen();
                                    else ui.setStatus(status, "参数已更新 · 点击打开双路开始预览", ui.muted);
                                })
                        .create();
        settingsDialog.setOnCancelListener(dialog -> restore.run());
        settingsDialog.setOnDismissListener(
                dialog -> {
                    detach(devices[slot]);
                    detach(settings[slot]);
                    detach(brightness[slot]);
                    detach(brightnessText[slot]);
                    settingsDialog = null;
                });
        settingsDialog.show();
        settingsDialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 20, ui.border));
        settingsDialog
                .getWindow()
                .setLayout(
                        ui.dp(Math.min(480, getResources().getConfiguration().screenWidthDp - 40)),
                        -2);
    }

    private void detach(android.view.View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private void requestOpen() {
        if (!visible || closing) return;
        if (checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED)
            openPair();
        else requestPermissions(new String[] {"android.permission.CAMERA"}, 1);
    }

    private void fitPreview(int slot) {
        TextureView texture = previews[slot].texture;
        if (texture.getWidth() == 0 || texture.getHeight() == 0) return;
        StreamConfig config =
                applied[slot] == null
                        ? settings[slot].config(UVCCamera.PIXEL_FORMAT_NV21)
                        : applied[slot];
        texture.setTransform(
                PreviewTransform.fitCenter(
                        texture.getWidth(),
                        texture.getHeight(),
                        config.width,
                        config.height,
                        rotations[slot]));
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        createManager();
        updateButtons();
    }

    private void createManager() {
        if (!visible || manager != null) return;
        CameraEvents events = new CameraEvents();
        manager = new UvcCameraManager(this, events);
        events.owner = manager;
        refreshDevices();
        updateButtons();
    }

    private final class CameraEvents implements UvcCameraManager.Listener {
        private UvcCameraManager owner;

        private boolean current() {
            return visible && manager == owner && !closing;
        }

        @Override
        public void onAttached(UsbDevice device) {
            if (current()) refreshDevices();
        }

        @Override
        public void onDetached(UsbDevice device) {
            if (!current()) return;
            if (cameraSlot(device) >= 0) failPair("摄像头已断开 · 请重新选择设备", null);
            else refreshDevices();
        }

        @Override
        public void onOpened(UvcCameraManager.Camera camera) {
            if (!current() || camera.isClosed()) return;
            int slot = cameraSlot(camera.device);
            if (slot < 0 || cameras[slot] != camera) return;
            opened[slot] = true;
            previews[slot].state(
                    true,
                    camera.config.width
                            + "×"
                            + camera.config.height
                            + " · "
                            + camera.config.fps
                            + " FPS · "
                            + rotations[slot]
                            + "°");
            boolean ready = opened[0] && opened[1];
            ui.setStatus(
                    status,
                    ready ? "预览运行中 · 两路设备独立采集" : "正在连接 · 等待另一路摄像头",
                    ready ? ui.accent : ui.ir);
            updateButtons();
            camera.withControls(
                    control -> {
                        boolean enabled = control.isBrightnessEnable();
                        int value = enabled ? control.getBrightnessPercent() : 0;
                        runOnUiThread(
                                () -> {
                                    if (current()
                                            && cameras[slot] == camera
                                            && !camera.isClosed()) {
                                        brightness[slot].setEnabled(enabled);
                                        brightness[slot].setProgress(value);
                                        DemoUi.setText(
                                                brightnessText[slot],
                                                enabled ? "设备亮度 · " + value + "%" : "当前设备不支持亮度调节");
                                    }
                                });
                    });
        }

        @Override
        public void onError(UsbDevice device, String message, Throwable cause) {
            if (!current()) return;
            int slot = cameraSlot(device);
            if (slot < 0) return;
            if (cameras[slot].isClosed()) failPair(message, cause);
            else ui.setStatus(status, errorText(message, cause), ui.error);
        }

        @Override
        public void onShutdown() {
            if (manager != owner) return;
            manager = null;
            closing = false;
            releaseRetiredPreviews();
            boolean restart = restartPending && visible;
            restartPending = false;
            if (visible) {
                createManager();
                if (restart) openPair();
                else if (!failed) ui.setStatus(status, "预览已停止 · 可分别调整 RGB / IR 参数", ui.muted);
            }
        }
    }

    private int cameraSlot(UsbDevice device) {
        for (int i = 0; i < cameras.length; i++) {
            if (cameras[i] != null
                    && cameras[i].device.getDeviceName().equals(device.getDeviceName())) return i;
        }
        return -1;
    }

    private boolean hasCameras() {
        return cameras[0] != null || cameras[1] != null;
    }

    private void updateButtons() {
        boolean idle = !hasCameras();
        open.setEnabled(visible && manager != null && !closing && (idle || opened[0] && opened[1]));
        DemoUi.setText(open, idle ? "打开双路" : "应用并重启");
        stop.setEnabled(visible && !closing && !idle);
        refresh.setEnabled(visible && manager != null && !closing && idle);
    }

    private String[] selectedDeviceNames() {
        return deviceSelectors.selectedNames();
    }

    private void refreshDevices() {
        if (manager == null || closing) return;
        deviceSelectors.refresh(manager.devices(), restoredDevices);
        restoredDevices = null;
        updateDeviceNames();
    }

    private void updateDeviceNames() {
        for (int i = 0; i < 2; i++) {
            UsbDevice device = deviceSelectors.selected(i);
            DemoUi.setText(
                    deviceNames[i],
                    device == null ? "在参数面板选择设备" : String.valueOf(device.getProductName()));
        }
    }

    private void openPair() {
        if (!visible || manager == null || closing) return;
        UsbDevice rgb = deviceSelectors.selected(0), ir = deviceSelectors.selected(1);
        if (rgb == null || ir == null || rgb.getDeviceName().equals(ir.getDeviceName())) {
            ui.setStatus(status, "RGB 与 IR 必须选择不同设备", ui.error);
            return;
        }
        for (Surface surface : surfaces) {
            if (surface == null || !surface.isValid()) {
                ui.setStatus(status, "预览正在准备，请稍后重试", ui.ir);
                return;
            }
        }
        failed = false;
        if (hasCameras()) {
            restartPending = true;
            ui.setStatus(status, "正在切换 · 释放旧会话后应用新设置", ui.ir);
            closeManager();
            return;
        }
        previousTick = 0;
        UsbDevice[] selected = {rgb, ir};
        try {
            ui.setStatus(status, "正在连接 · 请允许相机与 USB 设备访问", ui.ir);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            for (int i = 0; i < 2; i++) {
                applied[i] = settings[i].config(UVCCamera.PIXEL_FORMAT_NV21);
                rotations[i] = settings[i].rotationDegrees();
                previousCounts[i] = 0;
                fitPreview(i);
                previews[i].connecting();
                // Preview-only: no frame listener or unused NV21 conversion.
                cameras[i] = manager.open(selected[i], applied[i], surfaces[i], null);
            }
            updateButtons();
            uiThread.post(tick);
        } catch (RuntimeException error) {
            failPair("打开失败", error);
        }
    }

    private String errorText(String message, Throwable cause) {
        return message + (cause == null ? "" : " · " + cause.getMessage());
    }

    private void failPair(String message, Throwable cause) {
        failed = true;
        restartPending = false;
        ui.setStatus(status, errorText(message, cause), ui.error);
        closeManager();
    }

    private void stopPair() {
        failed = false;
        restartPending = false;
        ui.setStatus(status, "正在停止采集…", ui.muted);
        closeManager();
    }

    /**
     * The manager shutdown callback drains both native sessions before reuse or Surface release.
     */
    private void closeManager() {
        if (closing) return;
        uiThread.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        for (int i = 0; i < 2; i++) {
            cameras[i] = null;
            opened[i] = false;
            brightness[i].setEnabled(false);
            DemoUi.setText(brightnessText[i], "打开摄像头后可调节设备亮度");
            DemoUi.setText(counters[i], "未打开 · 等待采集");
            previews[i].state(false, "独立 UVC 视频流");
        }
        closing = manager != null;
        updateButtons();
        if (manager != null) manager.close();
        else releaseRetiredPreviews();
    }

    private void releaseRetiredPreviews() {
        for (Runnable release : retiredPreviews) release.run();
        retiredPreviews.clear();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] values) {
        super.onRequestPermissionsResult(request, names, values);
        if (request != 1 || !visible) return;
        if (values.length > 0 && values[0] == PackageManager.PERMISSION_GRANTED) openPair();
        else ui.setStatus(status, "需要相机权限才能开始预览", ui.error);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (settingsDialog != null) settingsDialog.cancel();
        for (int i = 0; i < 2; i++) out.putIntArray("capture_" + i, settings[i].selection());
        out.putStringArray("devices", selectedDeviceNames());
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStop() {
        visible = false;
        restartPending = false;
        if (settingsDialog != null) settingsDialog.cancel();
        closeManager();
        super.onStop();
    }
}
