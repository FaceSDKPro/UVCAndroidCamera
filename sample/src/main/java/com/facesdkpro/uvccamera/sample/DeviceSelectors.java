package com.facesdkpro.uvccamera.sample;

import android.hardware.usb.UsbDevice;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Demo role binding shared by both screens. USB enumeration order never implies RGB or IR. */
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
        String[] previous = restored != null && restored.length == 2 ? restored : selectedNames();
        List<String> labels = new ArrayList<>(connected.size());
        for (UsbDevice device : connected) {
            labels.add(
                    String.format(
                            Locale.ROOT,
                            "%s · %04X:%04X",
                            device.getProductName(),
                            device.getVendorId(),
                            device.getProductId()));
        }
        boolean unchanged = populated && devices.equals(connected) && descriptions.equals(labels);
        devices = connected;
        if (unchanged && restored == null) return;
        descriptions = labels;
        populated = true;
        for (int role = 0; role < 2; role++) {
            List<String> choices = new ArrayList<>(labels.size() + 1);
            choices.add(role == 0 ? "请选择 RGB 摄像头" : "请选择 IR 摄像头");
            choices.addAll(labels);
            ui.items(controls[role], choices);
            int selected = 0;
            for (int i = 0; i < devices.size(); i++) {
                UsbDevice device = devices.get(i);
                boolean known =
                        role == 0
                                ? device.getVendorId() == 0x1187 && device.getProductId() == 0x3a49
                                : device.getVendorId() == 0x1871 && device.getProductId() == 0x3b49;
                if (device.getDeviceName().equals(previous[role])
                        || previous[role] == null && known) {
                    selected = i + 1;
                }
            }
            controls[role].setSelection(selected);
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

    /** Restore by USB identity: hotplug may change adapter positions while a dialog is open. */
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
}
