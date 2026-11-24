package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java state machine guarding rear-LCD backlight-off (DEVICE_ID_NONE) transitions
 * and guaranteed restoration to DEVICE_ID_PANEL on wake, pause, or teardown.
 */
final class DisplayBacklightPolicy {
    static final String DEVICE_PANEL = "DEVICE_ID_PANEL";
    static final String DEVICE_NONE = "DEVICE_ID_NONE";

    private boolean backlightOff;
    private String restoreTarget = DEVICE_PANEL;

    boolean isBacklightOff() { return backlightOff; }
    String restoreTarget() { return restoreTarget; }

    /**
     * Returns DEVICE_ID_NONE when switching off is safe and not yet off; returns null if
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
    }

    /** Returns the display device ID to restore when currently off, otherwise null. */
    String planRestore() {
        if (!backlightOff) return null;
        return restoreTarget;
    }

    void markRestored() {
        backlightOff = false;
    }
}
