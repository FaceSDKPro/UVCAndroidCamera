# JNI registers these upstream names and callback signatures.
-keep class com.serenegiant.usb.UVCCamera { *; }
-keep class com.serenegiant.usb.UVCControl { *; }
-keep interface com.serenegiant.usb.IFrameCallback { *; }
-keep class com.facesdkpro.uvccamera.FrameTransforms { *; }
-keep interface com.serenegiant.usb.IButtonCallback { *; }
-keep interface com.serenegiant.usb.IStatusCallback { *; }
-keepclassmembers class * implements com.serenegiant.usb.IFrameCallback { public void onFrame(java.nio.ByteBuffer); }
-keepclassmembers class * implements com.serenegiant.usb.IButtonCallback { *; }
-keepclassmembers class * implements com.serenegiant.usb.IStatusCallback { *; }
