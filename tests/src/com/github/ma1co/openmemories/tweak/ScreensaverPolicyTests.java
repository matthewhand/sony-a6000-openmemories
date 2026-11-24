package com.github.ma1co.openmemories.tweak;

import java.util.HashSet;
import java.util.Set;

final class ScreensaverPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        stageLifecycleAndWakeReasons();
        customTimeoutAndZeroDisablesLogically();
        styleParsingRandomDividerAndClamping();
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

    private static void customTimeoutAndZeroDisablesLogically() {
        expect(!ScreensaverPolicy.isEnabledForDelay(0), "0s disables screensaver");
        expect(ScreensaverPolicy.isEnabledForDelay(15), "15s enables screensaver");

        ScreensaverPolicy p = new ScreensaverPolicy();
        p.onUserActivity(0L);
        expect(p.evaluate(14999L, 15, 30) == ScreensaverPolicy.Stage.ACTIVE, "stays ACTIVE before 15s");
        expect(p.evaluate(15000L, 15, 30) == ScreensaverPolicy.Stage.SCREENSAVER, "enters SCREENSAVER at 15s");
        expect(p.evaluate(44999L, 15, 30) == ScreensaverPolicy.Stage.SCREENSAVER, "stays SCREENSAVER before +30s");
        expect(p.evaluate(45000L, 15, 30) == ScreensaverPolicy.Stage.FULL_DIM, "enters FULL_DIM at +30s");

        ScreensaverPolicy noDim = new ScreensaverPolicy();
        noDim.onUserActivity(0L);
        expect(noDim.evaluate(10000L, 10, 0) == ScreensaverPolicy.Stage.SCREENSAVER, "enters SCREENSAVER");
        expect(noDim.evaluate(9999999L, 10, 0) == ScreensaverPolicy.Stage.SCREENSAVER, "dimSec=0 never dims");
        expect(ScreensaverPolicy.computeAlpha(9999999L, 0L) == 1.0f, "dimSec=0 stays full alpha");
        expect("OFF".equals(ScreensaverPolicy.formatCountdown(noDim.remainingToDimMs(10000L, 0))), "dim OFF label");
        expect(noDim.evaluate(10000000L, 0, 300) == ScreensaverPolicy.Stage.ACTIVE, "delaySec=0 returns ACTIVE");
    }

    private static void styleParsingRandomDividerAndClamping() {
        expect(ScreensaverPolicy.concreteStyles().length == 6, "6 concrete styles");
        expect(ScreensaverPolicy.parseStyle(null) == ScreensaverPolicy.Style.RANDOM, "default style is Random");
        expect(ScreensaverPolicy.parseStyle("unknown") == ScreensaverPolicy.Style.RANDOM, "unknown falls back to Random");
        expect(ScreensaverPolicy.parseStyle("matrix") == ScreensaverPolicy.Style.MATRIX, "parses matrix");
        expect(ScreensaverPolicy.parseStyle("gallery") == ScreensaverPolicy.Style.GALLERY, "parses gallery");
        expect(ScreensaverPolicy.parseStyle("clock") == ScreensaverPolicy.Style.CLOCK, "parses clock");
        expect(ScreensaverPolicy.parseStyle("bouncer") == ScreensaverPolicy.Style.BOUNCER, "parses bouncer");

        String[] labels = ScreensaverPolicy.pickerLabels();
        expect(labels.length == 8, "Random + divider + 6 concrete styles");
        expect("Random".equals(labels[0]), "Random is first");
        expect(ScreensaverPolicy.DIVIDER_LABEL.equals(labels[1]), "divider separates Random from rest");
        expect(ScreensaverPolicy.styleForPickerIndex(0) == ScreensaverPolicy.Style.RANDOM, "index 0 -> Random");
        expect(ScreensaverPolicy.styleForPickerIndex(1) == null, "divider index returns null");
        expect(ScreensaverPolicy.styleForPickerIndex(2) == ScreensaverPolicy.Style.MATRIX, "index 2 -> Matrix");
        expect(ScreensaverPolicy.pickerIndex(ScreensaverPolicy.Style.RANDOM) == 0, "pickerIndex Random");
        expect(ScreensaverPolicy.pickerIndex(ScreensaverPolicy.Style.MATRIX) == 2, "pickerIndex Matrix after divider");

        // Random resolves across multiple concrete styles and never returns RANDOM itself
        Set<ScreensaverPolicy.Style> seen = new HashSet<ScreensaverPolicy.Style>();
        for (long seed = 1L; seed <= 60L; seed++) {
            ScreensaverPolicy.Style chosen = ScreensaverPolicy.resolveConcreteStyle(ScreensaverPolicy.Style.RANDOM, seed);
            expect(chosen != null && chosen != ScreensaverPolicy.Style.RANDOM, "resolved concrete style");
            seen.add(chosen);
        }
        expect(seen.size() == 6, "Random spans all 6 concrete styles");
        expect(ScreensaverPolicy.resolveConcreteStyle(ScreensaverPolicy.Style.CLOCK, 42L) == ScreensaverPolicy.Style.CLOCK,
                "explicit style preserved");

        expect(ScreensaverPolicy.clampDelaySec(-5) == 0, "clamps negative delay to 0");
        expect(ScreensaverPolicy.clampDelaySec(99999) == ScreensaverPolicy.MAX_DELAY_SEC, "clamps max delay");
        expect(ScreensaverPolicy.formatTimingSummary(0, 300).contains("Disabled"), "disabled summary");
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
