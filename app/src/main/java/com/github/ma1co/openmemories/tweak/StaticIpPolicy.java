package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java policy for Android API 10 Settings.System static Wi-Fi IPv4 configuration.
 * Contains zero hardcoded personal LAN addresses: resolves existing saved Settings.System
 * values or seeds from an active DHCP lease (DhcpInfo).
 */
final class StaticIpPolicy {
    static final String KEY_USE_STATIC = "wifi_use_static_ip";
    static final String KEY_IP = "wifi_static_ip";
    static final String KEY_NETMASK = "wifi_static_netmask";
    static final String KEY_GATEWAY = "wifi_static_gateway";
    static final String KEY_DNS1 = "wifi_static_dns1";
    static final String KEY_DNS2 = "wifi_static_dns2";
    static final String DEFAULT_FALLBACK_DNS2 = "8.8.8.8";
    static final String DEFAULT_FALLBACK_NETMASK = "255.255.255.0";

    interface Store {
        boolean putString(String key, String value);
        String getString(String key);
        boolean putInt(String key, int value);
        int getInt(String key, int def);
    }

    static final class Config {
        final String ip;
        final String netmask;
        final String gateway;
        final String dns1;
        final String dns2;

        Config(String ip, String netmask, String gateway, String dns1, String dns2) {
            this.ip = ip;
            this.netmask = netmask;
            this.gateway = gateway;
            this.dns1 = dns1;
            this.dns2 = dns2;
        }

        boolean isValid() {
            return isValidIpv4(ip)
                    && isValidIpv4(netmask)
                    && isValidIpv4(gateway)
                    && isValidIpv4(dns1)
                    && (dns2 == null || dns2.length() == 0 || isValidIpv4(dns2));
        }
    }

    static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private StaticIpPolicy() {}

    static boolean isValidIpv4(String value) {
        if (value == null || value.length() < 7 || value.length() > 15) return false;
        if ("0.0.0.0".equals(value) || "255.255.255.255".equals(value)) return false;
        int parts = 0;
        int i = 0;
        int len = value.length();
        while (i < len) {
            if (parts >= 4) return false;
            int start = i;
            int num = 0;
            while (i < len && value.charAt(i) != '.') {
                char c = value.charAt(i);
                if (c < '0' || c > '9') return false;
                num = num * 10 + (c - '0');
                if (num > 255) return false;
                i++;
            }
            int digits = i - start;
            if (digits == 0 || digits > 3) return false;
            if (digits > 1 && value.charAt(start) == '0') return false;
            parts++;
            if (i < len) {
                if (i == len - 1) return false;
                i++;
            }
        }
        return parts == 4;
    }

    /** Convert Android DhcpInfo little-endian IPv4 integer into dotted-quad string, or null if 0. */
    static String intToIpv4(int littleEndianAddr) {
        if (littleEndianAddr == 0) return null;
        return (littleEndianAddr & 0xFF) + "."
                + ((littleEndianAddr >>> 8) & 0xFF) + "."
                + ((littleEndianAddr >>> 16) & 0xFF) + "."
                + ((littleEndianAddr >>> 24) & 0xFF);
    }

    /** Build a Config from active Android DhcpInfo integer fields, returning null if incomplete. */
    static Config fromDhcpInts(int ipAddr, int netmaskAddr, int gatewayAddr, int dns1Addr, int dns2Addr) {
        String ip = intToIpv4(ipAddr);
        String gw = intToIpv4(gatewayAddr);
        if (!isValidIpv4(ip) || !isValidIpv4(gw)) return null;
        String mask = intToIpv4(netmaskAddr);
        if (!isValidIpv4(mask)) mask = DEFAULT_FALLBACK_NETMASK;
        String d1 = intToIpv4(dns1Addr);
        if (!isValidIpv4(d1)) d1 = gw;
        String d2 = intToIpv4(dns2Addr);
        if (!isValidIpv4(d2)) d2 = DEFAULT_FALLBACK_DNS2;
        Config cfg = new Config(ip, mask, gw, d1, d2);
        return cfg.isValid() ? cfg : null;
    }

    static Config readSavedConfig(Store store) {
        if (store == null) return null;
        Config cfg = new Config(
                store.getString(KEY_IP),
                store.getString(KEY_NETMASK),
                store.getString(KEY_GATEWAY),
                store.getString(KEY_DNS1),
                store.getString(KEY_DNS2));
        return cfg.isValid() ? cfg : null;
    }

    /** Prefer already-saved valid Settings.System config; otherwise seed from active DHCP lease. */
    static Config resolveConfig(Store store, Config activeDhcpFallback) {
        Config saved = readSavedConfig(store);
        if (saved != null) return saved;
        if (activeDhcpFallback != null && activeDhcpFallback.isValid()) return activeDhcpFallback;
        return null;
    }

    static boolean isStaticEnabled(Store store) {
        return store != null && store.getInt(KEY_USE_STATIC, 0) == 1;
    }

    static Result applyStatic(Store store, Config cfg) {
        if (store == null || cfg == null) return new Result(false, "Missing store or static IP config");
        if (!isValidIpv4(cfg.ip)) return new Result(false, "Invalid static IP");
        if (!isValidIpv4(cfg.netmask)) return new Result(false, "Invalid netmask");
        if (!isValidIpv4(cfg.gateway)) return new Result(false, "Invalid gateway");
        if (!isValidIpv4(cfg.dns1)) return new Result(false, "Invalid DNS1");
        String dns2 = (cfg.dns2 == null || cfg.dns2.length() == 0) ? DEFAULT_FALLBACK_DNS2 : cfg.dns2;
        if (!isValidIpv4(dns2)) return new Result(false, "Invalid DNS2");

        if (!store.putString(KEY_IP, cfg.ip)
                || !store.putString(KEY_NETMASK, cfg.netmask)
                || !store.putString(KEY_GATEWAY, cfg.gateway)
                || !store.putString(KEY_DNS1, cfg.dns1)
                || !store.putString(KEY_DNS2, dns2)) {
            store.putInt(KEY_USE_STATIC, 0);
            return new Result(false, "Write failed for address fields; kept DHCP");
        }
        if (!store.putInt(KEY_USE_STATIC, 1)) {
            store.putInt(KEY_USE_STATIC, 0);
            return new Result(false, "Write failed for wifi_use_static_ip; kept DHCP");
        }

        boolean match = store.getInt(KEY_USE_STATIC, 0) == 1
                && cfg.ip.equals(store.getString(KEY_IP))
                && cfg.netmask.equals(store.getString(KEY_NETMASK))
                && cfg.gateway.equals(store.getString(KEY_GATEWAY))
                && cfg.dns1.equals(store.getString(KEY_DNS1))
                && dns2.equals(store.getString(KEY_DNS2));
        if (!match) {
            store.putInt(KEY_USE_STATIC, 0);
            return new Result(false, "Readback mismatch; reverted to DHCP");
        }
        return new Result(true, "Static " + cfg.ip + " saved (toggle Wi-Fi OFF/ON to apply)");
    }

    static Result restoreDhcp(Store store) {
        if (store == null) return new Result(false, "Missing store");
        if (!store.putInt(KEY_USE_STATIC, 0)) {
            return new Result(false, "Failed to write wifi_use_static_ip=0");
        }
        if (store.getInt(KEY_USE_STATIC, -1) != 0) {
            return new Result(false, "Readback did not confirm DHCP (0)");
        }
        return new Result(true, "DHCP restored (toggle Wi-Fi OFF/ON to apply)");
    }

    static String summary(Store store) {
        if (store == null) return "Settings unavailable";
        if (isStaticEnabled(store)) {
            String ip = store.getString(KEY_IP);
            return "Static " + (ip == null || ip.length() == 0 ? "(unset)" : ip) + " (dhcpcd bypassed)";
        }
        Config saved = readSavedConfig(store);
        return saved != null ? "DHCP (saved static: " + saved.ip + ")" : "DHCP (Auto)";
    }
}
