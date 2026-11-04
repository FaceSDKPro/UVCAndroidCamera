// Copyright 2026 FaceSDKPro. Apache-2.0.
#include "libuvc.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <initializer_list>
static bool failRealloc; static void *watched; static int watchedFrees;
extern "C" void *__real_realloc(void *,size_t); extern "C" void __real_free(void *);
extern "C" void *__wrap_realloc(void *p,size_t size){if(failRealloc){failRealloc=false;return nullptr;}return __real_realloc(p,size);}
extern "C" void __wrap_free(void *p){if(p && p==watched)++watchedFrees;__real_free(p);}
extern "C" uvc_error_t uvc_ensure_frame_size(uvc_frame_t *, size_t);
static int checkRgbStride(){
 int cases=0;
 for(int width:{2,4,6,8,10,18,32,34})for(int height:{1,2,3}){
  int ss=width*2+6,ds=width*4+6;size_t sourceBytes=(height-1)*ss+width*2,targetBytes=height*ds;
  auto*src=static_cast<uint8_t*>(malloc(height*ss));auto*storage=static_cast<uint8_t*>(malloc(targetBytes+2));assert(src&&storage);
  memset(src,128,height*ss);memset(storage,0xcd,targetBytes+2);
  for(int y=0;y<height;++y)for(int x=0;x<width;++x)src[y*ss+x*2]=static_cast<uint8_t>(x*19+y*7);
  uvc_frame_t in={},out={};in.data=src;in.data_bytes=sourceBytes;in.width=width;in.height=height;in.step=ss;in.frame_format=UVC_FRAME_FORMAT_YUYV;
  out.data=storage+1;out.capacity_bytes=targetBytes;out.step=ds;
  assert(uvc_yuyv2rgbx(&in,&out)==UVC_SUCCESS);
  for(int y=0;y<height;++y){for(int x=0;x<width;++x){uint8_t gray=src[y*ss+x*2];for(int c=0;c<3;++c)assert(storage[1+y*ds+x*4+c]==gray);assert(storage[1+y*ds+x*4+3]==255);}for(int x=width*4;x<ds;++x)assert(storage[1+y*ds+x]==0xcd);}
  assert(storage[0]==0xcd&&storage[targetBytes+1]==0xcd);
  in.data_bytes=sourceBytes-1;assert(uvc_yuyv2rgbx(&in,&out)==UVC_ERROR_INVALID_PARAM);in.data_bytes=sourceBytes;
  out.capacity_bytes=(height-1)*ds+width*4-1;assert(uvc_yuyv2rgbx(&in,&out)==UVC_ERROR_NO_MEM);
  free(src);free(storage);++cases;
 }
 uint8_t color[4]={0,0,255,255},rgb[8]={};uvc_frame_t in={},out={};in.data=color;in.data_bytes=4;in.width=2;in.height=1;in.frame_format=UVC_FRAME_FORMAT_YUYV;
 out.data=rgb;out.capacity_bytes=8;assert(uvc_yuyv2rgbx(&in,&out)==UVC_SUCCESS);const uint8_t expected[8]={178,0,0,255,255,208,28,255};assert(!memcmp(rgb,expected,8));
 return cases;
}
int main(){
    uint8_t pixels[16]={1,2,3,4},metadata[8]={8,7,6,5,4,3,2,1};
    uvc_frame_t input={}; input.data=pixels;input.data_bytes=16;input.width=4;input.height=2;input.step=8;input.frame_format=UVC_FRAME_FORMAT_YUYV;
    uvc_frame_t *out=uvc_allocate_frame(16);assert(out);void *original=out->data;
    failRealloc=true;assert(uvc_ensure_frame_size(out,32)==UVC_ERROR_NO_MEM);assert(out->data==original && out->capacity_bytes==16 && out->data_bytes==16);
    input.metadata=metadata;input.metadata_bytes=4;assert(uvc_duplicate_frame(&input,out)==UVC_SUCCESS);assert(!memcmp(out->data,pixels,16)&&!memcmp(out->metadata,metadata,4));
    void *oldMetadata=out->metadata;input.metadata_bytes=8;failRealloc=true;
    assert(uvc_duplicate_frame(&input,out)==UVC_ERROR_NO_MEM);assert(out->metadata==oldMetadata && out->metadata_bytes==4);
    assert(uvc_duplicate_frame(&input,out)==UVC_SUCCESS && out->metadata_bytes==8);
    watched=out->metadata;input.metadata=nullptr;input.metadata_bytes=0;
    assert(uvc_duplicate_frame(&input,out)==UVC_SUCCESS && out->metadata_bytes==0 && out->metadata==watched);
    uvc_free_frame(out);assert(watchedFrees==1);watched=nullptr;
    uint8_t borrowedPixels[16]={},borrowedMetadata[4]={};out=uvc_allocate_frame(0);assert(out);out->library_owns_data=0;
    out->data=borrowedPixels;out->capacity_bytes=16;out->metadata=borrowedMetadata;out->metadata_bytes=4;
    input.metadata=metadata;input.metadata_bytes=8;assert(uvc_duplicate_frame(&input,out)==UVC_ERROR_NO_MEM);assert(out->metadata==borrowedMetadata);
    input.metadata_bytes=4;assert(uvc_duplicate_frame(&input,out)==UVC_SUCCESS);assert(!memcmp(borrowedMetadata,metadata,4));
    assert(uvc_duplicate_frame(nullptr,out)==UVC_ERROR_INVALID_PARAM);input.metadata=nullptr;assert(uvc_duplicate_frame(&input,out)==UVC_ERROR_INVALID_PARAM);
    input.metadata_bytes=0;input.data=nullptr;assert(uvc_duplicate_frame(&input,out)==UVC_ERROR_INVALID_PARAM);uvc_free_frame(out);
    int rgbCases=checkRgbStride();
    printf("{\"memory_checks\":10,\"rgb_stride_cases\":%d,\"rgb_color_fixture\":true,\"failed\":0,\"allocation_failure_injected\":true}\n",rgbCases);
}
