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
    // One request per write: bar 0 starts at 0xa1, bar 1 at 0xb1, the channel is the offset.
    assert(SET(0,0,0,2,15)==0);assert(calls==1&&requests[0]==0xa3&&values[0]==15);calls=0;
    assert(SET(0,0,1,6,7)==0);assert(calls==1&&requests[0]==0xb7&&values[0]==7);calls=0;
    assert(SET(0,0,0,0,1)==0);assert(requests[0]==0xa1&&values[0]==1);calls=0;
    assert(SET(0,0,1,3,0)==0);assert(requests[0]==0xb4&&values[0]==0);calls=0;
    // Range rejection: nothing touches the device. Channel -1 is not a thing any more; off() is the off request.
    assert(SET(0,0,2,0,1)==-EINVAL);assert(SET(0,0,-1,0,1)==-EINVAL);assert(SET(0,0,0,7,1)==-EINVAL);
    assert(SET(0,0,0,-1,0)==-EINVAL);assert(SET(0,0,0,0,16)==-EINVAL);assert(SET(0,0,0,0,-1)==-EINVAL);
    assert(calls==0);
    // Error propagation: the failing request is reported and the driver is switched off.
    fail_request=0xa5;assert(SET(0,0,0,4,3)==-EACCES);
    assert(calls==2&&requests[0]==0xa5&&requests[1]==0x99&&values[1]==0);calls=0;fail_request=0;
    // off(): exactly the vendor off request. Writing zeros to the colours would leave the bars faintly lit.
    assert(Java_me_jxl_kiosk_plugins_rockchip_NativeLed_off(0,0)==0);
    assert(calls==1&&requests[0]==0x99&&values[0]==0);calls=0;
    fail_request=0x99;assert(Java_me_jxl_kiosk_plugins_rockchip_NativeLed_off(0,0)==-EACCES);fail_request=0;
    assert(opens==closes);return 0;
}
