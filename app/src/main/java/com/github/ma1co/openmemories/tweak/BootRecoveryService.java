package com.github.ma1co.openmemories.tweak;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;

/**
 * Best-effort boot automation reconciling persisted Developer settings safely.
 * Protected by a 3-incomplete-start crash-loop breaker. Contains zero hardcoded SSIDs or IPs.
 */
public final class BootRecoveryService extends Service {
    private static final String GUARD_PREFS = "boot_recovery_guard";
    private static final String KEY_FAILURES = "incomplete_starts";
    private static final int MAX_FAILURES = 3;

    private final Handler handler = new Handler();
    private boolean started;

    static void resetCrashGuard(Context context) {
        context.getSharedPreferences(GUARD_PREFS, MODE_PRIVATE)
                .edit().putInt(KEY_FAILURES, 0).commit();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }

    @Override public void onCreate() {
        super.onCreate();
        final SharedPreferences guard = getSharedPreferences(GUARD_PREFS, MODE_PRIVATE);
        int failures = guard.getInt(KEY_FAILURES, 0);
        if (failures >= MAX_FAILURES) {
            Logger.info("BootRecovery", "Disabled after repeated incomplete starts");
            stopSelf();
            return;
        }
        guard.edit().putInt(KEY_FAILURES, failures + 1).commit();
        started = true;

        handler.postDelayed(new Runnable() { @Override public void run() { restoreNetworkPhase(); } }, 2000L);
        handler.postDelayed(new Runnable() { @Override public void run() { launchUiPhase(); } }, 5000L);
        handler.postDelayed(new Runnable() { @Override public void run() { restoreDaemonsIfConnected(); } }, 15000L);
        handler.postDelayed(new Runnable() { @Override public void run() { restoreDaemonsIfConnected(); } }, 25000L);
        handler.postDelayed(new Runnable() { @Override public void run() { restoreDaemonsIfConnected(); } }, 35000L);
        handler.postDelayed(new Runnable() { @Override public void run() { restoreDaemonsIfConnected(); } }, 45000L);
        handler.postDelayed(new Runnable() { @Override public void run() { finishBoot(guard); } }, 55000L);
    }

    private void restoreNetworkPhase() {
        if (!started) return;
        try {
            DeveloperSettingsPolicy.Snapshot desired = DeveloperSettingsPolicy.read(
                    new AndroidDeveloperSettingsStore(this));
            AndroidStaticIpStore ipStore = new AndroidStaticIpStore(this);
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean wifiOn = wifi != null && wifi.isWifiEnabled();
            DeveloperSettingsPolicy.RuntimeState state = new DeveloperSettingsPolicy.RuntimeState(
                    wifiOn, false, "", StaticIpPolicy.isStaticEnabled(ipStore),
                    PeriodicWifiService.isArmed(this), false, false);
            DeveloperSettingsPolicy.ReconcilePlan plan = DeveloperSettingsPolicy.planRestore(desired, state);

            if (plan.applyStaticIp) {
                StaticIpPolicy.Config saved = StaticIpPolicy.readSavedConfig(ipStore);
                if (saved != null) StaticIpPolicy.applyStatic(ipStore, saved);
            }
            if (plan.inhibitApo) {
                SonyPowerBridge.applyAutoPowerOffMode(this, AutoPowerOffPolicy.MODE_DISABLE);
            }
            if (plan.enableWifi && wifi != null) {
                wifi.setWifiEnabled(true);
            }
            if (plan.armWatchdog) {
                PeriodicWifiService.setArmed(this, true);
            } else {
                PeriodicWifiService.resumeIfNeeded(this);
            }
        } catch (Exception e) {
            Logger.error("BootRecovery.network", e);
        }
    }

    protected Class<?> resolveBootActivityClass() {
        boolean autoWebcam = new AndroidDeveloperSettingsStore(this)
                .getBoolean(LivePreviewActivity.KEY_BOOT_WEBCAM, true);
        return autoWebcam ? LivePreviewActivity.class : MainActivity.class;
    }

    private void launchUiPhase() {
        if (!started) return;
        try {
            Intent launch = new Intent(this, resolveBootActivityClass());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Exception e) {
            Logger.error("BootRecovery.launch", e);
        }
    }

    private void restoreDaemonsIfConnected() {
        if (!started) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            boolean connected = info != null && info.isConnected();
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifi == null ? null : wifi.getConnectionInfo();
            String ssid = wifiInfo == null ? "" : wifiInfo.getSSID();

            String adbBin = getApplicationInfo().nativeLibraryDir + "/libadbd.so";
            String[] telnetCmd = new String[] { "busybox", "telnetd", "-l", "sh" };
            boolean adbRunning = Procfs.findProcess(new String[] { adbBin }) != -1;
            boolean telnetRunning = Procfs.findProcess(telnetCmd) != -1;

            DeveloperSettingsPolicy.Snapshot desired = DeveloperSettingsPolicy.read(
                    new AndroidDeveloperSettingsStore(this));
            DeveloperSettingsPolicy.RuntimeState state = new DeveloperSettingsPolicy.RuntimeState(
                    wifi != null && wifi.isWifiEnabled(), connected, ssid,
                    StaticIpPolicy.isStaticEnabled(new AndroidStaticIpStore(this)),
                    PeriodicWifiService.isArmed(this), adbRunning, telnetRunning);
            DeveloperSettingsPolicy.ReconcilePlan plan = DeveloperSettingsPolicy.planRestore(desired, state);

            if (plan.startAdb) {
                Shell.execAndroid(adbBin + " &");
            }
            if (plan.startTelnet) {
                Shell.exec("busybox telnetd -l sh");
            }
        } catch (Exception e) {
            Logger.error("BootRecovery.daemons", e);
        }
    }

    private void finishBoot(SharedPreferences guard) {
        if (!started) return;
        guard.edit().putInt(KEY_FAILURES, 0).commit();
        stopSelf();
    }

    @Override public void onDestroy() {
        started = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
