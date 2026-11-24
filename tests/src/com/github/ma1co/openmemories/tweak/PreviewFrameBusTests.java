package com.github.ma1co.openmemories.tweak;

final class PreviewFrameBusTests {
    private static int assertions;

    public static void main(String[] args) {
        PreviewFrameBus bus = new PreviewFrameBus();
        expect(!PreviewFrameBus.isValidJpeg(new byte[] { 0, 1, 2, 3 }), "rejects non-JPEG");
        expect(!bus.publish(new byte[] { 0, 1, 2, 3 }, 1000L), "refuses non-JPEG publish");

        byte[] padded = new byte[] { (byte) 0xFF, (byte) 0xD8, 0x11, 0x22, (byte) 0xFF, (byte) 0xD9, 0x00, 0x00 };
        expect(PreviewFrameBus.isValidJpeg(padded), "accepts JPEG with trailing zero padding");
        expect(bus.publish(padded, 1000L), "publishes padded JPEG");
        PreviewFrameBus.Frame f = bus.latestIfFresh(2000L, 5000L);
        expect(f != null && f.jpeg.length == 6, "trims trailing padding cleanly");
        expect((f.jpeg[5] & 0xFF) == 0xD9, "ends at EOI");
        expect(bus.latestIfFresh(6000L, 5000L) == null, "stale at exact boundary");

        CameraOptionPresets.Preset[] presets = CameraOptionPresets.ordered();
        expect(presets.length >= 4 && presets[0].width == 640 && presets[0].height == 0 && presets[0].frameRateMilliFps == 30000,
                "first preset uses 640x0 30000 milli-fps");
        System.out.println("PASS PreviewFrameBusTests: " + assertions + " assertions");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
