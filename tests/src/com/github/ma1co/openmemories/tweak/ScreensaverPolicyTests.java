package com.github.ma1co.openmemories.tweak;

final class ScreensaverPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        stageLifecycleAndWakeReasons();
        fadeCurveAndCountdownFormatting();
        clockRollbackAndDisableGuard();
        System.out.println("PASS ScreensaverPolicyTests: " + assertions + " assertions");
    }

    private static void stageLifecycleAndWakeReasons() {
        ScreensaverPolicy p = new ScreensaverPolicy();
        p.onUserActivity(1000L);
        expect(p.stage() == ScreensaverPolicy.Stage.ACTIVE, "starts ACTIVE");
        expect(p.remainingToSaverMs(31000L) == 30000L, "30s remaining to saver");
        expect(p.evaluate(60999L, true) == ScreensaverPolicy.Stage.ACTIVE, "stays ACTIVE at 59.999s");
        expect(p.evaluate(61000L, true) == ScreensaverPolicy.Stage.SCREENSAVER, "enters SCREENSAVER at 60s");
        expect(p.remainingToDimMs(61000L) == 300000L, "5m remaining at saver entry");
        expect(p.evaluate(360999L, true) == ScreensaverPolicy.Stage.SCREENSAVER, "stays SCREENSAVER at 4m59.999s");
        expect(p.evaluate(361000L, true) == ScreensaverPolicy.Stage.FULL_DIM, "enters FULL_DIM at 5m");
        expect(p.remainingToDimMs(361000L) == 0L, "0 remaining in FULL_DIM");
        p.wake(400000L, "motion");
        expect(p.stage() == ScreensaverPolicy.Stage.ACTIVE, "wakes to ACTIVE");
        expect("motion".equals(p.lastWakeReason()), "records wake reason");
        p.enterFullDimNow(401000L);
        expect(p.stage() == ScreensaverPolicy.Stage.FULL_DIM, "explicit FULL_DIM transition");
    }

    private static void fadeCurveAndCountdownFormatting() {
        expect(ScreensaverPolicy.computeAlpha(0L) == 1.0f, "full alpha at 0s");
        expect(ScreensaverPolicy.computeAlpha(285000L) == 1.0f, "full alpha before 15s fade window");
        float mid = ScreensaverPolicy.computeAlpha(292500L);
        expect(mid > 0.45f && mid < 0.55f, "half alpha mid-fade");
        expect(ScreensaverPolicy.computeAlpha(300000L) == 0.0f, "zero alpha at 5m");
        expect(ScreensaverPolicy.computeAlpha(350000L) == 0.0f, "clamps at zero past 5m");
        expect("05:00".equals(ScreensaverPolicy.formatCountdown(300000L)), "05:00");
        expect("00:01".equals(ScreensaverPolicy.formatCountdown(500L)), "00:01");
        expect("00:00".equals(ScreensaverPolicy.formatCountdown(0L)), "00:00");
    }

    private static void clockRollbackAndDisableGuard() {
        ScreensaverPolicy p = new ScreensaverPolicy();
        p.enterScreensaverNow(50000L);
        expect(p.evaluate(40000L, true) == ScreensaverPolicy.Stage.SCREENSAVER, "clock rollback stays SCREENSAVER");
        expect(p.evaluate(50000L, false) == ScreensaverPolicy.Stage.ACTIVE, "disabled returns to ACTIVE");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
