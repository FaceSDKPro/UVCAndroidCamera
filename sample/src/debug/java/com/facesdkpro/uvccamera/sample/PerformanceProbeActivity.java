package com.facesdkpro.uvccamera.sample;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.widget.LinearLayout;
import android.util.Base64;
import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.UvcCameraManager;
import com.serenegiant.usb.UVCCamera;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Repeatable process-CPU comparison, not a measurement of electrical power. Saves no pixels. */
public final class PerformanceProbeActivity extends Activity {
    final Handler main = new Handler(Looper.getMainLooper());
    final List<UvcCameraManager.Camera> cameras = new ArrayList<>();
    final Surface[] surfaces = new Surface[2];
    final AtomicInteger[] updates = {new AtomicInteger(), new AtomicInteger()};
    UvcCameraManager manager;
    int opened, width, height, format, delay, duration, trial, frameType, captureFps;
    boolean verifyBuffers;
    final AtomicInteger checkedBuffers=new AtomicInteger(), invalidBuffers=new AtomicInteger();
    boolean preview, previewOnly, done;
    long cpuStart, wallStart;
    long[] counts, renders;
    final JSONArray samples = new JSONArray();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        width = getIntent().getIntExtra("width", 1920); height = getIntent().getIntExtra("height", 1080);
        format = getIntent().getIntExtra("format", UVCCamera.PIXEL_FORMAT_NV21);
        frameType = getIntent().getIntExtra("frameType", UVCCamera.UVC_VS_FRAME_MJPEG);
        captureFps = getIntent().getIntExtra("captureFps", 30);
        verifyBuffers = getIntent().getBooleanExtra("verifyBuffers", false);
        delay = getIntent().getIntExtra("delay", 0); duration = getIntent().getIntExtra("duration", 15000);
        preview = getIntent().getBooleanExtra("preview", false);
        previewOnly = getIntent().getBooleanExtra("previewOnly", false);
        getWindow().addFlags(128);
        LinearLayout row = new LinearLayout(this); setContentView(row);
        for (int i=0;i<2;i++) {
            final int slot=i; TextureView view=new TextureView(this); row.addView(view,new LinearLayout.LayoutParams(0,-1,1));
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){surfaces[slot]=new Surface(t);maybeStart();}
                public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){}
                public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return true;}
                public void onSurfaceTextureUpdated(SurfaceTexture t){updates[slot].incrementAndGet();}
            });
        }
        main.postDelayed(this::maybeStart,500);
        main.postDelayed(()->fail("timeout"),120000);
    }
    void maybeStart() {
        if(manager!=null||done||(preview&&(surfaces[0]==null||surfaces[1]==null)))return;
        manager=new UvcCameraManager(this,new UvcCameraManager.Listener(){
            public void onOpened(UvcCameraManager.Camera c){if(++opened==2)main.postDelayed(()->begin(),5000);}
            public void onError(UsbDevice d,String m,Throwable e){fail(m+" "+e);}
            public void onShutdown(){report();}
        });
        List<UsbDevice> devices=manager.devices();
        if(devices.size()!=2){fail("Expected two cameras, found "+devices.size());return;}
        for(int i=0;i<2;i++) {
            StreamConfig config=new StreamConfig(width,height,captureFps,frameType,format,0);
            cameras.add(manager.open(devices.get(i),config,preview?surfaces[i]:null,previewOnly?null:(camera,frame)->{
                if(verifyBuffers) {
                    int pixels=width*height;
                    int expected=(format==2||format==3)?pixels*3/2:format==4||format==7?pixels*3:format==6?pixels*4:format==8?pixels:pixels*2;
                    ByteBuffer view=frame.pixels();
                    if(view.remaining()!=expected||!view.isDirect()||!view.isReadOnly())invalidBuffers.incrementAndGet();
                    else {view.get(0);view.get(expected-1);checkedBuffers.incrementAndGet();}
                }
                if(delay>0)SystemClock.sleep(delay);
            }));
        }
    }
    void begin() {
        if(done)return;
        counts=new long[]{cameras.get(0).receivedFrames(),cameras.get(1).receivedFrames()};
        renders=new long[]{updates[0].get(),updates[1].get()};
        cpuStart=Process.getElapsedCpuTime();wallStart=SystemClock.elapsedRealtime();
        main.postDelayed(this::sample,duration);
    }
    void sample() {
        if(done)return;
        try {
            long wall=SystemClock.elapsedRealtime()-wallStart,cpu=Process.getElapsedCpuTime()-cpuStart;
            JSONObject item=new JSONObject();item.put("wall_ms",wall);item.put("process_cpu_ms",cpu);item.put("cpu_percent_one_core",100.0*cpu/wall);
            JSONArray fps=new JSONArray(), rendered=new JSONArray();
            long delivered=0;
            for(int i=0;i<2;i++){long frames=cameras.get(i).receivedFrames()-counts[i];delivered+=frames;fps.put(1000.0*frames/wall);rendered.put(1000.0*(updates[i].get()-renders[i])/wall);}
            item.put("cpu_ms_per_delivered_frame",delivered>0?1.0*cpu/delivered:JSONObject.NULL);
            item.put("callback_fps",fps);item.put("preview_fps",rendered);samples.put(item);
            if(++trial<3)begin();else{done=true;manager.close();}
        }catch(Exception e){fail(e.toString());}
    }
    void fail(String message){if(done)return;done=true;android.util.Log.e("UvcPerformance","ERROR="+message);if(manager!=null)manager.close();}
    void report() {
        try {
            JSONObject report=new JSONObject();report.put("run_id",getIntent().getStringExtra("runId"));report.put("width",width);report.put("height",height);report.put("format",format);report.put("frame_type",frameType);report.put("capture_fps",captureFps);report.put("verify_buffers",verifyBuffers);report.put("checked_buffers",checkedBuffers.get());report.put("invalid_buffers",invalidBuffers.get());report.put("preview",preview);report.put("preview_only",previewOnly);report.put("callback_delay_ms",delay);report.put("samples",samples);report.put("images_saved",false);report.put("electrical_power_measured",false);report.put("exposure_locked",false);
            android.util.Log.i("UvcPerformance","REPORT="+Base64.encodeToString(report.toString().getBytes("UTF-8"),Base64.NO_WRAP));
        }catch(Exception e){android.util.Log.e("UvcPerformance","report",e);}
    }
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);if(manager!=null)manager.close();for(Surface s:surfaces)if(s!=null)s.release();super.onDestroy();}
}
