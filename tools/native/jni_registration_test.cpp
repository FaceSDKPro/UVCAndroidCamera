// Copyright 2026 FaceSDKPro. Apache-2.0.
#include "utilbase.h"
#include <assert.h>
#include <stdio.h>
static JNIInvokeInterface functions={};static JavaVM vm={&functions};
static JNINativeInterface nativeFunctions={};static JNIEnv env={&nativeFunctions};
static int getEnvResult,cameraResult,controlResult,cameraCalls,controlCalls,vmSets;
static jint getEnv(JavaVM*,void **out,jint version){assert(version==JNI_VERSION_1_6);*out=&env;return getEnvResult;}
int register_uvccamera(JNIEnv *e){assert(e==&env);++cameraCalls;return cameraResult;}
int register_uvccontrol(JNIEnv *e){assert(e==&env);++controlCalls;return controlResult;}
void setVM(JavaVM *v){assert(v==&vm);++vmSets;}
extern "C" jint JNI_OnLoad(JavaVM*,void*);
int main(){functions.GetEnv=getEnv;
 for(int scenario=0;scenario<4;++scenario){getEnvResult=scenario==0?JNI_ERR:JNI_OK;cameraResult=scenario==1?-1:0;controlResult=scenario==2?-1:0;cameraCalls=controlCalls=vmSets=0;
  const jint result=JNI_OnLoad(&vm,nullptr);assert(result==(scenario==3?JNI_VERSION_1_6:JNI_ERR));assert(vmSets==(scenario==3?1:0));
  assert(cameraCalls==(scenario==0?0:1));assert(controlCalls==(scenario>=2?1:0));
 }printf("{\"registration_cases\":4,\"failed\":0,\"jni_table_simulated\":true}\n");}
