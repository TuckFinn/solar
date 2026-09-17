package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 2026-07-11 — Rockbox-Y1 install + HOME switch behind Debug experiment (off by default).
 * Layman: Settings → Debug → Rockbox experiment unlocks install and switch-to-Rockbox.
 * Technical: prefs + sys.solar.rockbox.experiment for companion/Xposed-side gates.
 * Y2 uses Rockbox-Y1 APK with SolarRockboxCompat Xposed module for coexistence.
 * Reversal: always return true from {@link #isEnabled}.
 */
public final class RockboxExperiment {

    public static final String PREF_ROCKBOX_EXPERIMENT = "solar_rockbox_experiment";
    /** Readable from companion process without loading Solar prefs. */
    public static final String SYSPROP_EXPERIMENT = "sys.solar.rockbox.experiment";
    private static final String PREFS = "SOLAR_SETTINGS";

    private RockboxExperiment() {}

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_ROCKBOX_EXPERIMENT, false);
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        return isEnabled(PrefsWriteAudit.get(ctx, PREFS));
    }

    public static void setEnabled(Context ctx, boolean enable) {
        if (ctx == null) return;
        PrefsWriteAudit.get(ctx, PREFS)
                .edit()
                .putBoolean(PREF_ROCKBOX_EXPERIMENT, enable)
                .apply();
        syncSysprop(enable);
    }

    /** Call on boot / settings open so companion launcher picker sees current value. */
    public static void syncSyspropFromPrefs(Context ctx) {
        syncSysprop(isEnabled(ctx));
    }

    private static void syncSysprop(boolean enable) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                    .invoke(null, SYSPROP_EXPERIMENT, enable ? "1" : "0");
        } catch (Throwable ignored) {
            if (RootShell.canRun()) {
                RootShell.run("setprop " + SYSPROP_EXPERIMENT + " " + (enable ? "1" : "0"));
            }
        }
    }

    /** Companion / policy without Context — reads sysprop written by Solar. */
    public static boolean isEnabledFromSysprop() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, SYSPROP_EXPERIMENT, "0");
            return "1".equals(String.valueOf(v));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Test hook — pref only. */
    static boolean isEnabledForTest(boolean experimentPref) {
        return experimentPref;
    }
}
