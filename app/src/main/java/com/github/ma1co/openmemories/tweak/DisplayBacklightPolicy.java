package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java state machine guarding rear-LCD / EVF / backlight-off display output transitions
 * (DEVICE_ID_PANEL, DEVICE_ID_FINDER, DEVICE_ID_NONE) with guaranteed restoration on wake/exit.
 */
final class DisplayBacklightPolicy {
    static final String DEVICE_PANEL = "DEVICE_ID_PANEL";
    static final String DEVICE_FINDER = "DEVICE_ID_FINDER";
    static final String DEVICE_NONE = "DEVICE_ID_NONE";

    enum Output {
        PANEL(DEVICE_PANEL, "LCD"),
        FINDER(DEVICE_FINDER, "EVF"),
        OFF(DEVICE_NONE, "OFF");

        final String deviceId;
        final String shortLabel;

        Output(String deviceId, String shortLabel) {
            this.deviceId = deviceId;
            this.shortLabel = shortLabel;
        }
    }

    private boolean backlightOff;
    private Output currentOutput = Output.PANEL;
    private String restoreTarget = DEVICE_PANEL;

    static Output nextCycleOutput(Output current) {
        if (current == Output.PANEL) return Output.FINDER;
        if (current == Output.FINDER) return Output.OFF;
        return Output.PANEL;
    }

    static Output fromDeviceId(String deviceId) {
        if (DEVICE_FINDER.equals(deviceId)) return Output.FINDER;
        if (DEVICE_NONE.equals(deviceId)) return Output.OFF;
        return Output.PANEL;
    }

    boolean isBacklightOff() { return backlightOff; }
    Output currentOutput() { return currentOutput; }
    String restoreTarget() { return restoreTarget; }

    /**
     * Returns DEVICE_ID_NONE when auto-dim switching off is safe and not yet off; returns null if
     * disabled, already off, or if the active display is an external/EVF output that must not be disturbed.
     */
    String planTurnOff(boolean enabled, String activeDevice) {
        if (!enabled || backlightOff) return null;
        if (activeDevice != null && activeDevice.length() > 0 && !DEVICE_PANEL.equals(activeDevice)) {
            return null;
        }
        restoreTarget = DEVICE_PANEL;
        return DEVICE_NONE;
    }

    void markTurnedOff() {
        backlightOff = true;
        currentOutput = Output.OFF;
    }

    /** Record an explicit user output selection (LCD, EVF, or OFF). */
    void markExplicitOutput(Output target) {
        Output resolved = target == null ? Output.PANEL : target;
        currentOutput = resolved;
        if (resolved == Output.OFF) {
            backlightOff = true;
            restoreTarget = DEVICE_PANEL;
        } else {
            backlightOff = false;
            restoreTarget = resolved.deviceId;
        }
    }

    /** Returns the display device ID to restore when currently off, otherwise null. */
    String planRestore() {
        if (!backlightOff) return null;
        return restoreTarget;
    }

    /** Returns DEVICE_ID_PANEL whenever current output is not already PANEL (used on pause/exit). */
    String planRestoreToPanel() {
        if (!backlightOff && currentOutput == Output.PANEL) return null;
        return DEVICE_PANEL;
    }

    void markRestored() {
        backlightOff = false;
        currentOutput = fromDeviceId(restoreTarget);
    }

    void markRestoredToPanel() {
        backlightOff = false;
        currentOutput = Output.PANEL;
        restoreTarget = DEVICE_PANEL;
    }
}
