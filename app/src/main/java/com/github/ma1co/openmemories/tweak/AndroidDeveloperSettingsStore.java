package com.github.ma1co.openmemories.tweak;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin SharedPreferences adapter for DeveloperSettingsPolicy. */
final class AndroidDeveloperSettingsStore implements DeveloperSettingsPolicy.Store {
    private final SharedPreferences prefs;

    AndroidDeveloperSettingsStore(Context context) {
        this.prefs = context.getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override public boolean getBoolean(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    @Override public boolean putBoolean(String key, boolean value) {
        return prefs.edit().putBoolean(key, value).commit();
    }

    @Override public String getString(String key, String def) {
        return prefs.getString(key, def);
    }

    @Override public boolean putString(String key, String value) {
        return prefs.edit().putString(key, value).commit();
    }
}
