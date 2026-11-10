package com.facesdkpro.uvccamera;

import android.hardware.usb.UsbDevice;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Optional role suggestions. Unknown or ambiguous roles stay null; never opens a device. */
public final class CameraSelection {
    public final UsbDevice rgb, ir;
    private static final Pattern LOWER_CAMEL = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern UPPER_CAMEL = Pattern.compile("([A-Z])([A-Z][a-z])");
    private static final Pattern CAMERA_WORD = Pattern.compile("(?i)camera|sensor|cam(?=$|[^a-z])");
    private static final Pattern RGB = Pattern.compile("(^|[^A-Z])(RGB|COLOR|COLOUR|VISIBLE)(?=$|[^A-Z])");
    private static final Pattern IR = Pattern.compile("(^|[^A-Z])(IR|NIR|INFRARED)(?=$|[^A-Z])");

    private CameraSelection(UsbDevice rgb, UsbDevice ir) { this.rgb = rgb; this.ir = ir; }

    /** Two distinct UVC devices, suitable for an explicit paired-session binding. */
    public static boolean isPair(UsbDevice rgb, UsbDevice ir) {
        return UvcDevices.isCamera(rgb) && UvcDevices.isCamera(ir)
                && !rgb.getDeviceName().equals(ir.getDeviceName());
    }

    public static CameraSelection suggest(List<UsbDevice> devices) {
        return suggest(devices, null, null);
    }

    /** Retain selections by current USB device name before considering model/name hints.
     * A missing preferred device is not silently replaced. USB device names are connection IDs,
     * not persistent identities across reconnects. No serial-number permission is requested. */
    public static CameraSelection suggest(List<UsbDevice> devices, String rgbDeviceName, String irDeviceName) {
        if (devices == null) throw new NullPointerException("devices");
        List<UsbDevice> cameras = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UsbDevice device : devices) {
            if (!UvcDevices.isCamera(device) || !seen.add(device.getDeviceName())) continue;
            cameras.add(device);
            candidates.add(new Candidate(device.getDeviceName(), hint(device.getVendorId(),
                    device.getProductId(), device.getProductName())));
        }
        int[] selected = choose(candidates, rgbDeviceName, irDeviceName);
        return new CameraSelection(selected[0] < 0 ? null : cameras.get(selected[0]),
                selected[1] < 0 ? null : cameras.get(selected[1]));
    }

    // Positive = RGB, negative = IR. Verified model profiles outrank product-name hints.
    static int hint(int vendor, int product, String name) {
        if (vendor == 0x1187 && product == 0x3a49 || vendor == 0x1871 && product == 0x1a49) return 2;
        if (vendor == 0x1871 && (product == 0x3b49 || product == 0x1b49)) return -2;
        if (name == null) return 0;
        String separated = CAMERA_WORD.matcher(name).replaceAll(" $0 ");
        String words = UPPER_CAMEL.matcher(LOWER_CAMEL.matcher(separated).replaceAll("$1 $2"))
                .replaceAll("$1 $2").toUpperCase(Locale.ROOT);
        boolean rgb = RGB.matcher(words).find() || words.contains("可见光") || words.contains("彩色");
        boolean ir = IR.matcher(words).find() || words.contains("红外");
        return rgb == ir ? 0 : rgb ? 1 : -1;
    }

    static final class Candidate {
        final String deviceName;
        final int hint;
        Candidate(String deviceName, int hint) { this.deviceName = deviceName; this.hint = hint; }
    }

    static int[] choose(List<Candidate> devices, String rgbName, String irName) {
        int rgb = find(devices, rgbName), ir = find(devices, irName);
        if (rgb >= 0 && rgb == ir) return new int[] {-1, -1};
        if (rgbName == null) rgb = unique(devices, 1, ir);
        if (irName == null) ir = unique(devices, -1, rgb);
        return new int[] {rgb, ir};
    }

    private static int find(List<Candidate> devices, String name) {
        if (name != null) for (int i = 0; i < devices.size(); i++)
            if (name.equals(devices.get(i).deviceName)) return i;
        return -1;
    }

    private static int unique(List<Candidate> devices, int role, int excluded) {
        int selected = -1, confidence = 0;
        boolean tied = false;
        for (int i = 0; i < devices.size(); i++) {
            int score = devices.get(i).hint * role;
            if (i == excluded || score <= 0) continue;
            if (score > confidence) { selected = i; confidence = score; tied = false; }
            else if (score == confidence) tied = true;
        }
        return tied ? -1 : selected;
    }
}
