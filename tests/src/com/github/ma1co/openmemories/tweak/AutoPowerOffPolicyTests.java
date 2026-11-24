package com.github.ma1co.openmemories.tweak;

final class AutoPowerOffPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        modeComputation();
        deduplicatesRepeatedTransitions();
        resetAllowsRebroadcast();
        System.out.println("PASS AutoPowerOffPolicyTests: " + assertions + " assertions");
    }

    private static void modeComputation() {
        expect(AutoPowerOffPolicy.shouldInhibit(true, true), "inhibits when keep-awake and active");
        expect(!AutoPowerOffPolicy.shouldInhibit(true, false), "does not inhibit when idle");
        expect(!AutoPowerOffPolicy.shouldInhibit(false, true), "does not inhibit when preference off");
        expect(AutoPowerOffPolicy.MODE_DISABLE.equals(AutoPowerOffPolicy.modeFor(true)), "inhibit -> APO/NO");
        expect(AutoPowerOffPolicy.MODE_NORMAL.equals(AutoPowerOffPolicy.modeFor(false)), "normal -> APO/NORMAL");
    }

    private static void deduplicatesRepeatedTransitions() {
        AutoPowerOffPolicy p = new AutoPowerOffPolicy();
        expect(AutoPowerOffPolicy.MODE_DISABLE.equals(p.nextBroadcastMode(true, true)), "first transition sends APO/NO");
        expect(p.nextBroadcastMode(true, true) == null, "repeated identical state suppressed");
        expect(AutoPowerOffPolicy.MODE_NORMAL.equals(p.nextBroadcastMode(false, true)), "disabling sends APO/NORMAL");
        expect(p.nextBroadcastMode(false, false) == null, "already normal suppressed");
    }

    private static void resetAllowsRebroadcast() {
        AutoPowerOffPolicy p = new AutoPowerOffPolicy();
        expect(AutoPowerOffPolicy.MODE_DISABLE.equals(p.nextBroadcastMode(true, true)), "initial APO/NO");
        p.reset();
        expect(p.lastSentMode() == null, "lastSentMode cleared on reset");
        expect(AutoPowerOffPolicy.MODE_DISABLE.equals(p.nextBroadcastMode(true, true)), "re-broadcasts after reset");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
