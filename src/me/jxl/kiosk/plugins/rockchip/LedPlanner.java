// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns "what each bar shows now" and "what it should show" into hardware requests.
 *
 * A bar's state is {channel, level}; channel -1 means off and null means unknown.
 * A colour channel cannot be switched off by writing level 0 (that leaves it dimly
 * lit), so a bar can only be darkened or recoloured with the global off request
 * followed by rewriting what should stay lit. Plain level changes and switching a
 * dark bar on are single writes that leave the other bar alone.
 *
 * Operations: {side} is the global off, {side, channel, level} writes one channel.
 */
final class LedPlanner {
    private LedPlanner() {}

    static List<int[]> plan(int[][] last, int[][] want) {
        List<int[]> ops = new ArrayList<>();
        boolean clear = false;
        for (int i = 0; i < want.length; i++) {
            if (last[i] == null || (last[i][0] >= 0 && want[i][0] != last[i][0])) clear = true;
        }
        if (clear) {
            ops.add(new int[]{-1});
            for (int i = 0; i < want.length; i++) if (want[i][0] >= 0) ops.add(new int[]{i, want[i][0], want[i][1]});
        } else {
            for (int i = 0; i < want.length; i++) {
                if (!Arrays.equals(last[i], want[i]) && want[i][0] >= 0) ops.add(new int[]{i, want[i][0], want[i][1]});
            }
        }
        return ops;
    }
}
