package com.github.ma1co.openmemories.tweak;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;

final class PreviewHttpServerTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        canonicalAndAndroidIpWebcamEndpoints();
        basicAuthEnforcement();
        System.out.println("PASS PreviewHttpServerTests: " + assertions + " assertions");
    }

    private static void canonicalAndAndroidIpWebcamEndpoints() throws Exception {
        final PreviewFrameBus bus = new PreviewFrameBus();
        final ScreensaverPolicy screen = new ScreensaverPolicy();
        final long[] clock = new long[] { 10000L };
        final boolean[] motionEnabled = new boolean[] { true };
        final long[] lastPoll = new long[] { -1L };
        screen.wake(clock[0], "init");

        PreviewHttpServer.ControlHooks hooks = new PreviewHttpServer.ControlHooks() {
            @Override public long nowMs() { return clock[0]; }
            @Override public long epochMs() { return 1732300000000L; }
            @Override public String screenStage() { return screen.stage().name(); }
            @Override public boolean motionDetected() { return true; }
            @Override public boolean motionDetectEnabled() { return motionEnabled[0]; }
            @Override public int motionEvents() { return 4; }
            @Override public String lastWakeReason() { return screen.lastWakeReason(); }
            @Override public String ipAddress() { return "192.0.2.6"; }
            @Override public int rssiDbm() { return -52; }
            @Override public int batteryLevelPercent() { return 91; }
            @Override public float batteryTempCelsius() { return 33.2f; }
            @Override public float batteryVoltageVolts() { return 7.75f; }
            @Override public void wakeScreen(String reason) { screen.wake(clock[0], reason); }
            @Override public void enterScreensaver() { screen.enterScreensaverNow(clock[0]); }
            @Override public void enterFullDim() { screen.enterFullDimNow(clock[0]); }
            @Override public void setMotionDetectEnabled(boolean enabled) { motionEnabled[0] = enabled; }
            @Override public void onSensorsPolled(long nowMs) { lastPoll[0] = nowMs; }
        };

        PreviewHttpServer server = new PreviewHttpServer(
                InetAddress.getByName("127.0.0.1"), 0, bus, hooks, null, null);
        server.start();
        try {
            int port = server.boundPort();
            expect(port > 0, "bound ephemeral loopback port");

            expect(httpText(port, "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 503 "), "/health 503 when empty");
            expect(httpText(port, "GET /snapshot.jpg HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 503 "), "/snapshot.jpg 503 when empty");

            final byte[] jpg1 = new byte[] { (byte) 0xFF, (byte) 0xD8, 0x01, 0x02, (byte) 0xFF, (byte) 0xD9 };
            final byte[] jpg2 = new byte[] { (byte) 0xFF, (byte) 0xD8, 0x03, 0x04, 0x05, (byte) 0xFF, (byte) 0xD9 };
            bus.publish(jpg1, clock[0]);

            String health = httpText(port, "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n");
            expect(health.startsWith("HTTP/1.1 200 ") && health.contains("\"battery_level\":91"), "/health 200 with battery");

            for (String snapPath : new String[] { "/snapshot.jpg", "/shot.jpg" }) {
                byte[] snap = httpBytes(port, "GET " + snapPath + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String head = new String(snap, "ISO-8859-1");
                expect(head.startsWith("HTTP/1.1 200 "), snapPath + " 200 OK");
                expect(head.contains("X-Sony-Deliberate-Capture: false"), snapPath + " marks non-shutter");
                int sep = head.indexOf("\r\n\r\n");
                expect(Arrays.equals(Arrays.copyOfRange(snap, sep + 4, snap.length), jpg1), snapPath + " body exact");
            }

            // Android IP Webcam compatibility endpoints
            String ipStatus = httpText(port, "GET /status.json?show_avail=1 HTTP/1.1\r\nHost: localhost\r\n\r\n");
            expect(ipStatus.startsWith("HTTP/1.1 200 ") && ipStatus.contains("\"curvals\":{"), "/status.json 200");
            String ipSensors = httpText(port, "GET /sensors.json HTTP/1.1\r\nHost: localhost\r\n\r\n");
            expect(ipSensors.startsWith("HTTP/1.1 200 ") && ipSensors.contains("\"battery_level\"") && ipSensors.contains("\"motion_active\""), "/sensors.json 200");
            expect(lastPoll[0] == clock[0], "/sensors.json records poll timestamp");

            String setOff = httpText(port, "GET /settings/motion_detect?set=off HTTP/1.1\r\nHost: localhost\r\n\r\n");
            expect(setOff.startsWith("HTTP/1.1 200 ") && setOff.contains("Ok") && !motionEnabled[0], "/settings/motion_detect=off");
            String setOvOn = httpText(port, "GET /settings/overlay?set=on HTTP/1.1\r\nHost: localhost\r\n\r\n");
            expect(setOvOn.startsWith("HTTP/1.1 200 ") && screen.stage() == ScreensaverPolicy.Stage.SCREENSAVER, "/settings/overlay=on");

            // Canonical POST screen controls
            expect(httpText(port, "POST /screen/dim HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 200 ")
                    && screen.stage() == ScreensaverPolicy.Stage.FULL_DIM, "POST /screen/dim");
            expect(httpText(port, "POST /screen/wake HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 200 ")
                    && screen.stage() == ScreensaverPolicy.Stage.ACTIVE, "POST /screen/wake");

            // Verify MJPEG streaming on both /liveview.mjpeg and /video alias
            Socket s = new Socket("127.0.0.1", port);
            s.setSoTimeout(3000);
            try {
                OutputStream os = s.getOutputStream();
                os.write("GET /video HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("UTF-8"));
                os.flush();
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(100L); } catch (InterruptedException ignored) {}
                        clock[0] += 200L;
                        bus.publish(jpg2, clock[0]);
                    }
                }).start();
                InputStream is = s.getInputStream();
                ByteArrayOutputStream collected = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                long deadline = System.currentTimeMillis() + 2000L;
                while (System.currentTimeMillis() < deadline) {
                    int n = is.read(chunk);
                    if (n <= 0) break;
                    collected.write(chunk, 0, n);
                    String txt = new String(collected.toByteArray(), "ISO-8859-1");
                    if (txt.contains("Content-Length: " + jpg1.length)
                            && txt.contains("Content-Length: " + jpg2.length)) break;
                }
                String mjpeg = new String(collected.toByteArray(), "ISO-8859-1");
                expect(mjpeg.startsWith("HTTP/1.1 200 ") && mjpeg.contains("multipart/x-mixed-replace; boundary=a6000frame"), "/video MJPEG header");
                expect(mjpeg.contains("Content-Length: " + jpg1.length) && mjpeg.contains("Content-Length: " + jpg2.length), "/video streamed 2 frames");
            } finally {
                s.close();
            }

            expect(httpText(port, "GET /unknown HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 404 "), "unknown returns 404");
        } finally {
            server.stop();
        }
    }

    private static void basicAuthEnforcement() throws Exception {
        PreviewFrameBus bus = new PreviewFrameBus();
        PreviewHttpServer.ControlHooks hooks = new PreviewHttpServer.ControlHooks() {
            @Override public long nowMs() { return 1000L; }
            @Override public long epochMs() { return 1000L; }
            @Override public String screenStage() { return "ACTIVE"; }
            @Override public boolean motionDetected() { return false; }
            @Override public boolean motionDetectEnabled() { return true; }
            @Override public int motionEvents() { return 0; }
            @Override public String lastWakeReason() { return "init"; }
            @Override public String ipAddress() { return "192.0.2.6"; }
            @Override public int rssiDbm() { return -60; }
            @Override public int batteryLevelPercent() { return 100; }
            @Override public float batteryTempCelsius() { return 30.0f; }
            @Override public float batteryVoltageVolts() { return 8.0f; }
            @Override public void wakeScreen(String r) {}
            @Override public void enterScreensaver() {}
            @Override public void enterFullDim() {}
            @Override public void setMotionDetectEnabled(boolean e) {}
            @Override public void onSensorsPolled(long n) {}
        };
        PreviewHttpServer server = new PreviewHttpServer(
                InetAddress.getByName("127.0.0.1"), 0, bus, hooks, "cam", "secret");
        server.start();
        try {
            int port = server.boundPort();
            expect(httpText(port, "GET /status HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 401 "), "Basic auth rejects missing creds");
            String token = PreviewHttpServer.base64Encode("cam:secret");
            expect(httpText(port, "GET /status HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic " + token + "\r\n\r\n").startsWith("HTTP/1.1 200 "), "Basic auth accepts valid creds");
        } finally {
            server.stop();
        }
    }

    private static String httpText(int port, String req) throws Exception {
        return new String(httpBytes(port, req), "UTF-8");
    }

    private static byte[] httpBytes(int port, String req) throws Exception {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(3000);
        try {
            OutputStream os = s.getOutputStream();
            os.write(req.getBytes("UTF-8"));
            os.flush();
            InputStream is = s.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            s.close();
        }
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
