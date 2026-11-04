package com.facesdkpro.uvccamera.sample;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import com.facesdkpro.uvccamera.StreamConfig;
import com.facesdkpro.uvccamera.UvcCameraManager;
import com.facesdkpro.uvccamera.PreviewTransform;
import com.serenegiant.usb.UVCCamera;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Independent preview/control screen. Uses preview-only sessions and never saves images. */
public final class MainActivity extends Activity {
    private UvcCameraManager manager;
    private final UvcCameraManager.Camera[] cameras=new UvcCameraManager.Camera[2];
    private final Surface[] surfaces=new Surface[2];
    private final Spinner[] devices=new Spinner[2];
    private final SeekBar[] brightness=new SeekBar[2];
    private final TextView[] brightnessText=new TextView[2],deviceNames=new TextView[2],counters=new TextView[2];
    private final DemoUi.PreviewCard[] previews=new DemoUi.PreviewCard[2];
    private final Handler uiThread=new Handler(Looper.getMainLooper());
    private List<UsbDevice> available=new ArrayList<>();
    private DemoUi ui;
    private TextView status;
    private Button open,stop;
    private final CaptureSettingsView[] settings=new CaptureSettingsView[2];
    private final StreamConfig[] applied=new StreamConfig[2];
    private final int[] rotations=new int[2];
    private final long[] previousCounts=new long[2];
    private String[] restoredDevices;
    private long previousTick;
    private AlertDialog settingsDialog;
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            long now=android.os.SystemClock.elapsedRealtimeNanos();double seconds=previousTick==0?1:(now-previousTick)/1e9;
            for(int i=0;i<2;i++){
                UvcCameraManager.Camera camera=cameras[i];boolean active=camera!=null&&!camera.isClosed();
                long count=active?camera.receivedFrames():0;
                counters[i].setText(active?String.format(Locale.ROOT,"%.1f FPS   ·   已收到 %d 帧",Math.max(0,count-previousCounts[i])/seconds,count):"未打开 · 等待采集");
                if(!active)previews[i].state(false,"独立 UVC 视频流");
                previousCounts[i]=count;
            }
            previousTick=now;uiThread.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(128);ui=new DemoUi(this);
        LinearLayout screen=ui.column();screen.addView(ui.header("双路视频预览","RGB / IR · 独立设备控制",ui.button("返回",R.drawable.ic_back,false,this::finish)));
        status=ui.status("设备连接后可分别调整预览参数");screen.addView(status,ui.margin(-1,-2,20,0,20,14));
        int width=getResources().getConfiguration().screenWidthDp,height=getResources().getConfiguration().screenHeightDp;
        boolean compact=height<520||width<600;
        boolean sideBySide=width>=600;
        LinearLayout streams=sideBySide?ui.row():ui.column();
        for(int i=0;i<2;i++){
            final int slot=i;String role=i==0?"RGB":"IR";int color=i==0?ui.rgb:ui.ir;
            devices[i]=ui.spinner(role+" 设备");settings[i]=new CaptureSettingsView(this,role);
            if(state!=null)settings[i].restore(state.getIntArray("capture_"+i));
            brightness[i]=new SeekBar(this);brightness[i].setContentDescription(role+" 设备亮度");brightness[i].setMax(100);brightness[i].setEnabled(false);
            brightness[i].setProgressTintList(ColorStateList.valueOf(color));brightness[i].setThumbTintList(ColorStateList.valueOf(color));
            brightnessText[i]=ui.label("打开摄像头后可调节设备亮度",12,ui.muted);
            brightness[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int value,boolean user){
                    if(user&&cameras[slot]!=null){brightnessText[slot].setText("设备亮度 · "+value+"%");cameras[slot].withControls(control->control.setBrightnessPercent(value));}
                }
                public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
            });
            LinearLayout channel=ui.column();LinearLayout title=ui.row();
            TextView roleMark=ui.strong(role,13,color);roleMark.setPadding(ui.dp(9),ui.dp(6),ui.dp(9),ui.dp(6));roleMark.setBackground(ui.shape(ui.tint(color,20),8,0));title.addView(roleMark);ui.gap(title,10);
            deviceNames[i]=ui.label("未选择设备",12,ui.muted);deviceNames[i].setSingleLine();deviceNames[i].setEllipsize(TextUtils.TruncateAt.END);title.addView(deviceNames[i],new LinearLayout.LayoutParams(0,-2,1));ui.gap(title,8);
            Button parameters=ui.button("参数",R.drawable.ic_tune,false,()->showSettings(slot));parameters.setContentDescription(role+" 参数");title.addView(parameters);channel.addView(title);ui.gap(channel,10);
            previews[i]=ui.new PreviewCard(i==0?"RGB 可见光":"IR 近红外","独立 UVC 视频流",color);channel.addView(previews[i],compact?new LinearLayout.LayoutParams(-1,ui.dp(260)):new LinearLayout.LayoutParams(-1,0,1));
            counters[i]=ui.label("未打开 · 等待采集",12,ui.muted);counters[i].setContentDescription(role+" 预览统计");counters[i].setPadding(ui.dp(4),ui.dp(10),0,0);channel.addView(counters[i]);
            bindSurface(i);
            if(sideBySide){if(i>0)ui.gap(streams,16);streams.addView(channel,new LinearLayout.LayoutParams(0,compact?-2:-1,1));}
            else{if(i>0)ui.gap(streams,12);streams.addView(channel,new LinearLayout.LayoutParams(-1,compact?-2:0,compact?0:1));}
        }
        LinearLayout.LayoutParams content=new LinearLayout.LayoutParams(-1,0,1);content.setMargins(ui.dp(20),0,ui.dp(20),0);
        if(compact){ScrollView page=new ScrollView(this);page.addView(streams);screen.addView(page,content);}
        else screen.addView(streams,content);
        LinearLayout footer=ui.row();footer.setPadding(ui.dp(20),ui.dp(14),ui.dp(20),ui.dp(14));
        open=ui.button("打开双路",0,true,this::requestOpen);
        if(width>=900||width>=640&&width>height){
            footer.addView(ui.label("两路预览独立旋转 · 参数按设备能力设置",12,ui.muted),new LinearLayout.LayoutParams(0,-2,1));
            footer.addView(open,new LinearLayout.LayoutParams(ui.dp(200),-2));
        }else footer.addView(open,new LinearLayout.LayoutParams(0,-2,1));
        ui.gap(footer,10);
        stop=ui.button("停止",R.drawable.ic_stop,false,this::stopPair);stop.setEnabled(false);footer.addView(stop,new LinearLayout.LayoutParams(ui.dp(100),-2));ui.gap(footer,10);
        footer.addView(ui.iconButton("刷新设备",R.drawable.ic_refresh,this::refreshDevices),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));screen.addView(footer);
        if(state!=null)restoredDevices=state.getStringArray("devices");ui.install(this,screen);
    }
    private void bindSurface(int slot){
        previews[slot].texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){surfaces[slot]=new Surface(texture);fitPreview(slot);}
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){fitPreview(slot);}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
                if(cameras[slot]!=null)cameras[slot].close();if(surfaces[slot]!=null)surfaces[slot].release();surfaces[slot]=null;return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        });
    }
    private void showSettings(int slot){
        if(settingsDialog!=null)return;
        String role=slot==0?"RGB":"IR";int[] previous=settings[slot].selection();int selected=devices[slot].getSelectedItemPosition();
        LinearLayout panel=ui.column();panel.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(12));
        detach(devices[slot]);detach(settings[slot]);detach(brightness[slot]);detach(brightnessText[slot]);
        panel.addView(ui.field("USB 设备",devices[slot]));ui.gap(panel,16);panel.addView(settings[slot]);ui.gap(panel,18);
        panel.addView(brightnessText[slot]);ui.gap(panel,6);panel.addView(brightness[slot],new LinearLayout.LayoutParams(-1,ui.dp(44)));
        ui.gap(panel,10);panel.addView(ui.label("不支持的采集组合会明确报错。应用后，正在运行的两路预览将重新打开。",12,ui.muted));
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        Runnable restore=()->{settings[slot].restore(previous);devices[slot].setSelection(selected);};
        settingsDialog=new AlertDialog.Builder(this).setTitle(role+" 采集设置").setView(scroll)
                .setNegativeButton("取消",(dialog,which)->restore.run())
                .setPositiveButton("应用设置",(dialog,which)->{
                    updateDeviceNames();boolean active=false;for(UvcCameraManager.Camera camera:cameras)if(camera!=null&&!camera.isClosed())active=true;
                    if(active)requestOpen();else ui.setStatus(status,"参数已更新 · 点击打开双路开始预览",ui.muted);
                }).create();
        settingsDialog.setOnCancelListener(dialog->restore.run());
        settingsDialog.setOnDismissListener(dialog->{detach(devices[slot]);detach(settings[slot]);detach(brightness[slot]);detach(brightnessText[slot]);settingsDialog=null;});
        settingsDialog.show();settingsDialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface,20,ui.border));
        settingsDialog.getWindow().setLayout(ui.dp(Math.min(480,getResources().getConfiguration().screenWidthDp-40)),-2);
    }
    private void detach(android.view.View view){if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);}
    private void requestOpen(){if(checkSelfPermission("android.permission.CAMERA")==PackageManager.PERMISSION_GRANTED)openPair();else requestPermissions(new String[]{"android.permission.CAMERA"},1);}
    private void fitPreview(int slot){
        TextureView texture=previews[slot].texture;if(texture.getWidth()==0||texture.getHeight()==0)return;
        StreamConfig config=applied[slot]==null?settings[slot].config(UVCCamera.PIXEL_FORMAT_NV21):applied[slot];
        texture.setTransform(PreviewTransform.fitCenter(texture.getWidth(),texture.getHeight(),config.width,config.height,rotations[slot]));
    }
    @Override protected void onStart(){
        super.onStart();manager=new UvcCameraManager(this,new UvcCameraManager.Listener(){
            public void onAttached(UsbDevice device){refreshDevices();}
            public void onDetached(UsbDevice device){ui.setStatus(status,"摄像头已断开 · 请重新选择设备",ui.error);refreshDevices();}
            public void onOpened(UvcCameraManager.Camera camera){
                ui.setStatus(status,"预览运行中 · 两路设备独立采集",ui.accent);open.setEnabled(true);open.setText("应用并重启");
                for(int i=0;i<2;i++)if(cameras[i]==camera){
                    final int slot=i;previews[i].state(true,camera.config.width+"×"+camera.config.height+" · "+camera.config.fps+" FPS · "+rotations[i]+"°");
                    camera.withControls(control->{boolean enabled=control.isBrightnessEnable();int value=enabled?control.getBrightnessPercent():0;
                        runOnUiThread(()->{if(cameras[slot]==camera&&!camera.isClosed()){
                            brightness[slot].setEnabled(enabled);brightness[slot].setProgress(value);
                            brightnessText[slot].setText(enabled?"设备亮度 · "+value+"%":"当前设备不支持亮度调节");
                        }});
                    });
                }
            }
            public void onError(UsbDevice device,String message,Throwable cause){open.setEnabled(true);ui.setStatus(status,message+(cause==null?"":" · "+cause.getMessage()),ui.error);}
        });refreshDevices();uiThread.post(tick);
    }
    private String[] selectedDeviceNames(){
        String[] names=new String[2];for(int i=0;i<2;i++){int position=devices[i].getSelectedItemPosition()-1;if(position>=0&&position<available.size())names[i]=available.get(position).getDeviceName();}return names;
    }
    private void refreshDevices(){
        if(manager==null)return;String[] selected=selectedDeviceNames();if(restoredDevices!=null&&restoredDevices.length==2){selected=restoredDevices;restoredDevices=null;}
        available=manager.devices();
        for(int role=0;role<2;role++){
            List<String> labels=new ArrayList<>();labels.add(role==0?"请选择 RGB 摄像头":"请选择 IR 摄像头");int choice=0;
            for(int i=0;i<available.size();i++){
                UsbDevice device=available.get(i);labels.add(String.format(Locale.ROOT,"%s · %04X:%04X",device.getProductName(),device.getVendorId(),device.getProductId()));
                boolean known=role==0?device.getVendorId()==0x1187&&device.getProductId()==0x3a49:device.getVendorId()==0x1871&&device.getProductId()==0x3b49;
                if(device.getDeviceName().equals(selected[role])||selected[role]==null&&known)choice=i+1;
            }
            ui.items(devices[role],labels);devices[role].setSelection(choice);
        }updateDeviceNames();
    }
    private void updateDeviceNames(){for(int i=0;i<2;i++){int index=devices[i].getSelectedItemPosition()-1;deviceNames[i].setText(index>=0&&index<available.size()?String.valueOf(available.get(index).getProductName()):"在参数面板选择设备");}}
    private void openPair(){
        if(manager==null||available.size()<2){ui.setStatus(status,"请连接两个独立 UVC 摄像头",ui.error);return;}
        int a=devices[0].getSelectedItemPosition()-1,b=devices[1].getSelectedItemPosition()-1;
        if(a<0||b<0||a>=available.size()||b>=available.size()||a==b){ui.setStatus(status,"RGB 与 IR 必须选择不同设备",ui.error);return;}
        if(surfaces[0]==null||surfaces[1]==null){ui.setStatus(status,"预览正在准备，请稍后重试",ui.ir);return;}
        stopPair();open.setEnabled(false);stop.setEnabled(true);int[] chosen={a,b};previousTick=0;
        for(int i=0;i<2;i++){
            StreamConfig config=settings[i].config(UVCCamera.PIXEL_FORMAT_NV21);applied[i]=config;rotations[i]=settings[i].rotationDegrees();previousCounts[i]=0;
            fitPreview(i);previews[i].connecting();cameras[i]=manager.open(available.get(chosen[i]),config,surfaces[i],null);
        }ui.setStatus(status,"正在连接 · 请允许相机与 USB 设备访问",ui.ir);
    }
    private void stopPair(){
        for(int i=0;i<2;i++){if(cameras[i]!=null)cameras[i].close();cameras[i]=null;brightness[i].setEnabled(false);brightnessText[i].setText("打开摄像头后可调节设备亮度");previews[i].state(false,"独立 UVC 视频流");}
        open.setEnabled(true);open.setText("打开双路");stop.setEnabled(false);ui.setStatus(status,"预览已停止 · 可分别调整 RGB / IR 参数",ui.muted);
    }
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] values){super.onRequestPermissionsResult(request,names,values);if(values.length>0&&values[0]==PackageManager.PERMISSION_GRANTED)openPair();else ui.setStatus(status,"需要相机权限才能开始预览",ui.error);}
    @Override protected void onSaveInstanceState(Bundle out){
        if(settingsDialog!=null)settingsDialog.cancel();for(int i=0;i<2;i++)out.putIntArray("capture_"+i,settings[i].selection());out.putStringArray("devices",selectedDeviceNames());super.onSaveInstanceState(out);
    }
    @Override protected void onStop(){
        if(settingsDialog!=null)settingsDialog.cancel();uiThread.removeCallbacks(tick);stopPair();if(manager!=null)manager.close();manager=null;super.onStop();
    }
}
