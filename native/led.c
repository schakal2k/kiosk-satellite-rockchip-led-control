// SPDX-License-Identifier: Apache-2.0
// Interoperability facts: davidcoulson/kiosk-satellite issue #494 and
// maxlyth/ha-paneld's vendor protocol research, plus the ioctl map observed in
// the Allnet 13" meeting-room tablet's LED demo app (libjnielc.so, MainActivity).
// No vendor code is included.
//
// Dual-bar protocol (Allnet 174716): every LED bar has seven colour channels.
// Bar 0 uses ioctl requests 0xa1..0xa7, bar 1 uses 0xb1..0xb7. The argument is
// a level from 0 to 15.
//   channel 0 red, 1 green, 2 blue, 3 cyan, 4 magenta, 5 yellow, 6 white
// A level of 0 is NOT off: on the device, writing 0 to every channel leaves the
// bars faintly white. The only real off is request 0x99, which the vendor demo
// uses for its off switch. Switching colour therefore means 0x99 first, then
// writing only the new channel (see LedPlanner on the Java side).
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define LED_BARS 2
#define LED_CHANNELS 7
#define LED_MAX_LEVEL 15

static const unsigned long BAR_BASE[LED_BARS] = {0xa1, 0xb1};

static int open_led(void) {
    int fd;
    do { fd = open("/dev/ledjni", O_RDWR | O_CLOEXEC | O_NOCTTY); }
    while (fd < 0 && errno == EINTR);
    return fd;
}

JNIEXPORT jint JNICALL
Java_me_jxl_kiosk_plugins_rockchip_NativeLed_probe(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int fd = open_led();
    if (fd < 0) return -errno;
    close(fd);
    return 0;
}

JNIEXPORT jint JNICALL
Java_me_jxl_kiosk_plugins_rockchip_NativeLed_set(JNIEnv *env, jclass cls, jint bar, jint channel, jint level) {
    (void)env; (void)cls;
    if (bar < 0 || bar >= LED_BARS || channel < 0 || channel >= LED_CHANNELS || level < 0 || level > LED_MAX_LEVEL) return -EINVAL;
    int fd = open_led();
    if (fd < 0) return -errno;
    int error = ioctl(fd, BAR_BASE[bar] + (unsigned long)channel, (unsigned long)level) < 0 ? -errno : 0;
    if (error) ioctl(fd, 0x99, 0UL);
    close(fd);
    return error;
}

JNIEXPORT jint JNICALL
Java_me_jxl_kiosk_plugins_rockchip_NativeLed_off(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int fd = open_led();
    if (fd < 0) return -errno;
    int result = ioctl(fd, 0x99, 0UL) < 0 ? -errno : 0;
    close(fd);
    return result;
}
