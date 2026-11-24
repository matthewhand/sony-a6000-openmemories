package com.github.ma1co.openmemories.tweak;

/** Pure-Java deterministic state machine: 60s idle -> 5m screensaver -> pure-black full dim. */
final class ScreensaverPolicy {
    static final long IDLE_TO_SAVER_MS = 60000L;
    static final long SAVER_TO_DIM_MS = 300000L;
    static final long FADE_WINDOW_MS = 15000L;

    enum Stage { ACTIVE, SCREENSAVER, FULL_DIM }

    private Stage stage = Stage.ACTIVE;
    private long stageStartMs = -1L;
    private long lastNowMs = -1L;
    private String lastWakeReason = "init";

    Stage stage() { return stage; }
    String lastWakeReason() { return lastWakeReason; }

    void onUserActivity(long nowMs) {
        wake(nowMs, "user");
    }

    void wake(long nowMs, String reason) {
        stage = Stage.ACTIVE;
        stageStartMs = nowMs;
        lastNowMs = nowMs;
        lastWakeReason = (reason == null || reason.length() == 0) ? "user" : reason;
    }

    void enterScreensaverNow(long nowMs) {
        stage = Stage.SCREENSAVER;
        stageStartMs = nowMs;
        lastNowMs = nowMs;
    }

    void enterFullDimNow(long nowMs) {
        stage = Stage.FULL_DIM;
        stageStartMs = nowMs;
        lastNowMs = nowMs;
    }

    Stage evaluate(long nowMs, boolean enabled) {
        if (!enabled) {
            stage = Stage.ACTIVE;
            stageStartMs = nowMs;
            lastNowMs = nowMs;
            return stage;
        }
        if (stageStartMs < 0L || (lastNowMs >= 0L && nowMs < lastNowMs)) {
            stageStartMs = nowMs;
            lastNowMs = nowMs;
            return stage;
        }
        lastNowMs = nowMs;
        long elapsed = nowMs - stageStartMs;
        if (stage == Stage.ACTIVE && elapsed >= IDLE_TO_SAVER_MS) {
            stage = Stage.SCREENSAVER;
            stageStartMs = nowMs;
        } else if (stage == Stage.SCREENSAVER && elapsed >= SAVER_TO_DIM_MS) {
            stage = Stage.FULL_DIM;
            stageStartMs = nowMs;
        }
        return stage;
    }

    long remainingToDimMs(long nowMs) {
        if (stage == Stage.FULL_DIM) return 0L;
        if (stage != Stage.SCREENSAVER || stageStartMs < 0L || nowMs < stageStartMs) {
            return SAVER_TO_DIM_MS;
        }
        long rem = SAVER_TO_DIM_MS - (nowMs - stageStartMs);
        return rem < 0L ? 0L : rem;
    }

    long remainingToSaverMs(long nowMs) {
        if (stage != Stage.ACTIVE) return 0L;
        if (stageStartMs < 0L || nowMs < stageStartMs) return IDLE_TO_SAVER_MS;
        long rem = IDLE_TO_SAVER_MS - (nowMs - stageStartMs);
        return rem < 0L ? 0L : rem;
    }

    static float computeAlpha(long saverElapsedMs) {
        if (saverElapsedMs < 0L) return 1.0f;
        if (saverElapsedMs >= SAVER_TO_DIM_MS) return 0.0f;
        long fadeStart = SAVER_TO_DIM_MS - FADE_WINDOW_MS;
        if (saverElapsedMs <= fadeStart) return 1.0f;
        return (float) (SAVER_TO_DIM_MS - saverElapsedMs) / (float) FADE_WINDOW_MS;
    }

    static String formatCountdown(long remainingMs) {
        long sec = remainingMs <= 0L ? 0L : (remainingMs + 999L) / 1000L;
        long min = sec / 60L;
        long remSec = sec % 60L;
        return (min < 10 ? "0" : "") + min + ":" + (remSec < 10 ? "0" : "") + remSec;
    }
}
