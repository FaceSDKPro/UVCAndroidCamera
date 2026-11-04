// Modified by FaceSDKPro, 2026-11-04; see UPSTREAM.md.
#include <stdlib.h>
#include <linux/time.h>
#include <unistd.h>
#include "utilbase.h"
#include "UVCStatusCallback.h"
#include "libuvc_internal.h"

#define	LOCAL_DEBUG 0

UVCStatusCallback::UVCStatusCallback(uvc_device_handle_t *devh)
:	mDeviceHandle(devh),
	mStatusCallbackObj(NULL),
	istatuscallback_fields{} {

	ENTER();
	pthread_mutex_init(&status_mutex, NULL);

	uvc_set_status_callback(mDeviceHandle, uvc_status_callback, (void *)this);
	EXIT();
}

UVCStatusCallback::~UVCStatusCallback() {

	ENTER();
	pthread_mutex_destroy(&status_mutex);
	EXIT();
}

int UVCStatusCallback::setCallback(JNIEnv *env, jobject status_callback_obj) {
	
	ENTER();
	pthread_mutex_lock(&status_mutex);
	{
		if (!env->IsSameObject(mStatusCallbackObj, status_callback_obj)) {
			istatuscallback_fields.onStatus = NULL;
			if (mStatusCallbackObj) {
				env->DeleteGlobalRef(mStatusCallbackObj);
			}
			mStatusCallbackObj = status_callback_obj;
			if (status_callback_obj) {
				// get method IDs of Java object for callback
				jclass clazz = env->GetObjectClass(status_callback_obj);
				if (LIKELY(clazz)) {
					istatuscallback_fields.onStatus = env->GetMethodID(clazz,
						"onStatus",	"(IIIILjava/nio/ByteBuffer;)V");
					env->DeleteLocalRef(clazz);
				} else {
					LOGW("failed to get object class");
				}
				env->ExceptionClear();
				if (!istatuscallback_fields.onStatus) {
					LOGE("Can't find IStatusCallback#onStatus");
					env->DeleteGlobalRef(status_callback_obj);
					mStatusCallbackObj = status_callback_obj = NULL;
				}
			}
		} else if (status_callback_obj) {
            // Each JNI setter receives a new global ref, even for the same object.
            env->DeleteGlobalRef(status_callback_obj);
        }
	}
	pthread_mutex_unlock(&status_mutex);
	RETURN(0, int);
}

void UVCStatusCallback::notifyStatusCallback(JNIEnv *env, uvc_status_class status_class, int event, int selector, uvc_status_attribute status_attribute, void *data, size_t data_len) {
    // Snapshot a local ref under the mutex. Java may clear/replace this listener
    // reentrantly; never call it while holding the setter's mutex.
    pthread_mutex_lock(&status_mutex);
    jobject callback = mStatusCallbackObj ? env->NewLocalRef(mStatusCallbackObj) : NULL;
    jmethodID method = istatuscallback_fields.onStatus;
    pthread_mutex_unlock(&status_mutex);
    if (callback) {
        jobject buf = env->NewDirectByteBuffer(data, data_len);
        if (buf) {
            env->CallVoidMethod(callback, method, (int)status_class, event, selector, (int)status_attribute, buf);
            env->DeleteLocalRef(buf);
        }
        env->DeleteLocalRef(callback);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void UVCStatusCallback::uvc_status_callback(uvc_status_class status_class, int event, int selector, uvc_status_attribute status_attribute, void *data, size_t data_len, void *user_ptr) {

	UVCStatusCallback *statusCallback = reinterpret_cast<UVCStatusCallback *>(user_ptr);

	JavaVM *vm = getVM();
	JNIEnv *env = getEnv();
	bool  isAttached = false;
	if(env == NULL){
		// attach current thread to JavaVM
		if (vm->AttachCurrentThread(&env, NULL) != JNI_OK) return;
		isAttached = true;
	}

	statusCallback->notifyStatusCallback(env, status_class, event, selector, status_attribute, data, data_len);

	if(isAttached){
		vm->DetachCurrentThread();
	}
}
