package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java boot broadcast filter and per-boot deduplication policy.
 * Handles both standard Android BOOT_COMPLETED and Sony's DAConnectionManagerService.BootCompleted.
 */
final class BootRecoveryPolicy {
    static final String ACTION_ANDROID_BOOT = "android.intent.action.BOOT_COMPLETED";
    static final String ACTION_SONY_BOOT = "com.android.server.DAConnectionManagerService.BootCompleted";

    private boolean triggeredThisBoot;

    static boolean isBootAction(String action) {
        return ACTION_ANDROID_BOOT.equals(action) || ACTION_SONY_BOOT.equals(action);
    }

    synchronized boolean shouldTrigger(String action, boolean enabled) {
        if (!enabled || !isBootAction(action)) return false;
        if (triggeredThisBoot) return false;
        triggeredThisBoot = true;
        return true;
    }

    synchronized void resetForTests() {
        triggeredThisBoot = false;
    }
}
