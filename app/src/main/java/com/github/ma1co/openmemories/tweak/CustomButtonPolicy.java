package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java mapping and transitions for Sony's physical C1 custom button
 * (ISV_KEY_CUSTOM1 = 622, legacy ISV_KEY_CUSTOM = 588) inside LivePreviewActivity.
 */
final class CustomButtonPolicy {
    static final String KEY_C1_ACTION = "c1_button_action";
    static final int SCAN_CUSTOM1 = 622;
    static final int SCAN_CUSTOM_LEGACY = 588;

    enum Action {
        TOGGLE_SAVER_DIM("toggle_saver_dim", "Toggle Screensaver & Dim (Live Hold)"),
        CYCLE_STAGE("cycle_stage", "Cycle Live -> Saver -> Dim"),
        TOGGLE_DIM_NOW("toggle_dim_now", "Toggle LCD Backlight Off Now"),
        TOGGLE_HUD("toggle_hud", "Toggle Debug HUD"),
        TOGGLE_MOTION("toggle_motion", "Toggle Motion Wake"),
        NEXT_STYLE("next_style", "Next Screensaver Style"),
        DISABLED("disabled", "Disabled (Ignore C1)");

        final String id;
        final String label;

        Action(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private CustomButtonPolicy() {}

    static boolean isC1ScanCode(int scanCode) {
        return scanCode == SCAN_CUSTOM1 || scanCode == SCAN_CUSTOM_LEGACY;
    }

    static Action parseAction(String id) {
        if (id != null) {
            Action[] values = Action.values();
            for (int i = 0; i < values.length; i++) {
                if (values[i].id.equals(id)) return values[i];
            }
        }
        return Action.TOGGLE_SAVER_DIM;
    }

    static String[] labels() {
        Action[] values = Action.values();
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i].label;
        return out;
    }

    static int indexOf(Action action) {
        Action target = action == null ? Action.TOGGLE_SAVER_DIM : action;
        Action[] values = Action.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return 0;
    }

    static ScreensaverPolicy.Stage nextStage(ScreensaverPolicy.Stage current) {
        if (current == ScreensaverPolicy.Stage.ACTIVE) return ScreensaverPolicy.Stage.SCREENSAVER;
        if (current == ScreensaverPolicy.Stage.SCREENSAVER) return ScreensaverPolicy.Stage.FULL_DIM;
        return ScreensaverPolicy.Stage.ACTIVE;
    }

    static ScreensaverPolicy.Style nextConcreteStyle(ScreensaverPolicy.Style current) {
        ScreensaverPolicy.Style[] styles = ScreensaverPolicy.concreteStyles();
        for (int i = 0; i < styles.length; i++) {
            if (styles[i] == current) {
                return styles[(i + 1) % styles.length];
            }
        }
        return styles[0];
    }
}
