package com.github.ma1co.openmemories.tweak;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Thin Android broadcast adapter for Sony DAConnectionManagerService power management. */
final class SonyPowerBridge {
    private SonyPowerBridge() {}

    static void notifyAppInfo(Context context, ComponentName component) {
        if (context == null || component == null) return;
        try {
            Intent intent = new Intent(AutoPowerOffPolicy.ACTION_APP_INFO);
            intent.putExtra(AutoPowerOffPolicy.EXTRA_PACKAGE_NAME, component.getPackageName());
            intent.putExtra(AutoPowerOffPolicy.EXTRA_CLASS_NAME, component.getClassName());
            context.sendBroadcast(intent);
        } catch (RuntimeException e) {
            Logger.error("SonyPowerBridge.notifyAppInfo", e);
        }
    }

    static void applyAutoPowerOffMode(Context context, String mode) {
        if (context == null || mode == null) return;
        try {
            Intent intent = new Intent(AutoPowerOffPolicy.ACTION_APO);
            intent.putExtra(AutoPowerOffPolicy.EXTRA_APO_INFO, mode);
            context.sendBroadcast(intent);
            Logger.info("SonyPowerBridge", "Sent " + mode);
        } catch (RuntimeException e) {
            Logger.error("SonyPowerBridge.apo", e);
        }
    }
}
