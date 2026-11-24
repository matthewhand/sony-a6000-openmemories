package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java state machine and Sony ScalarInput scan-code mapping for physical
 * half-press AutoFocus (S1: 516/517) and full-press 24 MP photo capture (S2: 518)
 * inside LivePreviewActivity.
 */
final class ShutterCapturePolicy {
    static final int SCAN_S1_1 = 516; // ScalarInput.ISV_KEY_S1_1
    static final int SCAN_S1_2 = 517; // ScalarInput.ISV_KEY_S1_2
    static final int SCAN_S2 = 518;   // ScalarInput.ISV_KEY_S2

    static final int SHUTTER_STATUS_OK = 0;
    static final int SHUTTER_STATUS_CANCELED = 1;
    static final int SHUTTER_STATUS_ERROR = 2;

    static final long CAPTURE_TIMEOUT_MS = 6000L;
    static final long SEQUENCE_RESUME_DELAY_MS = 350L;
    static final long FLASH_DURATION_MS = 180L;

    enum State {
        IDLE,
        FOCUSING,
        AF_LOCKED,
        AF_FAILED,
        CAPTURING
    }

    private volatile State state = State.IDLE;
    private volatile int photosTaken;
    private volatile boolean s1Held;

    static boolean isS1ScanCode(int scanCode) {
        return scanCode == SCAN_S1_1 || scanCode == SCAN_S1_2;
    }

    static boolean isS2ScanCode(int scanCode) {
        return scanCode == SCAN_S2;
    }

    static boolean isAnyShutterScanCode(int scanCode) {
        return isS1ScanCode(scanCode) || isS2ScanCode(scanCode);
    }

    State state() { return state; }
    int photosTaken() { return photosTaken; }
    boolean isCapturing() { return state == State.CAPTURING; }
    boolean isS1Held() { return s1Held; }

    /** Returns true if LivePreviewActivity should invoke normalCamera.autoFocus(). */
    synchronized boolean onS1Down() {
        s1Held = true;
        if (state == State.CAPTURING || state == State.FOCUSING) return false;
        state = State.FOCUSING;
        return true;
    }

    /** Records AF callback result; returns true if still relevant (not already capturing/idle). */
    synchronized boolean onAutoFocusComplete(boolean success) {
        if (state != State.FOCUSING) return false;
        state = success ? State.AF_LOCKED : State.AF_FAILED;
        return true;
    }

    /** Returns true if LivePreviewActivity should invoke normalCamera.cancelAutoFocus(). */
    synchronized boolean onS1Up() {
        s1Held = false;
        if (state == State.CAPTURING) return false;
        boolean hadAf = (state != State.IDLE);
        state = State.IDLE;
        return hadAf;
    }

    /** Returns true if LivePreviewActivity should pause CameraSequence and fire burstableTakePicture(). */
    synchronized boolean onS2Down() {
        if (state == State.CAPTURING) return false;
        state = State.CAPTURING;
        return true;
    }

    /**
     * Completes an active capture attempt (from CameraEx.ShutterListener.onShutter or timeout).
     * Returns true if this transition actually ended an active capture (idempotent against duplicate callbacks).
     */
    synchronized boolean onCaptureFinished(int status) {
        if (state != State.CAPTURING) return false;
        if (status == SHUTTER_STATUS_OK) {
            photosTaken++;
        }
        state = State.IDLE;
        return true;
    }

    synchronized void reset() {
        state = State.IDLE;
        s1Held = false;
    }

    static String formatAfBadge(State st) {
        if (st == State.FOCUSING) return "[ AF... ]";
        if (st == State.AF_LOCKED) return "[ AF LOCKED ]";
        if (st == State.AF_FAILED) return "[ AF NO LOCK ]";
        if (st == State.CAPTURING) return "[ CAPTURING 24MP ]";
        return "";
    }

    static String formatCaptureResultBanner(int status, int totalPhotos) {
        if (status == SHUTTER_STATUS_OK) {
            return "PHOTO #" + totalPhotos + " SAVED TO SD (24MP)";
        }
        if (status == SHUTTER_STATUS_CANCELED) {
            return "SHUTTER CANCELED — resuming stream";
        }
        return "SHUTTER FAILED (code=" + status + ") — resuming stream";
    }
}
