package com.github.ma1co.openmemories.tweak;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

public final class PeriodicWifiService extends Service {
    private static final String TAG = "OpenMemoriesWifiWd";
    private static final String PREFS = "periodic_wifi";
    private static final Object JOURNAL_LOCK = new Object();
    private static boolean serviceActive;
    private static boolean startPending;

    private final PeriodicWifiPolicy policy = new PeriodicWifiPolicy();
    private HandlerThread thread;
    private Handler worker;
    private PeriodicWifiPolicy.Device device;
    private int workerStartId;
    private volatile boolean destroyed;

    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            long delay;
            synchronized (JOURNAL_LOCK) {
                delay = policy.tick(SystemClock.elapsedRealtime(), device);
            }
            if (destroyed) return;
            if (delay == PeriodicWifiPolicy.STOP) {
                stopSelfResult(workerStartId);
            } else {
                worker.postDelayed(this, delay);
            }
        }
    };

    static boolean isArmed(Context context) {
        synchronized (JOURNAL_LOCK) {
            return read(prefs(context)).armed;
        }
    }

    static boolean setArmed(Context context, boolean armed) {
        synchronized (JOURNAL_LOCK) {
            SharedPreferences preferences = prefs(context);
            PeriodicWifiPolicy.Journal previous = read(preferences);
            if (!save(preferences, previous.withArmed(armed))) return false;
        }
        return start(context);
    }

    static void resumeIfNeeded(Context context) {
        synchronized (JOURNAL_LOCK) {
            PeriodicWifiPolicy.Journal journal = read(prefs(context));
            if (!journal.pendingOn && !journal.armed) return;
        }
        start(context);
    }

    static boolean resumeOnceOnWifiEnabled(Context context) {
        synchronized (JOURNAL_LOCK) {
            PeriodicWifiPolicy.Journal journal = read(prefs(context));
            if (!PeriodicWifiPolicy.shouldStartOnWifiEnabled(
                    true, serviceActive || startPending, journal)) {
                return false;
            }
            startPending = true;
        }
        return start(context);
    }

    static String shortStatus(Context context) {
        synchronized (JOURNAL_LOCK) {
            PeriodicWifiPolicy.Journal j = read(prefs(context));
            if (j.pendingOn) {
                return "RESTORING (" + j.onAttempts + "/" + PeriodicWifiPolicy.ATTEMPT_LIMIT + ")";
            }
            return (j.armed ? "ON" : "OFF") + " (" + j.offAttempts + " cyc)";
        }
    }

    private static boolean start(Context context) {
        synchronized (JOURNAL_LOCK) {
            if (!serviceActive) startPending = true;
        }
        try {
            boolean started = context.startService(new Intent(context, PeriodicWifiService.class)) != null;
            if (!started) {
                synchronized (JOURNAL_LOCK) { startPending = false; }
            }
            return started;
        } catch (RuntimeException e) {
            synchronized (JOURNAL_LOCK) { startPending = false; }
            Log.w(TAG, "Unable to start Wi-Fi watchdog");
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static PeriodicWifiPolicy.Journal read(SharedPreferences preferences) {
        return new PeriodicWifiPolicy.Journal(
                preferences.getBoolean("armed", true), preferences.getInt("arm_generation", 0),
                preferences.getInt("off_attempts", 0),
                preferences.getBoolean("pending_on", false), preferences.getInt("on_attempts", 0));
    }

    private static boolean save(SharedPreferences preferences, PeriodicWifiPolicy.Journal journal) {
        boolean saved = preferences.edit()
                .putBoolean("armed", journal.armed)
                .putInt("arm_generation", journal.armGeneration)
                .putInt("off_attempts", journal.offAttempts)
                .putBoolean("pending_on", journal.pendingOn)
                .putInt("on_attempts", journal.onAttempts)
                .commit();
        if (!saved) Log.w(TAG, "Could not persist Wi-Fi watchdog state");
        return saved;
    }

    @Override public void onCreate() {
        super.onCreate();
        synchronized (JOURNAL_LOCK) {
            serviceActive = true;
            startPending = false;
        }
        Context app = getApplicationContext();
        device = new WifiDevice((WifiManager) app.getSystemService(Context.WIFI_SERVICE), prefs(app));
        thread = new HandlerThread("OpenMemoriesWifiWd");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, final int startId) {
        worker.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                workerStartId = startId;
                worker.removeCallbacks(check);
                worker.post(check);
            }
        });
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        synchronized (JOURNAL_LOCK) {
            serviceActive = false;
            startPending = false;
        }
        destroyed = true;
        worker.removeCallbacksAndMessages(null);
        thread.quit();
        super.onDestroy();
    }

    private static final class WifiDevice implements PeriodicWifiPolicy.Device {
        private final WifiManager wifi;
        private final SharedPreferences preferences;

        WifiDevice(WifiManager wifi, SharedPreferences preferences) {
            this.wifi = wifi;
            this.preferences = preferences;
        }

        @Override public PeriodicWifiPolicy.Journal readJournal() { return read(preferences); }

        @Override public boolean saveJournal(PeriodicWifiPolicy.Journal journal) {
            boolean wasPending = read(preferences).pendingOn;
            boolean saved = save(preferences, journal);
            if (saved && wasPending && !journal.pendingOn) Log.i(TAG, "Wi-Fi ON confirmed");
            return saved;
        }

        @Override public boolean readWifiEnabled() {
            try { return wifi != null && wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLED; }
            catch (RuntimeException e) { return false; }
        }

        @Override public boolean readWifiDisabled() {
            try { return wifi != null && wifi.getWifiState() == WifiManager.WIFI_STATE_DISABLED; }
            catch (RuntimeException e) { return false; }
        }

        @Override public void requestWifi(boolean enabled) {
            try {
                if (wifi != null && wifi.setWifiEnabled(enabled)) {
                    Log.i(TAG, "Requested Wi-Fi " + (enabled ? "ON" : "OFF"));
                } else {
                    Log.w(TAG, "Wi-Fi " + (enabled ? "ON" : "OFF") + " request rejected");
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Wi-Fi " + (enabled ? "ON" : "OFF") + " request failed");
            }
        }
    }
}
