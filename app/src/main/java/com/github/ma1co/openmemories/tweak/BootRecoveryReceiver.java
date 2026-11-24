package com.github.ma1co.openmemories.tweak;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

/**
 * Launches BootRecoveryService once per boot when boot autorun is enabled,
 * and supports generic ADB static-IP provisioning broadcasts (immune to
 * Sony ScalarALauncher's stopAppSwitches() Activity lockout).
 */
public final class BootRecoveryReceiver extends BroadcastReceiver {
    static final String ACTION_APPLY_STATIC_IP = "com.github.ma1co.openmemories.tweak.APPLY_STATIC_IP";
    private static final String TAG = "OpenMemoriesBoot";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (ACTION_APPLY_STATIC_IP.equals(action)) {
            handleStaticIpProvision(context.getApplicationContext(), intent);
            return;
        }
        if (!BootRecoveryPolicy.isBootAction(action)) return;
        Logger.info("BootRecoveryReceiver", "Received boot action=" + action);
        DeveloperSettingsPolicy.Snapshot snap = DeveloperSettingsPolicy.read(
                new AndroidDeveloperSettingsStore(context));
        if (!BootRecoveryPolicy.SHARED.shouldTrigger(action, snap.bootAutorun)) return;
        try {
            context.startService(new Intent(context, BootRecoveryService.class));
        } catch (RuntimeException e) {
            Logger.error("BootRecoveryReceiver", e);
        }
    }

    private static void handleStaticIpProvision(final Context app, Intent intent) {
        AndroidDeveloperSettingsStore devStore = new AndroidDeveloperSettingsStore(app);
        if (intent.hasExtra(ScreensaverPolicy.KEY_HUD_ENABLED)) {
            boolean hud = intent.getBooleanExtra(ScreensaverPolicy.KEY_HUD_ENABLED, ScreensaverPolicy.DEFAULT_HUD_ENABLED);
            devStore.putBoolean(ScreensaverPolicy.KEY_HUD_ENABLED, hud);
            Log.i(TAG, "ADB screensaver HUD provision: " + hud);
        }
        DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_STATIC_IP, true);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_WIFI, true);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_ADB, true);

        String ip = intent.getStringExtra(LivePreviewActivity.EXTRA_STATIC_IP);
        if (ip == null || ip.trim().length() == 0) return;
        String mask = intent.getStringExtra(LivePreviewActivity.EXTRA_STATIC_NETMASK);
        String gw = intent.getStringExtra(LivePreviewActivity.EXTRA_STATIC_GATEWAY);
        String dns1 = intent.getStringExtra(LivePreviewActivity.EXTRA_STATIC_DNS1);
        String dns2 = intent.getStringExtra(LivePreviewActivity.EXTRA_STATIC_DNS2);
        if (mask == null || mask.length() == 0) mask = StaticIpPolicy.DEFAULT_FALLBACK_NETMASK;
        if (dns1 == null || dns1.length() == 0) dns1 = gw;
        if (dns2 == null || dns2.length() == 0) dns2 = StaticIpPolicy.DEFAULT_FALLBACK_DNS2;

        StaticIpPolicy.Config cfg = new StaticIpPolicy.Config(ip, mask, gw, dns1, dns2);
        AndroidStaticIpStore ipStore = new AndroidStaticIpStore(app);
        StaticIpPolicy.Result r = StaticIpPolicy.applyStatic(ipStore, cfg);
        Log.i(TAG, "ADB static IP provision: " + (r.ok ? "OK " : "FAIL ") + r.message);
        if (!r.ok) return;

        final WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                DeveloperSettingsPolicy.recordTrustedSsidIfAvailable(devStore, info.getSSID());
            }
        } catch (RuntimeException ignored) {}

        Thread bounce = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    wm.setWifiEnabled(false);
                    Thread.sleep(2000L);
                    wm.setWifiEnabled(true);
                } catch (Throwable ignored) {}
            }
        }, "om-static-ip-bounce");
        bounce.setDaemon(true);
        bounce.start();
    }
}
