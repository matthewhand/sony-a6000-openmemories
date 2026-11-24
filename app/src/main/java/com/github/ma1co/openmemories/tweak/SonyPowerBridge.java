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

    /**
     * Completes an interrupted Sony DAConnectionManagerService state=4 (ScalarABlackLauncher)
     * exit handshake via android.app.DAConnectionManager.finishComp() and best-effort
     * ActivityManagerNative.resumeAppSwitches().
     */
    static void completeAndroidExitIfStuck(Context context) {
        if (context == null) return;
        try {
            Class<?> amn = Class.forName("android.app.ActivityManagerNative");
            Object am = amn.getMethod("getDefault").invoke(null);
            if (am != null) {
                am.getClass().getMethod("resumeAppSwitches").invoke(am);
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> dacmClass = Class.forName("android.app.DAConnectionManager");
            Object dacm = dacmClass.getConstructor(Context.class).newInstance(context);
            dacmClass.getMethod("finishComp").invoke(dacm);
            Logger.info("SonyPowerBridge", "Invoked DAConnectionManager.finishComp()");
        } catch (Throwable t) {
            Logger.error("SonyPowerBridge.finishComp", t);
        }
    }
}
