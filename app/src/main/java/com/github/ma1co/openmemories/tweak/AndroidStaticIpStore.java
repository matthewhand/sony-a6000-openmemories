package com.github.ma1co.openmemories.tweak;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;

/**
 * Android Settings.System + SharedPreferences backup adapter for StaticIpPolicy.
 * Mirrors saved static IPv4 strings into private SharedPreferences so Sony firmware
 * resets of Settings.System never lose the user's configured static IPv4 settings.
 */
final class AndroidStaticIpStore implements StaticIpPolicy.Store {
    private static final String BACKUP_PREFS = "static_ip_backup";

    private final ContentResolver resolver;
    private final SharedPreferences backup;

    AndroidStaticIpStore(Context context) {
        Context app = context.getApplicationContext();
        this.resolver = app.getContentResolver();
        this.backup = app.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE);
    }

    @Override public boolean putString(String key, String value) {
        if (value != null && value.length() > 0) {
            backup.edit().putString(key, value).commit();
        }
        try { return Settings.System.putString(resolver, key, value); }
        catch (RuntimeException e) { return false; }
    }

    @Override public String getString(String key) {
        try {
            String sys = Settings.System.getString(resolver, key);
            if (sys != null && sys.length() > 0) {
                if (!sys.equals(backup.getString(key, null))) {
                    backup.edit().putString(key, sys).commit();
                }
                return sys;
            }
        } catch (RuntimeException ignored) {}
        return backup.getString(key, null);
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
