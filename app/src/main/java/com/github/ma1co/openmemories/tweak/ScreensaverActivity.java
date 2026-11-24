package com.github.ma1co.openmemories.tweak;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;

/** Standalone screensaver preview activity; supports all 6 styles, HUD toggle, and LCD backlight-off. */
public final class ScreensaverActivity extends Activity {
    private static final long FRAME_MS = 60L;
    private static final long TELEMETRY_MS = 2000L;
    private static final long GALLERY_SLIDE_MS = 6000L;

    private final Handler handler = new Handler();
    private final ScreensaverPolicy policy = new ScreensaverPolicy();
    private final SonyDisplayBacklight displayBacklight = new SonyDisplayBacklight();
    private ScreensaverView view;
    private long saverStartedAt;
    private long lastGalleryLoadMs;
    private int galleryIndex;
    private int delaySec = ScreensaverPolicy.DEFAULT_DELAY_SEC;
    private int dimSec = ScreensaverPolicy.DEFAULT_DIM_SEC;
    private boolean dimBacklightOff = true;
    private ScreensaverPolicy.Style style = ScreensaverPolicy.Style.MATRIX;
    private boolean running;
    private boolean waking;

    private final Runnable frameTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            int evalDelay = delaySec > 0 ? delaySec : ScreensaverPolicy.DEFAULT_DELAY_SEC;
            ScreensaverPolicy.Stage stage = policy.evaluate(now, evalDelay, dimSec);
            if (stage == ScreensaverPolicy.Stage.FULL_DIM) {
                view.step(0.0f, true);
                displayBacklight.turnOff(ScreensaverActivity.this, dimBacklightOff);
                handler.removeCallbacks(telemetryTick);
                return;
            }
            if (style == ScreensaverPolicy.Style.GALLERY && (now - lastGalleryLoadMs) >= GALLERY_SLIDE_MS) {
                loadNextGallerySlide(now);
            }
            long elapsed = now >= saverStartedAt ? (now - saverStartedAt) : 0L;
            view.step(ScreensaverPolicy.computeAlpha(elapsed, dimSec * 1000L), false);
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
        BootRecoveryPolicy.SHARED.onUiResumed();
        SonyPowerBridge.notifyAppInfo(this, getComponentName());
        if (DeveloperSettingsPolicy.read(new AndroidDeveloperSettingsStore(this)).wantKeepAwake) {
            SonyPowerBridge.applyAutoPowerOffMode(this, AutoPowerOffPolicy.MODE_DISABLE);
        }
        SharedPreferences prefs = getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, MODE_PRIVATE);
        delaySec = ScreensaverPolicy.clampDelaySec(prefs.getInt(ScreensaverPolicy.KEY_DELAY_SEC, ScreensaverPolicy.DEFAULT_DELAY_SEC));
        dimSec = ScreensaverPolicy.clampDimSec(prefs.getInt(ScreensaverPolicy.KEY_DIM_SEC, ScreensaverPolicy.DEFAULT_DIM_SEC));
        ScreensaverPolicy.Style configured = ScreensaverPolicy.parseStyle(
                prefs.getString(ScreensaverPolicy.KEY_STYLE, ScreensaverPolicy.Style.RANDOM.id));
        style = ScreensaverPolicy.resolveConcreteStyle(configured, SystemClock.uptimeMillis() ^ System.nanoTime());
        boolean hudOn = prefs.getBoolean(ScreensaverPolicy.KEY_HUD_ENABLED, ScreensaverPolicy.DEFAULT_HUD_ENABLED);
        dimBacklightOff = prefs.getBoolean(LivePreviewActivity.KEY_DIM_BACKLIGHT_OFF, true);

        view.setStyle(style);
        view.setShowHud(hudOn);
        displayBacklight.restore(this);

        waking = false;
        running = true;
        saverStartedAt = SystemClock.uptimeMillis();
        lastGalleryLoadMs = 0L;
        policy.enterScreensaverNow(saverStartedAt);
        if (style == ScreensaverPolicy.Style.GALLERY) {
            loadNextGallerySlide(saverStartedAt);
        }
        refreshTelemetry();
        handler.removeCallbacksAndMessages(null);
        handler.post(frameTick);
        handler.postDelayed(telemetryTick, TELEMETRY_MS);
    }

    @Override protected void onPause() {
        running = false;
        BootRecoveryPolicy.SHARED.onUiPaused();
        handler.removeCallbacksAndMessages(null);
        displayBacklight.restore(this);
        view.clearGalleryBitmap();
        super.onPause();
    }

    @Override protected void onDestroy() {
        displayBacklight.release(this);
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && !waking) {
            waking = true;
            displayBacklight.restore(this);
            finish();
        }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event != null && event.getAction() == MotionEvent.ACTION_DOWN && !waking) {
            waking = true;
            displayBacklight.restore(this);
            finish();
        }
        return true;
    }

    private void loadNextGallerySlide(long nowMs) {
        lastGalleryLoadMs = nowMs;
        File[] stills = MotionGalleryStore.listRecentStills(24);
        if (stills.length == 0) {
            view.clearGalleryBitmap();
            return;
        }
        File target = stills[galleryIndex % stills.length];
        galleryIndex = (galleryIndex + 1) % stills.length;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeFile(target.getAbsolutePath(), opts);
            if (bmp != null) {
                view.setGalleryBitmap(bmp, target.getName());
            }
        } catch (Throwable ignored) {}
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
        String rem = ScreensaverPolicy.formatCountdown(policy.remainingToDimMs(SystemClock.uptimeMillis(), dimSec));
        view.updateTelemetry(
                ipStr + " " + mode,
                (ssid.length() == 0 ? "--" : ssid) + "  " + rssi + " dBm",
                style.label + " | DIM " + rem,
                rssi);
    }
}
