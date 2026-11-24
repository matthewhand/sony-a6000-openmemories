package com.github.ma1co.openmemories.tweak;

import java.util.Arrays;
import java.util.List;

final class MotionStoragePolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        ageExpirationAndClockGuard();
        capAndFreeSpaceFifoRotation();
        filenameAndSummaryFormatting();
        System.out.println("PASS MotionStoragePolicyTests: " + assertions + " assertions");
    }

    private static void ageExpirationAndClockGuard() {
        long now = 1795384000000L; // 2026
        long day = 24L * 3600L * 1000L;
        List<MotionStoragePolicy.FileEntry> files = Arrays.asList(
                new MotionStoragePolicy.FileEntry("motion_old.jpg", 100_000L, now - 4 * day),
                new MotionStoragePolicy.FileEntry("motion_mid.jpg", 100_000L, now - 2 * day),
                new MotionStoragePolicy.FileEntry("motion_new.jpg", 100_000L, now - 1000L));

        List<String> del = MotionStoragePolicy.planPrune(
                files, now, 10L * 1024L * 1024L, 3 * day, 500L * 1024L * 1024L, MotionStoragePolicy.MIN_FREE_SD_BYTES);
        expect(del.size() == 1 && "motion_old.jpg".equals(del.get(0)), "deletes only >72h file");

        // Clock not synced (1970) must NOT age-delete valid 2026 stills
        List<String> del1970 = MotionStoragePolicy.planPrune(
                files, 60_000L, 10L * 1024L * 1024L, 3 * day, 500L * 1024L * 1024L, MotionStoragePolicy.MIN_FREE_SD_BYTES);
        expect(del1970.isEmpty(), "1970 clock never age-prunes stills");
    }

    private static void capAndFreeSpaceFifoRotation() {
        long now = 1795384000000L;
        long mb = 1024L * 1024L;
        List<MotionStoragePolicy.FileEntry> files = Arrays.asList(
                new MotionStoragePolicy.FileEntry("b.jpg", 2 * mb, now - 2000L),
                new MotionStoragePolicy.FileEntry("a.jpg", 2 * mb, now - 5000L),
                new MotionStoragePolicy.FileEntry("c.jpg", 2 * mb, now - 1000L));

        // 6 MB used with 3 MB cap -> delete oldest two (a.jpg, b.jpg), keep c.jpg
        List<String> capDel = MotionStoragePolicy.planPrune(
                files, now, 3 * mb, 72L * 3600L * 1000L, 500 * mb, 100 * mb);
        expect(capDel.size() == 2 && "a.jpg".equals(capDel.get(0)) && "b.jpg".equals(capDel.get(1)),
                "FIFO cap rotation deletes oldest first");

        // Low SD free space (97 MB free, 100 MB floor) -> delete a.jpg + b.jpg to recover >= 100 MB
        List<String> freeDel = MotionStoragePolicy.planPrune(
                files, now, 50 * mb, 72L * 3600L * 1000L, 97 * mb, 100 * mb);
        expect(freeDel.size() == 2 && "a.jpg".equals(freeDel.get(0)), "free-space floor triggers FIFO rotation");

        // Unknown SD free space (-1L from StatFs failure) must NOT wipe existing stills when under folder cap
        List<String> unknownFreeDel = MotionStoragePolicy.planPrune(
                files, now, 50 * mb, 72L * 3600L * 1000L, -1L, 100 * mb);
        expect(unknownFreeDel.isEmpty(), "unknown freeBytes (-1) does not wipe existing stills");

        expect(MotionStoragePolicy.canWriteNewStill(105 * mb, 100 * mb, 70_000), "allows write above free floor");
        expect(!MotionStoragePolicy.canWriteNewStill(99 * mb, 100 * mb, 70_000), "blocks write below free floor");
        expect(!MotionStoragePolicy.canWriteNewStill(-1L, 100 * mb, 70_000), "blocks write when freeBytes unknown");
    }

    private static void filenameAndSummaryFormatting() {
        String name = MotionStoragePolicy.formatFilename(1795382640000L, 7);
        expect(name.startsWith("motion_20261122_212400_007.jpg"), "UTC sortable filename: " + name);
        expect(MotionStoragePolicy.clampMaxMb(1) == MotionStoragePolicy.MIN_MAX_MB, "clamps min MB");
        expect(MotionStoragePolicy.clampRetentionHours(0) == MotionStoragePolicy.MIN_RETENTION_HOURS, "clamps min hours");
        MotionStoragePolicy.StorageStats stats = new MotionStoragePolicy.StorageStats(
                12, 3L * 1024L * 1024L, 12L * 1024L * 1024L * 1024L, 250L * 1024L * 1024L, 72);
        String summary = MotionStoragePolicy.formatSummary(stats);
        expect(summary.contains("12 stills") && summary.contains("250 MB") && summary.contains("GB"),
                "human-readable summary: " + summary);
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
