package com.github.ma1co.openmemories.tweak;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pure-Java lifecycle, FIFO rotation, and SD-card free-space protection policy
 * for motion-triggered JPEG stills saved to /sdcard/A6000_MOTION.
 */
final class MotionStoragePolicy {
    static final String DIR_PATH = "/sdcard/A6000_MOTION";
    static final String KEY_SAVE_SD = "motion_save_sd";
    static final String KEY_MAX_MB = "motion_max_mb";
    static final String KEY_RETENTION_HOURS = "motion_retention_hours";

    static final int DEFAULT_MAX_MB = 250;
    static final int DEFAULT_RETENTION_HOURS = 72;
    static final int MIN_MAX_MB = 10;
    static final int MAX_MAX_MB = 4096;
    static final int MIN_RETENTION_HOURS = 1;
    static final int MAX_RETENTION_HOURS = 24 * 90;
    static final long MIN_FREE_SD_BYTES = 100L * 1024L * 1024L;
    static final long MOTION_STILL_PREVIEW_MS = 4500L;

    static final class FileEntry {
        final String name;
        final long sizeBytes;
        final long lastModifiedMs;

        FileEntry(String name, long sizeBytes, long lastModifiedMs) {
            this.name = name;
            this.sizeBytes = sizeBytes < 0L ? 0L : sizeBytes;
            this.lastModifiedMs = lastModifiedMs;
        }
    }

    static final class StorageStats {
        final int fileCount;
        final long usedBytes;
        final long freeBytes;
        final long maxBytes;
        final int retentionHours;

        StorageStats(int fileCount, long usedBytes, long freeBytes, long maxBytes, int retentionHours) {
            this.fileCount = fileCount;
            this.usedBytes = usedBytes;
            this.freeBytes = freeBytes;
            this.maxBytes = maxBytes;
            this.retentionHours = retentionHours;
        }
    }

    private MotionStoragePolicy() {}

    static int clampMaxMb(int mb) {
        if (mb < MIN_MAX_MB) return MIN_MAX_MB;
        if (mb > MAX_MAX_MB) return MAX_MAX_MB;
        return mb;
    }

    static int clampRetentionHours(int hours) {
        if (hours < MIN_RETENTION_HOURS) return MIN_RETENTION_HOURS;
        if (hours > MAX_RETENTION_HOURS) return MAX_RETENTION_HOURS;
        return hours;
    }

    /**
     * Plans oldest-first deletions to satisfy:
     * 1) retention age (only when wall clock is valid >= 2020 to avoid deleting real stills when clock is 1970)
     * 2) folder capacity cap (maxBytes)
     * 3) minimum SD free-space safety floor (minFreeBytes)
     */
    static List<String> planPrune(List<FileEntry> entries, long nowMs,
                                  long maxBytes, long retentionMs,
                                  long freeBytes, long minFreeBytes) {
        List<String> toDelete = new ArrayList<String>();
        if (entries == null || entries.isEmpty()) return toDelete;

        List<FileEntry> sorted = new ArrayList<FileEntry>(entries);
        Collections.sort(sorted, new Comparator<FileEntry>() {
            @Override public int compare(FileEntry a, FileEntry b) {
                if (a.lastModifiedMs < b.lastModifiedMs) return -1;
                if (a.lastModifiedMs > b.lastModifiedMs) return 1;
                return a.name.compareTo(b.name);
            }
        });

        boolean validClock = nowMs >= SntpClientPolicy.MIN_VALID_EPOCH_MS;
        long totalUsed = 0L;
        for (int i = 0; i < sorted.size(); i++) {
            totalUsed += sorted.get(i).sizeBytes;
        }

        boolean knownFree = freeBytes >= 0L;
        long projectedFree = knownFree ? freeBytes : 0L;
        List<FileEntry> survivors = new ArrayList<FileEntry>(sorted.size());

        // Pass 1: age expiration (only if both nowMs and file timestamp are valid post-2020 epochs)
        for (int i = 0; i < sorted.size(); i++) {
            FileEntry e = sorted.get(i);
            boolean expired = validClock && retentionMs > 0L
                    && e.lastModifiedMs >= SntpClientPolicy.MIN_VALID_EPOCH_MS
                    && (nowMs - e.lastModifiedMs) > retentionMs;
            if (expired) {
                toDelete.add(e.name);
                totalUsed -= e.sizeBytes;
                if (knownFree) projectedFree += e.sizeBytes;
            } else {
                survivors.add(e);
            }
        }

        // Pass 2: FIFO cap & SD free-space floor enforcement (skip free-space floor when StatFs failed / < 0)
        for (int i = 0; i < survivors.size(); i++) {
            boolean overCap = maxBytes >= 0L && totalUsed > maxBytes;
            boolean lowFree = knownFree && minFreeBytes > 0L && projectedFree < minFreeBytes;
            if (!overCap && !lowFree) break;
            FileEntry e = survivors.get(i);
            toDelete.add(e.name);
            totalUsed -= e.sizeBytes;
            if (knownFree) projectedFree += e.sizeBytes;
        }
        return toDelete;
    }

    static boolean canWriteNewStill(long freeBytes, long minFreeBytes, int stillBytes) {
        if (stillBytes <= 0) return false;
        return (freeBytes - (long) stillBytes) >= minFreeBytes;
    }

    static String formatFilename(long epochMs, int seq) {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(epochMs > 0L ? epochMs : 0L);
        return String.format(Locale.US, "motion_%04d%02d%02d_%02d%02d%02d_%03d.jpg",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
                Math.abs(seq) % 1000);
    }

    static String formatBytesCompact(long bytes) {
        if (bytes < 0L) return "--";
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String formatSummary(StorageStats stats) {
        if (stats == null) return "Storage unavailable";
        long capMb = stats.maxBytes / (1024L * 1024L);
        return stats.fileCount + " stills · "
                + formatBytesCompact(stats.usedBytes) + "/" + capMb + " MB · "
                + stats.retentionHours + "h · SD free " + formatBytesCompact(stats.freeBytes);
    }
}
