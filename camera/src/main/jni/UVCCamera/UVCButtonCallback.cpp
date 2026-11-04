// Modified by FaceSDKPro, 2026-11-04; see UPSTREAM.md.
#include <stdlib.h>
#include <linux/time.h>
#include <unistd.h>
#include "utilbase.h"
#include "UVCButtonCallback.h"
#include "libuvc_internal.h"

#define	LOCAL_DEBUG 0

UVCButtonCallback::UVCButtonCallback(uvc_device_handle_t *devh)
:	mDeviceHandle(devh),
	mButtonCallbackObj(NULL),
	ibuttoncallback_fields{} {

	ENTER();
	pthread_mutex_init(&button_mutex, NULL);

	uvc_set_button_callback(mDeviceHandle, uvc_button_callback, (void *)this);
	EXIT();
}

UVCButtonCallback::~UVCButtonCallback() {

	ENTER();
	pthread_mutex_destroy(&button_mutex);
	mButtonCallbackObj = NULL;
	ibuttoncallback_fields.onButton = NULL;
	EXIT();
}

int UVCButtonCallback::setCallback(JNIEnv *env, jobject button_callback_obj) {
	
	ENTER();
	pthread_mutex_lock(&button_mutex);
	{
		if (!env->IsSameObject(mButtonCallbackObj, button_callback_obj)) {
			ibuttoncallback_fields.onButton = NULL;
			if (mButtonCallbackObj) {
				env->DeleteGlobalRef(mButtonCallbackObj);
			}
			mButtonCallbackObj = button_callback_obj;
			if (button_callback_obj) {
				// get method IDs of Java object for callback
				jclass clazz = env->GetObjectClass(button_callback_obj);
				if (LIKELY(clazz)) {
					ibuttoncallback_fields.onButton = env->GetMethodID(clazz,
						"onButton",	"(II)V");
					env->DeleteLocalRef(clazz);
				} else {
					LOGW("failed to get object class");
				}
				env->ExceptionClear();
				if (!ibuttoncallback_fields.onButton) {
					LOGE("Can't find IButtonCallback#onButton");
					env->DeleteGlobalRef(button_callback_obj);
					mButtonCallbackObj = button_callback_obj = NULL;
				}
			}
		} else if (button_callback_obj) {
            // Each JNI setter receives a new global ref, even for the same object.
            env->DeleteGlobalRef(button_callback_obj);
        }
	}
	pthread_mutex_unlock(&button_mutex);
	RETURN(0, int);
}

void UVCButtonCallback::notifyButtonCallback(JNIEnv *env, int button, int state) {
    // Snapshot a local ref under the mutex. Java may clear/replace this listener
    // reentrantly; never call it while holding the setter's mutex.
    pthread_mutex_lock(&button_mutex);
    jobject callback = mButtonCallbackObj ? env->NewLocalRef(mButtonCallbackObj) : NULL;
    jmethodID method = ibuttoncallback_fields.onButton;
    pthread_mutex_unlock(&button_mutex);
    if (callback) {
        env->CallVoidMethod(callback, method, button, state);
        env->DeleteLocalRef(callback);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void UVCButtonCallback::uvc_button_callback(int button, int state, void *user_ptr) {

	UVCButtonCallback *buttonCallback = reinterpret_cast<UVCButtonCallback *>(user_ptr);

	JavaVM *vm = getVM();
	JNIEnv *env = getEnv();
	bool  isAttached = false;
	if(env == NULL){
		// attach current thread to JavaVM
		if (vm->AttachCurrentThread(&env, NULL) != JNI_OK) return;
		isAttached = true;
	}

	buttonCallback->notifyButtonCallback(env, button, state);

	if(isAttached){
		vm->DetachCurrentThread();
	}
}
