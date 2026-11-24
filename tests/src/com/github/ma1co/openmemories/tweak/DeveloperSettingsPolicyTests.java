package com.github.ma1co.openmemories.tweak;

import java.util.HashMap;
import java.util.Map;

final class DeveloperSettingsPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        ssidNormalizationAndTrust();
        defaultsAreConservative();
        persistRecordsAndRestoresInOrder();
        disabledPersistProducesNoOpPlan();
        untrustedSsidBlocksAdbAndTelnet();
        autoBootstrapCapturesActiveStateOnce();
        System.out.println("PASS DeveloperSettingsPolicyTests: " + assertions + " assertions");
    }

    private static void ssidNormalizationAndTrust() {
        expect("LabNet".equals(DeveloperSettingsPolicy.normalizeSsid("\"LabNet\"")), "strips quotes");
        expect("".equals(DeveloperSettingsPolicy.normalizeSsid("<unknown ssid>")), "unknown ssid -> empty");
        expect("".equals(DeveloperSettingsPolicy.normalizeSsid(null)), "null -> empty");
        expect(DeveloperSettingsPolicy.isTrustedSsid("LabNet", "\"LabNet\""), "exact SSID match");
        expect(!DeveloperSettingsPolicy.isTrustedSsid("LabNet", "CoffeeShop"), "rejects mismatched SSID");
        expect(DeveloperSettingsPolicy.isTrustedSsid("", "AnyConnected"), "unpinned allows connected");
        expect(!DeveloperSettingsPolicy.isTrustedSsid("", ""), "disconnected never trusted");
    }

    private static void defaultsAreConservative() {
        FakeStore store = new FakeStore();
        DeveloperSettingsPolicy.Snapshot s = DeveloperSettingsPolicy.read(store);
        expect(s.persistEnabled, "persist enabled by default");
        expect(!s.wantWifi, "wifi off until user enables");
        expect(!s.wantAdb, "adb off until user enables");
        expect(!s.wantTelnet, "telnet off by default");
        expect(s.wantKeepAwake, "keep awake on by default");
        expect(s.bootAutorun, "boot autorun on by default");
    }

    private static void persistRecordsAndRestoresInOrder() {
        FakeStore store = new FakeStore();
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_STATIC_IP, true);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_WIFI, true);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_ADB, true);
        DeveloperSettingsPolicy.recordTrustedSsidIfAvailable(store, "\"StudioLan\"");

        DeveloperSettingsPolicy.Snapshot desired = DeveloperSettingsPolicy.read(store);
        // Before Wi-Fi connects: apply static IP, enable Wi-Fi, inhibit APO, defer ADB
        DeveloperSettingsPolicy.RuntimeState cold = new DeveloperSettingsPolicy.RuntimeState(
                false, false, "", false, false, false);
        DeveloperSettingsPolicy.ReconcilePlan p1 = DeveloperSettingsPolicy.planRestore(desired, cold);
        expect(p1.applyStaticIp, "re-applies static IP before Wi-Fi");
        expect(p1.enableWifi, "enables Wi-Fi");
        expect(p1.inhibitApo, "inhibits APO");
        expect(!p1.startAdb, "defers ADB until connected");
        expect(!p1.startTelnet, "telnet stays off when not requested");

        // After Wi-Fi connects to trusted SSID: start ADB
        DeveloperSettingsPolicy.RuntimeState connected = new DeveloperSettingsPolicy.RuntimeState(
                true, true, "\"StudioLan\"", true, false, false);
        DeveloperSettingsPolicy.ReconcilePlan p2 = DeveloperSettingsPolicy.planRestore(desired, connected);
        expect(!p2.applyStaticIp && !p2.enableWifi, "already reconciled items skipped");
        expect(p2.startAdb, "starts ADB on trusted SSID");
        expect(DeveloperSettingsPolicy.summary(desired).contains("StudioLan"), "summary includes trusted SSID");
    }

    private static void disabledPersistProducesNoOpPlan() {
        FakeStore store = new FakeStore();
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_WIFI, true);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_PERSIST_ENABLED, false);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_ADB, true);
        DeveloperSettingsPolicy.Snapshot s = DeveloperSettingsPolicy.read(store);
        expect(!s.persistEnabled, "persist disabled");
        expect(!s.wantAdb, "toggles not recorded while persist disabled");
        DeveloperSettingsPolicy.ReconcilePlan plan = DeveloperSettingsPolicy.planRestore(
                s, new DeveloperSettingsPolicy.RuntimeState(false, false, "", false, false, false));
        expect(plan.isEmpty(), "no-op restore plan when persist disabled");
    }

    private static void untrustedSsidBlocksAdbAndTelnet() {
        FakeStore store = new FakeStore();
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_ADB, true);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_TELNET, true);
        DeveloperSettingsPolicy.recordTrustedSsidIfAvailable(store, "StudioLan");

        DeveloperSettingsPolicy.ReconcilePlan plan = DeveloperSettingsPolicy.planRestore(
                DeveloperSettingsPolicy.read(store),
                new DeveloperSettingsPolicy.RuntimeState(true, true, "PublicWifi", true, false, false));
        expect(!plan.startAdb && !plan.startTelnet, "untrusted SSID blocks ADB and Telnet");
    }

    private static void autoBootstrapCapturesActiveStateOnce() {
        FakeStore store = new FakeStore();
        // Idle cold start (even with dormant staticIpActive=true while Wi-Fi is off) must not lock in want_wifi=false
        expect(!DeveloperSettingsPolicy.bootstrapSnapshotIfEmpty(
                store, new DeveloperSettingsPolicy.RuntimeState(false, false, "", false, false, false)),
                "does not bootstrap from all-off state");
        expect(!DeveloperSettingsPolicy.bootstrapSnapshotIfEmpty(
                store, new DeveloperSettingsPolicy.RuntimeState(false, false, "", true, false, false)),
                "does not bootstrap want_wifi=false from dormant staticIpActive when Wi-Fi is off");
        expect(!store.getBoolean(DeveloperSettingsPolicy.KEY_HAS_SNAPSHOT, false),
                "has_snapshot remains false when nothing active");

        // Active runtime state is auto-captured once
        expect(DeveloperSettingsPolicy.bootstrapSnapshotIfEmpty(
                store, new DeveloperSettingsPolicy.RuntimeState(true, true, "\"StudioLan\"", true, true, false)),
                "bootstraps active Wi-Fi/StaticIP/ADB");
        DeveloperSettingsPolicy.Snapshot s = DeveloperSettingsPolicy.read(store);
        expect(s.wantWifi && s.wantStaticIp && s.wantAdb && !s.wantTelnet, "captured active flags");
        expect("StudioLan".equals(s.trustedSsid), "captured connected SSID");

        // User explicitly turns ADB off -> second bootstrap must NOT overwrite user choice
        DeveloperSettingsPolicy.recordBooleanIfPersisting(store, DeveloperSettingsPolicy.KEY_WANT_ADB, false);
        expect(!DeveloperSettingsPolicy.bootstrapSnapshotIfEmpty(
                store, new DeveloperSettingsPolicy.RuntimeState(true, true, "\"StudioLan\"", true, true, false)),
                "does not overwrite existing snapshot");
        expect(!DeveloperSettingsPolicy.read(store).wantAdb, "explicit OFF choice preserved");

        // Disabled persistence skips bootstrap
        FakeStore offStore = new FakeStore();
        DeveloperSettingsPolicy.recordBooleanIfPersisting(offStore, DeveloperSettingsPolicy.KEY_PERSIST_ENABLED, false);
        expect(!DeveloperSettingsPolicy.bootstrapSnapshotIfEmpty(
                offStore, new DeveloperSettingsPolicy.RuntimeState(true, true, "\"StudioLan\"", true, true, false)),
                "skipped when persistence disabled");

        // Self-healing when static IP is active/desired but want_wifi was left false
        FakeStore healStore = new FakeStore();
        DeveloperSettingsPolicy.recordBooleanIfPersisting(healStore, DeveloperSettingsPolicy.KEY_WANT_STATIC_IP, true);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(healStore, DeveloperSettingsPolicy.KEY_WANT_WIFI, false);
        DeveloperSettingsPolicy.Snapshot healed = DeveloperSettingsPolicy.readAndHealForNetwork(healStore, true);
        expect(healed.wantWifi, "readAndHealForNetwork heals want_wifi when static IP is active");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }

    private static final class FakeStore implements DeveloperSettingsPolicy.Store {
        final Map<String, Boolean> b = new HashMap<String, Boolean>();
        final Map<String, String> s = new HashMap<String, String>();
        @Override public boolean getBoolean(String k, boolean d) { return b.containsKey(k) ? b.get(k).booleanValue() : d; }
        @Override public boolean putBoolean(String k, boolean v) { b.put(k, Boolean.valueOf(v)); return true; }
        @Override public String getString(String k, String d) { return s.containsKey(k) ? s.get(k) : d; }
        @Override public boolean putString(String k, String v) { s.put(k, v); return true; }
    }
}
