package com.facesdkpro.uvccamera.sample;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.facesdkpro.uvccamera.StreamConfig;
import com.serenegiant.usb.UVCCamera;

/** 构造明确的采集请求；实际支持情况由设备协商决定。 */
final class CaptureSettingsView extends LinearLayout {
    final Spinner resolution, fps, transport, rotation;
    private static final int[] WIDTH = {640, 1280, 1920},
            HEIGHT = {480, 720, 1080},
            FPS = {5, 10, 15, 20, 25, 30, 60};

    CaptureSettingsView(Context context, String role) {
        super(context);
        setOrientation(VERTICAL);
        DemoUi ui = new DemoUi(context);
        resolution = ui.spinner(context.getString(R.string.resolution_for_role, role));
        ui.items(resolution, new String[] {"640 × 480", "1280 × 720", "1920 × 1080"});
        fps = ui.spinner(context.getString(R.string.fps_for_role, role));
        ui.items(
                fps,
                new String[] {"5 FPS", "10 FPS", "Middle · 15 FPS", "20 FPS", "25 FPS", "High · 30 FPS", "60 FPS"});
        fps.setSelection(2);
        transport = ui.spinner(context.getString(R.string.format_for_role, role));
        ui.items(transport, new String[] {"MJPEG", "YUYV"});
        rotation = ui.spinner(context.getString(R.string.rotation_for_role, role));
        String[] rotations = new String[4];
        for (int i = 0; i < rotations.length; i++)
            rotations[i] = context.getString(R.string.rotation_option, i * 90);
        ui.items(rotation, rotations);
        LinearLayout first = ui.row();
        first.addView(ui.field(context.getString(R.string.resolution), resolution), new LayoutParams(0, -2, 1));
        ui.gap(first, 10);
        first.addView(ui.field(context.getString(R.string.capture_fps), fps), new LayoutParams(0, -2, 1));
        addView(first);
        ui.gap(this, 12);
        LinearLayout second = ui.row();
        second.addView(ui.field(context.getString(R.string.transport_format), transport), new LayoutParams(0, -2, 1));
        ui.gap(second, 10);
        second.addView(ui.field(context.getString(R.string.preview_rotation), rotation), new LayoutParams(0, -2, 1));
        addView(second);
    }

    StreamConfig config(int pixelFormat) {
        // UI 选项只生成请求参数，不表示摄像头一定支持该组合。
        int index = resolution.getSelectedItemPosition();
        return new StreamConfig(
                WIDTH[index],
                HEIGHT[index],
                FPS[fps.getSelectedItemPosition()],
                transport.getSelectedItemPosition() == 0
                        ? UVCCamera.UVC_VS_FRAME_MJPEG
                        : UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,
                pixelFormat,
                0);
    }

    int rotationDegrees() {
        return rotation.getSelectedItemPosition() * 90;
    }

    int[] selection() {
        return new int[] {
            resolution.getSelectedItemPosition(),
            fps.getSelectedItemPosition(),
            transport.getSelectedItemPosition(),
            rotation.getSelectedItemPosition()
        };
    }

    void restore(int[] values) {
        if (values == null || values.length != 4) return;
        Spinner[] controls = {resolution, fps, transport, rotation};
        for (int i = 0; i < 4; i++)
            if (values[i] >= 0 && values[i] < controls[i].getCount())
                controls[i].setSelection(values[i]);
    }
}
