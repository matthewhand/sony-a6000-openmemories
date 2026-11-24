package com.github.ma1co.openmemories.tweak;

final class QuickPopupMenuPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        rssiQualityAndUptimeFormatting();
        boundedHistoryRingBuffer();
        telemetryHeaderAndQuickActionLabels();
        System.out.println("PASS QuickPopupMenuPolicyTests: " + assertions + " assertions");
    }

    private static void rssiQualityAndUptimeFormatting() {
        expect("Excellent".equals(QuickPopupMenuPolicy.classifyRssiQuality(-18)), "-18 dBm is Excellent");
        expect("Good".equals(QuickPopupMenuPolicy.classifyRssiQuality(-62)), "-62 dBm is Good");
        expect("Fair".equals(QuickPopupMenuPolicy.classifyRssiQuality(-74)), "-74 dBm is Fair");
        expect("Weak".equals(QuickPopupMenuPolicy.classifyRssiQuality(-85)), "-85 dBm is Weak");

        expect("00:00:00".equals(QuickPopupMenuPolicy.formatDurationSec(-5L)), "negative clamps to 00:00:00");
        expect("00:01:05".equals(QuickPopupMenuPolicy.formatDurationSec(65L)), "65s -> 00:01:05");
        expect("02:03:04".equals(QuickPopupMenuPolicy.formatDurationSec(7384L)), "7384s -> 02:03:04");
    }

    private static void boundedHistoryRingBuffer() {
        QuickPopupMenuPolicy.EventHistory h = new QuickPopupMenuPolicy.EventHistory();
        expect(h.size() == 0, "starts empty");
        expect(h.formatRecent(1000L).contains("no recent events"), "empty placeholder");

        for (int i = 1; i <= 7; i++) {
            h.record(i * 1000L, "ev" + i);
        }
        expect(h.size() == QuickPopupMenuPolicy.HISTORY_CAPACITY, "capped at HISTORY_CAPACITY");
        String formatted = h.formatRecent(10000L);
        expect(formatted.contains("3s ago: ev7"), "most recent event first");
        expect(formatted.contains("7s ago: ev3"), "oldest retained event present");
        expect(!formatted.contains("ev1") && !formatted.contains("ev2"), "evicted oldest events");
    }

    private static void telemetryHeaderAndQuickActionLabels() {
        QuickPopupMenuPolicy.EventHistory h = new QuickPopupMenuPolicy.EventHistory();
        h.record(8000L, "client GET /liveview.mjpeg");
        QuickPopupMenuPolicy.TelemetrySnapshot snap = new QuickPopupMenuPolicy.TelemetrySnapshot(
                "192.0.2.10", "StudioNet", -42, true,
                true, true, "2026-11-23 00:15:00Z",
                "STREAMING", "", "640x0_30fps_memmap", 15.2f, 1240L,
                2, 1, 84, "192.0.2.50 GET /liveview.mjpeg (2s ago)",
                98, 7.62f, 29.5f, true, 3661L,
                "ACTIVE", 60, 300,
                "Random", "Matrix Rain", false, true,
                true, false, 3, "motion",
                true, "3 files (0.4 MB / 250 MB)", h.formatRecent(10000L));

        String text = QuickPopupMenuPolicy.formatTelemetryHeader(snap);
        expect(text.contains("[API & CLIENTS]"), "includes API section");
        expect(text.contains("1 MJPEG stream · 2 active HTTP · 84 total reqs"), "includes client counts");
        expect(text.contains("192.0.2.10 [STATIC] · SSID: StudioNet"), "includes network section");
        expect(text.contains("-42 dBm (Excellent)"), "includes RSSI quality");
        expect(text.contains("98% · 7.62V · 29.5C · APO/NO: ACTIVE"), "includes power & thermal");
        expect(text.contains("01:01:01 · Stage: ACTIVE [HOLD]"), "includes uptime & hold flag");
        expect(text.contains("2s ago: client GET /liveview.mjpeg"), "includes recent history");

        String[] labels = QuickPopupMenuPolicy.buildQuickActionLabels(snap);
        expect(labels.length == QuickPopupMenuPolicy.actions().length, "label count matches QuickAction enum");
        expect(labels[0].startsWith("[ON]"), "Live Hold shows [ON]");
        expect(labels[3].startsWith("[OFF]"), "HUD shows [OFF]");
        expect(QuickPopupMenuPolicy.actionAt(0) == QuickPopupMenuPolicy.QuickAction.TOGGLE_LIVE_HOLD,
                "actionAt(0) is TOGGLE_LIVE_HOLD");
        expect(QuickPopupMenuPolicy.actionAt(999) == QuickPopupMenuPolicy.QuickAction.CLOSE_POPUP,
                "out of range defaults to CLOSE_POPUP");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
