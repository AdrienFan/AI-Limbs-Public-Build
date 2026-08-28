package com.ailimbs.netmutualprobe;

import android.content.Context;
import android.content.SharedPreferences;

final class ProbeStore {
    private static final String PREFS = "probe_state";
    private ProbeStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static long inc(Context c, String key) {
        SharedPreferences p = prefs(c);
        long value = p.getLong(key, 0L) + 1L;
        p.edit().putLong(key, value).apply();
        return value;
    }

    static void putLong(Context c, String key, long value) {
        prefs(c).edit().putLong(key, value).apply();
    }

    static void putString(Context c, String key, String value) {
        prefs(c).edit().putString(key, value == null ? "" : value).apply();
    }

    static long getLong(Context c, String key) {
        return prefs(c).getLong(key, 0L);
    }

    static String getString(Context c, String key) {
        return prefs(c).getString(key, "");
    }

    static void putBoolean(Context c, String key, boolean value) {
        prefs(c).edit().putBoolean(key, value).apply();
    }

    static boolean getBoolean(Context c, String key) {
        return prefs(c).getBoolean(key, false);
    }
}
