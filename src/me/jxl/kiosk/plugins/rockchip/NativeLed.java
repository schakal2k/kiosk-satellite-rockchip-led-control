// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchip;

public final class NativeLed {
    private NativeLed() {}
    public static void load(String path) { System.load(path); }
    public static native int probe();
    /** bar 0 or 1, channel 0..6, level 0..15. See native/led.c for the channel map. Level 0 is not off. */
    public static native int set(int bar, int channel, int level);
    /** Vendor off request (0x99). */
    public static native int off();
}
