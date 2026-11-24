package com.github.ma1co.openmemories.tweak;

final class BootRecoveryPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        BootRecoveryPolicy p = new BootRecoveryPolicy();
        expect(BootRecoveryPolicy.isBootAction(BootRecoveryPolicy.ACTION_ANDROID_BOOT), "accepts Android BOOT_COMPLETED");
        expect(BootRecoveryPolicy.isBootAction(BootRecoveryPolicy.ACTION_SONY_BOOT), "accepts Sony BootCompleted");
        expect(!BootRecoveryPolicy.isBootAction("android.intent.action.TIME_TICK"), "rejects unrelated action");
        expect(!p.shouldTrigger(BootRecoveryPolicy.ACTION_SONY_BOOT, false), "disabled does not trigger");
        expect(p.shouldTrigger(BootRecoveryPolicy.ACTION_SONY_BOOT, true), "first Sony boot triggers");
        expect(!p.shouldTrigger(BootRecoveryPolicy.ACTION_ANDROID_BOOT, true), "duplicate broadcast deduped");
        p.resetForTests();
        expect(p.shouldTrigger(BootRecoveryPolicy.ACTION_ANDROID_BOOT, true), "triggers again after reset");
        System.out.println("PASS BootRecoveryPolicyTests: " + assertions + " assertions");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
