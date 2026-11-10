package com.facesdkpro.uvccamera;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraSelectionTest {
    private CameraSelection.Candidate device(String id, String name) {
        return new CameraSelection.Candidate(id, CameraSelection.hint(1, 2, name));
    }

    @Test public void namesRecognizeRolesWithoutDependingOnCaseOrEnumerationOrder() {
        for (String name : new String[] {"KP26BLD RGB", "rgb_camera", "Color Camera", "CameraRGB", "RGBCamera", "rgbcamera", "RGBCAMERA", "彩色摄像头"})
            assertEquals(name, 1, CameraSelection.hint(1, 2, name));
        for (String name : new String[] {"KP26BLD NIR", "ir_camera", "Infrared Camera", "IRCamera", "CameraIR", "ircamera", "IRCAMERA", "近红外摄像头"})
            assertEquals(name, -1, CameraSelection.hint(1, 2, name));
        assertArrayEquals(new int[] {1, 0}, CameraSelection.choose(Arrays.asList(
                device("ir", "NIR"), device("rgb", "rgb")), null, null));
        assertArrayEquals(new int[] {0, 1}, CameraSelection.choose(Arrays.asList(
                device("rgb", "RGB"), device("ir", "IR")), null, null));
    }

    @Test public void unknownAndCombinedNamesNeverGuessRolesFromSubstringsOrListOrder() {
        assertFalse(CameraSelection.isPair(null, null));
        for (String name : new String[] {null, "", "UVC Camera", "FIRST CAMERA", "IRIS Camera", "Wireless Camera", "Firmware Camera", "RGB / IR Camera"})
            assertEquals(name, 0, CameraSelection.hint(1, 2, name));
        assertArrayEquals(new int[] {-1, -1}, CameraSelection.choose(Arrays.asList(
                device("a", "UVC Camera"), device("b", "UVC Camera")), null, null));
        assertArrayEquals(new int[] {0, -1}, CameraSelection.choose(Arrays.asList(
                device("a", "RGB"), device("b", "UVC Camera")), null, null));
        assertArrayEquals(new int[] {-1, -1}, CameraSelection.choose(Collections.emptyList(), null, null));
    }

    @Test public void verifiedModelsWorkWithoutNamesAndOutrankNameHints() {
        assertEquals(2, CameraSelection.hint(0x1187, 0x3a49, null));
        assertEquals(-2, CameraSelection.hint(0x1871, 0x3b49, "UVC Camera"));
        assertEquals(2, CameraSelection.hint(0x1871, 0x1a49, "UVC Camera"));
        assertEquals(-2, CameraSelection.hint(0x1871, 0x1b49, null));
        assertArrayEquals(new int[] {1, 2}, CameraSelection.choose(Arrays.asList(
                device("other", "RGB"), new CameraSelection.Candidate("known-rgb", 2),
                new CameraSelection.Candidate("known-ir", -2)), null, null));
    }

    @Test public void equallyStrongCandidatesRequireManualSelection() {
        assertArrayEquals(new int[] {-1, 2}, CameraSelection.choose(Arrays.asList(
                device("a", "RGB"), device("b", "RGB"), device("c", "IR")), null, null));
        assertArrayEquals(new int[] {-1, -1}, CameraSelection.choose(Arrays.asList(
                new CameraSelection.Candidate("a", 2), new CameraSelection.Candidate("b", 2),
                device("c", "IR"), device("d", "NIR")), null, null));
    }

    @Test public void manualChoicesWinAndTheSameDeviceCannotFillBothRoles() {
        List<CameraSelection.Candidate> devices = Arrays.asList(device("a", "RGB"), device("b", "IR"));
        assertArrayEquals(new int[] {1, 0}, CameraSelection.choose(devices, "b", "a"));
        assertArrayEquals(new int[] {1, -1}, CameraSelection.choose(devices, "b", null));
        assertArrayEquals(new int[] {-1, -1}, CameraSelection.choose(devices, "a", "a"));
        assertArrayEquals(new int[] {1, 0}, CameraSelection.choose(Arrays.asList(
                device("ir", "Unknown"), device("rgb", "Unknown")), "rgb", "ir"));
    }

    @Test public void aMissingPreferredDeviceIsNotReplacedByAnotherModel() {
        assertArrayEquals(new int[] {-1, 1}, CameraSelection.choose(Arrays.asList(
                device("new-rgb", "RGB"), device("ir", "IR")), "disconnected-rgb", "ir"));
    }
}
