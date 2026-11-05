package com.facesdkpro.uvccamera;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraFailureContractTest {
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
