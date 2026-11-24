package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java deterministic state machine for configurable screensaver & full-dim timing,
 * style selection (Random default + 6 concrete styles), and HUD toggle.
 * Any delay > 0s enables screensaver; 0s disables it logically.
 */
final class ScreensaverPolicy {
    static final String KEY_DELAY_SEC = "screensaver_delay_sec";
    static final String KEY_DIM_SEC = "screensaver_dim_sec";
    static final String KEY_STYLE = "screensaver_style";
    static final String KEY_HUD_ENABLED = "screensaver_hud_enabled";

    static final int DEFAULT_DELAY_SEC = 60;
    static final int DEFAULT_DIM_SEC = 300;
    static final int MAX_DELAY_SEC = 3600;
    static final int MAX_DIM_SEC = 7200;

    static final long IDLE_TO_SAVER_MS = DEFAULT_DELAY_SEC * 1000L;
    static final long SAVER_TO_DIM_MS = DEFAULT_DIM_SEC * 1000L;
    static final long FADE_WINDOW_MS = 15000L;
    static final String DIVIDER_LABEL = "────────────────────";

    enum Stage { ACTIVE, SCREENSAVER, FULL_DIM }

    enum Style {
        RANDOM("random", "Random"),
        MATRIX("matrix", "Matrix Rain"),
        GALLERY("gallery", "Motion Gallery"),
        STARFIELD("starfield", "Starfield Warp"),
        CLOCK("clock", "Digital Clock"),
        BOUNCER("bouncer", "a6000 Bouncer"),
        MINIMAL("minimal", "Minimal Dark");

        final String id;
        final String label;

        Style(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final Style[] CONCRETE_STYLES = new Style[] {
            Style.MATRIX, Style.GALLERY, Style.STARFIELD,
            Style.CLOCK, Style.BOUNCER, Style.MINIMAL
    };

    private Stage stage = Stage.ACTIVE;
    private long stageStartMs = -1L;
    private long lastNowMs = -1L;
    private String lastWakeReason = "init";

    Stage stage() { return stage; }
    String lastWakeReason() { return lastWakeReason; }

    static int clampDelaySec(int sec) {
        if (sec <= 0) return 0;
        if (sec > MAX_DELAY_SEC) return MAX_DELAY_SEC;
        return sec;
    }

    static int clampDimSec(int sec) {
        if (sec <= 0) return 0;
        if (sec > MAX_DIM_SEC) return MAX_DIM_SEC;
        return sec;
    }

    static boolean isEnabledForDelay(int delaySec) {
        return delaySec > 0;
    }

    static Style parseStyle(String id) {
        if (id != null) {
            Style[] values = Style.values();
            for (int i = 0; i < values.length; i++) {
                if (values[i].id.equals(id)) return values[i];
            }
        }
        return Style.RANDOM;
    }

    static Style[] concreteStyles() {
        Style[] copy = new Style[CONCRETE_STYLES.length];
        System.arraycopy(CONCRETE_STYLES, 0, copy, 0, CONCRETE_STYLES.length);
        return copy;
    }

    /** Resolves RANDOM to a concrete style using seed; returns configured concrete style directly. */
    static Style resolveConcreteStyle(Style configured, long seed) {
        if (configured != null && configured != Style.RANDOM) return configured;
        long mixed = seed ^ (seed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= (mixed >>> 33);
        int idx = (int) (Math.abs(mixed) % CONCRETE_STYLES.length);
        return CONCRETE_STYLES[idx];
    }

    /** Picker labels: [0]=Random, [1]=Divider, [2..7]=6 concrete styles. */
    static String[] pickerLabels() {
        String[] out = new String[2 + CONCRETE_STYLES.length];
        out[0] = Style.RANDOM.label;
        out[1] = DIVIDER_LABEL;
        for (int i = 0; i < CONCRETE_STYLES.length; i++) {
            out[2 + i] = CONCRETE_STYLES[i].label;
        }
        return out;
    }

    static int pickerIndex(Style style) {
        Style s = style == null ? Style.RANDOM : style;
        if (s == Style.RANDOM) return 0;
        for (int i = 0; i < CONCRETE_STYLES.length; i++) {
            if (CONCRETE_STYLES[i] == s) return 2 + i;
        }
        return 0;
    }

    /** Returns null when the non-selectable divider row (index 1) is tapped. */
    static Style styleForPickerIndex(int index) {
        if (index == 0) return Style.RANDOM;
        if (index >= 2 && index < 2 + CONCRETE_STYLES.length) {
            return CONCRETE_STYLES[index - 2];
        }
        return null;
    }

    static String formatTimingSummary(int delaySec, int dimSec) {
        int d = clampDelaySec(delaySec);
        int dim = clampDimSec(dimSec);
        if (d <= 0) return "Disabled (0s)";
        if (dim <= 0) return d + "s -> screensaver (no auto-dim)";
        return d + "s -> screensaver -> dim after " + dim + "s";
    }

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
        return evaluate(nowMs, enabled ? DEFAULT_DELAY_SEC : 0, DEFAULT_DIM_SEC);
    }

    Stage evaluate(long nowMs, int delaySec, int dimSec) {
        int safeDelay = clampDelaySec(delaySec);
        int safeDim = clampDimSec(dimSec);
        if (safeDelay <= 0) {
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
        long delayMs = safeDelay * 1000L;
        long dimMs = safeDim * 1000L;
        if (stage == Stage.ACTIVE && elapsed >= delayMs) {
            stage = Stage.SCREENSAVER;
            stageStartMs = nowMs;
        } else if (stage == Stage.SCREENSAVER && dimMs > 0L && elapsed >= dimMs) {
            stage = Stage.FULL_DIM;
            stageStartMs = nowMs;
        }
        return stage;
    }

    long remainingToDimMs(long nowMs) {
        return remainingToDimMs(nowMs, DEFAULT_DIM_SEC);
    }

    long remainingToDimMs(long nowMs, int dimSec) {
        int safeDim = clampDimSec(dimSec);
        if (safeDim <= 0) return -1L;
        long dimMs = safeDim * 1000L;
        if (stage == Stage.FULL_DIM) return 0L;
        if (stage != Stage.SCREENSAVER || stageStartMs < 0L || nowMs < stageStartMs) {
            return dimMs;
        }
        long rem = dimMs - (nowMs - stageStartMs);
        return rem < 0L ? 0L : rem;
    }

    long remainingToSaverMs(long nowMs) {
        return remainingToSaverMs(nowMs, DEFAULT_DELAY_SEC);
    }

    long remainingToSaverMs(long nowMs, int delaySec) {
        int safeDelay = clampDelaySec(delaySec);
        if (safeDelay <= 0) return -1L;
        long delayMs = safeDelay * 1000L;
        if (stage != Stage.ACTIVE) return 0L;
        if (stageStartMs < 0L || nowMs < stageStartMs) return delayMs;
        long rem = delayMs - (nowMs - stageStartMs);
        return rem < 0L ? 0L : rem;
    }

    static float computeAlpha(long saverElapsedMs) {
        return computeAlpha(saverElapsedMs, SAVER_TO_DIM_MS);
    }

    static float computeAlpha(long saverElapsedMs, long saverToDimMs) {
        if (saverToDimMs <= 0L) return 1.0f;
        if (saverElapsedMs < 0L) return 1.0f;
        if (saverElapsedMs >= saverToDimMs) return 0.0f;
        long fade = Math.min(FADE_WINDOW_MS, saverToDimMs / 2L);
        if (fade <= 0L) return 1.0f;
        long fadeStart = saverToDimMs - fade;
        if (saverElapsedMs <= fadeStart) return 1.0f;
        return (float) (saverToDimMs - saverElapsedMs) / (float) fade;
    }

    static String formatCountdown(long remainingMs) {
        if (remainingMs < 0L) return "OFF";
        long sec = remainingMs <= 0L ? 0L : (remainingMs + 999L) / 1000L;
        long min = sec / 60L;
        long remSec = sec % 60L;
        return (min < 10 ? "0" : "") + min + ":" + (remSec < 10 ? "0" : "") + remSec;
    }
}
