package com.github.ma1co.openmemories.tweak;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class ConditionTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        returnsImmediatelyWhenInitiallyTrue();
        pollsUntilConditionBecomesTrue();
        throwsTimeoutWhenNeverTrue();
        System.out.println("PASS ConditionTests: " + assertions + " assertions");
    }

    private static void returnsImmediatelyWhenInitiallyTrue() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        Condition.waitFor(new Condition.Runnable() {
            @Override public boolean run() {
                calls.incrementAndGet();
                return true;
            }
        }, 5, 50);
        expect(calls.get() == 1, "single evaluation when initially true");
    }

    private static void pollsUntilConditionBecomesTrue() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        Condition.waitFor(new Condition.Runnable() {
            @Override public boolean run() {
                return calls.incrementAndGet() >= 3;
            }
        }, 5, 100);
        expect(calls.get() == 3, "polled until third attempt succeeded");
    }

    private static void throwsTimeoutWhenNeverTrue() throws Exception {
        boolean timedOut = false;
        try {
            Condition.waitFor(new Condition.Runnable() {
                @Override public boolean run() { return false; }
            }, 5, 15);
        } catch (TimeoutException expected) {
            timedOut = true;
        }
        expect(timedOut, "throws TimeoutException when condition never holds");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
