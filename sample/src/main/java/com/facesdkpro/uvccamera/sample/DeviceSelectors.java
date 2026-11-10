package com.facesdkpro.uvccamera.sample;

import android.hardware.usb.UsbDevice;
import android.widget.Spinner;
import com.facesdkpro.uvccamera.CameraSelection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 两个演示页面共用的设备选择；USB 枚举顺序不决定 RGB/IR 角色。 */
final class DeviceSelectors {
    private final DemoUi ui;
    private final Spinner[] controls;
    private List<UsbDevice> devices = Collections.emptyList();
    private List<String> descriptions = Collections.emptyList();
    private boolean populated;

    DeviceSelectors(DemoUi ui, Spinner[] controls) {
        this.ui = ui;
        this.controls = controls;
    }

    void refresh(List<UsbDevice> connected, String[] restored) {
        // 保留当前选择；型号及名称的角色推荐统一由 SDK 完成。
        String[] previous = restored != null && restored.length == 2 ? restored : selectedNames();
        List<String> labels = new ArrayList<>(connected.size());
        for (UsbDevice device : connected) {
            String name = device.getProductName();
            labels.add(
                    String.format(
                            Locale.ROOT,
                            "%s · %04X:%04X",
                            name == null || name.trim().isEmpty() ? ui.context.getString(R.string.usb_camera) : name,
                            device.getVendorId(),
                            device.getProductId()));
        }
        boolean unchanged = populated && devices.equals(connected) && descriptions.equals(labels);
        devices = connected;
        if (unchanged && restored == null) return;
        descriptions = labels;
        populated = true;
        CameraSelection suggested = CameraSelection.suggest(devices, previous[0], previous[1]);
        UsbDevice[] selectedDevices = {suggested.rgb, suggested.ir};
        for (int role = 0; role < 2; role++) {
            List<String> choices = new ArrayList<>(labels.size() + 1);
            choices.add(role == 0 ? ui.context.getString(R.string.select_rgb) : ui.context.getString(R.string.select_ir));
            choices.addAll(labels);
            ui.deviceItems(controls[role], choices);
            controls[role].setSelection(devices.indexOf(selectedDevices[role]) + 1);
        }
    }

    UsbDevice selected(int role) {
        int index = controls[role].getSelectedItemPosition() - 1;
        return index >= 0 && index < devices.size() ? devices.get(index) : null;
    }

    String[] selectedNames() {
        String[] names = new String[2];
        for (int role = 0; role < 2; role++) {
            UsbDevice device = selected(role);
            names[role] = device == null ? null : device.getDeviceName();
        }
        return names;
    }

    /** 按 USB 设备名恢复，避免热插拔后沿用旧列表下标。 */
    void restore(int role, String deviceName) {
        int selected = 0;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getDeviceName().equals(deviceName)) {
                selected = i + 1;
                break;
            }
        }
        controls[role].setSelection(selected);
    }

    int size() {
        return devices.size();
    }

    boolean hasPair() {
        return CameraSelection.isPair(selected(0), selected(1));
    }

    int statusText() {
        return size() < 2 ? R.string.waiting_for_two_cameras
                : hasPair() ? R.string.devices_ready : R.string.select_roles_manually;
    }
}
