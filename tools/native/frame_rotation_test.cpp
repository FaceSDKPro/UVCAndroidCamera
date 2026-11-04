// Copyright 2026 FaceSDKPro. Apache-2.0. Synthetic pixels only.
#include "FrameRotation.h"
#include <assert.h>
#include <stdio.h>
#include <vector>

int main() {
    int cases = 0;
    for (int format : {2,3,8}) for (int w : {2,4,34,640}) for (int h : {2,6,36}) for (int degrees : {0,90,180,270}) {
        const size_t ySize = static_cast<size_t>(w) * h, size = format == 8 ? ySize : ySize * 3 / 2;
        std::vector<uint8_t> input(size + 2), output(size + 2, 0xcd);
        for (size_t i = 0; i < size; ++i) input[i+1] = static_cast<uint8_t>(i * 73 + 17);
        assert(rotatePackedFrame(input.data()+1,size,output.data()+1,size,w,h,format,degrees));
        const int dw = degrees % 180 ? h : w;
        for (int y=0;y<h;++y) for (int x=0;x<w;++x) {
            int dx=degrees==90?h-1-y:degrees==180?w-1-x:degrees==270?y:x;
            int dy=degrees==90?x:degrees==180?h-1-y:degrees==270?w-1-x:y;
            assert(output[1+dy*dw+dx]==input[1+y*w+x]);
        }
        if (format != 8) for(int y=0;y<h/2;++y) for(int x=0;x<w/2;++x) for(int c=0;c<2;++c) {
            int dx=degrees==90?h/2-1-y:degrees==180?w/2-1-x:degrees==270?y:x;
            int dy=degrees==90?x:degrees==180?h/2-1-y:degrees==270?w/2-1-x:y;
            assert(output[1+ySize+(dy*(dw/2)+dx)*2+c]==input[1+ySize+(y*(w/2)+x)*2+c]);
        }
        assert(output.front()==0xcd && output.back()==0xcd);
        assert(!rotatePackedFrame(input.data()+1,size-1,output.data()+1,size,w,h,format,degrees));
        assert(!rotatePackedFrame(input.data()+1,size,output.data()+1,size-1,w,h,format,degrees));
        assert(!rotatePackedFrame(input.data(),size,input.data()+1,size,w,h,format,degrees));
        ++cases;
    }
    uint8_t odd[9]={0,1,2,3,4,5,6,7,8}, out[9]={};
    assert(rotatePackedFrame(odd,9,out,9,3,3,8,90));
    assert(out[0]==6 && out[1]==3 && out[2]==0 && out[8]==2);
    assert(!rotatePackedFrame(odd,9,out,9,3,3,3,90));
    assert(!rotatePackedFrame(odd,9,out,9,3,3,8,45));
    printf("{\"rotation_cases\":%d,\"odd_gray_cases\":1,\"failed\":0}\n",cases);
}
