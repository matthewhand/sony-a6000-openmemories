package com.github.ma1co.openmemories.tweak;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Live CameraEx/CameraSequence LAN MJPEG & snapshot server with integrated
 * Screensaver/Full-Dim overlay, async low-priority motion wake, real battery telemetry,
 * and Home Assistant core Android IP Webcam compatibility.
 *
 * Key design details:
 * - CameraEx/CameraSequence open & startPreviewSequence run on the main thread (Sony HAL affinity).
 * - Motion detection decodes JPEGs asynchronously on a dedicated low-priority thread and skips
 *   decoding when ACTIVE unless an external client recently polled /sensors.json, keeping live
 *   MJPEG frame rate smooth.
 * - ScreensaverView overlays SurfaceView inside this same Activity so MJPEG streaming never drops
 *   during SCREENSAVER or pure-black FULL_DIM. Never triggers a mechanical shutter exposure.
 */
public final class LivePreviewActivity extends Activity implements SurfaceHolder.Callback {
    static final String KEY_MOTION_WAKE = "motion_wake_enabled";
    static final String KEY_BOOT_WEBCAM = "boot_start_webcam";
    static final String KEY_DIM_BACKLIGHT_OFF = "dim_backlight_off";

    private static final String TAG = "OpenMemoriesWebcam";
    private static final long UI_TICK_MS = 60L;
    private static final long TELEMETRY_MS = 1000L;
    private static final long MOTION_INTERVAL_MS = 500L;
    private static final long SENSORS_POLL_WINDOW_MS = 30000L;
    private static final int MOTION_W = 80;
    private static final int MOTION_H = 60;
    private static final int LIVEVIEW_BUF_CAPACITY = 256 * 1024;

    private final Handler uiHandler = new Handler();
    private final ScreensaverPolicy screenPolicy = new ScreensaverPolicy();
    private final MotionDetectorPolicy motionPolicy = new MotionDetectorPolicy();
    private final PreviewFrameBus frameBus = new PreviewFrameBus();
    private final SonyDisplayBacklight displayBacklight = new SonyDisplayBacklight();
    private final Object captureLock = new Object();

    private AndroidDeveloperSettingsStore devStore;
    private SurfaceView surfaceView;
    private TextView statusStrip;
    private ScreensaverView saverOverlay;
    private PreviewHttpServer httpServer;
    private BroadcastReceiver batteryReceiver;

    private Object cameraEx;
    private Camera normalCamera;
    private Object cameraSequence;
    private boolean previewStarted;
    private boolean sequenceStarted;
    private String activePresetName = "";

    private volatile boolean running;
    private volatile boolean captureRunning;
    private volatile boolean surfaceReady;
    private volatile boolean lastMotionDetected;
    private volatile boolean motionWakeEnabled = true;
    private volatile long lastSensorsPollMs = -1L;

    private volatile String cachedIp = "offline";
    private volatile int cachedRssi = -85;
    private volatile boolean cachedStaticOn;
    private volatile boolean connectedServicesReconciled;
    private volatile int cachedBatteryLevel = -1;
    private volatile float cachedBatteryTemp = Float.NaN;
    private volatile float cachedBatteryVoltage = -1.0f;

    private Thread captureThread;
    private Thread motionThread;
    private long saverEnteredAtMs;
    private boolean screenEnabled = true;
    private boolean dimBacklightOffEnabled = true;
    private int consumedWakeScan = -1;

    private final PreviewHttpServer.ControlHooks httpHooks = new PreviewHttpServer.ControlHooks() {
        @Override public long nowMs() { return SystemClock.elapsedRealtime(); }
        @Override public long epochMs() { return System.currentTimeMillis(); }
        @Override public String screenStage() { return screenPolicy.stage().name(); }
        @Override public boolean motionDetected() { return lastMotionDetected; }
        @Override public boolean motionDetectEnabled() { return motionWakeEnabled; }
        @Override public int motionEvents() { return motionPolicy.motionEvents(); }
        @Override public String lastWakeReason() { return screenPolicy.lastWakeReason(); }
        @Override public String ipAddress() { return cachedIp; }
        @Override public int rssiDbm() { return cachedRssi; }
        @Override public int batteryLevelPercent() { return cachedBatteryLevel; }
        @Override public float batteryTempCelsius() { return cachedBatteryTemp; }
        @Override public float batteryVoltageVolts() { return cachedBatteryVoltage; }
        @Override public void wakeScreen(final String reason) {
            uiHandler.post(new Runnable() { @Override public void run() { wakeToLive(reason); } });
        }
        @Override public void enterScreensaver() {
            uiHandler.post(new Runnable() { @Override public void run() { switchToScreensaver(); } });
        }
        @Override public void enterFullDim() {
            uiHandler.post(new Runnable() { @Override public void run() { switchToFullDim(); } });
        }
        @Override public void setMotionDetectEnabled(boolean enabled) {
            motionWakeEnabled = enabled;
            if (!enabled) lastMotionDetected = false;
            if (devStore != null) devStore.putBoolean(KEY_MOTION_WAKE, enabled);
        }
        @Override public void onSensorsPolled(long nowMs) {
            lastSensorsPollMs = nowMs;
        }
    };

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            ScreensaverPolicy.Stage prev = screenPolicy.stage();
            ScreensaverPolicy.Stage stage = screenPolicy.evaluate(now, screenEnabled);
            if (prev != ScreensaverPolicy.Stage.SCREENSAVER && stage == ScreensaverPolicy.Stage.SCREENSAVER) {
                saverEnteredAtMs = now;
            }
            applyStageVisuals(stage, now);
            long nextDelay = (stage == ScreensaverPolicy.Stage.SCREENSAVER) ? UI_TICK_MS : 500L;
            uiHandler.postDelayed(this, nextDelay);
        }
    };

    private final Runnable telemetryTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshTelemetry();
            uiHandler.postDelayed(this, TELEMETRY_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        devStore = new AndroidDeveloperSettingsStore(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        statusStrip = new TextView(this);
        statusStrip.setTypeface(Typeface.MONOSPACE);
        statusStrip.setTextSize(12f);
        statusStrip.setTextColor(Color.rgb(165, 243, 252));
        statusStrip.setBackgroundColor(Color.argb(150, 0, 0, 0));
        statusStrip.setPadding(8, 4, 8, 4);
        statusStrip.setText("WEBCAM :8088 starting...");
        FrameLayout.LayoutParams stripLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        stripLp.gravity = Gravity.TOP;
        root.addView(statusStrip, stripLp);

        saverOverlay = new ScreensaverView(this);
        saverOverlay.setVisibility(View.GONE);
        root.addView(saverOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                updateBatteryFromIntent(intent);
            }
        };
    }

    @Override protected void onResume() {
        super.onResume();
        screenEnabled = devStore.getBoolean(MainActivity.KEY_SAVER_ENABLED, true);
        motionWakeEnabled = devStore.getBoolean(KEY_MOTION_WAKE, true);
        dimBacklightOffEnabled = devStore.getBoolean(KEY_DIM_BACKLIGHT_OFF, true);
        displayBacklight.restore(this);

        SonyPowerBridge.notifyAppInfo(this, getComponentName());
        SonyPowerBridge.applyAutoPowerOffMode(this, AutoPowerOffPolicy.MODE_DISABLE);

        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) updateBatteryFromIntent(sticky);

        running = true;
        frameBus.reopen();
        motionPolicy.reset();
        screenPolicy.wake(SystemClock.elapsedRealtime(), "start");
        saverOverlay.setVisibility(View.GONE);
        statusStrip.setVisibility(View.VISIBLE);

        startHttpServer();
        bootstrapPersistedTogglesIfEmpty();
        NtpSyncHelper.syncAsyncIfEnabled(this, false);
        openCameraOnMainThread();
        surfaceView.getHolder().addCallback(this);
        if (surfaceReady) {
            startPreviewAndSequenceOnMainThread(surfaceView.getHolder());
        }

        refreshTelemetry();
        uiHandler.removeCallbacksAndMessages(null);
        uiHandler.post(uiTick);
        uiHandler.postDelayed(telemetryTick, TELEMETRY_MS);
    }

    @Override protected void onPause() {
        running = false;
        uiHandler.removeCallbacksAndMessages(null);
        displayBacklight.restore(this);
        frameBus.close();
        stopHttpServer();
        surfaceView.getHolder().removeCallback(this);
        stopCameraPipelineOnMainThread();
        try { unregisterReceiver(batteryReceiver); } catch (RuntimeException ignored) {}
        SonyPowerBridge.applyAutoPowerOffMode(this, AutoPowerOffPolicy.MODE_NORMAL);
        super.onPause();
    }

    @Override protected void onDestroy() {
        displayBacklight.release(this);
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (running) startPreviewAndSequenceOnMainThread(holder);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopSequenceAndWorkers();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        int scan = event.getScanCode();
        int code = event.getKeyCode();
        int action = event.getAction();

        if (consumedWakeScan == scan && scan != 0) {
            if (action == KeyEvent.ACTION_UP) consumedWakeScan = -1;
            return true;
        }
        if (screenPolicy.stage() != ScreensaverPolicy.Stage.ACTIVE) {
            if (action == KeyEvent.ACTION_DOWN) {
                consumedWakeScan = scan;
                wakeToLive("key");
            }
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            screenPolicy.onUserActivity(SystemClock.elapsedRealtime());
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU
                    || scan == 514 || scan == 595) {
                finish();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || scan == 232) {
                switchToScreensaver();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) {
            if (screenPolicy.stage() != ScreensaverPolicy.Stage.ACTIVE) {
                wakeToLive("touch");
                return true;
            }
            screenPolicy.onUserActivity(SystemClock.elapsedRealtime());
        }
        return super.onTouchEvent(event);
    }

    private void updateBatteryFromIntent(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) {
            cachedBatteryLevel = (level * 100) / scale;
        }
        int tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (tenthsC != Integer.MIN_VALUE) {
            cachedBatteryTemp = tenthsC / 10.0f;
        }
        int mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        if (mv > 0) {
            cachedBatteryVoltage = mv / 1000.0f;
        }
    }

    private void wakeToLive(String reason) {
        displayBacklight.restore(this);
        screenPolicy.wake(SystemClock.elapsedRealtime(), reason);
        saverOverlay.setVisibility(View.GONE);
        statusStrip.setVisibility(View.VISIBLE);
        refreshTelemetry();
    }

    private void switchToScreensaver() {
        displayBacklight.restore(this);
        saverEnteredAtMs = SystemClock.elapsedRealtime();
        screenPolicy.enterScreensaverNow(saverEnteredAtMs);
        statusStrip.setVisibility(View.GONE);
        saverOverlay.setVisibility(View.VISIBLE);
        saverOverlay.step(1.0f, false);
        refreshTelemetry();
    }

    private void switchToFullDim() {
        screenPolicy.enterFullDimNow(SystemClock.elapsedRealtime());
        statusStrip.setVisibility(View.GONE);
        saverOverlay.setVisibility(View.VISIBLE);
        saverOverlay.step(0.0f, true);
        displayBacklight.turnOff(this, dimBacklightOffEnabled);
    }

    private void applyStageVisuals(ScreensaverPolicy.Stage stage, long now) {
        if (stage == ScreensaverPolicy.Stage.ACTIVE) {
            displayBacklight.restore(this);
            if (saverOverlay.getVisibility() != View.GONE) saverOverlay.setVisibility(View.GONE);
            if (statusStrip.getVisibility() != View.VISIBLE) statusStrip.setVisibility(View.VISIBLE);
        } else if (stage == ScreensaverPolicy.Stage.SCREENSAVER) {
            displayBacklight.restore(this);
            if (statusStrip.getVisibility() != View.GONE) statusStrip.setVisibility(View.GONE);
            if (saverOverlay.getVisibility() != View.VISIBLE) saverOverlay.setVisibility(View.VISIBLE);
            long elapsed = now >= saverEnteredAtMs ? (now - saverEnteredAtMs) : 0L;
            saverOverlay.step(ScreensaverPolicy.computeAlpha(elapsed), false);
        } else {
            if (statusStrip.getVisibility() != View.GONE) statusStrip.setVisibility(View.GONE);
            if (saverOverlay.getVisibility() != View.VISIBLE) saverOverlay.setVisibility(View.VISIBLE);
            saverOverlay.step(0.0f, true);
            displayBacklight.turnOff(this, dimBacklightOffEnabled);
        }
    }

    private void reconcileConnectedServicesAsync() {
        final Context app = getApplicationContext();
        NtpSyncHelper.syncAsyncIfEnabled(app, false);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                    String ssid = info == null ? "" : DeveloperSettingsPolicy.normalizeSsid(info.getSSID());
                    String adbBin = getApplicationInfo().nativeLibraryDir + "/libadbd.so";
                    String[] telnetCmd = new String[] { "busybox", "telnetd", "-l", "sh" };
                    boolean adbRunning = Procfs.findProcess(new String[] { adbBin }) != -1;
                    boolean telnetRunning = Procfs.findProcess(telnetCmd) != -1;
                    DeveloperSettingsPolicy.Snapshot desired = DeveloperSettingsPolicy.read(devStore);
                    DeveloperSettingsPolicy.RuntimeState st = new DeveloperSettingsPolicy.RuntimeState(
                            true, true, ssid, StaticIpPolicy.isStaticEnabled(new AndroidStaticIpStore(app)),
                            adbRunning, telnetRunning);
                    DeveloperSettingsPolicy.ReconcilePlan plan = DeveloperSettingsPolicy.planRestore(desired, st);
                    if (plan.startAdb) Shell.execAndroid(adbBin + " &");
                    if (plan.startTelnet) Shell.exec("busybox telnetd -l sh");
                } catch (Throwable ignored) {}
            }
        }, "om-webcam-reconcile");
        t.setDaemon(true);
        t.start();
    }

    private void bootstrapPersistedTogglesIfEmpty() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean wifiOn = wm != null && (wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED
                    || wm.getWifiState() == WifiManager.WIFI_STATE_ENABLING);
            String ssid = "";
            boolean connected = false;
            if (wm != null && wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getIpAddress() != 0) {
                    connected = true;
                    ssid = DeveloperSettingsPolicy.normalizeSsid(info.getSSID());
                }
            }
            boolean staticOn = StaticIpPolicy.isStaticEnabled(new AndroidStaticIpStore(this));
            String adbBin = getApplicationInfo().nativeLibraryDir + "/libadbd.so";
            boolean adbRunning = Procfs.findProcess(new String[] { adbBin }) != -1;
            boolean telnetRunning = Procfs.findProcess(new String[] { "busybox", "telnetd", "-l", "sh" }) != -1;
            DeveloperSettingsPolicy.bootstrapSnapshotIfEmpty(
                    devStore,
                    new DeveloperSettingsPolicy.RuntimeState(wifiOn, connected, ssid, staticOn, adbRunning, telnetRunning));
        } catch (Throwable ignored) {}
    }

    private void startHttpServer() {
        if (httpServer != null) return;
        PreviewHttpServer server = new PreviewHttpServer(PreviewHttpServer.DEFAULT_PORT, frameBus, httpHooks);
        try {
            server.start();
            httpServer = server;
            Log.i(TAG, "HTTP preview server listening on port " + server.boundPort());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            frameBus.setCameraState("HTTP_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private void stopHttpServer() {
        PreviewHttpServer server = httpServer;
        httpServer = null;
        if (server != null) server.stop();
    }

    private void openCameraOnMainThread() {
        synchronized (captureLock) {
            if (cameraEx != null) return;
            try {
                frameBus.setCameraState("OPENING_CAMERA", "", "");
                Class<?> exClass = Class.forName("com.sony.scalar.hardware.CameraEx");
                Class<?> openOptClass = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
                cameraEx = exClass.getMethod("open", Integer.TYPE, openOptClass).invoke(null, 0, null);
                if (cameraEx == null) throw new IllegalStateException("CameraEx.open returned null");
                normalCamera = (Camera) exClass.getMethod("getNormalCamera").invoke(cameraEx);
                if (normalCamera == null) throw new IllegalStateException("getNormalCamera returned null");
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                String msg = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
                Log.e(TAG, "openCamera error: " + msg, cause);
                frameBus.setCameraState("ERROR", msg, "");
            }
        }
    }

    private void startPreviewAndSequenceOnMainThread(SurfaceHolder holder) {
        synchronized (captureLock) {
            if (cameraEx == null || normalCamera == null || cameraSequence != null) return;
            try {
                normalCamera.setPreviewDisplay(holder);
                normalCamera.startPreview();
                previewStarted = true;

                Class<?> exClass = Class.forName("com.sony.scalar.hardware.CameraEx");
                Class<?> seqClass = Class.forName("com.sony.scalar.hardware.CameraSequence");
                Class<?> optClass = Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
                Method setIntOpt = optClass.getMethod("setOption", String.class, Integer.TYPE);
                Method setBoolOpt = optClass.getMethod("setOption", String.class, Boolean.TYPE);
                Method setStrOpt = optClass.getMethod("setOption", String.class, String.class);
                Method setReleaseLock = seqClass.getMethod("setReleaseLock", Boolean.TYPE);
                Method startSeq = seqClass.getMethod("startPreviewSequence", optClass);

                Object seq = seqClass.getMethod("open", exClass).invoke(null, cameraEx);
                if (seq == null) throw new IllegalStateException("CameraSequence.open returned null");

                String memMapPath = "/data/data/" + getPackageName() + "/lib/lib_memorymap.so";
                CameraOptionPresets.Preset[] presets = CameraOptionPresets.ordered();
                String lastErr = "";
                for (int i = 0; i < presets.length; i++) {
                    CameraOptionPresets.Preset p = presets[i];
                    try {
                        Object opts = optClass.newInstance();
                        if (p.includeMemoryMap) setStrOpt.invoke(opts, "MEMORY_MAP_FILE", memMapPath);
                        setIntOpt.invoke(opts, "EXPOSURE_COUNT", 1);
                        setIntOpt.invoke(opts, "RECORD_COUNT", 1);
                        setBoolOpt.invoke(opts, "INTERIM_PRE_REVIEW_ENABLED", false);
                        setBoolOpt.invoke(opts, "AUTO_RELEASE_LOCK_ENABLED", false);
                        setIntOpt.invoke(opts, "PREVIEW_FRAME_RATE", p.frameRateMilliFps);
                        setIntOpt.invoke(opts, "PREVIEW_FRAME_WIDTH", p.width);
                        setIntOpt.invoke(opts, "PREVIEW_FRAME_HEIGHT", p.height);
                        if (p.formatOption != CameraOptionPresets.FORMAT_DEFAULT) {
                            setIntOpt.invoke(opts, "PREVIEW_FRAME_FORMAT", p.formatOption);
                        }
                        setReleaseLock.invoke(seq, false);
                        startSeq.invoke(seq, opts);
                        cameraSequence = seq;
                        sequenceStarted = true;
                        activePresetName = p.name;
                        Log.i(TAG, "CameraSequence started with preset " + p.name);
                        frameBus.setCameraState("WARMING_UP", "", p.name);
                        break;
                    } catch (Throwable t) {
                        Throwable cause = unwrap(t);
                        lastErr = p.name + " -> " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                        Log.w(TAG, "Preset failed: " + lastErr);
                    }
                }
                if (!sequenceStarted) {
                    try { seqClass.getMethod("release").invoke(seq); } catch (Throwable ignored) {}
                    throw new IllegalStateException("All presets failed; last=" + lastErr);
                }

                captureRunning = true;
                captureThread = new Thread(new Runnable() {
                    @Override public void run() { runFrameProducer(); }
                }, "om-frame-producer");
                captureThread.setDaemon(true);
                captureThread.start();

                motionThread = new Thread(new Runnable() {
                    @Override public void run() { runAsyncMotionWorker(); }
                }, "om-motion-worker");
                motionThread.setPriority(Thread.MIN_PRIORITY);
                motionThread.setDaemon(true);
                motionThread.start();
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                String msg = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
                Log.e(TAG, "startPreviewAndSequence error: " + msg, cause);
                frameBus.setCameraState("ERROR", msg, activePresetName);
            }
        }
    }

    private void stopSequenceAndWorkers() {
        captureRunning = false;
        Thread ct = captureThread;
        captureThread = null;
        if (ct != null && ct != Thread.currentThread()) {
            ct.interrupt();
            try { ct.join(1500L); } catch (InterruptedException ignored) {}
        }
        Thread mt = motionThread;
        motionThread = null;
        if (mt != null && mt != Thread.currentThread()) {
            mt.interrupt();
            try { mt.join(1000L); } catch (InterruptedException ignored) {}
        }
        synchronized (captureLock) {
            if (cameraSequence != null) {
                try {
                    if (sequenceStarted) {
                        cameraSequence.getClass().getMethod("stopPreviewSequence").invoke(cameraSequence);
                    }
                } catch (Throwable ignored) {}
                try {
                    cameraSequence.getClass().getMethod("release").invoke(cameraSequence);
                } catch (Throwable ignored) {}
                cameraSequence = null;
                sequenceStarted = false;
            }
        }
    }

    private void stopCameraPipelineOnMainThread() {
        stopSequenceAndWorkers();
        synchronized (captureLock) {
            if (normalCamera != null) {
                try { if (previewStarted) normalCamera.stopPreview(); } catch (Throwable ignored) {}
                normalCamera = null;
                previewStarted = false;
            }
            if (cameraEx != null) {
                try { cameraEx.getClass().getMethod("release").invoke(cameraEx); } catch (Throwable ignored) {}
                cameraEx = null;
            }
        }
    }

    /** High-throughput frame producer; never performs synchronous JPEG decoding. */
    private void runFrameProducer() {
        ByteBuffer directBuf = ByteBuffer.allocateDirect(LIVEVIEW_BUF_CAPACITY);
        int emptyReads = 0;

        try {
            Class<?> seqClass = Class.forName("com.sony.scalar.hardware.CameraSequence");
            Method getFrames = seqClass.getMethod("getPreviewSequenceFrames", Integer.TYPE);
            Class<?> devBufClass = Class.forName("com.sony.scalar.hardware.DeviceBuffer");
            Method readOneArg = devBufClass.getMethod("read", ByteBuffer.class);
            Method releaseMethod = Class.forName("com.sony.scalar.hardware.DeviceMemory").getMethod("release");

            while (captureRunning && !Thread.currentThread().isInterrupted()) {
                byte[] jpeg = null;
                synchronized (captureLock) {
                    if (cameraSequence == null || !captureRunning) break;
                    Object framesObj = getFrames.invoke(cameraSequence, 1);
                    if (framesObj != null) {
                        int len = Array.getLength(framesObj);
                        try {
                            if (len > 0) {
                                Object frame = Array.get(framesObj, 0);
                                if (frame != null) {
                                    if (!devBufClass.isInstance(frame)) {
                                        frameBus.setCameraState("ERROR",
                                                "Unexpected frame class: " + frame.getClass().getName(),
                                                activePresetName);
                                        break;
                                    }
                                    directBuf.clear();
                                    int size = ((Integer) readOneArg.invoke(frame, directBuf)).intValue();
                                    if (size >= 4 && size <= LIVEVIEW_BUF_CAPACITY) {
                                        jpeg = new byte[size];
                                        directBuf.rewind();
                                        directBuf.get(jpeg, 0, size);
                                    }
                                }
                            }
                        } finally {
                            for (int i = 0; i < len; i++) {
                                Object dm = Array.get(framesObj, i);
                                if (dm != null) {
                                    try { releaseMethod.invoke(dm); } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }

                if (jpeg != null) {
                    emptyReads = 0;
                    frameBus.publish(jpeg, SystemClock.elapsedRealtime());
                    int mjpegCount = httpServer == null ? 0 : httpServer.mjpegClientCount();
                    long sleepMs = (mjpegCount <= 0) ? 200L : (mjpegCount == 1 ? 35L : 65L);
                    Thread.sleep(sleepMs);
                } else {
                    emptyReads++;
                    if (emptyReads % 20 == 0) {
                        frameBus.setCameraState("WAITING_FRAMES", "empty_reads=" + emptyReads, activePresetName);
                    }
                    Thread.sleep(40L);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            String msg = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
            Log.e(TAG, "Frame producer error: " + msg, cause);
            frameBus.setCameraState("ERROR", msg, activePresetName);
        }
    }

    /**
     * Async low-priority motion worker so BitmapFactory never stalls live MJPEG streaming.
     * Skips decoding while screen is ACTIVE unless HA recently polled /sensors.json.
     */
    private void runAsyncMotionWorker() {
        int[] argbScratch = new int[MOTION_W * MOTION_H];
        byte[] grayScratch = new byte[MOTION_W * MOTION_H];
        long lastAnalyzedSeq = 0L;

        while (captureRunning && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(MOTION_INTERVAL_MS);
                if (!captureRunning || !motionWakeEnabled) {
                    lastMotionDetected = false;
                    continue;
                }
                long now = SystemClock.elapsedRealtime();
                boolean needForWake = screenPolicy.stage() != ScreensaverPolicy.Stage.ACTIVE;
                boolean needForHa = lastSensorsPollMs >= 0L && (now - lastSensorsPollMs) < SENSORS_POLL_WINDOW_MS;
                if (!needForWake && !needForHa) {
                    continue;
                }
                PreviewFrameBus.Frame latest = frameBus.latestIfFresh(now, PreviewFrameBus.DEFAULT_FRESH_MS);
                if (latest == null || latest.seq == lastAnalyzedSeq) continue;
                lastAnalyzedSeq = latest.seq;
                analyzeMotionFrame(latest.jpeg, now, argbScratch, grayScratch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {}
        }
    }

    private void analyzeMotionFrame(byte[] jpeg, long nowMs, int[] argbScratch, byte[] grayScratch) {
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 8;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return;
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w <= 0 || h <= 0) return;
            int[] srcPixels = (w * h <= argbScratch.length) ? argbScratch : new int[w * h];
            bmp.getPixels(srcPixels, 0, w, 0, 0, w, h);
            MotionDetectorPolicy.downsampleArgbToGray(srcPixels, w, h, grayScratch, MOTION_W, MOTION_H);
            MotionDetectorPolicy.Evaluation ev = motionPolicy.update(grayScratch, MOTION_W, MOTION_H, nowMs);
            lastMotionDetected = ev.frameMoved;
            if (ev.wakeTriggered && screenPolicy.stage() != ScreensaverPolicy.Stage.ACTIVE) {
                Log.i(TAG, "Motion wake triggered (ratio=" + ev.changedRatio + ")");
                uiHandler.post(new Runnable() {
                    @Override public void run() { wakeToLive("motion"); }
                });
            }
        } catch (Throwable ignored) {
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private void refreshTelemetry() {
        String ipStr = "offline";
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
                    rssi = info.getRssi();
                    if (info.getIpAddress() != 0) ipStr = Formatter.formatIpAddress(info.getIpAddress());
                }
            }
        } catch (RuntimeException ignored) {}

        cachedIp = ipStr;
        cachedRssi = rssi;
        cachedStaticOn = staticOn;

        if (!connectedServicesReconciled && !"offline".equals(ipStr)) {
            connectedServicesReconciled = true;
            reconcileConnectedServicesAsync();
        }

        long now = SystemClock.elapsedRealtime();
        int mjpeg = httpServer == null ? 0 : httpServer.mjpegClientCount();
        float fps = Math.round(frameBus.fps() * 10.0f) / 10.0f;
        String state = frameBus.cameraState();
        String err = frameBus.cameraError();
        String motionStr = lastMotionDetected ? "MOTION" : "still";

        if (screenPolicy.stage() == ScreensaverPolicy.Stage.ACTIVE) {
            String remSaver = ScreensaverPolicy.formatCountdown(screenPolicy.remainingToSaverMs(now));
            String strip = "LIVE " + ipStr + ":8088 | " + state
                    + " " + fps + "fps | cli:" + mjpeg
                    + " | " + motionStr + " | SAVER " + remSaver;
            if (err.length() > 0) strip = strip + "\nERR: " + err;
            statusStrip.setText(strip);
        } else if (screenPolicy.stage() == ScreensaverPolicy.Stage.SCREENSAVER) {
            String mode = cachedStaticOn ? "[STATIC]" : "[DHCP]";
            String remDim = ScreensaverPolicy.formatCountdown(screenPolicy.remainingToDimMs(now));
            saverOverlay.updateTelemetry(
                    "LIVE " + ipStr + ":8088 " + mode,
                    fps + "fps cli:" + mjpeg + " " + cachedRssi + "dBm",
                    "MOT:" + motionPolicy.motionEvents() + " | DIM " + remDim,
                    cachedRssi);
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
