package com.github.ma1co.openmemories.tweak;

final class AndroidIpWebcamProtocolTests {
    private static int assertions;

    public static void main(String[] args) {
        statusAndSensorsSchema();
        omitsUnknownBatteryFields();
        parsesOnOffQueryParameters();
        System.out.println("PASS AndroidIpWebcamProtocolTests: " + assertions + " assertions");
    }

    private static void statusAndSensorsSchema() {
        AndroidIpWebcamProtocol.Telemetry t = new AndroidIpWebcamProtocol.Telemetry(
                "640x424", true, false, true, 2, 87, 34.5f, 7.82f, 1732300000000L);
        String status = AndroidIpWebcamProtocol.buildStatusJson(t);
        expect(status.contains("\"curvals\":{"), "contains curvals");
        expect(status.contains("\"video_size\":\"640x424\""), "includes video_size");
        expect(status.contains("\"motion_detect\":\"on\""), "motion_detect on");
        expect(status.contains("\"overlay\":\"off\""), "overlay off");
        expect(status.contains("\"avail\":{\"video_size\":[\"640x424\"]}"), "includes avail");

        String sensors = AndroidIpWebcamProtocol.buildSensorsJson(t);
        expect(sensors.contains("\"battery_level\":{\"unit\":\"%\",\"data\":[[1732300000000,[87]]]}"), "battery_level schema");
        expect(sensors.contains("\"battery_temp\":{\"unit\":\"C\",\"data\":[[1732300000000,[34.5]]]}"), "battery_temp schema");
        expect(sensors.contains("\"battery_voltage\":{\"unit\":\"V\",\"data\":[[1732300000000,[7.82]]]}"), "battery_voltage schema");
        expect(sensors.contains("\"motion_active\":{\"unit\":\"\",\"data\":[[1732300000000,[1]]]}"), "motion_active schema");
        expect(sensors.contains("\"video_connections\":{\"unit\":\"\",\"data\":[[1732300000000,[2]]]}"), "video_connections schema");
    }

    private static void omitsUnknownBatteryFields() {
        AndroidIpWebcamProtocol.Telemetry t = new AndroidIpWebcamProtocol.Telemetry(
                "640x424", false, true, false, 0, -1, Float.NaN, -1.0f, 1000L);
        String sensors = AndroidIpWebcamProtocol.buildSensorsJson(t);
        expect(!sensors.contains("battery_level"), "omits unknown battery_level");
        expect(!sensors.contains("battery_temp"), "omits unknown battery_temp");
        expect(!sensors.contains("battery_voltage"), "omits unknown battery_voltage");
        expect(sensors.contains("\"motion_active\":{\"unit\":\"\",\"data\":[[1000,[0]]]}"), "still includes motion_active");
    }

    private static void parsesOnOffQueryParameters() {
        expect(Boolean.TRUE.equals(AndroidIpWebcamProtocol.parseOnOffSetParam("set=on")), "set=on");
        expect(Boolean.FALSE.equals(AndroidIpWebcamProtocol.parseOnOffSetParam("foo=1&set=off")), "set=off");
        expect(Boolean.TRUE.equals(AndroidIpWebcamProtocol.parseOnOffSetParam("set=1")), "set=1");
        expect(AndroidIpWebcamProtocol.parseOnOffSetParam("set=maybe") == null, "rejects invalid value");
        expect(AndroidIpWebcamProtocol.parseOnOffSetParam("") == null, "empty -> null");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
