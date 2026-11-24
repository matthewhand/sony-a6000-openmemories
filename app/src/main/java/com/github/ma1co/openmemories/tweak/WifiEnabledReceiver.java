package com.github.ma1co.openmemories.tweak;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;

/** Starts an already-armed Wi-Fi watchdog once after Wi-Fi reaches WIFI_STATE_ENABLED. */
public final class WifiEnabledReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) return;
        int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
        if (state != WifiManager.WIFI_STATE_ENABLED) return;
        PeriodicWifiService.resumeOnceOnWifiEnabled(context);
    }
}
