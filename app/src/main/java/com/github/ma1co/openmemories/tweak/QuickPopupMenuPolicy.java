package com.github.ma1co.openmemories.tweak;

import java.util.Locale;

/**
 * Pure-Java telemetry formatter, RSSI signal classifier, bounded activity history ring buffer,
 * and compact large-cell quick-action menu model for the C2 Custom Button popup inside
 * LivePreviewActivity (designed for ~2.5 large cells visible at once on the 3" 640x480 LCD).
 */
final class QuickPopupMenuPolicy {
    static final int HISTORY_CAPACITY = 5;
    static final String NAV_HINT = "UP/DN Move | OK Pick | MENU: Full | C2 Close";

    enum QuickAction {
        CYCLE_DISPLAY_OUTPUT,
        TOGGLE_HTTP_SERVER,
        OPEN_SETTINGS,
        ENTER_SCREENSAVER,
        TOGGLE_LIVE_HOLD,
        NEXT_STYLE,
        TOGGLE_HUD,
        TOGGLE_MOTION_WAKE,
        TOGGLE_SD_SAVE,
        CONFIGURE_C1,
        SYNC_NTP_NOW,
        SHOW_DETAILS
    }

    static final class QuickCell {
        final QuickAction action;
        final String title;
        final String subtitle;
        final boolean activeHighlight;

        QuickCell(QuickAction action, String title, String subtitle, boolean activeHighlight) {
            this.action = action;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.activeHighlight = activeHighlight;
        }
    }

    static final class EventHistory {
        private final long[] timestamps = new long[HISTORY_CAPACITY];
        private final String[] messages = new String[HISTORY_CAPACITY];
        private int count;
        private int head; // points to next write slot

        synchronized void record(long nowElapsedMs, String message) {
            if (message == null || message.trim().length() == 0) return;
            timestamps[head] = nowElapsedMs;
            messages[head] = message.trim();
            head = (head + 1) % HISTORY_CAPACITY;
            if (count < HISTORY_CAPACITY) count++;
        }

        synchronized int size() {
            return count;
        }

        synchronized String formatRecent(long nowElapsedMs) {
            if (count == 0) return "  (no recent events yet)";
            StringBuilder sb = new StringBuilder(192);
            for (int i = 0; i < count; i++) {
                int idx = (head - 1 - i + HISTORY_CAPACITY * 2) % HISTORY_CAPACITY;
                long ageSec = nowElapsedMs >= timestamps[idx] ? (nowElapsedMs - timestamps[idx]) / 1000L : 0L;
                if (i > 0) sb.append('\n');
                sb.append("  - ").append(ageSec).append("s ago: ").append(messages[idx]);
            }
            return sb.toString();
        }
    }

    static final class TelemetrySnapshot {
        final String ip;
        final String ssid;
        final int rssiDbm;
        final boolean staticIp;
        final boolean adbOn;
        final boolean telnetOn;
        final String clockIso;

        final boolean httpServerRunning;
        final String displayOutputLabel;
        final String c1ActionHint;

        final String cameraState;
        final String cameraError;
        final String preset;
        final float fps;
        final long totalFrames;

        final int activeHttpClients;
        final int mjpegClients;
        final int totalHttpRequests;
        final String lastClientSummary;

        final int batteryPercent;
        final float batteryVoltageV;
        final float batteryTempC;
        final boolean keepAwakeOn;
        final long uptimeSec;

        final String screenStage;
        final int saverDelaySec;
        final int saverDimSec;
        final String configuredStyleLabel;
        final String activeStyleLabel;
        final boolean saverHudOn;
        final boolean liveHoldOn;

        final boolean motionWakeOn;
        final boolean motionDetected;
        final int motionEvents;
        final String lastWakeReason;
        final boolean sdSaveOn;
        final String sdSummary;
        final String recentHistory;

        TelemetrySnapshot(
                String ip, String ssid, int rssiDbm, boolean staticIp,
                boolean adbOn, boolean telnetOn, String clockIso,
                boolean httpServerRunning, String displayOutputLabel, String c1ActionHint,
                String cameraState, String cameraError, String preset, float fps, long totalFrames,
                int activeHttpClients, int mjpegClients, int totalHttpRequests, String lastClientSummary,
                int batteryPercent, float batteryVoltageV, float batteryTempC, boolean keepAwakeOn, long uptimeSec,
                String screenStage, int saverDelaySec, int saverDimSec,
                String configuredStyleLabel, String activeStyleLabel, boolean saverHudOn, boolean liveHoldOn,
                boolean motionWakeOn, boolean motionDetected, int motionEvents, String lastWakeReason,
                boolean sdSaveOn, String sdSummary, String recentHistory) {
            this.ip = ip == null ? "offline" : ip;
            this.ssid = (ssid == null || ssid.length() == 0) ? "--" : ssid;
            this.rssiDbm = rssiDbm;
            this.staticIp = staticIp;
            this.adbOn = adbOn;
            this.telnetOn = telnetOn;
            this.clockIso = (clockIso == null || clockIso.length() == 0) ? "unsynced" : clockIso;
            this.httpServerRunning = httpServerRunning;
            this.displayOutputLabel = (displayOutputLabel == null || displayOutputLabel.length() == 0)
                    ? "LCD" : displayOutputLabel;
            this.c1ActionHint = (c1ActionHint == null || c1ActionHint.length() == 0)
                    ? "Disabled" : c1ActionHint;
            this.cameraState = cameraState == null ? "INIT" : cameraState;
            this.cameraError = cameraError == null ? "" : cameraError;
            this.preset = (preset == null || preset.length() == 0) ? "default" : preset;
            this.fps = fps;
            this.totalFrames = totalFrames;
            this.activeHttpClients = activeHttpClients;
            this.mjpegClients = mjpegClients;
            this.totalHttpRequests = totalHttpRequests;
            this.lastClientSummary = (lastClientSummary == null || lastClientSummary.length() == 0)
                    ? "none yet" : lastClientSummary;
            this.batteryPercent = batteryPercent;
            this.batteryVoltageV = batteryVoltageV;
            this.batteryTempC = batteryTempC;
            this.keepAwakeOn = keepAwakeOn;
            this.uptimeSec = uptimeSec < 0L ? 0L : uptimeSec;
            this.screenStage = screenStage == null ? "ACTIVE" : screenStage;
            this.saverDelaySec = saverDelaySec;
            this.saverDimSec = saverDimSec;
            this.configuredStyleLabel = configuredStyleLabel == null ? "Random" : configuredStyleLabel;
            this.activeStyleLabel = activeStyleLabel == null ? "Matrix Rain" : activeStyleLabel;
            this.saverHudOn = saverHudOn;
            this.liveHoldOn = liveHoldOn;
            this.motionWakeOn = motionWakeOn;
            this.motionDetected = motionDetected;
            this.motionEvents = motionEvents;
            this.lastWakeReason = (lastWakeReason == null || lastWakeReason.length() == 0) ? "init" : lastWakeReason;
            this.sdSaveOn = sdSaveOn;
            this.sdSummary = sdSummary == null ? "" : sdSummary;
            this.recentHistory = recentHistory == null ? "  (no recent events yet)" : recentHistory;
        }
    }

    private QuickPopupMenuPolicy() {}

    static String classifyRssiQuality(int rssiDbm) {
        if (rssiDbm >= -55) return "Excellent";
        if (rssiDbm >= -67) return "Good";
        if (rssiDbm >= -78) return "Fair";
        return "Weak";
    }

    static String formatDurationSec(long totalSec) {
        long s = totalSec < 0L ? 0L : totalSec;
        long hrs = s / 3600L;
        long mins = (s % 3600L) / 60L;
        long secs = s % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs);
    }

    /** Compact line 1 for the pinned C2 header (fits comfortably on 640px wide LCD). */
    static String formatCompactStatusLine1(TelemetrySnapshot s) {
        if (s == null) return "";
        if (!s.httpServerRunning) {
            return "SERVER :8088 OFF  |  " + s.cameraState + " " + s.fps + "fps";
        }
        return s.ip + ":8088  |  " + s.fps + "fps  |  Cli:" + s.mjpegClients + " (" + s.totalHttpRequests + " req)";
    }

    /** Compact line 2 for the pinned C2 header. */
    static String formatCompactStatusLine2(TelemetrySnapshot s) {
        if (s == null) return "";
        String bat = s.batteryPercent >= 0 ? (s.batteryPercent + "%") : "--";
        if (s.batteryVoltageV > 0f) {
            bat = bat + " " + String.format(Locale.US, "%.1fV", s.batteryVoltageV);
        }
        return "WiFi " + s.rssiDbm + "dBm  |  Bat " + bat + "  |  Out:" + s.displayOutputLabel;
    }

    /** Full scrollable telemetry & activity log shown in the "Details & History" sub-dialog. */
    static String formatTelemetryHeader(TelemetrySnapshot s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(640);
        sb.append("[API & CLIENTS]\n");
        sb.append("Server   : ").append(s.httpServerRunning ? "RUNNING" : "STOPPED")
          .append("  http://").append(s.ip).append(":8088\n");
        sb.append("Pipeline : ").append(s.cameraState)
          .append(" | ").append(s.fps).append(" fps | ").append(s.totalFrames).append(" frames\n");
        sb.append("Clients  : ").append(s.mjpegClients).append(" MJPEG | ")
          .append(s.activeHttpClients).append(" active HTTP | ").append(s.totalHttpRequests).append(" total reqs\n");
        sb.append("Last req : ").append(s.lastClientSummary).append('\n');
        if (s.cameraError.length() > 0) {
            sb.append("Cam note : ").append(s.cameraError).append('\n');
        }

        sb.append("\n[WI-FI & DAEMONS]\n");
        sb.append("Network  : ").append(s.ip).append(s.staticIp ? " [STATIC]" : " [DHCP]")
          .append(" | SSID: ").append(s.ssid).append('\n');
        sb.append("Signal   : ").append(s.rssiDbm).append(" dBm (").append(classifyRssiQuality(s.rssiDbm)).append(")")
          .append(" | ADB: ").append(s.adbOn ? "ON" : "OFF")
          .append(" | Telnet: ").append(s.telnetOn ? "ON" : "OFF").append('\n');
        sb.append("Clock    : ").append(s.clockIso).append('\n');

        sb.append("\n[POWER & DISPLAY]\n");
        sb.append("Battery  : ").append(s.batteryPercent >= 0 ? (s.batteryPercent + "%") : "--");
        if (s.batteryVoltageV > 0f) {
            sb.append(" | ").append(String.format(Locale.US, "%.2fV", s.batteryVoltageV));
        }
        if (!Float.isNaN(s.batteryTempC)) {
            sb.append(" | ").append(String.format(Locale.US, "%.1fC", s.batteryTempC));
        }
        sb.append(" | APO/NO: ").append(s.keepAwakeOn ? "ACTIVE" : "NORMAL").append('\n');
        sb.append("Display  : Output ").append(s.displayOutputLabel)
          .append(" | Stage ").append(s.screenStage)
          .append(s.liveHoldOn ? " [HOLD]" : "").append('\n');
        sb.append("Uptime   : ").append(formatDurationSec(s.uptimeSec))
          .append(" | C1: ").append(s.c1ActionHint).append('\n');
        sb.append("Saver    : ").append(s.configuredStyleLabel);
        if (!s.configuredStyleLabel.equals(s.activeStyleLabel)) {
            sb.append(" (").append(s.activeStyleLabel).append(')');
        }
        sb.append(" | ").append(ScreensaverPolicy.formatTimingSummary(s.saverDelaySec, s.saverDimSec))
          .append(" | HUD: ").append(s.saverHudOn ? "ON" : "OFF").append('\n');

        sb.append("\n[HISTORY & MOTION]\n");
        sb.append("Motion   : ").append(s.motionWakeOn ? "ON" : "OFF")
          .append(" (").append(s.motionDetected ? "MOTION" : "still").append(") | ")
          .append(s.motionEvents).append(" wakes | wake: ").append(s.lastWakeReason).append('\n');
        sb.append("SD Card  : ").append(s.sdSaveOn ? "ON | " : "OFF | ").append(s.sdSummary).append('\n');
        sb.append("Recent Activity:\n").append(s.recentHistory);
        return sb.toString();
    }

    static QuickAction[] actions() {
        return QuickAction.values();
    }

    static QuickAction actionAt(int index) {
        QuickAction[] vals = QuickAction.values();
        if (index < 0 || index >= vals.length) return QuickAction.OPEN_SETTINGS;
        return vals[index];
    }

    /** Build concise 2-line large-font action cells (title + short subtitle). */
    static QuickCell[] buildQuickCells(TelemetrySnapshot s) {
        String out = s == null ? "LCD" : s.displayOutputLabel;
        boolean srv = s == null || s.httpServerRunning;
        int mjpeg = s == null ? 0 : s.mjpegClients;
        int reqs = s == null ? 0 : s.totalHttpRequests;
        boolean hold = s != null && s.liveHoldOn;
        boolean hud = s != null && s.saverHudOn;
        boolean mot = s == null || s.motionWakeOn;
        int motEvents = s == null ? 0 : s.motionEvents;
        boolean sd = s != null && s.sdSaveOn;
        String sdSum = (s == null || s.sdSummary.length() == 0) ? "/sdcard/A6000_MOTION" : s.sdSummary;
        String style = s == null ? "Matrix Rain" : s.activeStyleLabel;
        String c1Hint = s == null ? "Pause Saver" : s.c1ActionHint;
        String clk = s == null ? "unsynced" : s.clockIso;
        int delay = s == null ? ScreensaverPolicy.DEFAULT_DELAY_SEC : s.saverDelaySec;
        int dim = s == null ? ScreensaverPolicy.DEFAULT_DIM_SEC : s.saverDimSec;

        return new QuickCell[] {
                new QuickCell(QuickAction.CYCLE_DISPLAY_OUTPUT,
                        "Display: " + out,
                        "Cycle LCD -> Viewfinder (EVF) -> OFF",
                        !"LCD".equals(out)),
                new QuickCell(QuickAction.TOGGLE_HTTP_SERVER,
                        "Server :8088: " + (srv ? "ON" : "OFF"),
                        srv ? (mjpeg + " MJPEG | " + reqs + " reqs (OK to Stop)") : "Stopped (OK to Start)",
                        srv),
                new QuickCell(QuickAction.OPEN_SETTINGS,
                        "Full Settings Menu (MENU)",
                        "All Camera, Wi-Fi, Saver & Developer tabs",
                        false),
                new QuickCell(QuickAction.ENTER_SCREENSAVER,
                        "Screensaver Now",
                        "Start " + style + " (Dim " + (dim > 0 ? (dim + "s") : "off") + ")",
                        false),
                new QuickCell(QuickAction.TOGGLE_LIVE_HOLD,
                        "Live Hold: " + (hold ? "ON" : "OFF"),
                        hold ? "Screensaver & Dim paused" : ("Auto-saver after " + (delay > 0 ? (delay + "s") : "off")),
                        hold),
                new QuickCell(QuickAction.NEXT_STYLE,
                        "Style: " + style,
                        "Press OK for next screensaver style",
                        false),
                new QuickCell(QuickAction.TOGGLE_HUD,
                        "Debug HUD: " + (hud ? "ON" : "OFF"),
                        "Show live stats on screensaver",
                        hud),
                new QuickCell(QuickAction.TOGGLE_MOTION_WAKE,
                        "Motion Wake: " + (mot ? "ON" : "OFF"),
                        motEvents + " wakes recorded this session",
                        mot),
                new QuickCell(QuickAction.TOGGLE_SD_SAVE,
                        "SD Motion Save: " + (sd ? "ON" : "OFF"),
                        sdSum,
                        sd),
                new QuickCell(QuickAction.CONFIGURE_C1,
                        "C1 Key: " + c1Hint,
                        "Press OK to choose C1 button action",
                        false),
                new QuickCell(QuickAction.SYNC_NTP_NOW,
                        "Sync Clock (NTP)",
                        clk,
                        false),
                new QuickCell(QuickAction.SHOW_DETAILS,
                        "Details & History",
                        "Wi-Fi, ADB/Telnet, Thermal & Event Log",
                        false)
        };
    }
}
