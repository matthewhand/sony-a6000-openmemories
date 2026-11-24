package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java policy managing Sony DAConnectionManagerService Auto Power Off (APO) state.
 * Inhibits camera auto-shutdown ("APO/NO") while keep-awake is enabled and Wi-Fi, ADB,
 * Telnet, or streaming activity is running, restoring "APO/NORMAL" otherwise.
 */
final class AutoPowerOffPolicy {
    static final String ACTION_APO = "com.android.server.DAConnectionManagerService.apo";
    static final String EXTRA_APO_INFO = "apo_info";
    static final String MODE_DISABLE = "APO/NO";
    static final String MODE_NORMAL = "APO/NORMAL";

    static final String ACTION_APP_INFO = "com.android.server.DAConnectionManagerService.AppInfoReceive";
    static final String EXTRA_PACKAGE_NAME = "package_name";
    static final String EXTRA_CLASS_NAME = "class_name";

    private String lastSentMode;

    static boolean shouldInhibit(boolean keepAwakePref, boolean activeNetworkOrDaemon) {
        return keepAwakePref && activeNetworkOrDaemon;
    }

    static String modeFor(boolean inhibit) {
        return inhibit ? MODE_DISABLE : MODE_NORMAL;
    }

    /**
     * Returns the APO mode string to broadcast, or null if the target mode matches the
     * last broadcast sent by this policy instance.
     */
    synchronized String nextBroadcastMode(boolean keepAwakePref, boolean activeNetworkOrDaemon) {
        String desired = modeFor(shouldInhibit(keepAwakePref, activeNetworkOrDaemon));
        if (desired.equals(lastSentMode)) {
            return null;
        }
        lastSentMode = desired;
        return desired;
    }

    synchronized String lastSentMode() {
        return lastSentMode;
    }

    synchronized void reset() {
        lastSentMode = null;
    }
}
