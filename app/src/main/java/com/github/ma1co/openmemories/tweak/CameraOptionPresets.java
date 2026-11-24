package com.github.ma1co.openmemories.tweak;

/**
 * Ordered CameraSequence.Options presets for Sony PlayMemories cameras (verified on ILCE-6000 V4).
 * PREVIEW_FRAME_RATE is in milli-fps (30000 = 30 fps) and PREVIEW_FRAME_HEIGHT = 0 selects
 * native sensor aspect ratio (640x424 on 3:2 bodies).
 */
final class CameraOptionPresets {
    static final int FORMAT_DEFAULT = 0;
    static final int FORMAT_JPEG = 256;

    static final class Preset {
        final String name;
        final int width;
        final int height;
        final int frameRateMilliFps;
        final int formatOption;
        final boolean includeMemoryMap;

        Preset(String name, int width, int height, int frameRateMilliFps,
               int formatOption, boolean includeMemoryMap) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.frameRateMilliFps = frameRateMilliFps;
            this.formatOption = formatOption;
            this.includeMemoryMap = includeMemoryMap;
        }
    }

    private static final Preset[] PRESETS = new Preset[] {
        new Preset("640x0_30fps_memmap", 640, 0, 30000, FORMAT_DEFAULT, true),
        new Preset("640x0_15fps_memmap", 640, 0, 15000, FORMAT_DEFAULT, true),
        new Preset("640x0_30fps_nomap", 640, 0, 30000, FORMAT_DEFAULT, false),
        new Preset("640x480_15fps_jpeg", 640, 480, 15000, FORMAT_JPEG, true)
    };

    private CameraOptionPresets() {}

    static Preset[] ordered() {
        Preset[] copy = new Preset[PRESETS.length];
        System.arraycopy(PRESETS, 0, copy, 0, PRESETS.length);
        return copy;
    }
}
