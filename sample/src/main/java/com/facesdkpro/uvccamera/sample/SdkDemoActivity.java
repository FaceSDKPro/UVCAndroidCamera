package com.facesdkpro.uvccamera.sample;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.facesdkpro.uvccamera.CameraFailure;
import com.facesdkpro.uvccamera.DualCameraConfig;
import com.facesdkpro.uvccamera.DualCameraSession;
import com.facesdkpro.uvccamera.FramePair;
import com.facesdkpro.uvccamera.Nv21Planes;
import com.facesdkpro.uvccamera.PairingDiagnostics;
import com.facesdkpro.uvccamera.PreviewTransform;
import com.facesdkpro.uvccamera.StreamConfig;
import com.serenegiant.usb.UVCCamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** 独立 SDK 接入演示：消费双目帧，不保存图像或生成识别结果。 */
public final class SdkDemoActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Spinner[] selectors = new Spinner[2];
    private final CaptureSettingsView[] settings = new CaptureSettingsView[2];
    private final DemoUi.Metric[] metrics = new DemoUi.Metric[4];
    private Spinner pairRate;
    private final AtomicLong pairs = new AtomicLong(), skewMicros = new AtomicLong();
    private final AtomicLong pairWidth = new AtomicLong(),
            pairHeight = new AtomicLong(),
            irWidth = new AtomicLong(),
            irHeight = new AtomicLong();
    private final List<Runnable> retiredPreviews = new ArrayList<>();
    private DeviceSelectors deviceSelectors;
    private static final int[] PAIR_RATES = {5, 10, 15, 30};
    private DemoUi ui;
    private DemoUi.PreviewCard previewCard;
    private TextView status;
    private TextView pairingDetails;
    private Button open, stop;
    private ImageButton refresh;
    private TextureView preview;
    private Surface surface;
    private DualCameraSession session;
    private DualCameraConfig appliedConfig = DualCameraConfig.defaults();
    private boolean visible, failed, restartPending;
    private String[] restoredDevices;
    private long previousRgb, previousIr, previousPairs, previousTick;
    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    DualCameraSession current = session;
                    long now = android.os.SystemClock.elapsedRealtimeNanos();
                    long rgb = current == null ? 0 : current.receivedFrames(DualCameraSession.Role.RGB);
                    long ir = current == null ? 0 : current.receivedFrames(DualCameraSession.Role.IR);
                    long n = pairs.get();
                    double seconds = previousTick == 0 ? 1 : (now - previousTick) / 1e9;
                    if (current != null && !current.isClosed()) {
                        PairingDiagnostics pairing = current.pairingDiagnostics();
                        metrics[0].set(
                                number(Math.max(0, rgb - previousRgb) / seconds),
                                n == 0 ? getString(R.string.frame_count, rgb)
                                        : getString(R.string.frame_count_size, rgb, pairWidth.get(), pairHeight.get()));
                        metrics[1].set(
                                number(Math.max(0, ir - previousIr) / seconds),
                                n == 0 ? getString(R.string.frame_count, ir)
                                        : getString(R.string.frame_count_size, ir, irWidth.get(), irHeight.get()));
                        metrics[2].set(
                                number(Math.max(0, n - previousPairs) / seconds),
                                getString(R.string.pair_counts, n, pairing.discardedFrames()));
                        metrics[3].set(n == 0 ? "—" : number(skewMicros.get() / 1000.0), getString(R.string.callback_skew));
                        DemoUi.setText(pairingDetails,
                                getString(R.string.pairing_drops, pairing.replacedRgb, pairing.replacedIr,
                                        pairing.outOfOrderRgb, pairing.outOfOrderIr, pairing.expiredRgb,
                                        pairing.expiredIr, pairing.skewRgb, pairing.skewIr));
                        if (n > 0) DemoUi.setText(previewCard.meta,
                                getString(R.string.capture_output, pairWidth.get(), pairHeight.get(),
                                        appliedConfig.rgbRotationDegrees));
                    } else clearMetrics();
                    previousRgb = rgb;
                    previousIr = ir;
                    previousPairs = n;
                    previousTick = now;
                    if (visible && session != null && !session.isClosed())
                        main.postDelayed(this, 1000);
                }
            };

    private String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new DemoUi(this);
        int width = getResources().getConfiguration().screenWidthDp,
                height = getResources().getConfiguration().screenHeightDp;
        boolean wide = height >= 520 && (width >= 900 || width >= 640 && width > height);
        boolean compact = !wide && height < 900;
        LinearLayout screen = ui.column();
        Button diagnostic =
                ui.button(
                        getString(R.string.dual_preview),
                        R.drawable.ic_grid,
                        false,
                        () -> startActivity(new Intent(this, MainActivity.class)));
        diagnostic.setContentDescription(getString(R.string.dual_preview_accessibility));
        screen.addView(ui.header(getString(R.string.app_name), getString(R.string.capture_subtitle), diagnostic));
        status = ui.status(getString(R.string.select_two_cameras));
        screen.addView(status, ui.margin(-1, -2, 20, 0, 20, 14));
        LinearLayout controls = buildControls(!compact);
        deviceSelectors = new DeviceSelectors(ui, selectors);
        previewCard = ui.new PreviewCard(getString(R.string.rgb_preview), getString(R.string.independent_gray8), ui.rgb);
        preview = previewCard.texture;
        bindPreview();
        LinearLayout stats = buildMetrics(wide);
        LinearLayout body = wide ? ui.row() : ui.column();
        if (wide) {
            LinearLayout dashboard = ui.column();
            dashboard.addView(previewCard, new LinearLayout.LayoutParams(-1, 0, 1));
            ui.gap(dashboard, 12);
            dashboard.addView(stats);
            body.addView(dashboard, new LinearLayout.LayoutParams(0, -1, 1));
            ui.gap(body, 16);
            body.addView(
                    controls,
                    new LinearLayout.LayoutParams(
                            ui.dp(Math.min(380, Math.max(310, width / 3))), -1));
        } else {
            body.addView(
                    previewCard,
                    new LinearLayout.LayoutParams(
                            -1, ui.dp(Math.min(300, Math.max(170, height / 4)))));
            ui.gap(body, 10);
            body.addView(stats);
            ui.gap(body, 12);
            body.addView(
                    controls,
                    compact
                            ? new LinearLayout.LayoutParams(-1, -2)
                            : new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.setMargins(ui.dp(20), 0, ui.dp(20), 0);
        if (compact) {
            ScrollView page = new ScrollView(this);
            page.setFillViewport(true);
            page.addView(body);
            screen.addView(page, bodyParams);
        } else screen.addView(body, bodyParams);
        LinearLayout footer = ui.row();
        footer.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
        open =
                ui.button(
                        getString(R.string.start_capture),
                        0,
                        true,
                        () -> {
                            if (checkSelfPermission(Manifest.permission.CAMERA)
                                    == PackageManager.PERMISSION_GRANTED) applySettings();
                            else requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
                        });
        stop = ui.button(getString(R.string.stop), R.drawable.ic_stop, false, this::stopSession);
        stop.setEnabled(false);
        if (wide) {
            LinearLayout caption = ui.column();
            caption.addView(ui.strong("RGB NV21  +  IR GRAY8", 13, ui.text));
            ui.gap(caption, 4);
            caption.addView(ui.label(getString(R.string.restart_to_apply), 12, ui.muted));
            footer.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
            footer.addView(open, new LinearLayout.LayoutParams(ui.dp(200), -2));
        } else footer.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        ui.gap(footer, 12);
        footer.addView(stop, new LinearLayout.LayoutParams(ui.dp(112), -2));
        screen.addView(footer);
        if (state != null) {
            for (int i = 0; i < 2; i++) settings[i].restore(state.getIntArray("capture_" + i));
            int rate = state.getInt("pair_rate", 2);
            if (rate >= 0 && rate < pairRate.getCount()) pairRate.setSelection(rate);
            restoredDevices = state.getStringArray("devices");
        }
        ui.install(this, screen);
    }

    private LinearLayout buildControls(boolean scrollSettings) {
        LinearLayout panel = ui.column();
        LinearLayout heading = ui.row();
        heading.addView(ui.strong(getString(R.string.capture_settings), 18, ui.text),
                new LinearLayout.LayoutParams(0, -2, 1));
        refresh = ui.iconButton(getString(R.string.refresh_devices), R.drawable.ic_refresh, this::refreshDevices);
        heading.addView(refresh, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        panel.addView(heading);
        ui.gap(panel, 10);
        LinearLayout fields = ui.column();
        for (int i = 0; i < 2; i++) {
            String role = i == 0 ? "RGB" : "IR";
            int color = i == 0 ? ui.rgb : ui.ir;
            LinearLayout card = ui.card(), head = ui.row();
            TextView mark = ui.strong(role, 12, color);
            mark.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
            mark.setBackground(ui.shape(ui.tint(color, 22), 7, 0));
            head.addView(mark);
            ui.gap(head, 10);
            head.addView(
                    ui.strong(i == 0 ? getString(R.string.rgb_camera) : getString(R.string.ir_camera), 15, ui.text),
                    new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(ui.label(i == 0 ? "NV21" : "GRAY8", 11, ui.muted));
            card.addView(head);
            ui.gap(card, 14);
            selectors[i] = ui.spinner(getString(R.string.device_for_role, role));
            card.addView(ui.deviceField(selectors[i]));
            ui.gap(card, 14);
            settings[i] = new CaptureSettingsView(this, role);
            card.addView(settings[i]);
            fields.addView(card);
            ui.gap(fields, 12);
        }
        LinearLayout pairing = ui.card();
        pairRate = ui.spinner(getString(R.string.pair_rate_limit));
        String[] rates = new String[PAIR_RATES.length];
        for (int i = 0; i < rates.length; i++)
            rates[i] = getString(R.string.pair_rate_option, PAIR_RATES[i]);
        ui.items(pairRate, rates);
        pairRate.setSelection(2);
        pairing.addView(ui.field(getString(R.string.pair_rate_limit), pairRate));
        ui.gap(pairing, 8);
        pairing.addView(ui.label(getString(R.string.pair_rate_note), 12, ui.muted));
        fields.addView(pairing);
        ui.gap(fields, 12);
        TextView hint = ui.label(getString(R.string.capture_settings_hint), 12, ui.muted);
        hint.setLineSpacing(ui.dp(3), 1);
        fields.addView(hint);
        ui.gap(fields, 8);
        fields.addView(ui.button(getString(R.string.privacy_policy), 0, false, this::showPrivacyPolicy));
        if (scrollSettings) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.addView(fields);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        } else panel.addView(fields);
        return panel;
    }

    private void showPrivacyPolicy() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy)
                .setMessage(R.string.privacy_summary)
                .setPositiveButton(android.R.string.ok, null);
        String url = BuildConfig.PRIVACY_POLICY_URL;
        if (!url.isEmpty()) {
            dialog.setNeutralButton(R.string.privacy_open_online, (ignored, button) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException error) {
                    Toast.makeText(this, R.string.privacy_browser_unavailable, Toast.LENGTH_SHORT).show();
                }
            });
        }
        dialog.show();
    }

    private LinearLayout buildMetrics(boolean wide) {
        metrics[0] = ui.new Metric(getString(R.string.rgb_fps), "FPS", ui.rgb);
        metrics[1] = ui.new Metric(getString(R.string.ir_fps), "FPS", ui.ir);
        metrics[2] = ui.new Metric(getString(R.string.pair_rate), getString(R.string.pairs_per_second), ui.accent);
        metrics[3] = ui.new Metric(getString(R.string.arrival_skew), "ms", ui.text);
        LinearLayout group = ui.column();
        int columns = wide ? 4 : 2;
        for (int start = 0; start < 4; start += columns) {
            LinearLayout row = ui.row();
            for (int i = start; i < start + columns; i++) {
                if (i > start) ui.gap(row, 8);
                row.addView(metrics[i], new LinearLayout.LayoutParams(0, -2, 1));
            }
            group.addView(row);
            if (start + columns < 4) ui.gap(group, 8);
        }
        pairingDetails = ui.label(getString(R.string.pairing_waiting), 11, ui.muted);
        group.addView(pairingDetails, ui.margin(-1, -2, 4, 8, 4, 0));
        return group;
    }

    private void clearMetrics() {
        for (DemoUi.Metric metric : metrics) metric.set("—", getString(R.string.waiting_for_capture));
        if (pairingDetails != null) DemoUi.setText(pairingDetails, getString(R.string.pairing_waiting));
    }

    private void bindPreview() {
        preview.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(
                            SurfaceTexture texture, int width, int height) {
                        surface = new Surface(texture);
                        fitPreview();
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(
                            SurfaceTexture texture, int width, int height) {
                        fitPreview();
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                        Surface retired = surface;
                        surface = null;
                        // 双目会话清理完成前，Surface/Texture 仍归旧会话使用。
                        Runnable release =
                                () -> {
                                    if (retired != null) retired.release();
                                    texture.release();
                                };
                        if (session == null) release.run();
                        else {
                            retiredPreviews.add(release);
                            stopSession();
                        }
                        return false;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
                });
    }

    private void fitPreview() {
        if (preview.getWidth() == 0 || preview.getHeight() == 0) return;
        preview.setTransform(
                PreviewTransform.fitCenter(
                        preview.getWidth(),
                        preview.getHeight(),
                        appliedConfig.rgb.width,
                        appliedConfig.rgb.height,
                        appliedConfig.rgbRotationDegrees));
    }

    private void refreshDevices() {
        if (session != null) return;
        deviceSelectors.refresh(DualCameraSession.devices(this), restoredDevices);
        restoredDevices = null;
        if (!failed)
            ui.setStatus(
                    status,
                    deviceSelectors.size() < 2 ? getString(R.string.waiting_for_two_cameras) : getString(R.string.devices_ready),
                    deviceSelectors.size() < 2 ? ui.muted : ui.accent);
    }

    private String[] selectedDeviceNames() {
        return deviceSelectors.selectedNames();
    }

    private void startSession() {
        if (!visible || session != null) return;
        UsbDevice rgb = deviceSelectors.selected(0), ir = deviceSelectors.selected(1);
        if (rgb == null || ir == null || rgb.getDeviceName().equals(ir.getDeviceName())) {
            ui.setStatus(status, getString(R.string.select_distinct_cameras), ui.error);
            return;
        }
        if (surface == null || !surface.isValid()) {
            ui.setStatus(status, getString(R.string.preview_preparing), ui.ir);
            return;
        }
        StreamConfig rgbConfig = settings[0].config(UVCCamera.PIXEL_FORMAT_NV21),
                irConfig = settings[1].config(UVCCamera.PIXEL_FORMAT_GRAY8);
        appliedConfig =
                DualCameraConfig.forStreams(rgbConfig, irConfig)
                        .withPairingLimits(50, 150, PAIR_RATES[pairRate.getSelectedItemPosition()])
                        .withRotations(settings[0].rotationDegrees(), settings[1].rotationDegrees());
        fitPreview();
        pairs.set(0);
        skewMicros.set(0);
        pairWidth.set(0);
        pairHeight.set(0);
        irWidth.set(0);
        irHeight.set(0);
        previousRgb = previousIr = previousPairs = previousTick = 0;
        failed = false;
        open.setEnabled(false);
        stop.setEnabled(true);
        refresh.setEnabled(false);
        clearMetrics();
        previewCard.connecting();
        ui.setStatus(status, getString(R.string.connecting_permissions), ui.ir);
        try {
            session =
                    new DualCameraSession(
                            this,
                            rgb,
                            ir,
                            appliedConfig,
                            surface,
                            null,
                            new DualCameraSession.Listener() {
                                @Override
                                public void onReady(DualCameraSession source) {
                                    if (session == source && visible) {
                                        ui.setStatus(status, getString(R.string.dual_capture_active), ui.accent);
                                        previewCard.state(true, getString(R.string.waiting_first_frame));
                                        open.setEnabled(true);
                                        open.setText(getString(R.string.apply_restart));
                                    }
                                }

                                @Override
                                public void onFailure(DualCameraSession source,
                                        DualCameraSession.Role role, CameraFailure failure) {
                                    if (session == source) {
                                        failed = true;
                                        restartPending = false;
                                        open.setEnabled(false);
                                    }
                                    if (session == source && visible) {
                                        String message = ui.failureText(failure);
                                        if (role != null)
                                            message = getString(R.string.camera_error_role, role.name(), message);
                                        ui.setStatus(status, message, ui.error);
                                        previewCard.state(false, getString(R.string.capture_stopped));
                                    }
                                }

                                @Override
                                public void onClosed(DualCameraSession source) {
                                    // 两路 USB、native 和在途帧消费者均已排空，才允许重开。
                                    if (session == source) {
                                        session = null;
                                        main.removeCallbacks(tick);
                                        open.setEnabled(visible);
                                        open.setText(getString(R.string.start_capture));
                                        stop.setEnabled(false);
                                        refresh.setEnabled(true);
                                        previewCard.state(false, getString(R.string.independent_gray8));
                                        clearMetrics();
                                        if (visible && !failed)
                                            ui.setStatus(status, getString(R.string.capture_stopped_settings), ui.muted);
                                        if (restartPending && visible && !failed) {
                                            restartPending = false;
                                            main.post(SdkDemoActivity.this::startSession);
                                        }
                                    }
                                    releaseRetiredPreviews();
                                }
                            },
                            this::consumePair);
            main.removeCallbacks(tick);
            main.post(tick);
        } catch (RuntimeException error) {
            open.setEnabled(true);
            open.setText(getString(R.string.start_capture));
            stop.setEnabled(false);
            refresh.setEnabled(true);
            previewCard.state(false, getString(R.string.capture_not_started));
            Log.w("UvcDemo", "Could not open capture", error);
            ui.setStatus(status, getString(R.string.open_failed), ui.error);
        }
    }

    /** 算法接入点：帧只在本次回调内有效；这里仅校验格式并更新统计。 */
    private void consumePair(FramePair pair) {
        Nv21Planes rgb = pair.rgbPlanes();
        if (!rgb.y().isDirect() || pair.ir.luma().remaining() != pair.ir.width * pair.ir.height)
            throw new IllegalStateException("Unexpected pixel contract");
        pairWidth.set(pair.rgb.width);
        pairHeight.set(pair.rgb.height);
        irWidth.set(pair.ir.width);
        irHeight.set(pair.ir.height);
        skewMicros.set(pair.arrivalSkewNanos / 1000);
        pairs.incrementAndGet();
    }

    private void applySettings() {
        if (session == null) startSession();
        else {
            // 先关闭旧会话；onClosed 再按新参数创建，避免两组设备同时占用。
            restartPending = true;
            open.setEnabled(false);
            session.close();
            ui.setStatus(status, getString(R.string.applying_settings), ui.ir);
        }
    }

    private void stopSession() {
        restartPending = false;
        main.removeCallbacks(tick);
        if (session != null) {
            session.close();
            clearMetrics();
            stop.setEnabled(false);
            previewCard.state(false, getString(R.string.releasing_capture));
            if (visible) ui.setStatus(status, getString(R.string.stopping_capture), ui.muted);
        }
    }

    private void releaseRetiredPreviews() {
        for (Runnable release : retiredPreviews) release.run();
        retiredPreviews.clear();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        open.setEnabled(session == null);
        refresh.setEnabled(session == null);
        refreshDevices();
    }

    @Override
    protected void onStop() {
        visible = false;
        main.removeCallbacks(tick);
        stopSession();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        for (int i = 0; i < 2; i++) out.putIntArray("capture_" + i, settings[i].selection());
        out.putInt("pair_rate", pairRate.getSelectedItemPosition());
        out.putStringArray("devices", selectedDeviceNames());
        super.onSaveInstanceState(out);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request != 1 || !visible) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) applySettings();
        else ui.setStatus(status, getString(R.string.capture_permission_required), ui.error);
    }
}
