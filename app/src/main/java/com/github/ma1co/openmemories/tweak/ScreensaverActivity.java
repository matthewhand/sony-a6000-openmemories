package com.github.ma1co.openmemories.tweak;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Fullscreen screensaver -> pure-black dim activity; wakes immediately on any key/touch. */
public final class ScreensaverActivity extends Activity {
    private static final long FRAME_MS = 60L;
    private static final long TELEMETRY_MS = 2000L;

    private final Handler handler = new Handler();
    private final ScreensaverPolicy policy = new ScreensaverPolicy();
    private ScreensaverView view;
    private long saverStartedAt;
    private boolean running;
    private boolean waking;

    private final Runnable frameTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            ScreensaverPolicy.Stage stage = policy.evaluate(now, true);
            if (stage == ScreensaverPolicy.Stage.FULL_DIM) {
                view.step(0.0f, true);
                handler.removeCallbacks(telemetryTick);
                return;
            }
            long elapsed = now >= saverStartedAt ? (now - saverStartedAt) : 0L;
            view.step(ScreensaverPolicy.computeAlpha(elapsed), false);
            handler.postDelayed(this, FRAME_MS);
        }
    };

    private final Runnable telemetryTick = new Runnable() {
        @Override public void run() {
            if (!running || policy.stage() == ScreensaverPolicy.Stage.FULL_DIM) return;
            refreshTelemetry();
            handler.postDelayed(this, TELEMETRY_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new ScreensaverView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        waking = false;
        running = true;
        saverStartedAt = SystemClock.elapsedRealtime();
        policy.enterScreensaverNow(saverStartedAt);
        refreshTelemetry();
        handler.removeCallbacksAndMessages(null);
        handler.post(frameTick);
        handler.postDelayed(telemetryTick, TELEMETRY_MS);
    }

    @Override protected void onPause() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && !waking) {
            waking = true;
            finish();
        }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event != null && event.getAction() == MotionEvent.ACTION_DOWN && !waking) {
            waking = true;
            finish();
        }
        return true;
    }

    private void refreshTelemetry() {
        String ipStr = "offline";
        String ssid = "--";
        int rssi = -85;
        boolean staticOn = false;
        try {
            staticOn = Settings.System.getInt(getContentResolver(), StaticIpPolicy.KEY_USE_STATIC, 0) == 1;
        } catch (RuntimeException ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    if (info.getSSID() != null) ssid = DeveloperSettingsPolicy.normalizeSsid(info.getSSID());
                    rssi = info.getRssi();
                    if (info.getIpAddress() != 0) ipStr = Formatter.formatIpAddress(info.getIpAddress());
                }
            }
        } catch (RuntimeException ignored) {}

        String mode = staticOn ? "[STATIC]" : "[DHCP]";
        String rem = ScreensaverPolicy.formatCountdown(policy.remainingToDimMs(SystemClock.elapsedRealtime()));
        view.updateTelemetry(
                ipStr + " " + mode,
                (ssid.length() == 0 ? "--" : ssid) + "  " + rssi + " dBm",
                "SAVER | DIM " + rem,
                rssi);
    }
}
