// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchip;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Fixed LED protocol for the optional root process. No shell commands from settings. */
public final class RootMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) return;
        NativeLed.load(args[0]);
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                if ("quit".equals(line)) break;
                int result = -22;
                if ("probe".equals(line)) result = NativeLed.probe();
                else if ("off".equals(line)) result = NativeLed.off();
                else if (line.startsWith("set ") && line.length() < 32) {
                    String[] values = line.split(" ");
                    if (values.length == 4) {
                        try { result = NativeLed.set(Integer.parseInt(values[1]), Integer.parseInt(values[2]), Integer.parseInt(values[3])); }
                        catch (NumberFormatException ignored) { result = -22; }
                    }
                }
                System.out.println("LED " + result);
                System.out.flush();
            }
        } finally { NativeLed.off(); }
    }
}
