package com.github.ma1co.openmemories.tweak;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SD card storage helper for motion-triggered JPEG stills (/sdcard/A6000_MOTION).
 * Enforces retention hours, folder size cap, and SD free-space floor asynchronously.
 */
final class MotionGalleryStore {
    private static final String TAG = "OpenMemoriesMotionStore";
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private MotionGalleryStore() {}

    static boolean isSaveEnabled(Context context) {
        return prefs(context).getBoolean(MotionStoragePolicy.KEY_SAVE_SD, true);
    }

    static void setSaveEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(MotionStoragePolicy.KEY_SAVE_SD, enabled).commit();
    }

    static int getMaxMb(Context context) {
        return MotionStoragePolicy.clampMaxMb(
                prefs(context).getInt(MotionStoragePolicy.KEY_MAX_MB, MotionStoragePolicy.DEFAULT_MAX_MB));
    }

    static void setMaxMb(Context context, int mb) {
        prefs(context).edit().putInt(MotionStoragePolicy.KEY_MAX_MB, MotionStoragePolicy.clampMaxMb(mb)).commit();
    }

    static int getRetentionHours(Context context) {
        return MotionStoragePolicy.clampRetentionHours(
                prefs(context).getInt(MotionStoragePolicy.KEY_RETENTION_HOURS, MotionStoragePolicy.DEFAULT_RETENTION_HOURS));
    }

    static void setRetentionHours(Context context, int hours) {
        prefs(context).edit().putInt(
                MotionStoragePolicy.KEY_RETENTION_HOURS,
                MotionStoragePolicy.clampRetentionHours(hours)).commit();
    }

    static File saveMotionStill(Context context, byte[] jpeg, long epochMs) {
        if (context == null || jpeg == null || jpeg.length < 4 || !isSaveEnabled(context)) return null;
        File dir = new File(MotionStoragePolicy.DIR_PATH);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create " + dir.getAbsolutePath());
            return null;
        }
        long maxBytes = (long) getMaxMb(context) * 1024L * 1024L;
        long retentionMs = (long) getRetentionHours(context) * 3600L * 1000L;
        pruneDirectory(dir, epochMs, maxBytes, retentionMs);

        long freeBytes = readSdFreeBytes();
        if (!MotionStoragePolicy.canWriteNewStill(freeBytes, MotionStoragePolicy.MIN_FREE_SD_BYTES, jpeg.length)) {
            Log.w(TAG, "Skipping motion still write: SD free space below floor (" + freeBytes + " bytes)");
            return null;
        }

        String name = MotionStoragePolicy.formatFilename(epochMs, SEQ.incrementAndGet());
        File tmp = new File(dir, "." + name + ".tmp");
        File out = new File(dir, name);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(jpeg);
            fos.flush();
            fos.close();
            fos = null;
            if (!tmp.renameTo(out)) {
                tmp.delete();
                return null;
            }
            return out;
        } catch (IOException e) {
            Log.w(TAG, "Failed writing motion still: " + e.getMessage());
            tmp.delete();
            return null;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    static File[] listRecentStills(int limit) {
        File dir = new File(MotionStoragePolicy.DIR_PATH);
        if (!dir.isDirectory()) return new File[0];
        File[] all = dir.listFiles();
        if (all == null || all.length == 0) return new File[0];
        List<File> jpegs = new ArrayList<File>();
        for (int i = 0; i < all.length; i++) {
            File f = all[i];
            if (f != null && f.isFile() && f.getName().endsWith(".jpg") && !f.getName().startsWith(".")) {
                jpegs.add(f);
            }
        }
        File[] sorted = jpegs.toArray(new File[jpegs.size()]);
        Arrays.sort(sorted, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long da = a.lastModified();
                long db = b.lastModified();
                if (db != da) return db > da ? 1 : -1;
                return b.getName().compareTo(a.getName());
            }
        });
        if (limit > 0 && sorted.length > limit) {
            File[] trimmed = new File[limit];
            System.arraycopy(sorted, 0, trimmed, 0, limit);
            return trimmed;
        }
        return sorted;
    }

    static int clearAllStills() {
        File[] files = listRecentStills(0);
        int removed = 0;
        for (int i = 0; i < files.length; i++) {
            if (files[i].delete()) removed++;
        }
        return removed;
    }

    static MotionStoragePolicy.StorageStats inspectStats(Context context) {
        File[] files = listRecentStills(0);
        long used = 0L;
        for (int i = 0; i < files.length; i++) {
            used += files[i].length();
        }
        long maxBytes = (long) getMaxMb(context) * 1024L * 1024L;
        return new MotionStoragePolicy.StorageStats(
                files.length, used, readSdFreeBytes(), maxBytes, getRetentionHours(context));
    }

    static long readSdFreeBytes() {
        try {
            StatFs sf = new StatFs("/sdcard");
            long blocks = sf.getAvailableBlocks() & 0xFFFFFFFFL;
            long blockSize = sf.getBlockSize() & 0xFFFFFFFFL;
            return blocks * blockSize;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static void pruneDirectory(File dir, long nowMs, long maxBytes, long retentionMs) {
        File[] files = listRecentStills(0);
        if (files.length == 0) return;
        List<MotionStoragePolicy.FileEntry> entries = new ArrayList<MotionStoragePolicy.FileEntry>(files.length);
        for (int i = 0; i < files.length; i++) {
            entries.add(new MotionStoragePolicy.FileEntry(
                    files[i].getName(), files[i].length(), files[i].lastModified()));
        }
        List<String> toDelete = MotionStoragePolicy.planPrune(
                entries, nowMs, maxBytes, retentionMs, readSdFreeBytes(), MotionStoragePolicy.MIN_FREE_SD_BYTES);
        for (int i = 0; i < toDelete.size(); i++) {
            new File(dir, toDelete.get(i)).delete();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                DeveloperSettingsPolicy.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
