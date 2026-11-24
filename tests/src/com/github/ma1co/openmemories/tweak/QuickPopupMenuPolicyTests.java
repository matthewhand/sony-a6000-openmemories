package com.github.ma1co.openmemories.tweak;

final class QuickPopupMenuPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        rssiQualityAndUptimeFormatting();
        boundedHistoryRingBuffer();
        compactHeaderCellsAndDetailsView();
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

    private static void compactHeaderCellsAndDetailsView() {
        QuickPopupMenuPolicy.EventHistory h = new QuickPopupMenuPolicy.EventHistory();
        h.record(8000L, "client GET /liveview.mjpeg");
        QuickPopupMenuPolicy.TelemetrySnapshot snap = new QuickPopupMenuPolicy.TelemetrySnapshot(
                "192.0.2.10", "StudioNet", -42, true,
                true, true, "2026-11-23 00:15:00Z",
                true, "EVF", "LCD/EVF/OFF",
                "STREAMING", "", "640x0_30fps_memmap", 15.2f, 1240L,
                2, 1, 84, "192.0.2.50 GET /liveview.mjpeg (2s ago)",
                98, 7.62f, 29.5f, true, 3661L,
                "ACTIVE", 60, 300,
                "Random", "Matrix Rain", false, true,
                true, false, 3, "motion",
                true, "3 files (0.4 MB / 250 MB)", h.formatRecent(10000L));

        String line1 = QuickPopupMenuPolicy.formatCompactStatusLine1(snap);
        String line2 = QuickPopupMenuPolicy.formatCompactStatusLine2(snap);
        expect(line1.contains("192.0.2.10:8088") && line1.contains("Cli:1 (84 req)"), "compact line1 has IP & clients");
        expect(line2.contains("WiFi -42dBm") && line2.contains("Out:EVF"), "compact line2 has WiFi & output");

        String details = QuickPopupMenuPolicy.formatTelemetryHeader(snap);
        expect(details.contains("[API & CLIENTS]") && details.contains("RUNNING"), "details shows server status");
        expect(details.contains("192.0.2.10 [STATIC] | SSID: StudioNet"), "details shows network");
        expect(details.contains("-42 dBm (Excellent)"), "details shows RSSI quality");
        expect(details.contains("98% | 7.62V | 29.5C | APO/NO: ACTIVE"), "details shows power & thermal");
        expect(details.contains("Output EVF | Stage ACTIVE [HOLD]"), "details shows output & stage");
        expect(details.contains("01:01:01 | C1: LCD/EVF/OFF"), "details shows uptime & C1");
        expect(details.contains("2s ago: client GET /liveview.mjpeg"), "details shows history");

        QuickPopupMenuPolicy.QuickCell[] cells = QuickPopupMenuPolicy.buildQuickCells(snap);
        expect(cells.length == QuickPopupMenuPolicy.actions().length, "cell count matches QuickAction enum");
        expect(cells[0].action == QuickPopupMenuPolicy.QuickAction.CYCLE_DISPLAY_OUTPUT
                && cells[0].title.equals("Display: EVF") && cells[0].activeHighlight, "first cell is Display: EVF");
        expect(cells[1].action == QuickPopupMenuPolicy.QuickAction.TOGGLE_HTTP_SERVER
                && cells[1].title.equals("Server :8088: ON") && cells[1].activeHighlight, "second cell is Server ON");
        expect(cells[2].action == QuickPopupMenuPolicy.QuickAction.OPEN_SETTINGS
                && cells[2].title.contains("Full Settings Menu"), "third cell surfaces Full Settings immediately");
        expect(cells[4].title.equals("Live Hold: ON") && cells[4].activeHighlight, "Live Hold highlighted when ON");
        expect(cells[6].title.equals("Debug HUD: OFF") && !cells[6].activeHighlight, "HUD not highlighted when OFF");
        expect(cells[9].title.equals("C1 Key: LCD/EVF/OFF"), "C1 picker cell shows current action");
        expect(QuickPopupMenuPolicy.NAV_HINT.contains("UP/DN") && QuickPopupMenuPolicy.NAV_HINT.contains("MENU: Full"),
                "nav hint tells user arrow keys move and MENU opens full settings");

        QuickPopupMenuPolicy.TelemetrySnapshot stopped = new QuickPopupMenuPolicy.TelemetrySnapshot(
                "192.0.2.10", "StudioNet", -42, true,
                true, true, "unsynced",
                false, "LCD", "Server On/Off",
                "STREAMING", "", "640x0_30fps_memmap", 15.0f, 100L,
                0, 0, 0, "none yet",
                100, 8.0f, 28.0f, true, 12L,
                "ACTIVE", 60, 300,
                "Random", "Matrix Rain", false, false,
                true, false, 0, "start",
                false, "", "");
        expect(QuickPopupMenuPolicy.formatCompactStatusLine1(stopped).contains("SERVER :8088 OFF"),
                "compact header reflects stopped HTTP server");
        expect(QuickPopupMenuPolicy.buildQuickCells(stopped)[1].title.equals("Server :8088: OFF"),
                "server cell reflects stopped state");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
