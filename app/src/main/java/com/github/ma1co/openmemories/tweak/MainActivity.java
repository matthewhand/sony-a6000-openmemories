package com.github.ma1co.openmemories.tweak;

import android.app.TabActivity;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.TabHost;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class MainActivity extends TabActivity {
    public static final File LOG_FILE = new File("/sdcard/TWEAKLOG.TXT");
    static final String KEY_SAVER_ENABLED = "screensaver_enabled";

    private final Handler idleHandler = new Handler();
    private final ScreensaverPolicy idlePolicy = new ScreensaverPolicy();
    private boolean resumed;
    private boolean autoLaunchHandled;
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            boolean enabled = getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_SAVER_ENABLED, true);
            if (idlePolicy.evaluate(SystemClock.elapsedRealtime(), enabled)
                    == ScreensaverPolicy.Stage.SCREENSAVER) {
                startActivity(new Intent(MainActivity.this, ScreensaverActivity.class));
                return;
            }
            if (enabled) {
                idleHandler.postDelayed(this, 5000L);
            }
        }
    };

    private void resetIdleTimer() {
        idlePolicy.onUserActivity(SystemClock.elapsedRealtime());
        idleHandler.removeCallbacks(idleCheck);
        if (resumed && getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_SAVER_ENABLED, true)) {
            idleHandler.postDelayed(idleCheck, ScreensaverPolicy.IDLE_TO_SAVER_MS);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getTabHost().requestFocus();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable exp) {
                Logger.error("UncaughtExceptionHandler", exp);
                saveLog();
                System.exit(0);
            }
        });

        addTab("info", "Info", android.R.drawable.ic_menu_info_details, InfoActivity.class);
        addTab("video", "Video", android.R.drawable.ic_menu_camera, VideoActivity.class);
        addTab("region", "Region", android.R.drawable.ic_menu_mapmode, RegionActivity.class);
        addTab("protection", "Protection", android.R.drawable.ic_lock_lock, ProtectionActivity.class);
        addTab("developer", "Developer", android.R.drawable.ic_menu_manage, DeveloperActivity.class);

        AndroidDeveloperSettingsStore devStore = new AndroidDeveloperSettingsStore(this);
        if (devStore.getBoolean(LivePreviewActivity.KEY_BOOT_WEBCAM, true)) {
            // Position on Developer tab so pressing MENU/BACK inside Webcam lands right on settings.
            getTabHost().setCurrentTabByTag("developer");
            reconcileNetworkBeforeWebcam(devStore);
            autoLaunchHandled = true;
            startActivity(new Intent(this, LivePreviewActivity.class));
        }
    }

    private void reconcileNetworkBeforeWebcam(AndroidDeveloperSettingsStore devStore) {
        try {
            DeveloperSettingsPolicy.Snapshot desired = DeveloperSettingsPolicy.read(devStore);
            AndroidStaticIpStore ipStore = new AndroidStaticIpStore(this);
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean wifiOn = wifi != null && (wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLED
                    || wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLING);
            DeveloperSettingsPolicy.RuntimeState state = new DeveloperSettingsPolicy.RuntimeState(
                    wifiOn, false, "", StaticIpPolicy.isStaticEnabled(ipStore), false, false);
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
        } catch (RuntimeException e) {
            Logger.error("MainActivity.autoReconcile", e);
        }
    }

    protected void addTab(String tag, String label, int iconId, Class activity) {
        TabHost.TabSpec tab = getTabHost().newTabSpec(tag);
        tab.setIndicator(label, getResources().getDrawable(iconId));
        tab.setContent(new Intent(this, activity));
        getTabHost().addTab(tab);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        resetIdleTimer();
        return super.dispatchKeyEvent(event);
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        resetIdleTimer();
    }

    @Override
    protected void onResume() {
        Logger.info("MainActivity", "application start");
        super.onResume();
        resumed = true;
        if (autoLaunchHandled) {
            // Returning from LivePreviewActivity via MENU/BACK stays cleanly in Developer settings.
            autoLaunchHandled = false;
        }
        resetIdleTimer();
    }

    @Override
    protected void onPause() {
        resumed = false;
        idleHandler.removeCallbacks(idleCheck);
        super.onPause();
        Logger.info("MainActivity", "application end");
        saveLog();
    }

    protected void saveLog() {
        try {
            FileWriter writer = new FileWriter(LOG_FILE, true);
            writer.write(Logger.getLogs() + "\n");
            writer.close();
            Logger.reset();
        } catch (IOException e) {}
    }
}
