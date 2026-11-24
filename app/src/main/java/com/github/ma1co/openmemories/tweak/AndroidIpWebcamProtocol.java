package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java protocol mapper implementing Home Assistant's core Android IP Webcam
 * (home-assistant-libs/pydroid-ipcam) JSON contracts so HAOS can discover camera,
 * battery, temperature, motion, and control switches with zero YAML configuration.
 */
final class AndroidIpWebcamProtocol {
    static final class Telemetry {
        final String videoSize;
        final boolean motionDetectEnabled;
        final boolean overlayVisible;
        final boolean motionActive;
        final int videoConnections;
        final int batteryLevelPercent;     // < 0 if unknown
        final float batteryTempCelsius;    // NaN if unknown
        final float batteryVoltageVolts;   // <= 0 if unknown
        final long epochMs;

        Telemetry(String videoSize, boolean motionDetectEnabled, boolean overlayVisible,
                  boolean motionActive, int videoConnections,
                  int batteryLevelPercent, float batteryTempCelsius,
                  float batteryVoltageVolts, long epochMs) {
            this.videoSize = (videoSize == null || videoSize.length() == 0) ? "640x424" : videoSize;
            this.motionDetectEnabled = motionDetectEnabled;
            this.overlayVisible = overlayVisible;
            this.motionActive = motionActive;
            this.videoConnections = videoConnections < 0 ? 0 : videoConnections;
            this.batteryLevelPercent = batteryLevelPercent;
            this.batteryTempCelsius = batteryTempCelsius;
            this.batteryVoltageVolts = batteryVoltageVolts;
            this.epochMs = epochMs;
        }
    }

    private AndroidIpWebcamProtocol() {}

    /** Builds /status.json matching pydroid-ipcam curvals/avail schema. */
    static String buildStatusJson(Telemetry t) {
        String vs = PreviewHttpServer.escapeJson(t.videoSize);
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"curvals\":{");
        sb.append("\"video_size\":\"").append(vs).append('"');
        sb.append(",\"motion_detect\":\"").append(t.motionDetectEnabled ? "on" : "off").append('"');
        sb.append(",\"overlay\":\"").append(t.overlayVisible ? "on" : "off").append('"');
        sb.append("},\"avail\":{");
        sb.append("\"video_size\":[\"").append(vs).append("\"]");
        sb.append("}}\n");
        return sb.toString();
    }

    /**
     * Builds /sensors.json matching pydroid-ipcam sensor node schema:
     *   "<key>": {"unit": "<u?>", "data": [[epochMs, [value]]]}
     * Only includes sensors that have valid readings.
     */
    static String buildSensorsJson(Telemetry t) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        boolean first = true;
        if (t.batteryLevelPercent >= 0 && t.batteryLevelPercent <= 100) {
            first = appendSensor(sb, first, "battery_level", "%", t.epochMs, Integer.toString(t.batteryLevelPercent));
        }
        if (!Float.isNaN(t.batteryTempCelsius)) {
            float rounded = Math.round(t.batteryTempCelsius * 10.0f) / 10.0f;
            first = appendSensor(sb, first, "battery_temp", "C", t.epochMs, Float.toString(rounded));
        }
        if (t.batteryVoltageVolts > 0.0f) {
            float rounded = Math.round(t.batteryVoltageVolts * 100.0f) / 100.0f;
            first = appendSensor(sb, first, "battery_voltage", "V", t.epochMs, Float.toString(rounded));
        }
        first = appendSensor(sb, first, "motion_active", "", t.epochMs, t.motionActive ? "1" : "0");
        appendSensor(sb, first, "video_connections", "", t.epochMs, Integer.toString(t.videoConnections));
        sb.append("}\n");
        return sb.toString();
    }

    /** Parse query parameter `set=on|off|1|0|true|false` from raw query string; returns null if missing/invalid. */
    static Boolean parseOnOffSetParam(String query) {
        if (query == null || query.length() == 0) return null;
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq > 0 && "set".equals(pair.substring(0, eq))) {
                String val = pair.substring(eq + 1).trim().toLowerCase();
                if ("on".equals(val) || "1".equals(val) || "true".equals(val)) return Boolean.TRUE;
                if ("off".equals(val) || "0".equals(val) || "false".equals(val)) return Boolean.FALSE;
            }
        }
        return null;
    }

    private static boolean appendSensor(StringBuilder sb, boolean first, String key,
                                        String unit, long ts, String rawNumber) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":{\"unit\":\"").append(unit)
                .append("\",\"data\":[[").append(ts).append(",[").append(rawNumber).append("]]]}");
        return false;
    }
}
