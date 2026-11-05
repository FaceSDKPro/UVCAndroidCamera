package com.facesdkpro.uvccamera;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared UVC discovery for the low-level manager and the paired-session API. */
final class UvcDevices {
    private UvcDevices() {}

    static boolean isCamera(UsbDevice device) {
        if (device == null) return false;
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    static List<UsbDevice> list(UsbManager usb) {
        if (usb == null) return Collections.emptyList();
        List<UsbDevice> result = new ArrayList<>();
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (isCamera(device)) result.add(device);
        }
        Collections.sort(result, (a, b) -> a.getDeviceName().compareTo(b.getDeviceName()));
        return Collections.unmodifiableList(result);
    }
}
