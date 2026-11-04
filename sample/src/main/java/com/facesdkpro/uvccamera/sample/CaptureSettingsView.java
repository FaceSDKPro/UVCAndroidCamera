package com.facesdkpro.uvccamera.sample;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.facesdkpro.uvccamera.StreamConfig;
import com.serenegiant.usb.UVCCamera;

/** Local controls for exact SDK capture requests. */
final class CaptureSettingsView extends LinearLayout {
    final Spinner resolution, fps, transport, rotation;
    private static final int[] WIDTH = {640, 1280, 1920},
            HEIGHT = {480, 720, 1080},
            FPS = {5, 10, 15, 20, 25, 30, 60};

    CaptureSettingsView(Context context, String role) {
        super(context);
        setOrientation(VERTICAL);
        DemoUi ui = new DemoUi(context);
        resolution = ui.spinner(role + " 分辨率");
        ui.items(resolution, new String[] {"640 × 480", "1280 × 720", "1920 × 1080"});
        fps = ui.spinner(role + " 采集帧率");
        ui.items(
                fps,
                new String[] {"5 FPS", "10 FPS", "15 FPS", "20 FPS", "25 FPS", "30 FPS", "60 FPS"});
        fps.setSelection(5);
        transport = ui.spinner(role + " 传输格式");
        ui.items(transport, new String[] {"MJPEG", "YUYV"});
        rotation = ui.spinner(role + " 旋转角度");
        ui.items(rotation, new String[] {"顺时针 0°", "顺时针 90°", "顺时针 180°", "顺时针 270°"});
        LinearLayout first = ui.row();
        first.addView(ui.field("分辨率", resolution), new LayoutParams(0, -2, 1));
        ui.gap(first, 10);
        first.addView(ui.field("采集帧率", fps), new LayoutParams(0, -2, 1));
        addView(first);
        ui.gap(this, 12);
        LinearLayout second = ui.row();
        second.addView(ui.field("传输格式", transport), new LayoutParams(0, -2, 1));
        ui.gap(second, 10);
        second.addView(ui.field("画面旋转", rotation), new LayoutParams(0, -2, 1));
        addView(second);
    }

    StreamConfig config(int pixelFormat) {
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
