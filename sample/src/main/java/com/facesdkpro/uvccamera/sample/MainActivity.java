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
import android.util.Log;
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

import com.facesdkpro.uvccamera.CameraFailure;
import com.facesdkpro.uvccamera.PreviewTransform;
import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.DualCameraSession.Role;
import com.facesdkpro.uvccamera.UvcCameraManager;

import java.util.ArrayList;
import java.util.List;

/** 双路预览与控制演示：只显示画面，不保存图像。 */
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
                            failPair(getString(R.string.camera_closed_reopen));
                            return;
                        }
                    }
                    long now = android.os.SystemClock.elapsedRealtimeNanos();
                    double seconds = previousTick == 0 ? 1 : (now - previousTick) / 1e9;
                    for (int i = 0; i < 2; i++) {
                        long count = cameras[i] == null ? 0 : cameras[i].receivedFrames();
                        DemoUi.setText(
                                counters[i],
                                getString(
                                        R.string.preview_stats,
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
        int width = getResources().getConfiguration().screenWidthDp,
                height = getResources().getConfiguration().screenHeightDp;
        boolean shortLandscape = width >= 600 && width > height && height < 520;
        LinearLayout screen = ui.column();
        status = shortLandscape ? ui.label(getString(R.string.connect_to_configure), 11, ui.muted)
                : ui.status(getString(R.string.connect_to_configure));
        if (shortLandscape) {
            screen.addView(ui.compactHeader(getString(R.string.dual_video_preview), status,
                    ui.iconButton(getString(R.string.back), R.drawable.ic_back, this::finish)));
        } else {
            screen.addView(
                    ui.header(
                            getString(R.string.dual_video_preview),
                            getString(R.string.preview_subtitle),
                            ui.button(getString(R.string.back), R.drawable.ic_back, false, this::finish)));
            screen.addView(status, ui.margin(-1, -2, 20, 0, 20, 14));
        }
        boolean compact = !shortLandscape && (height < 520 || width < 600);
        boolean sideBySide = width >= 600;
        LinearLayout streams = sideBySide ? ui.row() : ui.column();
        for (int i = 0; i < 2; i++) {
            final int slot = i;
            String role = i == 0 ? "RGB" : "IR";
            int color = i == 0 ? ui.rgb : ui.ir;
            devices[i] = ui.spinner(getString(R.string.device_for_role, role));
            settings[i] = new CaptureSettingsView(this, i == 0 ? Role.RGB : Role.IR);
            if (state != null) settings[i].restore(state.getIntArray("capture_" + i));
            brightness[i] = new SeekBar(this);
            brightness[i].setContentDescription(getString(R.string.brightness_for_role, role));
            brightness[i].setMax(100);
            brightness[i].setEnabled(false);
            brightness[i].setProgressTintList(ColorStateList.valueOf(color));
            brightness[i].setThumbTintList(ColorStateList.valueOf(color));
            brightnessText[i] = ui.label(getString(R.string.brightness_hint), 12, ui.muted);
            brightness[i].setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar bar, int value, boolean user) {
                            if (user && cameras[slot] != null) {
                                brightnessText[slot].setText(getString(R.string.brightness_value, value));
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
            deviceNames[i] = ui.label(getString(R.string.no_device_selected), 12, ui.muted);
            deviceNames[i].setSingleLine();
            deviceNames[i].setEllipsize(TextUtils.TruncateAt.END);
            title.addView(deviceNames[i], new LinearLayout.LayoutParams(0, -2, 1));
            ui.gap(title, 8);
            Button parameters =
                    ui.button(getString(R.string.settings), R.drawable.ic_tune, false, () -> showSettings(slot));
            parameters.setContentDescription(getString(R.string.settings_for_role, role));
            title.addView(parameters);
            if (!shortLandscape) {
                channel.addView(title);
                ui.gap(channel, 10);
            }
            previews[i] = ui.new PreviewCard(i == 0 ? getString(R.string.visible_light) : getString(R.string.infrared), getString(R.string.independent_stream), color);
            if (shortLandscape) {
                title.removeView(parameters);
                previews[i].addAction(parameters);
            }
            channel.addView(
                    previews[i],
                    compact
                            ? new LinearLayout.LayoutParams(-1, ui.dp(260))
                            : new LinearLayout.LayoutParams(-1, 0, 1));
            counters[i] = ui.label(getString(R.string.preview_idle), 12, ui.muted);
            counters[i].setContentDescription(getString(R.string.preview_stats_for_role, role));
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
        footer.setPadding(ui.dp(20), ui.dp(shortLandscape ? 4 : 14), ui.dp(20), ui.dp(shortLandscape ? 4 : 14));
        open = ui.button(getString(R.string.open_both), 0, true, this::requestOpen);
        open.setEnabled(false);
        if (shortLandscape) {
            footer.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        } else if (width >= 900 || width >= 640 && width > height) {
            footer.addView(
                    ui.label(getString(R.string.preview_rotation_hint), 12, ui.muted),
                    new LinearLayout.LayoutParams(0, -2, 1));
            footer.addView(open, new LinearLayout.LayoutParams(ui.dp(200), -2));
        } else footer.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        ui.gap(footer, 10);
        stop = ui.button(getString(R.string.stop), R.drawable.ic_stop, false, this::stopPair);
        stop.setEnabled(false);
        footer.addView(stop, new LinearLayout.LayoutParams(ui.dp(100), -2));
        ui.gap(footer, 10);
        refresh = ui.iconButton(getString(R.string.refresh_devices), R.drawable.ic_refresh, this::refreshDevices);
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
                        // 相机仍在释放时，等 onShutdown 后再销毁旧 Surface/Texture。
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
        // 弹窗复用当前控件；取消时恢复选择，运行中应用时重开两路。
        String role = slot == 0 ? "RGB" : "IR";
        int[] previous = settings[slot].selection();
        String selected = deviceSelectors.selectedNames()[slot];
        LinearLayout panel = ui.column();
        panel.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
        detach(devices[slot]);
        detach(settings[slot]);
        detach(brightness[slot]);
        detach(brightnessText[slot]);
        panel.addView(ui.deviceField(devices[slot]));
        ui.gap(panel, 16);
        panel.addView(settings[slot]);
        ui.gap(panel, 18);
        panel.addView(brightnessText[slot]);
        ui.gap(panel, 6);
        panel.addView(brightness[slot], new LinearLayout.LayoutParams(-1, ui.dp(44)));
        ui.gap(panel, 10);
        panel.addView(ui.label(getString(R.string.preview_settings_hint), 12, ui.muted));
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
                        .setTitle(getString(R.string.capture_settings_for_role, role))
                        .setView(scroll)
                        .setNegativeButton(getString(R.string.cancel), (dialog, which) -> restore.run())
                        .setPositiveButton(
                                getString(R.string.apply_settings),
                                (dialog, which) -> {
                                    updateDeviceNames();
                                    if (hasCameras()) requestOpen();
                                    else ui.setStatus(status, getString(R.string.settings_updated), ui.muted);
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
                        ? settings[slot].config()
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
            // 旧管理器的异步回调不能改动新页面状态。
            return visible && manager == owner && !closing;
        }

        @Override
        public void onAttached(UsbDevice device) {
            if (current()) refreshDevices();
        }

        @Override
        public void onDetached(UsbDevice device) {
            if (!current()) return;
            if (cameraSlot(device) >= 0) failPair(getString(R.string.camera_detached_select));
            else refreshDevices();
        }

        @Override
        public void onOpened(UvcCameraManager.Camera camera) {
            if (!current() || camera.isClosed()) return;
            int slot = cameraSlot(camera.device);
            if (slot < 0 || cameras[slot] != camera) return;
            StreamConfig actual = camera.actualConfig();
            applied[slot] = actual;
            fitPreview(slot);
            opened[slot] = true;
            previews[slot].state(
                    true,
                    actual.width
                            + "×"
                            + actual.height
                            + " · "
                            + actual.fps
                            + " FPS · "
                            + rotations[slot]
                            + "°");
            boolean ready = opened[0] && opened[1];
            ui.setStatus(
                    status,
                    ready ? getString(R.string.dual_preview_active) : getString(R.string.waiting_other_camera),
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
                                                enabled ? getString(R.string.brightness_value, value) : getString(R.string.brightness_unsupported));
                                    }
                                });
                    });
        }

        @Override
        public void onFailure(CameraFailure failure) {
            if (!current()) return;
            int slot = failure.device == null ? -1 : cameraSlot(failure.device);
            if (slot < 0 && failure.device != null) return;
            String message = ui.failureText(failure);
            if (slot >= 0 && cameras[slot].isClosed()) failPair(message);
            else ui.setStatus(status, message, ui.error);
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
                else if (!failed) ui.setStatus(status, getString(R.string.preview_stopped_settings), ui.muted);
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
        DemoUi.setText(open, idle ? getString(R.string.open_both) : getString(R.string.apply_restart));
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
        if (!failed && !hasCameras()) ui.setStatus(status, getString(deviceSelectors.statusText()),
                deviceSelectors.hasPair() ? ui.accent : ui.muted);
    }

    private void updateDeviceNames() {
        for (int i = 0; i < 2; i++) {
            UsbDevice device = deviceSelectors.selected(i);
            DemoUi.setText(
                    deviceNames[i],
                    device == null ? getString(R.string.choose_device_in_settings) : String.valueOf(device.getProductName()));
        }
    }

    private void openPair() {
        if (!visible || manager == null || closing) return;
        UsbDevice rgb = deviceSelectors.selected(0), ir = deviceSelectors.selected(1);
        if (!deviceSelectors.hasPair()) {
            ui.setStatus(status, getString(R.string.distinct_devices_required), ui.error);
            return;
        }
        for (Surface surface : surfaces) {
            if (surface == null || !surface.isValid()) {
                ui.setStatus(status, getString(R.string.preview_retry), ui.ir);
                return;
            }
        }
        failed = false;
        if (hasCameras()) {
            restartPending = true;
            ui.setStatus(status, getString(R.string.applying_settings), ui.ir);
            closeManager();
            return;
        }
        previousTick = 0;
        UsbDevice[] selected = {rgb, ir};
        try {
            ui.setStatus(status, getString(R.string.connecting_permissions), ui.ir);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            for (int i = 0; i < 2; i++) {
                applied[i] = settings[i].config();
                rotations[i] = settings[i].rotationDegrees();
                previousCounts[i] = 0;
                fitPreview(i);
                previews[i].connecting();
                // 仅预览，不注册帧回调或做多余的 NV21 转换。
                cameras[i] = manager.open(selected[i], applied[i], surfaces[i], null);
            }
            updateButtons();
            uiThread.post(tick);
        } catch (RuntimeException error) {
            Log.w("UvcDemo", "Could not open preview", error);
            failPair(getString(R.string.open_failed));
        }
    }

    private void failPair(String message) {
        failed = true;
        restartPending = false;
        ui.setStatus(status, message, ui.error);
        closeManager();
    }

    private void stopPair() {
        failed = false;
        restartPending = false;
        ui.setStatus(status, getString(R.string.stopping_capture), ui.muted);
        closeManager();
    }

    /** 停止接收新操作；onShutdown 后再重开或释放旧预览资源。 */
    private void closeManager() {
        if (closing) return;
        uiThread.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        for (int i = 0; i < 2; i++) {
            cameras[i] = null;
            opened[i] = false;
            brightness[i].setEnabled(false);
            DemoUi.setText(brightnessText[i], getString(R.string.brightness_hint));
            DemoUi.setText(counters[i], getString(R.string.preview_idle));
            previews[i].state(false, getString(R.string.independent_stream));
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
        else ui.setStatus(status, getString(R.string.preview_permission_required), ui.error);
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
