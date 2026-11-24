package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java cooldown and bounded retry policy for Sony CameraEx / CameraService overlay races.
 * On ILCE-6000 (Android 2.3.7), stopping preview and immediately calling startPreview() on the
 * same tick can fail with "Overlay Creation Failed!" -> RuntimeException("startPreview failed")
 * while native CameraService finishes releasing the previous hardware overlay.
 */
final class CameraRetryPolicy {
    static final long REOPEN_COOLDOWN_MS = 500L;
    static final long[] RETRY_DELAYS_MS = new long[] { 600L, 1200L, 2000L, 3000L };
    static final int MAX_RETRIES = RETRY_DELAYS_MS.length;

    private CameraRetryPolicy() {}

    /**
     * Returns remaining milliseconds to wait before reopening CameraEx after a recent stop,
     * or 0L on cold start or once the cooldown window has elapsed.
     */
    static long reopenCooldownMs(long nowElapsedMs, long lastStopElapsedMs) {
        if (lastStopElapsedMs <= 0L || nowElapsedMs < lastStopElapsedMs) return 0L;
        long elapsed = nowElapsedMs - lastStopElapsedMs;
        return elapsed >= REOPEN_COOLDOWN_MS ? 0L : (REOPEN_COOLDOWN_MS - elapsed);
    }

    /**
     * Returns bounded backoff delay in ms for the given 0-based failed attempt index,
     * or -1L once retries are exhausted.
     */
    static long retryDelayMs(int failedAttemptIndex) {
        if (failedAttemptIndex < 0 || failedAttemptIndex >= RETRY_DELAYS_MS.length) return -1L;
        return RETRY_DELAYS_MS[failedAttemptIndex];
    }
}
