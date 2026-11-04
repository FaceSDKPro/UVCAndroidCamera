// Copyright 2026 FaceSDKPro. Apache-2.0.
// Exercise production callback locks/ref ownership with a counted JNI table.
#include "utilbase.h"
#include "UVCButtonCallback.h"
#include "UVCStatusCallback.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>

struct Ref { int id; bool global; };
static int globals, locals, calls; static bool missingMethod;
static JNINativeInterface table = {}; static JNIEnv env = {&table};
static UVCButtonCallback *buttonTarget; static UVCStatusCallback *statusTarget;
static uvc_button_callback_t *buttonEntry; static void *buttonUser;
static uvc_status_callback_t *statusEntry; static void *statusUser;
static Ref listener = {1, false}, replacement = {2, false};
static int identity(jobject ref) { return ref ? reinterpret_cast<Ref *>(ref)->id : 0; }
static jobject ref(int id, bool global) {
    Ref *r = static_cast<Ref *>(malloc(sizeof(Ref))); assert(r); *r = {id, global};
    if (global) ++globals; else ++locals; return reinterpret_cast<jobject>(r);
}
static jobject newGlobal(JNIEnv *, jobject r) { return ref(identity(r), true); }
static jobject newLocal(JNIEnv *, jobject r) { return ref(identity(r), false); }
static void deleteGlobal(JNIEnv *, jobject r) { assert(r && reinterpret_cast<Ref *>(r)->global); --globals; free(r); }
static void deleteLocal(JNIEnv *, jobject r) { assert(r && !reinterpret_cast<Ref *>(r)->global); --locals; free(r); }
static jboolean same(JNIEnv *, jobject a, jobject b) { return identity(a) == identity(b); }
static jclass objectClass(JNIEnv *, jobject) { return reinterpret_cast<jclass>(ref(100, false)); }
static jmethodID method(JNIEnv *, jclass, const char *name, const char *) {
    return missingMethod ? nullptr : reinterpret_cast<jmethodID>(!strcmp(name, "onButton") ? 1 : 2);
}
static void clearException(JNIEnv *) {} static jboolean hasException(JNIEnv *) { return JNI_FALSE; }
static jobject buffer(JNIEnv *, void *data, jlong size) { assert(data && size == 4); return ref(200, false); }
static void invoke(JNIEnv *e, jobject callback, jmethodID id, va_list) {
    assert(identity(callback) == 1); ++calls;
    // Replace the global listener from inside its own Java callback. The local
    // snapshot must remain alive and the setter mutex must be unlocked.
    if (reinterpret_cast<uintptr_t>(id) == 1) buttonTarget->setCallback(e, newGlobal(e, reinterpret_cast<jobject>(&replacement)));
    else statusTarget->setCallback(e, newGlobal(e, reinterpret_cast<jobject>(&replacement)));
    assert(identity(callback) == 1);
}
JavaVM *getVM() { return nullptr; } JNIEnv *getEnv() { return &env; }
extern "C" void uvc_set_button_callback(uvc_device_handle_t *, uvc_button_callback_t cb, void *user) { buttonEntry = cb; buttonUser = user; }
extern "C" void uvc_set_status_callback(uvc_device_handle_t *, uvc_status_callback_t cb, void *user) { statusEntry = cb; statusUser = user; }
int main(int argc, char **argv) {
    alarm(5); // An old lock-held callback must fail instead of hanging the test.
    table.NewGlobalRef=newGlobal; table.NewLocalRef=newLocal; table.DeleteGlobalRef=deleteGlobal; table.DeleteLocalRef=deleteLocal;
    table.IsSameObject=same; table.GetObjectClass=objectClass; table.GetMethodID=method;
    table.ExceptionCheck=hasException; table.ExceptionClear=clearException; table.NewDirectByteBuffer=buffer; table.CallVoidMethodV=invoke;
    UVCButtonCallback button(nullptr); UVCStatusCallback status(nullptr); buttonTarget=&button; statusTarget=&status;
    jobject root=reinterpret_cast<jobject>(&listener);
    const bool reentrantOnly=argc>1 && !strcmp(argv[1],"--reentrant-only");
    for (int kind=0;kind<2;++kind) {
        auto set=[&](jobject r){ if(kind==0)button.setCallback(&env,r);else status.setCallback(&env,r); };
        set(newGlobal(&env,root));
        if(!reentrantOnly) {
            for(int i=0;i<1000;++i)set(newGlobal(&env,root));
            assert(globals==1 && locals==0);
        }
        uint8_t data[4]={1,2,3,4};
        if(kind==0)buttonEntry(1,1,buttonUser);
        else statusEntry(static_cast<uvc_status_class>(0),0,0,static_cast<uvc_status_attribute>(0),data,4,statusUser);
        assert(globals==1 && locals==0);
        set(nullptr); assert(globals==0 && locals==0);
        if(!reentrantOnly){missingMethod=true;set(newGlobal(&env,root));missingMethod=false;assert(globals==0 && locals==0);}
    }
    assert(calls==2); alarm(0);
    printf("{\"reentrant_callbacks\":2,\"duplicate_setters\":%d,\"invalid_methods\":%d,\"global_refs\":%d,\"local_refs\":%d,\"failed\":0,\"jni_table_simulated\":true}\n",reentrantOnly?0:2000,reentrantOnly?0:2,globals,locals);
}
