package com.github.ma1co.openmemories.tweak;

import java.util.Arrays;

final class MotionDetectorPolicyTests {
    private static int assertions;

    public static void main(String[] args) {
        hysteresisAndCooldown();
        bt601DownsampleAccuracy();
        System.out.println("PASS MotionDetectorPolicyTests: " + assertions + " assertions");
    }

    private static void hysteresisAndCooldown() {
        MotionDetectorPolicy det = new MotionDetectorPolicy(22, 0.04f, 2, 3000L);
        byte[] f0 = new byte[100];
        Arrays.fill(f0, (byte) 40);
        MotionDetectorPolicy.Evaluation e0 = det.update(f0, 10, 10, 1000L);
        expect(!e0.frameMoved && !e0.wakeTriggered, "baseline frame never fires");

        MotionDetectorPolicy.Evaluation e1 = det.update(f0, 10, 10, 1500L);
        expect(!e1.frameMoved && !e1.wakeTriggered, "identical frame is still");

        byte[] f2 = new byte[100];
        Arrays.fill(f2, (byte) 40);
        for (int i = 0; i < 10; i++) f2[i] = (byte) 120;
        MotionDetectorPolicy.Evaluation e2 = det.update(f2, 10, 10, 2000L);
        expect(e2.frameMoved && !e2.wakeTriggered, "single-frame spike blocked by hysteresis");

        byte[] f3 = new byte[100];
        Arrays.fill(f3, (byte) 40);
        for (int i = 10; i < 20; i++) f3[i] = (byte) 130;
        MotionDetectorPolicy.Evaluation e3 = det.update(f3, 10, 10, 2500L);
        expect(e3.frameMoved && e3.wakeTriggered, "second consecutive motion frame wakes");
        expect(det.motionEvents() == 1, "first motion event counted");

        byte[] f4 = new byte[100];
        Arrays.fill(f4, (byte) 40);
        for (int i = 20; i < 30; i++) f4[i] = (byte) 140;
        MotionDetectorPolicy.Evaluation e4 = det.update(f4, 10, 10, 3000L);
        expect(e4.frameMoved && !e4.wakeTriggered, "cooldown suppresses immediate re-wake");

        byte[] f5 = new byte[100];
        Arrays.fill(f5, (byte) 40);
        for (int i = 30; i < 40; i++) f5[i] = (byte) 150;
        MotionDetectorPolicy.Evaluation e5 = det.update(f5, 10, 10, 6000L);
        expect(e5.frameMoved && e5.wakeTriggered, "wakes again after cooldown");
        expect(det.motionEvents() == 2, "second motion event counted");
    }

    private static void bt601DownsampleAccuracy() {
        int[] argb = new int[] { 0xFF00FF00, 0xFFFF0000, 0xFF0000FF, 0xFFFFFFFF };
        byte[] gray = new byte[4];
        MotionDetectorPolicy.downsampleArgbToGray(argb, 2, 2, gray, 2, 2);
        expect((gray[0] & 0xFF) == 149, "BT.601 green luma");
        expect((gray[1] & 0xFF) == 76, "BT.601 red luma");
        expect((gray[2] & 0xFF) == 28, "BT.601 blue luma");
        expect((gray[3] & 0xFF) == 255, "BT.601 white luma");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
