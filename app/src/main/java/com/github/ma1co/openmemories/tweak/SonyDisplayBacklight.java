package com.github.ma1co.openmemories.tweak;

import android.app.Activity;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Method;

/**
 * Hardware rear-LCD backlight controller using Sony's DisplayManager (DEVICE_ID_NONE / DEVICE_ID_PANEL)
 * with Android WindowManager screenBrightness fallback and guaranteed restoration on wake/pause/destroy.
 */
final class SonyDisplayBacklight {
    private static final String TAG = "OpenMemoriesBacklight";

    private final DisplayBacklightPolicy policy = new DisplayBacklightPolicy();
    private Object displayManager;
    private Method getActiveDeviceMethod;
    private Method switchOutputMethod;
    private Method finishMethod;
    private boolean initialized;

    private void ensureInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> cls = Class.forName("com.sony.scalar.hardware.avio.DisplayManager");
            displayManager = cls.newInstance();
            try { getActiveDeviceMethod = cls.getMethod("getActiveDevice"); } catch (NoSuchMethodException ignored) {}
            switchOutputMethod = cls.getMethod("switchDisplayOutputTo", String.class);
            try { finishMethod = cls.getMethod("finish"); } catch (NoSuchMethodException ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "Sony DisplayManager unavailable: " + t.getClass().getSimpleName());
            displayManager = null;
        }
    }

    boolean isBacklightOff() {
        return policy.isBacklightOff();
    }

    void turnOff(Activity activity, boolean enabled) {
        if (!enabled || policy.isBacklightOff()) return;
        ensureInit();
        String active = readActiveDevice();
        String target = policy.planTurnOff(enabled, active);
        if (target == null) return;

        boolean switched = false;
        if (displayManager != null && switchOutputMethod != null) {
            try {
                switchOutputMethod.invoke(displayManager, target);
                switched = true;
                Log.i(TAG, "Switched Sony DisplayManager output to " + target);
            } catch (Throwable t) {
                Log.w(TAG, "DisplayManager switch to " + target + " failed: " + t.getClass().getSimpleName());
            }
        }
        setWindowBrightness(activity, 0.01f);
        if (switched || activity != null) {
            policy.markTurnedOff();
        }
    }

    void restore(Activity activity) {
        String target = policy.planRestore();
        setWindowBrightness(activity, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        if (target == null) return;

        if (displayManager != null && switchOutputMethod != null) {
            try {
                switchOutputMethod.invoke(displayManager, target);
                Log.i(TAG, "Restored Sony DisplayManager output to " + target);
            } catch (Throwable t) {
                Log.w(TAG, "DisplayManager restore to " + target + " failed: " + t.getClass().getSimpleName());
            }
        }
        policy.markRestored();
    }

    void release(Activity activity) {
        restore(activity);
        if (displayManager != null && finishMethod != null) {
            try { finishMethod.invoke(displayManager); } catch (Throwable ignored) {}
        }
        displayManager = null;
        initialized = false;
    }

    private String readActiveDevice() {
        if (displayManager == null || getActiveDeviceMethod == null) return null;
        try {
            Object val = getActiveDeviceMethod.invoke(displayManager);
            return val == null ? null : String.valueOf(val);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setWindowBrightness(Activity activity, float value) {
        if (activity == null) return;
        try {
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            if (lp.screenBrightness != value) {
                lp.screenBrightness = value;
                activity.getWindow().setAttributes(lp);
            }
        } catch (RuntimeException ignored) {}
    }
}
