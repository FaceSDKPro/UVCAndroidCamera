// Copyright 2026 FaceSDKPro. Apache-2.0.
// Production stream/frame sources, deterministic USB and allocator failures.
#include "libuvc.h"
extern "C" {
#include "libuvc_internal.h"
}
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <initializer_list>
static int mallocAt,callocAt,transferAt,submitAt,reallocAt;
static int mallocCalls,callocCalls,transferCalls,submitCalls,reallocCalls,liveTransfers,claims,releases,cases;
static bool failThread;static pthread_t cancelThreads[LIBUVC_NUM_TRANSFER_BUFS];static int cancels;
extern "C" void *__real_malloc(size_t);extern "C" void *__real_calloc(size_t,size_t);extern "C" void *__real_realloc(void*,size_t);
extern "C" int __real_pthread_create(pthread_t*,const pthread_attr_t*,void*(*)(void*),void*);
extern "C" void *__wrap_malloc(size_t n){if(++mallocCalls==mallocAt)return nullptr;return __real_malloc(n);}
extern "C" void *__wrap_calloc(size_t n,size_t s){if(++callocCalls==callocAt)return nullptr;return __real_calloc(n,s);}
extern "C" void *__wrap_realloc(void*p,size_t n){if(++reallocCalls==reallocAt)return nullptr;return __real_realloc(p,n);}
extern "C" int __wrap_pthread_create(pthread_t*t,const pthread_attr_t*a,void*(*fn)(void*),void*p){if(failThread){failThread=false;return EAGAIN;}return __real_pthread_create(t,a,fn,p);}
extern "C" uvc_error_t uvc_claim_if(uvc_device_handle_t*,int){++claims;return UVC_SUCCESS;}
extern "C" uvc_error_t uvc_release_if(uvc_device_handle_t*,int){++releases;return UVC_SUCCESS;}
extern "C" uvc_error_t __wrap_uvc_stream_ctrl(uvc_stream_handle_t*s,uvc_stream_ctrl_t*c){s->cur_ctrl=*c;return UVC_SUCCESS;}
extern "C" libusb_transfer *libusb_alloc_transfer(int packets){if(++transferCalls==transferAt)return nullptr;auto*t=static_cast<libusb_transfer*>(calloc(1,sizeof(libusb_transfer)+packets*sizeof(libusb_iso_packet_descriptor)));if(t)++liveTransfers;return t;}
extern "C" void libusb_free_transfer(libusb_transfer*t){if(t){--liveTransfers;free(t);}}
extern "C" int libusb_submit_transfer(libusb_transfer*){return ++submitCalls==submitAt?LIBUSB_ERROR_IO:0;}
static void *cancel(void*p){auto*t=static_cast<libusb_transfer*>(p);t->status=LIBUSB_TRANSFER_CANCELLED;t->callback(t);return nullptr;}
extern "C" int libusb_cancel_transfer(libusb_transfer*t){assert(cancels<LIBUVC_NUM_TRANSFER_BUFS);assert(!__real_pthread_create(&cancelThreads[cancels++],nullptr,cancel,t));return 0;}
extern "C" int libusb_set_interface_alt_setting(libusb_device_handle*,int,int){return 0;}
extern "C" int libusb_get_ss_endpoint_companion_descriptor(libusb_context*,const libusb_endpoint_descriptor*,libusb_ss_endpoint_companion_descriptor**out){*out=nullptr;return LIBUSB_ERROR_NOT_FOUND;}
extern "C" void libusb_free_ss_endpoint_companion_descriptor(libusb_ss_endpoint_companion_descriptor*){}
extern "C" uvc_error_t _uvc_populate_frame(uvc_stream_handle_t*);
static void callback(uvc_frame_t*,void*){}
static void reset(){mallocAt=callocAt=transferAt=submitAt=reallocAt=0;mallocCalls=callocCalls=transferCalls=submitCalls=reallocCalls=0;claims=releases=0;failThread=false;assert(liveTransfers==0);for(int i=0;i<cancels;++i)pthread_join(cancelThreads[i],nullptr);cancels=0;}
struct Fixture {
 uvc_device_handle_t dev={};uvc_device_info_t info={};uvc_streaming_interface_t si={};uvc_format_desc_t format={};uvc_frame_desc_t desc={};
 libusb_config_descriptor config={};libusb_interface interface={};libusb_interface_descriptor alternatives[2]={};libusb_endpoint_descriptor endpoint={};uvc_stream_handle_t stream={};uvc_stream_ctrl_t control={};
 uint8_t image[16]={1,2,3},metadata[8]={8,7,6};
 Fixture(){
  dev.info=&info;info.config=&config;config.interface=&interface;interface.num_altsetting=1;info.stream_ifs=&si;
  si.format_descs=&format;si.bEndpointAddress=0x81;format.parent=&si;format.bFormatIndex=1;format.frame_descs=&desc;
  const uint8_t guid[16]={'Y','U','Y','2',0,0,0x10,0,0x80,0,0,0xaa,0,0x38,0x9b,0x71};memcpy(format.guidFormat,guid,16);
  desc.parent=&format;desc.bFrameIndex=1;desc.wWidth=4;desc.wHeight=2;
  control.bFormatIndex=control.bFrameIndex=1;control.dwMaxVideoFrameSize=16;control.dwMaxPayloadTransferSize=32;
  stream.devh=&dev;stream.stream_if=&si;stream.cur_ctrl=control;stream.frame.library_owns_data=1;
  pthread_mutex_init(&stream.cb_mutex,nullptr);pthread_cond_init(&stream.cb_cond,nullptr);
 }
 ~Fixture(){assert(!stream.running);for(int i=0;i<cancels;++i)pthread_join(cancelThreads[i],nullptr);cancels=0;free(stream.frame.data);free(stream.frame.metadata);pthread_cond_destroy(&stream.cb_cond);pthread_mutex_destroy(&stream.cb_mutex);}
 void iso(bool valid){interface.num_altsetting=2;interface.altsetting=alternatives;alternatives[0].bNumEndpoints=1;alternatives[0].endpoint=&endpoint;endpoint.bEndpointAddress=0x81;endpoint.wMaxPacketSize=valid?32:0;stream.cur_ctrl.dwMaxPayloadTransferSize=valid?32:0;}
 void clean(){assert(!stream.running&&liveTransfers==0);for(int i=0;i<LIBUVC_NUM_TRANSFER_BUFS;++i)assert(!stream.transfers[i]&&!stream.transfer_bufs[i]);}
};
int main(){
 alarm(10);
 for(int failure=0;failure<5;++failure){reset();Fixture f;if(failure==0)callocAt=1;else mallocAt=failure;uvc_stream_handle_t*out=nullptr;
  assert(uvc_stream_open_ctrl(&f.dev,&out,&f.control)==UVC_ERROR_NO_MEM);assert(!f.dev.streams);assert(claims==releases);++cases;}
 for(int kind=0;kind<2;++kind)for(int nth:{1,5}){reset();Fixture f;if(kind==0)transferAt=nth;else mallocAt=nth;
  assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_ERROR_NO_MEM);f.clean();++cases;}
 {reset();Fixture f;f.iso(false);assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_ERROR_INVALID_MODE);f.clean();++cases;}
 for(int kind=0;kind<2;++kind){reset();Fixture f;f.iso(true);if(kind==0)transferAt=1;else mallocAt=1;assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_ERROR_NO_MEM);f.clean();++cases;}
 {reset();Fixture f;f.iso(true);assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_SUCCESS);assert(uvc_stream_stop(&f.stream)==UVC_SUCCESS);f.clean();++cases;}
 {reset();Fixture f;failThread=true;assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_ERROR_OTHER);f.clean();++cases;}
 for(int nth:{1,3}){reset();Fixture f;submitAt=nth;
  if(nth==1)assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_ERROR_IO);
  else{assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_SUCCESS);assert(liveTransfers==2);assert(uvc_stream_stop(&f.stream)==UVC_SUCCESS);}
  f.clean();++cases;}
 {reset();Fixture f;assert(uvc_stream_start(&f.stream,callback,nullptr,0)==UVC_SUCCESS);assert(liveTransfers==LIBUVC_NUM_TRANSFER_BUFS);
  assert(uvc_stream_stop(&f.stream)==UVC_SUCCESS);f.clean();++cases;}
 {reset();Fixture f;assert(uvc_stream_start(&f.stream,nullptr,nullptr,0)==UVC_SUCCESS);assert(uvc_stream_stop(&f.stream)==UVC_SUCCESS);f.clean();
  f.stream.holdbuf=f.image;f.stream.hold_bytes=sizeof(f.image);f.stream.meta_holdbuf=f.metadata;f.stream.meta_hold_bytes=4;f.stream.hold_seq=7;
  reallocAt=1;assert(_uvc_populate_frame(&f.stream)==UVC_ERROR_NO_MEM);assert(!f.stream.frame.data);
  reallocAt=0;assert(_uvc_populate_frame(&f.stream)==UVC_SUCCESS);assert(f.stream.frame.width==4&&f.stream.frame.height==2&&f.stream.frame.step==8&&f.stream.frame.sequence==7);
  assert(!memcmp(f.stream.frame.data,f.image,16)&&f.stream.frame.metadata_bytes==4);
  void*old=f.stream.frame.metadata;f.stream.meta_hold_bytes=8;reallocAt=reallocCalls+1;assert(_uvc_populate_frame(&f.stream)==UVC_ERROR_NO_MEM);assert(f.stream.frame.metadata==old);
  reallocAt=0;f.stream.meta_hold_bytes=0;assert(_uvc_populate_frame(&f.stream)==UVC_SUCCESS&&f.stream.frame.metadata_bytes==0);++cases;}
 reset();alarm(0);printf("{\"stream_cases\":%d,\"failed\":0,\"live_transfers\":0,\"usb_simulated\":true}\n",cases);
}
