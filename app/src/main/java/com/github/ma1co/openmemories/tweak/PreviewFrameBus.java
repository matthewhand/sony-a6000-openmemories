package com.github.ma1co.openmemories.tweak;

/**
 * Pure-Java thread-safe latest-JPEG frame bus for on-camera MJPEG streaming and snapshots.
 * Validates SOI/EOI markers, trims trailing Sony padding bytes, tracks rolling FPS, and notifies clients.
 */
final class PreviewFrameBus {
    static final int MAX_FRAME_BYTES = 512 * 1024;
    static final long DEFAULT_FRESH_MS = 5000L;

    static final class Frame {
        final long seq;
        final long timestampMs;
        final byte[] jpeg;

        Frame(long seq, long timestampMs, byte[] jpeg) {
            this.seq = seq;
            this.timestampMs = timestampMs;
            this.jpeg = jpeg;
        }
    }

    private final Object lock = new Object();
    private Frame latest;
    private long seq;
    private boolean closed;
    private String cameraState = "STARTING";
    private String cameraError = "";
    private String activePreset = "";

    private long fpsWindowStartMs = -1L;
    private int fpsWindowFrames;
    private float currentFps;

    static byte[] trimTrailingPadding(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_FRAME_BYTES) return null;
        if ((bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) return null;
        int end = bytes.length - 1;
        if ((bytes[end - 1] & 0xFF) == 0xFF && (bytes[end] & 0xFF) == 0xD9) return bytes;
        int floor = end - 16;
        if (floor < 2) floor = 2;
        for (int i = end - 1; i >= floor; i--) {
            if ((bytes[i] & 0xFF) == 0xD9 && (bytes[i - 1] & 0xFF) == 0xFF) {
                int cleanLen = i + 1;
                byte[] trimmed = new byte[cleanLen];
                System.arraycopy(bytes, 0, trimmed, 0, cleanLen);
                return trimmed;
            }
        }
        return null;
    }

    static boolean isValidJpeg(byte[] bytes) {
        return trimTrailingPadding(bytes) != null;
    }

    boolean publish(byte[] jpeg, long nowMs) {
        byte[] clean = trimTrailingPadding(jpeg);
        if (clean == null) return false;
        synchronized (lock) {
            if (closed) return false;
            seq++;
            latest = new Frame(seq, nowMs, clean);
            cameraState = "STREAMING";
            cameraError = "";
            if (fpsWindowStartMs < 0L || nowMs < fpsWindowStartMs) {
                fpsWindowStartMs = nowMs;
                fpsWindowFrames = 1;
            } else {
                fpsWindowFrames++;
                long span = nowMs - fpsWindowStartMs;
                if (span >= 1000L) {
                    currentFps = (fpsWindowFrames * 1000.0f) / (float) span;
                    fpsWindowStartMs = nowMs;
                    fpsWindowFrames = 0;
                }
            }
            lock.notifyAll();
            return true;
        }
    }

    void setCameraState(String state, String error, String preset) {
        synchronized (lock) {
            this.cameraState = state == null ? "UNKNOWN" : state;
            this.cameraError = error == null ? "" : error;
            if (preset != null) this.activePreset = preset;
            lock.notifyAll();
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            cameraState = "STOPPED";
            lock.notifyAll();
        }
    }

    void reopen() {
        synchronized (lock) {
            closed = false;
            cameraState = "STARTING";
            cameraError = "";
        }
    }

    boolean isClosed() {
        synchronized (lock) { return closed; }
    }

    Frame latestIfFresh(long nowMs, long maxAgeMs) {
        synchronized (lock) {
            if (closed || latest == null) return null;
            if (nowMs < latest.timestampMs) return null;
            if ((nowMs - latest.timestampMs) >= maxAgeMs) return null;
            return latest;
        }
    }

    Frame waitForNextFrame(long lastSeq, long timeoutMs) {
        long deadlineNs = System.nanoTime() + (timeoutMs > 0L ? timeoutMs * 1000000L : 0L);
        synchronized (lock) {
            while (!closed) {
                if (latest != null && latest.seq > lastSeq) return latest;
                long remNs = deadlineNs - System.nanoTime();
                if (remNs <= 0L) return null;
                long remMs = (remNs + 999999L) / 1000000L;
                try {
                    lock.wait(remMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }

    long frameCount() { synchronized (lock) { return seq; } }

    long frameAgeMs(long nowMs) {
        synchronized (lock) {
            if (latest == null || nowMs < latest.timestampMs) return -1L;
            return nowMs - latest.timestampMs;
        }
    }

    float fps() { synchronized (lock) { return currentFps; } }
    String cameraState() { synchronized (lock) { return cameraState; } }
    String cameraError() { synchronized (lock) { return cameraError; } }
    String activePreset() { synchronized (lock) { return activePreset; } }
}
