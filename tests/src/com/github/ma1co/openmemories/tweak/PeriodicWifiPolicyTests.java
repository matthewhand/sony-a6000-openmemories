package com.github.ma1co.openmemories.tweak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PeriodicWifiPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        offAckCycleAndFiveMinuteCadence();
        singleArmOnWifiEnabledGuards();
        disarmedStopsCleanly();
        System.out.println("PASS PeriodicWifiPolicyTests: " + assertions + " assertions");
    }

    private static void offAckCycleAndFiveMinuteCadence() {
        FakeWifi wifi = new FakeWifi();
        PeriodicWifiPolicy timer = new PeriodicWifiPolicy();
        expect(timer.tick(0L, wifi) == PeriodicWifiPolicy.FIRST_CYCLE_MS, "first cycle 20s");
        expect(timer.tick(20000L, wifi) == PeriodicWifiPolicy.OFF_POLL_MS, "requests OFF and polls");
        expect(timer.tick(20500L, wifi) == PeriodicWifiPolicy.OFF_POLL_MS, "waits while OFF not yet observed");
        wifi.disabled = true;
        wifi.enabled = false;
        expect(timer.tick(21000L, wifi) == PeriodicWifiPolicy.OFF_DELAY_MS, "holds 1s after OFF observed");
        expect(timer.tick(22000L, wifi) == PeriodicWifiPolicy.SETTLE_MS, "requests ON after hold");
        wifi.disabled = false;
        wifi.enabled = true;
        long next = timer.tick(27000L, wifi);
        expect(next == PeriodicWifiPolicy.CHECK_MS - 7000L, "schedules next 5-minute check");
        expect(wifi.requests.equals(Arrays.asList(Boolean.FALSE, Boolean.TRUE)), "exactly one OFF then ON");
    }

    private static void singleArmOnWifiEnabledGuards() {
        PeriodicWifiPolicy.Journal armed = new PeriodicWifiPolicy.Journal(true, 0, 0, false, 0);
        PeriodicWifiPolicy.Journal disarmed = new PeriodicWifiPolicy.Journal(false, 0, 0, false, 0);
        expect(PeriodicWifiPolicy.shouldStartOnWifiEnabled(true, false, armed), "arms once on WIFI_STATE_ENABLED");
        expect(!PeriodicWifiPolicy.shouldStartOnWifiEnabled(true, true, armed), "does not re-arm while active");
        expect(!PeriodicWifiPolicy.shouldStartOnWifiEnabled(false, false, armed), "ignores non-ENABLED event");
        expect(!PeriodicWifiPolicy.shouldStartOnWifiEnabled(true, false, disarmed), "explicit disabled stays off");
    }

    private static void disarmedStopsCleanly() {
        FakeWifi wifi = new FakeWifi();
        wifi.journal = new PeriodicWifiPolicy.Journal(false, 0, 0, false, 0);
        PeriodicWifiPolicy timer = new PeriodicWifiPolicy();
        expect(timer.tick(0L, wifi) == PeriodicWifiPolicy.STOP, "disarmed stops service");
        expect(wifi.requests.isEmpty(), "disarmed makes zero radio requests");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }

    private static final class FakeWifi implements PeriodicWifiPolicy.Device {
        PeriodicWifiPolicy.Journal journal = new PeriodicWifiPolicy.Journal(true, 0, 0, false, 0);
        boolean enabled = true;
        boolean disabled = false;
        final List<Boolean> requests = new ArrayList<Boolean>();
        @Override public PeriodicWifiPolicy.Journal readJournal() { return journal; }
        @Override public boolean saveJournal(PeriodicWifiPolicy.Journal j) { journal = j; return true; }
        @Override public boolean readWifiEnabled() { return enabled; }
        @Override public boolean readWifiDisabled() { return disabled; }
        @Override public void requestWifi(boolean e) { requests.add(Boolean.valueOf(e)); }
    }
}
