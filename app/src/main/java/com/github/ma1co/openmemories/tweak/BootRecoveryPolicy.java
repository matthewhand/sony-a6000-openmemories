package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java boot broadcast filter, per-boot deduplication, and UI-presence launch guard.
 * Handles both standard Android BOOT_COMPLETED and Sony's DAConnectionManagerService.BootCompleted,
 * and prevents delayed boot UI launch from interrupting an already-opened foreground Activity.
 */
final class BootRecoveryPolicy {
    static final String ACTION_ANDROID_BOOT = "android.intent.action.BOOT_COMPLETED";
    static final String ACTION_SONY_BOOT = "com.android.server.DAConnectionManagerService.BootCompleted";
    static final BootRecoveryPolicy SHARED = new BootRecoveryPolicy();

    private boolean triggeredThisBoot;
    private boolean uiOpenedThisBoot;
    private int foregroundUiCount;

    static boolean isBootAction(String action) {
        return ACTION_ANDROID_BOOT.equals(action) || ACTION_SONY_BOOT.equals(action);
    }

    synchronized boolean shouldTrigger(String action, boolean enabled) {
        if (!enabled || !isBootAction(action)) return false;
        if (triggeredThisBoot) return false;
        triggeredThisBoot = true;
        return true;
    }

    synchronized void markUiOpened() {
        uiOpenedThisBoot = true;
    }

    synchronized void onUiResumed() {
        uiOpenedThisBoot = true;
        foregroundUiCount++;
    }

    synchronized void onUiPaused() {
        if (foregroundUiCount > 0) foregroundUiCount--;
    }

    synchronized boolean isUiForeground() {
        return foregroundUiCount > 0;
    }

    /**
     * Returns true only when the app UI is neither currently foreground nor already opened
     * during this boot (preventing delayed BootRecoveryService launch from colliding with
     * a manual app open and triggering performNewIntents -> onPause -> onResume).
     */
    synchronized boolean shouldLaunchUi() {
        return foregroundUiCount == 0 && !uiOpenedThisBoot;
    }

    synchronized void resetForTests() {
        triggeredThisBoot = false;
        uiOpenedThisBoot = false;
        foregroundUiCount = 0;
    }
}
