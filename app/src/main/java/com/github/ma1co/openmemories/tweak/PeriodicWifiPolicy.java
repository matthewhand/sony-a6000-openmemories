package com.github.ma1co.openmemories.tweak;

/** Device-independent timing and ordering of a single OFF-acknowledged Wi-Fi watchdog. */
final class PeriodicWifiPolicy {
    static final long CHECK_MS = 300000L;
    static final long FIRST_CYCLE_MS = 20000L;
    static final long OFF_POLL_MS = 500L;
    static final long OFF_TIMEOUT_MS = 5000L;
    static final long OFF_DELAY_MS = 1000L;
    static final long SETTLE_MS = 5000L;
    static final long ON_RETRY_MS = 10000L;
    static final long STOP = -1L;
    static final int ATTEMPT_LIMIT = 3;

    interface Device {
        Journal readJournal();
        boolean saveJournal(Journal journal);
        boolean readWifiEnabled();
        boolean readWifiDisabled();
        void requestWifi(boolean enabled);
    }

    static final class Journal {
        final boolean armed;
        final int armGeneration;
        final int offAttempts;
        final boolean pendingOn;
        final int onAttempts;

        Journal(boolean armed, int armGeneration, int offAttempts, boolean pendingOn, int onAttempts) {
            this.armed = armed;
            this.armGeneration = armGeneration;
            this.offAttempts = offAttempts;
            this.pendingOn = pendingOn;
            this.onAttempts = onAttempts;
        }

        Journal withArmed(boolean enable) {
            int count = enable && !armed && !pendingOn ? 0 : offAttempts;
            int generation = enable && !armed ? armGeneration + 1 : armGeneration;
            return new Journal(enable, generation, count, pendingOn, onAttempts);
        }
    }

    private static final long UNSET = -1L;
    private long previousSample = UNSET;
    private long checkFrom = UNSET;
    private int previousArmGeneration;
    private long pendingSince = UNSET;
    private long offObservedAt = UNSET;
    private long lastOn = UNSET;
    private boolean startedOffHere;

    long tick(long now, Device device) {
        if (previousSample != UNSET && now < previousSample) {
            checkFrom = now;
            clearRestoreTiming();
        }
        previousSample = now;

        Journal journal = device.readJournal();
        if (journal.armGeneration != previousArmGeneration) {
            checkFrom = UNSET;
            previousArmGeneration = journal.armGeneration;
        }
        if (journal.pendingOn) {
            return restore(now, device, journal);
        }
        clearRestoreTiming();
        if (!journal.armed) {
            checkFrom = UNSET;
            return STOP;
        }

        long interval = journal.offAttempts == 0 ? FIRST_CYCLE_MS : CHECK_MS;
        if (checkFrom == UNSET) checkFrom = now;
        if (now - checkFrom < interval) {
            return interval - (now - checkFrom);
        }

        int cycles = journal.offAttempts == Integer.MAX_VALUE
                ? journal.offAttempts : journal.offAttempts + 1;
        Journal pending = new Journal(true, journal.armGeneration, cycles, true, 0);
        if (!device.saveJournal(pending)) {
            return ON_RETRY_MS;
        }

        checkFrom = now;
        pendingSince = now;
        offObservedAt = UNSET;
        startedOffHere = true;
        device.requestWifi(false);
        return OFF_POLL_MS;
    }

    private long restore(long now, Device device, Journal journal) {
        if (pendingSince == UNSET) {
            pendingSince = now;
        }

        if (lastOn == UNSET) {
            if (startedOffHere) {
                if (offObservedAt == UNSET) {
                    if (device.readWifiDisabled()) {
                        offObservedAt = now;
                        return OFF_DELAY_MS;
                    }
                    long waited = now - pendingSince;
                    if (waited < OFF_TIMEOUT_MS) {
                        return Math.min(OFF_POLL_MS, OFF_TIMEOUT_MS - waited);
                    }
                } else {
                    long held = now - offObservedAt;
                    if (held < OFF_DELAY_MS) {
                        return OFF_DELAY_MS - held;
                    }
                }
            } else {
                long grace = journal.onAttempts == 0 ? SETTLE_MS : ON_RETRY_MS;
                if (now - pendingSince < grace) {
                    return grace - (now - pendingSince);
                }
                if (journal.onAttempts >= ATTEMPT_LIMIT && device.readWifiEnabled()) {
                    return finishRestore(now, device, journal);
                }
            }
        } else {
            long elapsed = now - lastOn;
            if (elapsed < SETTLE_MS) {
                return SETTLE_MS - elapsed;
            }
            if (device.readWifiEnabled()) {
                return finishRestore(now, device, journal);
            }
            if (elapsed < ON_RETRY_MS) {
                return ON_RETRY_MS - elapsed;
            }
        }

        if (journal.onAttempts >= ATTEMPT_LIMIT) {
            return ON_RETRY_MS;
        }
        Journal attempted = new Journal(journal.armed, journal.armGeneration,
                journal.offAttempts, true, journal.onAttempts + 1);
        if (!device.saveJournal(attempted)) {
            return ON_RETRY_MS;
        }
        lastOn = now;
        device.requestWifi(true);
        return SETTLE_MS;
    }

    private long finishRestore(long now, Device device, Journal journal) {
        if (!device.saveJournal(new Journal(journal.armed, journal.armGeneration,
                journal.offAttempts, false, 0))) {
            return ON_RETRY_MS;
        }
        clearRestoreTiming();
        if (checkFrom == UNSET) checkFrom = now;
        return journal.armed ? Math.max(0L, CHECK_MS - (now - checkFrom)) : STOP;
    }

    private void clearRestoreTiming() {
        pendingSince = UNSET;
        offObservedAt = UNSET;
        lastOn = UNSET;
        startedOffHere = false;
    }

    static boolean shouldStartOnWifiEnabled(
            boolean wifiEnabledEvent, boolean serviceActiveOrStarting, Journal journal) {
        if (!wifiEnabledEvent || serviceActiveOrStarting || journal == null) {
            return false;
        }
        return journal.armed || journal.pendingOn;
    }
}
