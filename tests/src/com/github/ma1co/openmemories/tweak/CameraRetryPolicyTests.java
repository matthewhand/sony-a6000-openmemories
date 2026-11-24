package com.github.ma1co.openmemories.tweak;

final class CameraRetryPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        // Cold start / invalid timestamps need zero cooldown
        expect(CameraRetryPolicy.reopenCooldownMs(1000L, 0L) == 0L, "cold start has zero cooldown");
        expect(CameraRetryPolicy.reopenCooldownMs(1000L, -5L) == 0L, "negative stop time has zero cooldown");
        expect(CameraRetryPolicy.reopenCooldownMs(1000L, 1200L) == 0L, "clock skew returns zero cooldown");

        // Rapid pause->resume waits remaining cooldown window
        expect(CameraRetryPolicy.reopenCooldownMs(1000L, 1000L) == CameraRetryPolicy.REOPEN_COOLDOWN_MS,
                "same-tick resume waits full cooldown");
        expect(CameraRetryPolicy.reopenCooldownMs(1150L, 1000L) == CameraRetryPolicy.REOPEN_COOLDOWN_MS - 150L,
                "partial elapsed time waits remainder");
        expect(CameraRetryPolicy.reopenCooldownMs(1000L + CameraRetryPolicy.REOPEN_COOLDOWN_MS, 1000L) == 0L,
                "exact boundary needs no cooldown");
        expect(CameraRetryPolicy.reopenCooldownMs(5000L, 1000L) == 0L,
                "long elapsed needs no cooldown");

        // Bounded backoff retries
        expect(CameraRetryPolicy.MAX_RETRIES == 4, "four bounded retries configured");
        expect(CameraRetryPolicy.retryDelayMs(-1) == -1L, "negative attempt rejected");
        long prev = 0L;
        for (int i = 0; i < CameraRetryPolicy.MAX_RETRIES; i++) {
            long delay = CameraRetryPolicy.retryDelayMs(i);
            expect(delay > prev, "retry delay strictly increases for attempt " + i);
            prev = delay;
        }
        expect(CameraRetryPolicy.retryDelayMs(CameraRetryPolicy.MAX_RETRIES) == -1L,
                "exhausted retry returns -1");
        expect(CameraRetryPolicy.retryDelayMs(99) == -1L, "far out-of-range retry returns -1");

        System.out.println("PASS CameraRetryPolicyTests: " + assertions + " assertions");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
