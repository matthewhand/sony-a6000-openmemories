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

        // UI presence guard: prevents delayed BootRecoveryService from relaunching over active UI
        expect(p.shouldLaunchUi(), "fresh boot allows UI launch");
        expect(!p.isUiForeground(), "fresh boot has no foreground UI");
        p.markUiOpened();
        expect(!p.shouldLaunchUi(), "opening UI suppresses delayed boot relaunch");
        p.onUiResumed();
        expect(p.isUiForeground(), "resumed UI marked foreground");
        expect(!p.shouldLaunchUi(), "foreground UI suppresses delayed boot relaunch");
        p.onUiResumed();
        p.onUiPaused();
        expect(p.isUiForeground(), "overlapping transition keeps foreground count positive");
        p.onUiPaused();
        expect(!p.isUiForeground(), "all UI paused clears foreground");
        expect(!p.shouldLaunchUi(), "once opened this boot, delayed boot launch stays suppressed");
        p.onUiPaused();
        expect(!p.isUiForeground(), "extra pause does not underflow foreground count");
        p.resetForTests();
        expect(p.shouldLaunchUi(), "reset restores clean boot UI launch state");
        System.out.println("PASS BootRecoveryPolicyTests: " + assertions + " assertions");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
