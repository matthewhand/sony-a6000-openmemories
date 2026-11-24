package com.github.ma1co.openmemories.tweak;

import android.content.ContentResolver;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;

/** Android Settings.System + DhcpInfo adapter for StaticIpPolicy. */
final class AndroidStaticIpStore implements StaticIpPolicy.Store {
    private final ContentResolver resolver;

    AndroidStaticIpStore(Context context) {
        this.resolver = context.getContentResolver();
    }

    @Override public boolean putString(String key, String value) {
        try { return Settings.System.putString(resolver, key, value); }
        catch (RuntimeException e) { return false; }
    }

    @Override public String getString(String key) {
        try { return Settings.System.getString(resolver, key); }
        catch (RuntimeException e) { return null; }
    }

    @Override public boolean putInt(String key, int value) {
        try { return Settings.System.putInt(resolver, key, value); }
        catch (RuntimeException e) { return false; }
    }

    @Override public int getInt(String key, int def) {
        try { return Settings.System.getInt(resolver, key, def); }
        catch (RuntimeException e) { return def; }
    }

    static StaticIpPolicy.Config readActiveDhcpConfig(WifiManager wifiManager) {
        if (wifiManager == null) return null;
        try {
            DhcpInfo info = wifiManager.getDhcpInfo();
            if (info == null) return null;
            return StaticIpPolicy.fromDhcpInts(
                    info.ipAddress, info.netmask, info.gateway, info.dns1, info.dns2);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
