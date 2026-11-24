package com.github.ma1co.openmemories.tweak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class StaticIpPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        ipv4Validation();
        dhcpLittleEndianConversion();
        applyOrderReadbackAndRestore();
        rollbackOnWriteOrReadbackFailure();
        resolvePrefersSavedOverDhcpFallback();
        System.out.println("PASS StaticIpPolicyTests: " + assertions + " assertions");
    }

    private static void ipv4Validation() {
        expect(StaticIpPolicy.isValidIpv4("192.0.2.10"), "valid RFC 5737 IPv4");
        expect(StaticIpPolicy.isValidIpv4("198.51.100.1"), "valid gateway");
        expect(!StaticIpPolicy.isValidIpv4("0.0.0.0"), "rejects 0.0.0.0");
        expect(!StaticIpPolicy.isValidIpv4("255.255.255.255"), "rejects broadcast");
        expect(!StaticIpPolicy.isValidIpv4("192.0.2.999"), "rejects >255 octet");
        expect(!StaticIpPolicy.isValidIpv4("192.0.02.1"), "rejects leading zero");
        expect(!StaticIpPolicy.isValidIpv4("192.0.2."), "rejects trailing dot");
        expect(!StaticIpPolicy.isValidIpv4(null), "rejects null");
    }

    private static void dhcpLittleEndianConversion() {
        // 192.0.2.42 in little-endian int = 192 | (0<<8) | (2<<16) | (42<<24)
        int ip = 192 | (0 << 8) | (2 << 16) | (42 << 24);
        int mask = 255 | (255 << 8) | (255 << 16) | (0 << 24);
        int gw = 192 | (0 << 8) | (2 << 16) | (1 << 24);
        expect("192.0.2.42".equals(StaticIpPolicy.intToIpv4(ip)), "little-endian intToIpv4");
        expect(StaticIpPolicy.intToIpv4(0) == null, "zero -> null");

        StaticIpPolicy.Config cfg = StaticIpPolicy.fromDhcpInts(ip, mask, gw, 0, 0);
        expect(cfg != null, "valid DHCP config built");
        expect("192.0.2.42".equals(cfg.ip), "ip matches");
        expect("255.255.255.0".equals(cfg.netmask), "netmask matches");
        expect("192.0.2.1".equals(cfg.dns1), "dns1 falls back to gateway");
        expect("8.8.8.8".equals(cfg.dns2), "dns2 falls back to 8.8.8.8");
    }

    private static void applyOrderReadbackAndRestore() {
        FakeStore store = new FakeStore();
        StaticIpPolicy.Config cfg = new StaticIpPolicy.Config(
                "192.0.2.20", "255.255.255.0", "192.0.2.1", "192.0.2.1", "8.8.8.8");
        expect(!StaticIpPolicy.isStaticEnabled(store), "starts DHCP");
        StaticIpPolicy.Result r = StaticIpPolicy.applyStatic(store, cfg);
        expect(r.ok, "applyStatic ok");
        expect(StaticIpPolicy.isStaticEnabled(store), "enabled after apply");
        expect(store.writes.size() == 6, "6 keys written");
        expect(StaticIpPolicy.KEY_USE_STATIC.equals(store.writes.get(5)), "wifi_use_static_ip written last");
        expect(StaticIpPolicy.summary(store).contains("192.0.2.20"), "summary includes configured IP");
        expect(StaticIpPolicy.restoreDhcp(store).ok, "restoreDhcp ok");
        expect(!StaticIpPolicy.isStaticEnabled(store), "back to DHCP");
    }

    private static void rollbackOnWriteOrReadbackFailure() {
        FakeStore failWrite = new FakeStore();
        failWrite.failKey = StaticIpPolicy.KEY_GATEWAY;
        StaticIpPolicy.Config cfg = new StaticIpPolicy.Config(
                "192.0.2.20", "255.255.255.0", "192.0.2.1", "192.0.2.1", "8.8.8.8");
        StaticIpPolicy.Result r1 = StaticIpPolicy.applyStatic(failWrite, cfg);
        expect(!r1.ok, "fails when gateway write fails");
        expect(!StaticIpPolicy.isStaticEnabled(failWrite), "rolled back to DHCP on write error");

        FakeStore corruptRead = new FakeStore();
        corruptRead.corruptReadKey = StaticIpPolicy.KEY_IP;
        StaticIpPolicy.Result r2 = StaticIpPolicy.applyStatic(corruptRead, cfg);
        expect(!r2.ok, "fails on readback mismatch");
        expect(!StaticIpPolicy.isStaticEnabled(corruptRead), "rolled back to DHCP on readback mismatch");
    }

    private static void resolvePrefersSavedOverDhcpFallback() {
        FakeStore store = new FakeStore();
        StaticIpPolicy.Config dhcp = new StaticIpPolicy.Config(
                "192.0.2.50", "255.255.255.0", "192.0.2.1", "192.0.2.1", "8.8.8.8");
        StaticIpPolicy.Config first = StaticIpPolicy.resolveConfig(store, dhcp);
        expect(first != null && "192.0.2.50".equals(first.ip), "uses DHCP fallback when store empty");

        StaticIpPolicy.Config saved = new StaticIpPolicy.Config(
                "192.0.2.6", "255.255.255.0", "192.0.2.1", "192.0.2.1", "8.8.8.8");
        StaticIpPolicy.applyStatic(store, saved);
        StaticIpPolicy.restoreDhcp(store);
        StaticIpPolicy.Config resolved = StaticIpPolicy.resolveConfig(store, dhcp);
        expect(resolved != null && "192.0.2.6".equals(resolved.ip), "prefers saved Settings.System IP over DHCP");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }

    private static final class FakeStore implements StaticIpPolicy.Store {
        final Map<String, String> s = new HashMap<String, String>();
        final Map<String, Integer> i = new HashMap<String, Integer>();
        final List<String> writes = new ArrayList<String>();
        String failKey;
        String corruptReadKey;

        @Override public boolean putString(String k, String v) {
            writes.add(k);
            if (k.equals(failKey)) return false;
            s.put(k, v);
            return true;
        }

        @Override public String getString(String k) {
            if (k.equals(corruptReadKey)) return "198.51.100.99";
            return s.get(k);
        }

        @Override public boolean putInt(String k, int v) {
            writes.add(k);
            if (k.equals(failKey)) return false;
            i.put(k, v);
            return true;
        }

        @Override public int getInt(String k, int d) {
            Integer v = i.get(k);
            return v == null ? d : v.intValue();
        }
    }
}
