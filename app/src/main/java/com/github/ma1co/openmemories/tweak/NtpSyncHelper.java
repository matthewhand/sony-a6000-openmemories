package com.github.ma1co.openmemories.tweak;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android/Sony SNTP time sync helper.
 * - Queries UDP/123 on LAN gateway (if available), pool.ntp.org, and time.cloudflare.com.
 * - Sets Linux/Android system clock via Shell.exec("busybox date -u -s ...").
 * - Reflectively invokes Sony TimeUtil.setCurrentTime on Sony API V6+ bodies.
 */
final class NtpSyncHelper {
    private static final String TAG = "OpenMemoriesNtp";
    static final String KEY_AUTO_NTP = "auto_ntp_sync";
    static final int TIMEOUT_MS = SntpClientPolicy.WAN_TIMEOUT_MS;
    private static final long MIN_AUTO_RETRY_INTERVAL_MS = 15000L;
    private static final AtomicBoolean SYNC_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long lastAttemptElapsedMs = -1L;

    static final class SyncResult {
        final boolean ok;
        final long epochMs;
        final boolean linuxClockSet;
        final boolean sonyRtosClockSet;
        final String summary;

        SyncResult(boolean ok, long epochMs, boolean linuxClockSet,
                   boolean sonyRtosClockSet, String summary) {
            this.ok = ok;
            this.epochMs = epochMs;
            this.linuxClockSet = linuxClockSet;
            this.sonyRtosClockSet = sonyRtosClockSet;
            this.summary = summary;
        }
    }

    private NtpSyncHelper() {}

    static String[] candidateHosts(Context context) {
        List<String> hosts = new ArrayList<String>(4);
        if (context != null) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    DhcpInfo info = wm.getDhcpInfo();
                    if (info != null) {
                        String gw = StaticIpPolicy.intToIpv4(info.gateway);
                        if (StaticIpPolicy.isValidIpv4(gw)) hosts.add(gw);
                    }
                }
            } catch (RuntimeException ignored) {}
            try {
                StaticIpPolicy.Config saved = StaticIpPolicy.readSavedConfig(new AndroidStaticIpStore(context));
                if (saved != null && StaticIpPolicy.isValidIpv4(saved.gateway) && !hosts.contains(saved.gateway)) {
                    hosts.add(saved.gateway);
                }
            } catch (RuntimeException ignored) {}
        }
        hosts.add(SntpClientPolicy.DEFAULT_NTP_HOST);
        hosts.add(SntpClientPolicy.FALLBACK_NTP_HOST);
        return hosts.toArray(new String[hosts.size()]);
    }

    static SyncResult syncNow(Context context) {
        String[] hosts = candidateHosts(context);
        long epochMs = -1L;
        String usedHost = null;
        String lastErr = "no servers";
        for (int i = 0; i < hosts.length; i++) {
            String h = hosts[i];
            if (h == null || h.trim().length() == 0) continue;
            String trimmed = h.trim();
            try {
                long t = SntpClientPolicy.queryNtpServer(
                        trimmed, SntpClientPolicy.NTP_PORT, SntpClientPolicy.timeoutForHostMs(trimmed));
                if (t >= SntpClientPolicy.MIN_VALID_EPOCH_MS) {
                    epochMs = t;
                    usedHost = trimmed;
                    break;
                }
                lastErr = trimmed + ": invalid response";
            } catch (Exception e) {
                lastErr = trimmed + ": " + e.getClass().getSimpleName();
            }
        }
        if (epochMs < SntpClientPolicy.MIN_VALID_EPOCH_MS) {
            Log.w(TAG, "NTP failed (" + lastErr + ")");
            return new SyncResult(false, -1L, false, false, "NTP failed (" + lastErr + ")");
        }

        boolean linuxSet = false;
        String cmd = SntpClientPolicy.formatBusyBoxUtcDateCommand(epochMs);
        if (cmd != null) {
            try {
                Shell.exec(cmd);
                linuxSet = true;
            } catch (Throwable t) {
                Logger.error("NtpSyncHelper.linuxDate", t);
                Log.w(TAG, "Failed to apply BusyBox UTC date", t);
            }
        }

        boolean sonySet = trySetSonyV6CameraClock(epochMs);
        Calendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(epochMs);
        String stamp = SntpClientPolicy.formatUtcIso(utc) + "Z";
        String detail = "Synced " + stamp + " via " + usedHost
                + (sonySet ? " (Linux + Sony RTOS)" : " (Linux; RTOS needs API V6+)");
        Logger.info("NtpSync", detail);
        Log.i(TAG, detail);
        return new SyncResult(linuxSet || sonySet, epochMs, linuxSet, sonySet, detail);
    }

    private static boolean hasWifiIpv4(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getWifiState() != WifiManager.WIFI_STATE_ENABLED) return false;
            WifiInfo info = wm.getConnectionInfo();
            return info != null && info.getIpAddress() != 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void syncAsyncIfEnabled(final Context context, final boolean force) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        boolean enabled = new AndroidDeveloperSettingsStore(app).getBoolean(KEY_AUTO_NTP, true);
        if (!enabled) return;
        if (!force && System.currentTimeMillis() >= SntpClientPolicy.MIN_VALID_EPOCH_MS) return;
        if (!force && !hasWifiIpv4(app)) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        long prev = lastAttemptElapsedMs;
        if (!force && prev >= 0L && nowElapsed >= prev && (nowElapsed - prev) < MIN_AUTO_RETRY_INTERVAL_MS) {
            return;
        }
        if (!SYNC_IN_FLIGHT.compareAndSet(false, true)) return;
        lastAttemptElapsedMs = nowElapsed;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    syncNow(app);
                } finally {
                    SYNC_IN_FLIGHT.set(false);
                }
            }
        }, "om-ntp-sync");
        t.setDaemon(true);
        t.start();
    }

    private static boolean trySetSonyV6CameraClock(long epochMs) {
        try {
            Class<?> timeUtilCls = Class.forName("com.sony.scalar.sysutil.TimeUtil");
            Class<?> calCls = Class.forName("com.sony.scalar.sysutil.PlainCalendar");
            Class<?> tzCls = Class.forName("com.sony.scalar.sysutil.PlainTimeZone");
            Method setMethod = timeUtilCls.getMethod("setCurrentTime", calCls, tzCls);
            Object util = timeUtilCls.newInstance();
            Object currentTz = timeUtilCls.getMethod("getCurrentTimeZone").invoke(null);

            int gmtDiffMin = 0;
            int summerDiffMin = 0;
            if (currentTz != null) {
                gmtDiffMin = tzCls.getField("gmtDiff").getInt(currentTz);
                summerDiffMin = tzCls.getField("summerTimeDiff").getInt(currentTz);
            } else {
                currentTz = tzCls.newInstance();
            }

            long localMs = epochMs + ((long) (gmtDiffMin + summerDiffMin) * 60000L);
            Calendar localCal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            localCal.setTimeInMillis(localMs);

            Object plainCal = calCls.newInstance();
            calCls.getField("year").setInt(plainCal, localCal.get(Calendar.YEAR));
            calCls.getField("month").setInt(plainCal, localCal.get(Calendar.MONTH) + 1);
            calCls.getField("day").setInt(plainCal, localCal.get(Calendar.DAY_OF_MONTH));
            calCls.getField("hour").setInt(plainCal, localCal.get(Calendar.HOUR_OF_DAY));
            calCls.getField("minute").setInt(plainCal, localCal.get(Calendar.MINUTE));
            calCls.getField("second").setInt(plainCal, localCal.get(Calendar.SECOND));

            int rc = ((Integer) setMethod.invoke(util, plainCal, currentTz)).intValue();
            return rc == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
