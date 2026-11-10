package com.facesdkpro.uvccamera.sample;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.DualCameraSession.Role;

/** 构造采集偏好；就近匹配由 SDK 在打开设备时完成。 */
final class CaptureSettingsView extends LinearLayout {
    final Spinner resolution, fps, transport, rotation;
    private final Role role;
    private static final StreamConfig.Resolution[] RESOLUTIONS = StreamConfig.Resolution.values();
    private static final StreamConfig.Transport[] TRANSPORTS = StreamConfig.Transport.values();
    private static final int[] FPS = {5, 10, 15, 20, 25, 30, 60};

    CaptureSettingsView(Context context, Role role) {
        super(context);
        this.role = role;
        setOrientation(VERTICAL);
        DemoUi ui = new DemoUi(context);
        resolution = ui.spinner(context.getString(R.string.resolution_for_role, role));
        ui.items(resolution, new String[] {context.getString(R.string.resolution_middle),
                context.getString(R.string.resolution_high)});
        fps = ui.spinner(context.getString(R.string.fps_for_role, role));
        ui.items(
                fps,
                new String[] {"5 FPS", "10 FPS", context.getString(R.string.capture_middle),
                        "20 FPS", "25 FPS", context.getString(R.string.capture_high), "60 FPS"});
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

    StreamConfig config() {
        // 只传递用户选择；输出格式及能力匹配交给 SDK。
        return StreamConfig.preferred(role,
                RESOLUTIONS[resolution.getSelectedItemPosition()],
                FPS[fps.getSelectedItemPosition()],
                TRANSPORTS[transport.getSelectedItemPosition()]);
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
            if (values[i] >= 0 && values[i] < controls[i].getCount()) controls[i].setSelection(values[i]);
        // 旧界面的 1080p 索引恢复到新的高配档；FPS 的索引与含义保持兼容。
        if (values[0] == 2) resolution.setSelection(1);
    }
}
