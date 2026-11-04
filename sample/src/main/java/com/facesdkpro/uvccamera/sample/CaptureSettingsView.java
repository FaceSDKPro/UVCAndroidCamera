package com.facesdkpro.uvccamera.sample;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.facesdkpro.uvccamera.StreamConfig;
import com.serenegiant.usb.UVCCamera;

/** Capture requests, independently per device. Unsupported exact combinations fail during open. */
final class CaptureSettingsView extends LinearLayout {
    final Spinner resolution, fps, transport, rotation;
    private static final int[] WIDTH = {640, 1280, 1920}, HEIGHT = {480, 720, 1080};
    private static final int[] FPS = {5, 10, 15, 20, 25, 30, 60};

    CaptureSettingsView(Context context, String role) {
        super(context); setOrientation(VERTICAL);
        TextView label = new TextView(context); label.setText(role + " 采集参数（修改后应用）"); addView(label);
        LinearLayout row = new LinearLayout(context);
        resolution = spinner(row, new String[]{"640 × 480", "1280 × 720", "1920 × 1080"});
        fps = spinner(row, new String[]{"5 FPS", "10 FPS", "15 FPS", "20 FPS", "25 FPS", "30 FPS", "60 FPS"});
        fps.setSelection(5); addView(row);
        LinearLayout second = new LinearLayout(context);
        transport = spinner(second, new String[]{"MJPEG", "YUYV"});
        rotation = spinner(second, new String[]{"顺时针 0°", "顺时针 90°", "顺时针 180°", "顺时针 270°"}); addView(second);
        resolution.setContentDescription(role + " 分辨率"); fps.setContentDescription(role + " 采集帧率");
        transport.setContentDescription(role + " 传输格式"); rotation.setContentDescription(role + " 旋转角度");
    }

    private Spinner spinner(LinearLayout row, String[] labels) {
        Spinner result = new Spinner(getContext());
        result.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, labels));
        row.addView(result, new LinearLayout.LayoutParams(0, -2, 1)); return result;
    }

    StreamConfig config(int pixelFormat) {
        int index = resolution.getSelectedItemPosition();
        return new StreamConfig(WIDTH[index], HEIGHT[index], FPS[fps.getSelectedItemPosition()],
                transport.getSelectedItemPosition() == 0 ? UVCCamera.UVC_VS_FRAME_MJPEG : UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,
                pixelFormat, 0);
    }
    int rotationDegrees() { return rotation.getSelectedItemPosition() * 90; }
}
