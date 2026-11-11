// SPDX-License-Identifier: Apache-2.0
#include <assert.h>
#include <stdarg.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <errno.h>
static int opens, closes, calls, fail_request;
static unsigned long requests[64], values[64];
static int fake_open(const char *path,int flags,...) {
    assert(strcmp(path,"/dev/ledjni")==0);assert((flags&O_ACCMODE)==O_RDWR);opens++;return 9;
}
static int fake_close(int fd){assert(fd==9);closes++;return 0;}
static int fake_ioctl(int fd,unsigned long request,...) {
    assert(fd==9);va_list args;va_start(args,request);unsigned long value=va_arg(args,unsigned long);va_end(args);
    requests[calls]=request;values[calls++]=value;
    if((int)request==fail_request){errno=EACCES;return -1;}return 0;
}
#define open fake_open
#define close fake_close
#define ioctl fake_ioctl
#include "../native/led.c"
#define SET Java_me_jxl_kiosk_plugins_rockchip_NativeLed_set
int main(void){
    assert(Java_me_jxl_kiosk_plugins_rockchip_NativeLed_probe(0,0)==0);assert(calls==0);
    // Bar 0, channel 2 (blue), level 15: the six other channels are zeroed first, the selected one is written last.
    assert(SET(0,0,0,2,15)==0);
    assert(calls==7);
    const unsigned long order0[]={0xa1,0xa2,0xa4,0xa5,0xa6,0xa7,0xa3};
    for(int i=0;i<7;i++){assert(requests[i]==order0[i]);assert(values[i]==(i==6?15UL:0UL));}
    calls=0;
    // Bar 1 uses the 0xb1.. range.
    assert(SET(0,0,1,6,7)==0);assert(calls==7);
    assert(requests[0]==0xb1&&requests[5]==0xb6&&requests[6]==0xb7&&values[6]==7);calls=0;
    // Channel -1 switches the whole bar off and writes nothing else.
    assert(SET(0,0,0,-1,0)==0);assert(calls==7);
    for(int i=0;i<7;i++){assert(requests[i]==0xa1UL+i);assert(values[i]==0);}
    calls=0;
    // Range rejection: nothing touches the device.
    assert(SET(0,0,2,0,1)==-EINVAL);assert(SET(0,0,-1,0,1)==-EINVAL);assert(SET(0,0,0,7,1)==-EINVAL);
    assert(SET(0,0,0,-2,1)==-EINVAL);assert(SET(0,0,0,0,16)==-EINVAL);assert(SET(0,0,0,0,-1)==-EINVAL);
    assert(calls==0);
    // Error propagation: the failing request is reported and the driver is switched off.
    fail_request=0xa5;assert(SET(0,0,0,2,3)==-EACCES);
    assert(calls==5&&requests[4]==0x99&&values[4]==0);calls=0;fail_request=0;
    // off(): driver off, then every colour of both bars zeroed.
    assert(Java_me_jxl_kiosk_plugins_rockchip_NativeLed_off(0,0)==0);
    assert(calls==15&&requests[0]==0x99&&values[0]==0);
    assert(requests[1]==0xa1&&requests[7]==0xa7&&requests[8]==0xb1&&requests[14]==0xb7);
    assert(opens==closes);return 0;
}
