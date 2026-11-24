package com.github.ma1co.openmemories.tweak;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Launches BootRecoveryService once per boot when boot autorun is enabled. */
public final class BootRecoveryReceiver extends BroadcastReceiver {
    private static final BootRecoveryPolicy POLICY = new BootRecoveryPolicy();

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!BootRecoveryPolicy.isBootAction(action)) return;
        Logger.info("BootRecoveryReceiver", "Received boot action=" + action);
        DeveloperSettingsPolicy.Snapshot snap = DeveloperSettingsPolicy.read(
                new AndroidDeveloperSettingsStore(context));
        if (!POLICY.shouldTrigger(action, snap.bootAutorun)) return;
        try {
            context.startService(new Intent(context, BootRecoveryService.class));
        } catch (RuntimeException e) {
            Logger.error("BootRecoveryReceiver", e);
        }
    }
}
