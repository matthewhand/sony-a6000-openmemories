package com.github.ma1co.openmemories.tweak;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java zero-dependency HTTP/1.1 preview & control server (Android API 10 compatible).
 *
 * Canonical REST endpoints (Frigate / go2rtc / HA MJPEG / rest_command):
 *   GET  /liveview.mjpeg (alias /stream.mjpeg) - multipart/x-mixed-replace MJPEG stream
 *   GET  /snapshot.jpg   (alias /snapshot.jpeg)- latest cached preview JPEG (no shutter)
 *   GET  /health                               - 200 when fresh (<5s), else 503
 *   GET  /status         (alias /)             - 200 JSON telemetry
 *   POST /screen/wake                          - wake screensaver/dim to live view
 *   POST /screen/screensaver                   - enter screensaver immediately
 *   POST /screen/dim                           - enter pure-black full-dim immediately
 *
 * Home Assistant core Android IP Webcam (pydroid-ipcam) compatibility endpoints:
 *   GET  /video                                - alias for MJPEG stream
 *   GET  /shot.jpg                             - alias for cached preview JPEG
 *   GET  /status.json                          - pydroid-ipcam curvals/avail JSON
 *   GET  /sensors.json                         - pydroid-ipcam battery/motion/connections JSON
 *   GET  /settings/motion_detect?set=on|off    - toggle on-camera motion detection ("Ok")
 *   GET  /settings/overlay?set=on|off          - toggle screen overlay ("Ok")
 */
final class PreviewHttpServer {
    static final int DEFAULT_PORT = 8088;
    static final String BOUNDARY = "a6000frame";
    static final int MAX_CLIENTS = 6;
    static final int CLIENT_TIMEOUT_MS = 5000;
    static final int MAX_HEADER_BYTES = 4096;

    interface ControlHooks {
        long nowMs();
        long epochMs();
        String screenStage();
        boolean motionDetected();
        boolean motionDetectEnabled();
        int motionEvents();
        String lastWakeReason();
        String ipAddress();
        int rssiDbm();
        int batteryLevelPercent();
        float batteryTempCelsius();
        float batteryVoltageVolts();
        void wakeScreen(String reason);
        void enterScreensaver();
        void enterFullDim();
        void setMotionDetectEnabled(boolean enabled);
        void onSensorsPolled(long nowMs);
    }

    private final InetAddress bindAddress;
    private final int port;
    private final PreviewFrameBus bus;
    private final ControlHooks hooks;
    private final String expectedAuthHeader;

    private final AtomicInteger activeClients = new AtomicInteger(0);
    private final AtomicInteger mjpegClients = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private volatile String lastClientIp = "";
    private volatile String lastClientMethod = "";
    private volatile String lastClientPath = "";
    private volatile long lastClientAtMs = -1L;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    PreviewHttpServer(int port, PreviewFrameBus bus, ControlHooks hooks) {
        this(null, port, bus, hooks, null, null);
    }

    PreviewHttpServer(InetAddress bindAddress, int port, PreviewFrameBus bus, ControlHooks hooks,
                      String basicUser, String basicPass) {
        if (bus == null || hooks == null) throw new IllegalArgumentException("bus and hooks are required");
        this.bindAddress = bindAddress;
        this.port = port;
        this.bus = bus;
        this.hooks = hooks;
        if (basicUser != null && basicPass != null && basicUser.length() > 0 && basicPass.length() > 0) {
            this.expectedAuthHeader = "Basic " + base64Encode(basicUser + ":" + basicPass);
        } else {
            this.expectedAuthHeader = null;
        }
    }

    void start() throws IOException {
        if (running) return;
        ServerSocket ss = bindAddress == null
                ? new ServerSocket(port, 16)
                : new ServerSocket(port, 16, bindAddress);
        ss.setReuseAddress(true);
        this.serverSocket = ss;
        this.running = true;
        this.acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "om-http-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int boundPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    int activeClientCount() { return activeClients.get(); }
    int mjpegClientCount() { return mjpegClients.get(); }
    int totalRequestCount() { return totalRequests.get(); }

    String formatLastClientSummary(long nowMs) {
        long at = lastClientAtMs;
        String ip = lastClientIp;
        if (at < 0L || ip == null || ip.length() == 0) return "none yet";
        long ageSec = nowMs >= at ? (nowMs - at) / 1000L : 0L;
        return ip + " " + lastClientMethod + " " + lastClientPath + " (" + ageSec + "s ago)";
    }

    void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
    }

    private void acceptLoop() {
        while (running) {
            final Socket client;
            try {
                ServerSocket ss = serverSocket;
                if (ss == null) break;
                client = ss.accept();
            } catch (IOException e) {
                if (!running) break;
                continue;
            }
            if (activeClients.incrementAndGet() > MAX_CLIENTS) {
                activeClients.decrementAndGet();
                try {
                    client.setSoTimeout(1000);
                    writeSimpleResponse(client.getOutputStream(), 503, "Service Unavailable",
                            "application/json", "{\"ok\":false,\"error\":\"too_many_clients\"}\n", null);
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(client);
                }
                continue;
            }
            Thread worker = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        handleClient(client);
                    } finally {
                        activeClients.decrementAndGet();
                        closeQuietly(client);
                    }
                }
            }, "om-http-client");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(CLIENT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16384);

            String headerBlock = readHeaders(in);
            if (headerBlock == null || headerBlock.length() == 0) return;

            String[] lines = headerBlock.split("\r\n");
            if (lines.length == 0) return;
            String[] reqParts = lines[0].split(" ");
            if (reqParts.length < 2) {
                writeSimpleResponse(out, 400, "Bad Request", "text/plain", "Bad Request\n", null);
                return;
            }
            String method = reqParts[0].toUpperCase(Locale.US);
            String rawPath = reqParts[1];
            int q = rawPath.indexOf('?');
            String path = q >= 0 ? rawPath.substring(0, q) : rawPath;
            String query = q >= 0 ? rawPath.substring(q + 1) : "";
            totalRequests.incrementAndGet();
            InetAddress remoteAddr = socket.getInetAddress();
            lastClientIp = remoteAddr == null ? "client" : remoteAddr.getHostAddress();
            lastClientMethod = method;
            lastClientPath = path;
            lastClientAtMs = hooks.nowMs();

            if (expectedAuthHeader != null) {
                String auth = findHeader(lines, "authorization");
                if (auth == null || !constantTimeEquals(expectedAuthHeader, auth.trim())) {
                    writeSimpleResponse(out, 401, "Unauthorized", "text/plain", "Unauthorized\n",
                            "WWW-Authenticate: Basic realm=\"openmemories-webcam\"\r\n");
                    return;
                }
            }

            boolean isGet = "GET".equals(method);
            boolean isHead = "HEAD".equals(method);
            if (isGet || isHead) {
                if ("/".equals(path) || "/status".equals(path)) {
                    writeSimpleResponse(out, 200, "OK", "application/json", buildStatusJson(hooks.nowMs()), null, isHead);
                    return;
                }
                if ("/health".equals(path)) {
                    long now = hooks.nowMs();
                    boolean healthy = bus.latestIfFresh(now, PreviewFrameBus.DEFAULT_FRESH_MS) != null;
                    writeSimpleResponse(out, healthy ? 200 : 503, healthy ? "OK" : "Service Unavailable",
                            "application/json", buildStatusJson(now), null, isHead);
                    return;
                }
                if ("/snapshot.jpg".equals(path) || "/snapshot.jpeg".equals(path) || "/shot.jpg".equals(path)) {
                    PreviewFrameBus.Frame frame = bus.latestIfFresh(hooks.nowMs(), PreviewFrameBus.DEFAULT_FRESH_MS);
                    if (frame == null) {
                        writeSimpleResponse(out, 503, "Service Unavailable", "application/json",
                                "{\"ok\":false,\"error\":\"no_fresh_frame\"}\n", null, isHead);
                        return;
                    }
                    writeBinaryResponse(out, 200, "OK", "image/jpeg", frame.jpeg,
                            "Cache-Control: no-store\r\n"
                            + "X-Sony-Source: camera-sequence\r\n"
                            + "X-Sony-Deliberate-Capture: false\r\n", isHead);
                    return;
                }
                if (isGet && ("/liveview.mjpeg".equals(path) || "/stream.mjpeg".equals(path) || "/video".equals(path))) {
                    streamMjpeg(out);
                    return;
                }
                if ("/status.json".equals(path)) {
                    writeSimpleResponse(out, 200, "OK", "application/json",
                            AndroidIpWebcamProtocol.buildStatusJson(buildIpWebcamTelemetry()), null, isHead);
                    return;
                }
                if ("/sensors.json".equals(path)) {
                    hooks.onSensorsPolled(hooks.nowMs());
                    writeSimpleResponse(out, 200, "OK", "application/json",
                            AndroidIpWebcamProtocol.buildSensorsJson(buildIpWebcamTelemetry()), null, isHead);
                    return;
                }
                if (isGet && path.startsWith("/settings/")) {
                    handleIpWebcamSetting(out, path.substring("/settings/".length()), query);
                    return;
                }
            }

            if ("POST".equals(method)) {
                if ("/screen/wake".equals(path)) {
                    String prev = hooks.screenStage();
                    hooks.wakeScreen("http");
                    writeSimpleResponse(out, 200, "OK", "application/json",
                            "{\"ok\":true,\"action\":\"wake\",\"previous_stage\":\"" + escapeJson(prev)
                            + "\",\"screen_stage\":\"ACTIVE\"}\n", null);
                    return;
                }
                if ("/screen/screensaver".equals(path)) {
                    String prev = hooks.screenStage();
                    hooks.enterScreensaver();
                    writeSimpleResponse(out, 200, "OK", "application/json",
                            "{\"ok\":true,\"action\":\"screensaver\",\"previous_stage\":\"" + escapeJson(prev)
                            + "\",\"screen_stage\":\"SCREENSAVER\"}\n", null);
                    return;
                }
                if ("/screen/dim".equals(path)) {
                    String prev = hooks.screenStage();
                    hooks.enterFullDim();
                    writeSimpleResponse(out, 200, "OK", "application/json",
                            "{\"ok\":true,\"action\":\"dim\",\"previous_stage\":\"" + escapeJson(prev)
                            + "\",\"screen_stage\":\"FULL_DIM\"}\n", null);
                    return;
                }
            }

            writeSimpleResponse(out, 404, "Not Found", "application/json",
                    "{\"ok\":false,\"error\":\"not_found\"}\n", null, isHead);
        } catch (IOException ignored) {
            // Client disconnected or timed out cleanly.
        }
    }

    private void handleIpWebcamSetting(OutputStream out, String key, String query) throws IOException {
        Boolean val = AndroidIpWebcamProtocol.parseOnOffSetParam(query);
        if (val == null) {
            writeSimpleResponse(out, 400, "Bad Request", "text/plain", "Invalid set parameter\n", null);
            return;
        }
        if ("motion_detect".equals(key)) {
            hooks.setMotionDetectEnabled(val.booleanValue());
            writeSimpleResponse(out, 200, "OK", "text/plain", "Ok\n", null);
            return;
        }
        if ("overlay".equals(key)) {
            if (val.booleanValue()) hooks.enterScreensaver();
            else hooks.wakeScreen("http");
            writeSimpleResponse(out, 200, "OK", "text/plain", "Ok\n", null);
            return;
        }
        writeSimpleResponse(out, 404, "Not Found", "text/plain", "Unsupported setting\n", null);
    }

    private AndroidIpWebcamProtocol.Telemetry buildIpWebcamTelemetry() {
        boolean overlayOn = !"ACTIVE".equals(hooks.screenStage());
        return new AndroidIpWebcamProtocol.Telemetry(
                "640x424",
                hooks.motionDetectEnabled(),
                overlayOn,
                hooks.motionDetected(),
                mjpegClients.get(),
                hooks.batteryLevelPercent(),
                hooks.batteryTempCelsius(),
                hooks.batteryVoltageVolts(),
                hooks.epochMs());
    }

    private void streamMjpeg(OutputStream out) throws IOException {
        mjpegClients.incrementAndGet();
        try {
            PreviewFrameBus.Frame first = bus.latestIfFresh(hooks.nowMs(), PreviewFrameBus.DEFAULT_FRESH_MS);
            if (first == null) first = bus.waitForNextFrame(0L, 2500L);
            if (first == null) {
                writeSimpleResponse(out, 503, "Service Unavailable", "application/json",
                        "{\"ok\":false,\"error\":\"stream_unavailable\"}\n", null);
                return;
            }
            String hdr = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(hdr.getBytes("UTF-8"));
            writeMjpegPart(out, first.jpeg);
            long lastSeq = first.seq;
            while (running && !bus.isClosed()) {
                PreviewFrameBus.Frame next = bus.waitForNextFrame(lastSeq, 4000L);
                if (next == null) {
                    if (!running || bus.isClosed()) break;
                    if (bus.latestIfFresh(hooks.nowMs(), PreviewFrameBus.DEFAULT_FRESH_MS) == null) break;
                    continue;
                }
                writeMjpegPart(out, next.jpeg);
                lastSeq = next.seq;
            }
        } finally {
            mjpegClients.decrementAndGet();
        }
    }

    private static void writeMjpegPart(OutputStream out, byte[] jpeg) throws IOException {
        String part = "--" + BOUNDARY + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n\r\n";
        out.write(part.getBytes("UTF-8"));
        out.write(jpeg);
        out.write("\r\n".getBytes("UTF-8"));
        out.flush();
    }

    private String buildStatusJson(long nowMs) {
        long ageMs = bus.frameAgeMs(nowMs);
        boolean ok = bus.latestIfFresh(nowMs, PreviewFrameBus.DEFAULT_FRESH_MS) != null;
        float fpsRounded = Math.round(bus.fps() * 10.0f) / 10.0f;
        StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        sb.append("\"ok\":").append(ok);
        sb.append(",\"camera_state\":\"").append(escapeJson(bus.cameraState())).append('"');
        sb.append(",\"camera_error\":\"").append(escapeJson(bus.cameraError())).append('"');
        sb.append(",\"preset\":\"").append(escapeJson(bus.activePreset())).append('"');
        sb.append(",\"frames\":").append(bus.frameCount());
        sb.append(",\"fps\":").append(fpsRounded);
        sb.append(",\"frame_age_ms\":").append(ageMs);
        sb.append(",\"clients\":").append(activeClients.get());
        sb.append(",\"mjpeg_clients\":").append(mjpegClients.get());
        sb.append(",\"total_requests\":").append(totalRequests.get());
        sb.append(",\"last_client\":\"").append(escapeJson(formatLastClientSummary(nowMs))).append('"');
        sb.append(",\"screen_stage\":\"").append(escapeJson(hooks.screenStage())).append('"');
        sb.append(",\"motion_detect_enabled\":").append(hooks.motionDetectEnabled());
        sb.append(",\"motion_detected\":").append(hooks.motionDetected());
        sb.append(",\"motion_events\":").append(hooks.motionEvents());
        sb.append(",\"last_wake_reason\":\"").append(escapeJson(hooks.lastWakeReason())).append('"');
        sb.append(",\"battery_level\":").append(hooks.batteryLevelPercent());
        sb.append(",\"ip\":\"").append(escapeJson(hooks.ipAddress())).append('"');
        sb.append(",\"rssi\":").append(hooks.rssiDbm());
        sb.append("}\n");
        return sb.toString();
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) return null;
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) break;
            } else state = (b == '\r') ? 1 : 0;
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), "UTF-8");
    }

    private static String findHeader(String[] lines, String lowerName) {
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).trim().toLowerCase(Locale.US).equals(lowerName)) {
                return lines[i].substring(colon + 1);
            }
        }
        return null;
    }

    private static void writeSimpleResponse(OutputStream out, int code, String reason,
                                            String contentType, String body, String extraHeaders) throws IOException {
        writeSimpleResponse(out, code, reason, contentType, body, extraHeaders, false);
    }

    private static void writeSimpleResponse(OutputStream out, int code, String reason,
                                            String contentType, String body, String extraHeaders,
                                            boolean headOnly) throws IOException {
        writeBinaryResponse(out, code, reason, contentType, body.getBytes("UTF-8"), extraHeaders, headOnly);
    }

    private static void writeBinaryResponse(OutputStream out, int code, String reason,
                                            String contentType, byte[] body, String extraHeaders,
                                            boolean headOnly) throws IOException {
        StringBuilder sb = new StringBuilder(160);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (extraHeaders != null) sb.append(extraHeaders);
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (!headOnly) {
            out.write(body);
        }
        out.flush();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= (a.charAt(i) ^ b.charAt(i));
        return diff == 0;
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    static String base64Encode(String text) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (IOException e) { return ""; }
        final char[] map = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int b1 = i < data.length ? (data[i++] & 0xFF) : -1;
            int b2 = i < data.length ? (data[i++] & 0xFF) : -1;
            sb.append(map[b0 >>> 2]);
            sb.append(map[((b0 & 0x03) << 4) | (b1 >= 0 ? (b1 >>> 4) : 0)]);
            sb.append(b1 >= 0 ? map[((b1 & 0x0F) << 2) | (b2 >= 0 ? (b2 >>> 6) : 0)] : '=');
            sb.append(b2 >= 0 ? map[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
