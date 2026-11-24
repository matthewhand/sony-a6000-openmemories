package com.github.ma1co.openmemories.tweak;

final class CustomButtonPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        scanCodesAndActionParsing();
        stageAndStyleCycling();
        System.out.println("PASS CustomButtonPolicyTests: " + assertions + " assertions");
    }

    private static void scanCodesAndActionParsing() {
        expect(CustomButtonPolicy.isC1ScanCode(622), "accepts ISV_KEY_CUSTOM1 (622)");
        expect(CustomButtonPolicy.isC1ScanCode(588), "accepts legacy ISV_KEY_CUSTOM (588)");
        expect(!CustomButtonPolicy.isC1ScanCode(514), "rejects MENU (514)");
        expect(!CustomButtonPolicy.isC1ScanCode(0), "rejects 0");
        expect(CustomButtonPolicy.isC2ScanCode(623), "accepts ISV_KEY_CUSTOM2 (623)");
        expect(CustomButtonPolicy.isC2ScanCode(595), "accepts A6000 C2/Delete/? (595)");
        expect(!CustomButtonPolicy.isC2ScanCode(514), "rejects MENU (514) for C2");

        expect(CustomButtonPolicy.parseAction(null) == CustomButtonPolicy.Action.TOGGLE_SAVER_DIM,
                "default C1 action is toggle screensaver & dim");
        expect(CustomButtonPolicy.parseAction("bogus") == CustomButtonPolicy.Action.TOGGLE_SAVER_DIM,
                "unknown falls back to default");
        expect(CustomButtonPolicy.parseAction("cycle_display_output") == CustomButtonPolicy.Action.CYCLE_DISPLAY_OUTPUT,
                "parses cycle_display_output");
        expect(CustomButtonPolicy.parseAction("toggle_http_server") == CustomButtonPolicy.Action.TOGGLE_HTTP_SERVER,
                "parses toggle_http_server");
        expect(CustomButtonPolicy.parseAction("toggle_hud") == CustomButtonPolicy.Action.TOGGLE_HUD,
                "parses toggle_hud");
        expect(CustomButtonPolicy.parseAction("disabled") == CustomButtonPolicy.Action.DISABLED,
                "parses disabled");
        expect(CustomButtonPolicy.labels().length == CustomButtonPolicy.Action.values().length,
                "labels length matches enum");
        expect(CustomButtonPolicy.indexOf(CustomButtonPolicy.Action.CYCLE_DISPLAY_OUTPUT) == 1,
                "indexOf cycle_display_output");

        String tipDefault = CustomButtonPolicy.formatLaunchTip(CustomButtonPolicy.Action.TOGGLE_SAVER_DIM);
        expect(tipDefault.contains("MENU: Full Settings") && tipDefault.contains("C2(?): Quick Menu")
                && tipDefault.contains("C1: Pause Saver"),
                "default launch tip shows MENU, C2, OK, and C1 hint");
        String tipDisabled = CustomButtonPolicy.formatLaunchTip(CustomButtonPolicy.Action.DISABLED);
        expect(tipDisabled.contains("MENU: Full Settings") && tipDisabled.contains("C2(?): Quick Menu")
                && !tipDisabled.contains("C1:"),
                "disabled C1 omits C1 segment from launch tip");
    }

    private static void stageAndStyleCycling() {
        expect(CustomButtonPolicy.nextStage(ScreensaverPolicy.Stage.ACTIVE) == ScreensaverPolicy.Stage.SCREENSAVER,
                "ACTIVE -> SCREENSAVER");
        expect(CustomButtonPolicy.nextStage(ScreensaverPolicy.Stage.SCREENSAVER) == ScreensaverPolicy.Stage.FULL_DIM,
                "SCREENSAVER -> FULL_DIM");
        expect(CustomButtonPolicy.nextStage(ScreensaverPolicy.Stage.FULL_DIM) == ScreensaverPolicy.Stage.ACTIVE,
                "FULL_DIM -> ACTIVE");

        expect(CustomButtonPolicy.nextConcreteStyle(ScreensaverPolicy.Style.MATRIX) == ScreensaverPolicy.Style.GALLERY,
                "MATRIX -> GALLERY");
        expect(CustomButtonPolicy.nextConcreteStyle(ScreensaverPolicy.Style.MINIMAL) == ScreensaverPolicy.Style.MATRIX,
                "MINIMAL wraps to MATRIX");
        expect(CustomButtonPolicy.nextConcreteStyle(ScreensaverPolicy.Style.RANDOM) == ScreensaverPolicy.Style.MATRIX,
                "RANDOM starts at MATRIX");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
