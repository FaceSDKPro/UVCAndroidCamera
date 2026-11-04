package com.facesdkpro.uvccamera.sample;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.util.Base64;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.UvcCameraManager;
import com.serenegiant.usb.UVCCamera;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-only acceptance harness; excluded from release. No image data is written. */
public final class DeviceProbeActivity extends Activity {
    final Handler main = new Handler(Looper.getMainLooper());
    final List<UvcCameraManager.Camera> cameras = new ArrayList<>();
    final JSONArray checks = new JSONArray();
    final JSONArray fdSnapshots = new JSONArray();
    final Surface[] previews = new Surface[2];
    final AtomicInteger[] renderUpdates = {new AtomicInteger(),new AtomicInteger()};
    final AtomicInteger badBuffers = new AtomicInteger(), grayChecks = new AtomicInteger();
    final AtomicLong[] first = {new AtomicLong(),new AtomicLong()}, last = {new AtomicLong(),new AtomicLong()};
    UvcCameraManager manager; List<UsbDevice> devices; TextView text;
    int cycle, opened, stopped, controlChecks; boolean finished, measuring, expectingBadMode;
    Map<String,Integer> warmFds;

    void check(String name, boolean passed, String details) {
        try { JSONObject entry = new JSONObject(); entry.put("name",name); entry.put("passed",passed); entry.put("details",details); checks.put(entry); }
        catch(Exception ignored) {}
        android.util.Log.i("UvcCameraAcceptance",name+" passed="+passed+" "+details);
        text.append("\n"+name+": "+(passed?"通过":"失败"));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);text=new TextView(this);text.setPadding(20,20,20,20);text.setMaxLines(12);text.setText("UVC 双路采集验收：请允许相机和两路 USB 访问");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.addView(text);
        LinearLayout row=new LinearLayout(this);
        for(int i=0;i<2;i++){final int slot=i;TextureView view=new TextureView(this);row.addView(view,new LinearLayout.LayoutParams(0,-1,1));view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){previews[slot]=new Surface(texture);}
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){if(previews[slot]!=null)previews[slot].release();previews[slot]=null;return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture texture){renderUpdates[slot].incrementAndGet();}
        });}
        root.addView(row,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);getWindow().addFlags(128);
        if(checkSelfPermission("android.permission.CAMERA")!=0)requestPermissions(new String[]{"android.permission.CAMERA"},1);else start();
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] result){super.onRequestPermissionsResult(code,names,result);if(result.length>0&&result[0]==0)start();else{check("camera_permission",false,"denied");finishTest();}}
    void start() {
        snapshotFds("start");
        manager=new UvcCameraManager(this,new UvcCameraManager.Listener(){
            public void onOpened(UvcCameraManager.Camera camera){
                if(cycle<=7)camera.withControls(control->{
                    boolean supported=control.isBrightnessEnable();int value=supported?control.getBrightness():0;
                    main.post(()->{controlChecks++;check("control_read_cycle_"+cycle,true,"brightness_supported="+supported+", value="+value);});
                });
                if(++opened==2&&!measuring){measuring=true;if(cycle>=8){for(UvcCameraManager.Camera c:cameras)c.close();}else main.postDelayed(()->sampleAndClose(),8000);}
            }
            public void onClosed(UvcCameraManager.Camera camera){if(!expectingBadMode&&++stopped==2)main.postDelayed(()->afterClose(),400);}
            public void onError(UsbDevice device,String message,Throwable cause){
                if(expectingBadMode){expectingBadMode=false;check("unsupported_mode_rejected",true,message+" "+cause);main.postDelayed(()->openCycle(),300);}
                else {check("camera_error",false,message+" "+cause);finishTest();}
            }
            public void onShutdown(){main.postDelayed(()->writeReport(),500);}
        });
        int commandThreads=0,monitorThreads=0;
        for(Thread thread:Thread.getAllStackTraces().keySet()){
            if("UVC-commands".equals(thread.getName()))commandThreads++;
            if("USBMonitor".equals(thread.getName()))monitorThreads++;
        }
        check("shared_usb_command_looper",commandThreads==1&&monitorThreads==0,"commands="+commandThreads+", extra_monitors="+monitorThreads);
        devices=manager.devices();check("two_devices",devices.size()==2,"count="+devices.size());
        if(devices.size()!=2){finishTest();return;}
        main.postDelayed(this::openCycle,500);main.postDelayed(()->{if(!finished){check("timeout",false,"120s deadline");finishTest();}},120000);
    }
    void openCycle(){
        if(finished)return;
        if(cycle==3&&(previews[0]==null||previews[1]==null)){main.postDelayed(this::openCycle,100);return;}
        cycle++;opened=stopped=0;measuring=false;cameras.clear();
        for(int i=0;i<2;i++){
            final int slot=i;first[i].set(0);last[i].set(0);
            StreamConfig config=cycle>=5?new StreamConfig(640,480,30,UVCCamera.UVC_VS_FRAME_MJPEG,UVCCamera.PIXEL_FORMAT_GRAY8,0):StreamConfig.defaults();
            boolean showPreview=cycle==4||cycle==6||cycle==7||(cycle>=8&&(cycle&1)==0);
            cameras.add(manager.open(devices.get(i),config,showPreview?previews[i]:null,(cycle==7||(cycle>=8&&(cycle&1)==0))?null:cycle>=8?(camera,frame)->{}:(camera,frame)->{
                int expected=camera.config.pixelFormat==UVCCamera.PIXEL_FORMAT_GRAY8?640*480:640*480*3/2;
                if(frame.pixels().remaining()!=expected||!frame.pixels().isReadOnly()||!frame.pixels().isDirect())badBuffers.incrementAndGet();
                first[slot].compareAndSet(0,frame.arrivalTimeNanos);last[slot].set(frame.arrivalTimeNanos);
                if(frame.sequence==1){if(frame.copyLuma().length==640*480)grayChecks.incrementAndGet();else badBuffers.incrementAndGet();}
            }));
        }
    }
    void sampleAndClose(){
        if(finished)return;
        for(int i=0;i<2;i++){
            UvcCameraManager.Camera camera=cameras.get(i);long count=camera.receivedFrames();long span=last[i].get()-first[i].get();double fps=cycle==7?count/8.0:span>0?(count-1)*1e9/span:0;
            check("dual_cycle_"+cycle+"_camera_"+i,count>30&&fps>10,"vid="+devices.get(i).getVendorId()+", pid="+devices.get(i).getProductId()+", frames="+count+", fps="+fps+", preview="+((cycle==4||cycle==6||cycle==7)?"texture":"none"));
            if(cycle==4||cycle==6||cycle==7)check("preview_updates_camera_"+i,renderUpdates[i].get()>10,"updates="+renderUpdates[i].get());
        }
        for(UvcCameraManager.Camera camera:cameras)camera.close();
    }
    int fdCount(){String[] f=new File("/proc/self/fd").list();return f==null?-1:f.length;}
    Map<String,Integer> snapshotFds(String stage){
        Map<String,Integer> counts=new TreeMap<>();
        try{
            String[] names=new File("/proc/self/fd").list();
            if(names!=null)for(String name:names)try{
                String target=Os.readlink("/proc/self/fd/"+name);String kind;
                if(target.startsWith("/dev/bus/usb/"))kind="usb";
                else if(target.contains("eventfd"))kind="eventfd";
                else if(target.contains("eventpoll"))kind="eventpoll";
                else if(target.startsWith("pipe:"))kind="pipe";
                else if(target.startsWith("socket:"))kind="socket";
                else if(target.contains("dmabuf")||target.contains("dma_buf")||target.contains("/dev/ion")||target.contains("dma_heap")||target.contains("sync_file"))kind="graphics";
                else if(target.contains("ashmem"))kind="ashmem";
                else if(target.contains("memfd:"))kind="memfd";
                else if(target.endsWith(".apk")||target.endsWith(".dex")||target.endsWith(".oat")||target.endsWith(".art"))kind="code";
                else kind="other";
                counts.put(kind,counts.containsKey(kind)?counts.get(kind)+1:1);
            }catch(Exception ignored){}
            JSONObject snapshot=new JSONObject();snapshot.put("stage",stage);snapshot.put("fd_counts",new JSONObject(counts));
            fdSnapshots.put(snapshot);
            android.util.Log.i("UvcCameraAcceptance","FD_SNAPSHOT="+snapshot);
        }catch(Exception ignored){}
        return counts;
    }
    void afterClose(){
        if(finished)return;Map<String,Integer> current=snapshotFds("after_close_"+cycle);check("closed_cycle_"+cycle,cameras.get(0).isClosed()&&cameras.get(1).isClosed(),"fd_count="+fdCount());
        if(cycle==1){warmFds=current;expectingBadMode=true;StreamConfig bad=new StreamConfig(640,480,120,UVCCamera.UVC_VS_FRAME_MJPEG,UVCCamera.PIXEL_FORMAT_NV21,0);manager.open(devices.get(0),bad,null,(camera,frame)->{});}
        else if(cycle<3)openCycle();
        else if(cycle==3){
            boolean stable=warmFds!=null;
            for(String kind:new String[]{"usb","eventfd","eventpoll","pipe"})stable&=value(current,kind)==value(warmFds,kind);
            // Platform sockets can close during the run; a decrease is not a leaked connection.
            stable&=value(current,"socket")<=value(warmFds,"socket");
            check("usb_event_fd_stability",stable,"warm="+warmFds+", final_headless="+current);openCycle();
        }else if(cycle<7)openCycle();
        else if(cycle==7){check("frame_buffers",badBuffers.get()==0&&grayChecks.get()==12,"bad="+badBuffers+", gray_copies="+grayChecks);check("controls",controlChecks==14,"reads="+controlChecks);openCycle();}
        else if(cycle<17)openCycle();else finishTest();
    }
    int value(Map<String,Integer> map,String key){return map==null||!map.containsKey(key)?0:map.get(key);}
    int captureThreads(){
        int count=0;File[] tasks=new File("/proc/self/task").listFiles();if(tasks==null)return -1;
        for(File task:tasks)try(BufferedReader input=new BufferedReader(new FileReader(new File(task,"comm")))){
            String name=input.readLine();if("UVC-commands".equals(name)||"USBMonitor".equals(name)||"preview_thread".equals(name)||"capture_thread".equals(name)||"libusb_event".equals(name)||"uvc_callback".equals(name))count++;
        }catch(Exception ignored){}
        return count;
    }
    void finishTest(){if(finished)return;finished=true;if(manager!=null)manager.close();else writeReport();}
    void writeReport(){try{
        Map<String,Integer> closedFds=snapshotFds("after_shutdown");
        check("usb_released",value(closedFds,"usb")==0,"usb_fds="+value(closedFds,"usb"));
        int threads=captureThreads();check("capture_threads_released",threads==0,"remaining="+threads);
        int failures=0;for(int i=0;i<checks.length();i++)if(!checks.getJSONObject(i).getBoolean("passed"))failures++;
        JSONObject report=new JSONObject();report.put("run_id",getIntent().getStringExtra("runId"));report.put("checks",checks);report.put("failed",failures);report.put("passed",checks.length()-failures);report.put("images_saved",false);
        report.put("manual_hotplug","not_executed");report.put("hardware_sync","not_executed");
        report.put("fd_snapshots",fdSnapshots);
        try(FileOutputStream stream=openFileOutput("uvc-acceptance.json",MODE_PRIVATE)){stream.write(report.toString(2).getBytes("UTF-8"));}
        text.append("\n完成：失败 "+failures);
        String encoded=Base64.encodeToString(report.toString().getBytes("UTF-8"),Base64.NO_WRAP);int parts=(encoded.length()+1999)/2000;
        for(int i=0;i<parts;i++)android.util.Log.i("UvcCameraAcceptance","REPORT_PART="+i+"/"+parts+":"+encoded.substring(i*2000,Math.min(encoded.length(),(i+1)*2000)));
        android.util.Log.i("UvcCameraAcceptance","DONE failures="+failures);
    }catch(Exception error){android.util.Log.e("UvcCameraAcceptance","report",error);}}
    @Override protected void onDestroy(){if(manager!=null)manager.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
