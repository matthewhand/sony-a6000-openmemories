package com.github.ma1co.openmemories.tweak;

final class ShutterCapturePolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        scanCodes();
        halfPressAutoFocusLifecycle();
        fullPressCaptureAndIdempotentFinish();
        s1ReleaseDuringCaptureDoesNotBreakState();
        bannersAndBadges();
        System.out.println("PASS ShutterCapturePolicyTests: " + assertions + " assertions");
    }

    private static void scanCodes() {
        expect(ShutterCapturePolicy.isS1ScanCode(516), "516 is S1_1");
        expect(ShutterCapturePolicy.isS1ScanCode(517), "517 is S1_2");
        expect(!ShutterCapturePolicy.isS1ScanCode(518), "518 is not S1");
        expect(ShutterCapturePolicy.isS2ScanCode(518), "518 is S2");
        expect(!ShutterCapturePolicy.isS2ScanCode(516), "516 is not S2");
        expect(ShutterCapturePolicy.isAnyShutterScanCode(516)
                && ShutterCapturePolicy.isAnyShutterScanCode(517)
                && ShutterCapturePolicy.isAnyShutterScanCode(518), "accepts all S1/S2 codes");
        expect(!ShutterCapturePolicy.isAnyShutterScanCode(514), "MENU is not shutter");
    }

    private static void halfPressAutoFocusLifecycle() {
        ShutterCapturePolicy p = new ShutterCapturePolicy();
        expect(p.state() == ShutterCapturePolicy.State.IDLE, "starts IDLE");
        expect(p.onS1Down(), "first S1 down triggers AF");
        expect(p.state() == ShutterCapturePolicy.State.FOCUSING, "enters FOCUSING");
        expect(!p.onS1Down(), "duplicate S1 down ignored while FOCUSING");
        expect(p.onAutoFocusComplete(true), "AF callback accepted while FOCUSING");
        expect(p.state() == ShutterCapturePolicy.State.AF_LOCKED, "enters AF_LOCKED");
        expect(!p.onAutoFocusComplete(false), "late AF callback ignored after lock");
        expect(p.onS1Up(), "S1 up cancels AF");
        expect(p.state() == ShutterCapturePolicy.State.IDLE && !p.isS1Held(), "returns to IDLE");
        expect(!p.onS1Up(), "second S1 up is no-op");

        expect(p.onS1Down(), "S1 down again");
        expect(p.onAutoFocusComplete(false), "AF failure recorded");
        expect(p.state() == ShutterCapturePolicy.State.AF_FAILED, "enters AF_FAILED");
    }

    private static void fullPressCaptureAndIdempotentFinish() {
        ShutterCapturePolicy p = new ShutterCapturePolicy();
        expect(p.onS2Down(), "S2 starts capture from IDLE");
        expect(p.isCapturing(), "state is CAPTURING");
        expect(!p.onS2Down(), "re-entrant S2 rejected while CAPTURING");
        expect(!p.onS1Down(), "S1 ignored while CAPTURING");

        expect(p.onCaptureFinished(ShutterCapturePolicy.SHUTTER_STATUS_OK), "first finish accepted");
        expect(p.photosTaken() == 1, "incremented photo count");
        expect(p.state() == ShutterCapturePolicy.State.IDLE, "returned to IDLE");
        expect(!p.onCaptureFinished(ShutterCapturePolicy.SHUTTER_STATUS_OK), "duplicate finish ignored");
        expect(p.photosTaken() == 1, "photo count not double-counted");

        // Error/cancel does not increment photo count
        expect(p.onS2Down(), "second S2 accepted");
        expect(p.onCaptureFinished(ShutterCapturePolicy.SHUTTER_STATUS_ERROR), "error finish accepted");
        expect(p.photosTaken() == 1, "error does not increment photo count");
    }

    private static void s1ReleaseDuringCaptureDoesNotBreakState() {
        ShutterCapturePolicy p = new ShutterCapturePolicy();
        p.onS1Down();
        p.onAutoFocusComplete(true);
        expect(p.onS2Down(), "S2 from AF_LOCKED starts capture");
        expect(!p.onS1Up(), "releasing S1 during capture does not cancel capture");
        expect(p.isCapturing(), "still CAPTURING until onShutter completes");
        p.reset();
        expect(p.state() == ShutterCapturePolicy.State.IDLE && !p.isS1Held(), "reset returns to IDLE");
    }

    private static void bannersAndBadges() {
        expect("[ AF... ]".equals(ShutterCapturePolicy.formatAfBadge(ShutterCapturePolicy.State.FOCUSING)), "AF focusing badge");
        expect("[ AF LOCKED ]".equals(ShutterCapturePolicy.formatAfBadge(ShutterCapturePolicy.State.AF_LOCKED)), "AF locked badge");
        expect("[ AF NO LOCK ]".equals(ShutterCapturePolicy.formatAfBadge(ShutterCapturePolicy.State.AF_FAILED)), "AF failed badge");
        expect("[ CAPTURING 24MP ]".equals(ShutterCapturePolicy.formatAfBadge(ShutterCapturePolicy.State.CAPTURING)), "capturing badge");
        expect("".equals(ShutterCapturePolicy.formatAfBadge(ShutterCapturePolicy.State.IDLE)), "empty badge when IDLE");

        expect(ShutterCapturePolicy.formatCaptureResultBanner(ShutterCapturePolicy.SHUTTER_STATUS_OK, 3)
                .contains("PHOTO #3 SAVED TO SD"), "OK banner includes photo count");
        expect(ShutterCapturePolicy.formatCaptureResultBanner(ShutterCapturePolicy.SHUTTER_STATUS_CANCELED, 3)
                .contains("CANCELED"), "canceled banner");
        expect(ShutterCapturePolicy.formatCaptureResultBanner(ShutterCapturePolicy.SHUTTER_STATUS_ERROR, 3)
                .contains("FAILED"), "error banner");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
