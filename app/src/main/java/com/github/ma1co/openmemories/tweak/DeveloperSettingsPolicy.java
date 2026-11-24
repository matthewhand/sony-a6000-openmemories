package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java persistence and startup reconciliation policy for Developer tab settings.
 * Ensures operators do not need to re-toggle Wi-Fi, Static IPv4, Keep Awake,
 * or ADB after every camera restart. Contains zero hardcoded personal SSIDs or IPs.
 */
final class DeveloperSettingsPolicy {
    static final String PREFS_NAME = "developer_prefs";
    static final String KEY_PERSIST_ENABLED = "persist_enabled";
    static final String KEY_HAS_SNAPSHOT = "has_snapshot";
    static final String KEY_WANT_WIFI = "want_wifi";
    static final String KEY_WANT_STATIC_IP = "want_static_ip";
    static final String KEY_WANT_KEEP_AWAKE = "keep_awake_apo";
    static final String KEY_WANT_ADB = "want_adb";
    static final String KEY_WANT_TELNET = "want_telnet";
    static final String KEY_BOOT_AUTORUN = "boot_autorun";
    static final String KEY_TRUSTED_SSID = "trusted_ssid";

    interface Store {
        boolean getBoolean(String key, boolean def);
        boolean putBoolean(String key, boolean value);
        String getString(String key, String def);
        boolean putString(String key, String value);
    }

    static final class Snapshot {
        final boolean persistEnabled;
        final boolean wantWifi;
        final boolean wantStaticIp;
        final boolean wantKeepAwake;
        final boolean wantAdb;
        final boolean wantTelnet;
        final boolean bootAutorun;
        final String trustedSsid;

        Snapshot(boolean persistEnabled, boolean wantWifi, boolean wantStaticIp,
                 boolean wantKeepAwake, boolean wantAdb,
                 boolean wantTelnet, boolean bootAutorun, String trustedSsid) {
            this.persistEnabled = persistEnabled;
            this.wantWifi = wantWifi;
            this.wantStaticIp = wantStaticIp;
            this.wantKeepAwake = wantKeepAwake;
            this.wantAdb = wantAdb;
            this.wantTelnet = wantTelnet;
            this.bootAutorun = bootAutorun;
            this.trustedSsid = normalizeSsid(trustedSsid);
        }
    }

    static final class RuntimeState {
        final boolean wifiEnabled;
        final boolean wifiConnected;
        final String connectedSsid;
        final boolean staticIpActive;
        final boolean adbRunning;
        final boolean telnetRunning;

        RuntimeState(boolean wifiEnabled, boolean wifiConnected, String connectedSsid,
                     boolean staticIpActive, boolean adbRunning, boolean telnetRunning) {
            this.wifiEnabled = wifiEnabled;
            this.wifiConnected = wifiConnected;
            this.connectedSsid = normalizeSsid(connectedSsid);
            this.staticIpActive = staticIpActive;
            this.adbRunning = adbRunning;
            this.telnetRunning = telnetRunning;
        }
    }

    static final class ReconcilePlan {
        final boolean applyStaticIp;
        final boolean enableWifi;
        final boolean inhibitApo;
        final boolean startAdb;
        final boolean startTelnet;

        ReconcilePlan(boolean applyStaticIp, boolean enableWifi, boolean inhibitApo,
                      boolean startAdb, boolean startTelnet) {
            this.applyStaticIp = applyStaticIp;
            this.enableWifi = enableWifi;
            this.inhibitApo = inhibitApo;
            this.startAdb = startAdb;
            this.startTelnet = startTelnet;
        }

        boolean isEmpty() {
            return !applyStaticIp && !enableWifi && !inhibitApo
                    && !startAdb && !startTelnet;
        }
    }

    private DeveloperSettingsPolicy() {}

    static String normalizeSsid(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).trim();
        }
        if ("<unknown ssid>".equalsIgnoreCase(s) || "0x".equalsIgnoreCase(s)) {
            return "";
        }
        return s;
    }

    /** If a trusted SSID is pinned, require an exact match; otherwise allow any connected SSID. */
    static boolean isTrustedSsid(String pinnedTrustedSsid, String connectedSsid) {
        String connected = normalizeSsid(connectedSsid);
        if (connected.length() == 0) return false;
        String pinned = normalizeSsid(pinnedTrustedSsid);
        return pinned.length() == 0 || pinned.equals(connected);
    }

    static Snapshot read(Store store) {
        if (store == null) {
            return new Snapshot(true, false, false, true, false, false, true, "");
        }
        return new Snapshot(
                store.getBoolean(KEY_PERSIST_ENABLED, true),
                store.getBoolean(KEY_WANT_WIFI, false),
                store.getBoolean(KEY_WANT_STATIC_IP, false),
                store.getBoolean(KEY_WANT_KEEP_AWAKE, true),
                store.getBoolean(KEY_WANT_ADB, false),
                store.getBoolean(KEY_WANT_TELNET, false),
                store.getBoolean(KEY_BOOT_AUTORUN, true),
                store.getString(KEY_TRUSTED_SSID, ""));
    }

    static void recordBooleanIfPersisting(Store store, String key, boolean value) {
        if (store == null || key == null) return;
        if (KEY_PERSIST_ENABLED.equals(key) || store.getBoolean(KEY_PERSIST_ENABLED, true)) {
            store.putBoolean(key, value);
            if (!KEY_PERSIST_ENABLED.equals(key)) {
                store.putBoolean(KEY_HAS_SNAPSHOT, true);
            }
        }
    }

    static void recordTrustedSsidIfAvailable(Store store, String connectedSsid) {
        if (store == null || !store.getBoolean(KEY_PERSIST_ENABLED, true)) return;
        String norm = normalizeSsid(connectedSsid);
        if (norm.length() > 0) {
            store.putString(KEY_TRUSTED_SSID, norm);
        }
    }

    /**
     * Auto-captures already-active runtime toggles on first launch/upgrade when persistence
     * is enabled and no explicit snapshot has been recorded yet. Never overwrites explicit
     * user preferences once KEY_HAS_SNAPSHOT is true.
     */
    static boolean bootstrapSnapshotIfEmpty(Store store, RuntimeState current) {
        if (store == null || current == null) return false;
        if (!store.getBoolean(KEY_PERSIST_ENABLED, true)) return false;
        if (store.getBoolean(KEY_HAS_SNAPSHOT, false)) return false;
        // Require at least one live radio/daemon active so dormant Settings.System static-IP flags
        // while Wi-Fi is temporarily disabled never lock in want_wifi=false.
        boolean anyActive = current.wifiEnabled || current.adbRunning || current.telnetRunning;
        if (!anyActive) return false;
        store.putBoolean(KEY_WANT_WIFI, current.wifiEnabled);
        store.putBoolean(KEY_WANT_STATIC_IP, current.staticIpActive);
        store.putBoolean(KEY_WANT_ADB, current.adbRunning);
        store.putBoolean(KEY_WANT_TELNET, current.telnetRunning);
        recordTrustedSsidIfAvailable(store, current.connectedSsid);
        store.putBoolean(KEY_HAS_SNAPSHOT, true);
        return true;
    }

    /**
     * Heals snapshots where static IP is active/desired (or boot_start_webcam is active)
     * so Wi-Fi is never left disabled on boot or webcam launch.
     */
    static Snapshot readAndHealForNetwork(Store store, boolean staticIpActive) {
        Snapshot s = read(store);
        if (store != null && s.persistEnabled && !s.wantWifi && (s.wantStaticIp || staticIpActive)) {
            recordBooleanIfPersisting(store, KEY_WANT_WIFI, true);
            s = read(store);
        }
        return s;
    }

    static ReconcilePlan planRestore(Snapshot desired, RuntimeState current) {
        if (desired == null || current == null || !desired.persistEnabled) {
            return new ReconcilePlan(false, false, false, false, false);
        }
        boolean applyStatic = desired.wantStaticIp && !current.staticIpActive;
        boolean enableWifi = desired.wantWifi && !current.wifiEnabled;
        boolean trustedLink = current.wifiConnected
                && isTrustedSsid(desired.trustedSsid, current.connectedSsid);
        boolean startAdb = desired.wantAdb && !current.adbRunning && trustedLink;
        boolean startTelnet = desired.wantTelnet && !current.telnetRunning && trustedLink;
        boolean willHaveActivity = current.wifiEnabled || enableWifi
                || current.adbRunning || startAdb
                || current.telnetRunning || startTelnet;
        boolean inhibitApo = AutoPowerOffPolicy.shouldInhibit(desired.wantKeepAwake, willHaveActivity);
        return new ReconcilePlan(applyStatic, enableWifi, inhibitApo, startAdb, startTelnet);
    }

    static String summary(Snapshot s) {
        if (s == null || !s.persistEnabled) {
            return "Persistence disabled (toggles reset on reboot)";
        }
        StringBuilder sb = new StringBuilder("Saved:");
        boolean any = false;
        if (s.wantWifi) { sb.append(" Wi-Fi"); any = true; }
        if (s.wantStaticIp) { sb.append(" StaticIP"); any = true; }
        if (s.wantKeepAwake) { sb.append(" APO/NO"); any = true; }
        if (s.wantAdb) { sb.append(" ADB"); any = true; }
        if (s.wantTelnet) { sb.append(" Telnet"); any = true; }
        if (!any) sb.append(" (none)");
        if (s.trustedSsid.length() > 0) {
            sb.append(" [").append(s.trustedSsid).append(']');
        }
        return sb.toString();
    }
}
