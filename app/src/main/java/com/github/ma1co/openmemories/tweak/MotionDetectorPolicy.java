package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java lightweight frame-difference motion detector using integer BT.601 luma
 * ((77*r + 150*g + 29*b) >> 8), per-pixel delta threshold, changed-pixel fraction,
 * consecutive-hit hysteresis, and wake cooldown.
 */
final class MotionDetectorPolicy {
    static final int DEFAULT_THRESHOLD = 22;
    static final float DEFAULT_CHANGED_FRACTION = 0.04f;
    static final int DEFAULT_MIN_CONSECUTIVE_HITS = 2;
    static final long DEFAULT_COOLDOWN_MS = 3000L;

    private final int threshold;
    private final float changedFraction;
    private final int minConsecutiveHits;
    private final long cooldownMs;

    private byte[] previous;
    private int consecutiveHits;
    private long lastWakeMs = -1L;
    private float lastChangedRatio;
    private int motionEvents;

    MotionDetectorPolicy() {
        this(DEFAULT_THRESHOLD, DEFAULT_CHANGED_FRACTION, DEFAULT_MIN_CONSECUTIVE_HITS, DEFAULT_COOLDOWN_MS);
    }

    MotionDetectorPolicy(int threshold, float changedFraction, int minConsecutiveHits, long cooldownMs) {
        if (threshold < 0 || threshold > 255) throw new IllegalArgumentException("threshold must be in [0,255]");
        if (changedFraction <= 0.0f || changedFraction > 1.0f) throw new IllegalArgumentException("changedFraction must be in (0,1]");
        if (minConsecutiveHits < 1) throw new IllegalArgumentException("minConsecutiveHits must be >= 1");
        if (cooldownMs < 0L) throw new IllegalArgumentException("cooldownMs must be >= 0");
        this.threshold = threshold;
        this.changedFraction = changedFraction;
        this.minConsecutiveHits = minConsecutiveHits;
        this.cooldownMs = cooldownMs;
    }

    static final class Evaluation {
        final boolean frameMoved;
        final boolean wakeTriggered;
        final float changedRatio;

        Evaluation(boolean frameMoved, boolean wakeTriggered, float changedRatio) {
            this.frameMoved = frameMoved;
            this.wakeTriggered = wakeTriggered;
            this.changedRatio = changedRatio;
        }
    }

    synchronized Evaluation update(byte[] gray, int width, int height, long nowMs) {
        if (gray == null || width <= 0 || height <= 0 || gray.length < width * height) {
            throw new IllegalArgumentException("gray frame length must be >= width*height");
        }
        int total = width * height;
        if (previous == null || previous.length != total) {
            previous = new byte[total];
            System.arraycopy(gray, 0, previous, 0, total);
            consecutiveHits = 0;
            lastChangedRatio = 0.0f;
            return new Evaluation(false, false, 0.0f);
        }

        int changed = 0;
        for (int i = 0; i < total; i++) {
            int a = gray[i] & 0xFF;
            int b = previous[i] & 0xFF;
            int diff = a >= b ? (a - b) : (b - a);
            if (diff >= threshold) changed++;
            previous[i] = gray[i];
        }

        float ratio = (float) changed / (float) total;
        lastChangedRatio = ratio;
        boolean moved = ratio >= changedFraction;
        boolean wake = false;
        if (moved) {
            consecutiveHits++;
            boolean cooldownOk = (lastWakeMs < 0L) || (nowMs < lastWakeMs) || ((nowMs - lastWakeMs) >= cooldownMs);
            if (consecutiveHits >= minConsecutiveHits && cooldownOk) {
                wake = true;
                lastWakeMs = nowMs;
                motionEvents++;
            }
        } else {
            consecutiveHits = 0;
        }
        return new Evaluation(moved, wake, ratio);
    }

    synchronized void reset() {
        previous = null;
        consecutiveHits = 0;
        lastChangedRatio = 0.0f;
    }

    synchronized float lastChangedRatio() { return lastChangedRatio; }
    synchronized int motionEvents() { return motionEvents; }

    static void downsampleArgbToGray(int[] argb, int srcW, int srcH, byte[] out, int outW, int outH) {
        if (argb == null || out == null || srcW <= 0 || srcH <= 0 || outW <= 0 || outH <= 0) {
            throw new IllegalArgumentException("dimensions and buffers must be valid");
        }
        if (argb.length < srcW * srcH || out.length < outW * outH) {
            throw new IllegalArgumentException("buffer too small");
        }
        for (int oy = 0; oy < outH; oy++) {
            int sy = (oy * srcH) / outH;
            if (sy >= srcH) sy = srcH - 1;
            int rowOff = sy * srcW;
            int outRow = oy * outW;
            for (int ox = 0; ox < outW; ox++) {
                int sx = (ox * srcW) / outW;
                if (sx >= srcW) sx = srcW - 1;
                int px = argb[rowOff + sx];
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                out[outRow + ox] = (byte) ((77 * r + 150 * g + 29 * b) >> 8);
            }
        }
    }
}
