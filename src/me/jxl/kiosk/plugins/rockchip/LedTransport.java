// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchip;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

interface LedTransport extends AutoCloseable {
    int probe() throws Exception;
    int set(int bar, int channel, int level) throws Exception;
    int off() throws Exception;
    String label();
    void close();

    final class Direct implements LedTransport {
        public int probe() { return NativeLed.probe(); }
        public int set(int bar, int channel, int level) { return NativeLed.set(bar,channel,level); }
        public int off() { return NativeLed.off(); }
        public String label() { return "Direct device access"; }
        public void close() {}
    }
    final class Simulation implements LedTransport {
        public int probe() { return 0; }
        public int set(int bar, int channel, int level) { return 0; }
        public int off() { return 0; }
        public String label() { return "Simulation mode. No hardware is being changed."; }
        public void close() {}
    }
    final class Root implements LedTransport {
        private final Process process;
        private final BufferedWriter input;
        private final BlockingQueue<Integer> replies = new LinkedBlockingQueue<>(8);
        private volatile boolean closed;
        private boolean first = true;
        static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
        Root(String jar, String library) throws IOException {
            String command = "CLASSPATH=" + quote(jar) + " /system/bin/app_process /system/bin me.jxl.kiosk.plugins.rockchip.RootMain " + quote(library);
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!closed && (line = output.readLine()) != null) {
                        if (line.startsWith("LED ") && line.length() < 24) {
                            try { replies.offer(Integer.parseInt(line.substring(4))); }
                            catch (NumberFormatException ignored) { /* Ignore unrelated root-manager output. */ }
                        }
                    }
                } catch (IOException ignored) { /* Process shutdown closes the stream. */ }
                finally { replies.offer(-5); }
            }, "rockchip-led-root-output");
            reader.setDaemon(true); reader.start();
        }
        private int request(String value) throws Exception {
            if (closed) throw new IOException("Root helper is closed");
            input.write(value); input.newLine(); input.flush();
            Integer reply = replies.poll(first ? 20_000 : 1500, TimeUnit.MILLISECONDS);
            first = false;
            if (reply == null) { close(); throw new IOException("Root helper timed out. Check the root permission prompt and try again."); }
            return reply;
        }
        public int probe() throws Exception { return request("probe"); }
        public int set(int bar,int channel,int level) throws Exception { return request("set " + bar + " " + channel + " " + level); }
        public int off() throws Exception { return request("off"); }
        public String label() { return "Root helper access"; }
        public void close() {
            if (closed) return;
            closed = true;
            try { input.write("quit"); input.newLine(); input.flush(); input.close(); }
            catch (IOException ignored) { /* The helper may already have exited. */ }
            try { process.waitFor(250, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
