package com.facesdkpro.uvccamera;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraFailureContractTest {
    @Test public void nativeOpenFailuresHaveSpecificReasonsAndKeepDiagnostics() {
        int[] statuses = {-2, -51, -3, -4, -6, -7, -1};
        CameraFailure.Code[] codes = {CameraFailure.Code.UNSUPPORTED_MODE, CameraFailure.Code.UNSUPPORTED_MODE,
                CameraFailure.Code.USB_PERMISSION_DENIED, CameraFailure.Code.DEVICE_DETACHED,
                CameraFailure.Code.DEVICE_BUSY, CameraFailure.Code.OPEN_TIMEOUT, CameraFailure.Code.OPEN_FAILED};
        for (int i = 0; i < statuses.length; i++) {
            CameraFailure failure = CameraFailure.openFailed(null, "fixture", statuses[i]);
            assertEquals(codes[i], failure.code);
            assertEquals(Integer.valueOf(statuses[i]), failure.nativeStatus);
            assertEquals("fixture", failure.message);
        }
    }
    @Test public void structuredFailuresKeepOriginalStatusAndLegacyCallbackIsDeliveredOnce() {
        AtomicInteger single=new AtomicInteger(),dual=new AtomicInteger();
        Throwable cause=new IllegalStateException("unsupported synthetic mode");
        CameraFailure failure=new CameraFailure(CameraFailure.Code.OPEN_FAILED,null,"mode",cause,-51);
        UvcCameraManager.Listener oldSingle=new UvcCameraManager.Listener(){
            @Override public void onError(android.hardware.usb.UsbDevice device,String message,Throwable error){
                single.incrementAndGet();assertSame(cause,error);assertEquals("mode",message);
            }
        };
        DualCameraSession.Listener oldDual=new DualCameraSession.Listener(){
            @Override public void onError(DualCameraSession session,String message,Throwable error){
                dual.incrementAndGet();assertSame(cause,error);
            }
        };
        oldSingle.onFailure(failure);oldDual.onFailure(null,null,failure);
        assertEquals(1,single.get());assertEquals(1,dual.get());assertEquals(Integer.valueOf(-51),failure.nativeStatus);
    }
}
