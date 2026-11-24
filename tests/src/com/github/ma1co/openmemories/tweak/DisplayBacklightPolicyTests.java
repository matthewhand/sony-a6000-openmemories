package com.github.ma1co.openmemories.tweak;

final class DisplayBacklightPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        turnsOffOnlyFromPanelAndRestoresCleanly();
        respectsDisabledAndNonPanelOutputs();
        cyclesPanelFinderOffAndRestoresToPanel();
        System.out.println("PASS DisplayBacklightPolicyTests: " + assertions + " assertions");
    }

    private static void turnsOffOnlyFromPanelAndRestoresCleanly() {
        DisplayBacklightPolicy p = new DisplayBacklightPolicy();
        expect(!p.isBacklightOff(), "starts with backlight on");
        expect(p.planRestore() == null, "restore no-op when already on");
        expect(DisplayBacklightPolicy.DEVICE_NONE.equals(p.planTurnOff(true, DisplayBacklightPolicy.DEVICE_PANEL)),
                "plans DEVICE_ID_NONE from panel");
        p.markTurnedOff();
        expect(p.isBacklightOff(), "marked off");
        expect(p.planTurnOff(true, DisplayBacklightPolicy.DEVICE_PANEL) == null, "idempotent while off");
        expect(DisplayBacklightPolicy.DEVICE_PANEL.equals(p.planRestore()), "plans restore to panel");
        p.markRestored();
        expect(!p.isBacklightOff(), "marked restored");
        expect(p.planRestore() == null, "restore idempotent");
    }

    private static void respectsDisabledAndNonPanelOutputs() {
        DisplayBacklightPolicy p = new DisplayBacklightPolicy();
        expect(p.planTurnOff(false, DisplayBacklightPolicy.DEVICE_PANEL) == null, "disabled blocks turn-off");
        expect(p.planTurnOff(true, "DEVICE_ID_FINDER") == null, "EVF active fails safe");
        expect(p.planTurnOff(true, "DEVICE_ID_HDMI") == null, "HDMI active fails safe");
        expect(DisplayBacklightPolicy.DEVICE_NONE.equals(p.planTurnOff(true, null)),
                "null active device allows fallback turn-off");
    }

    private static void cyclesPanelFinderOffAndRestoresToPanel() {
        expect(DisplayBacklightPolicy.nextCycleOutput(DisplayBacklightPolicy.Output.PANEL)
                == DisplayBacklightPolicy.Output.FINDER, "LCD -> EVF");
        expect(DisplayBacklightPolicy.nextCycleOutput(DisplayBacklightPolicy.Output.FINDER)
                == DisplayBacklightPolicy.Output.OFF, "EVF -> OFF");
        expect(DisplayBacklightPolicy.nextCycleOutput(DisplayBacklightPolicy.Output.OFF)
                == DisplayBacklightPolicy.Output.PANEL, "OFF -> LCD");

        DisplayBacklightPolicy p = new DisplayBacklightPolicy();
        expect(p.currentOutput() == DisplayBacklightPolicy.Output.PANEL, "starts on PANEL");
        expect(p.planRestoreToPanel() == null, "restoreToPanel no-op when already PANEL");

        p.markExplicitOutput(DisplayBacklightPolicy.Output.FINDER);
        expect(p.currentOutput() == DisplayBacklightPolicy.Output.FINDER, "marked FINDER");
        expect(!p.isBacklightOff(), "FINDER is not backlight off");
        expect(p.planRestore() == null, "normal restore keeps FINDER active");
        expect(DisplayBacklightPolicy.DEVICE_PANEL.equals(p.planRestoreToPanel()),
                "pause/exit restoreToPanel returns to LCD from EVF");

        p.markExplicitOutput(DisplayBacklightPolicy.Output.OFF);
        expect(p.isBacklightOff(), "explicit OFF marks backlightOff");
        expect(DisplayBacklightPolicy.DEVICE_PANEL.equals(p.planRestore()), "wake from OFF restores PANEL");
        p.markRestoredToPanel();
        expect(p.currentOutput() == DisplayBacklightPolicy.Output.PANEL && !p.isBacklightOff(),
                "markRestoredToPanel resets cleanly");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
