package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 2026-07-14 — A5 Landscape mode behind Debug → Experiments (off by default).
 * Layman: turn on sideways Y1-style menus scaled to the tall phone's short edge (240p).
 * Tech: pref + forces a5_orientation landscape/portrait so chrome/metrics follow.
 * Reversal: drop helper; Device → orientation alone; or always return false from isEnabled.
 */
public final class A5LandscapeExperiment {

    public static final String PREF_A5_LANDSCAPE_EXPERIMENT = "solar_a5_landscape_experiment";
    private static final String PREFS = "SOLAR_SETTINGS";

    private A5LandscapeExperiment() {}

    /** Row only on A5 — other families stay Y1/Y2 landscape always. */
    public static boolean isAvailable() {
        return DeviceFeatures.isA5();
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_A5_LANDSCAPE_EXPERIMENT, false);
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        return isEnabled(PrefsWriteAudit.get(ctx, PREFS));
    }

    /**
     * 2026-07-14 — Persist experiment and sync orientation (land On / portrait Off).
     * Was: Device orientation cycle only. Now: one Clear On/Off for scaled Y1 landscape.
     * Reversal: write pref alone; leave a5_orientation untouched.
     */
    public static void setEnabled(Context ctx, boolean enable) {
        if (ctx == null) return;
        PrefsWriteAudit.get(ctx, PREFS)
                .edit()
                .putBoolean(PREF_A5_LANDSCAPE_EXPERIMENT, enable)
                .apply();
        // Sync Device orientation so portrait chrome / LandscapeOrientationGuard match.
        A5NavigationMode.setOrientation(ctx,
                enable ? A5NavigationMode.ORIENT_LANDSCAPE : A5NavigationMode.ORIENT_PORTRAIT);
    }

    /** Test hook — pref value only. */
    static boolean isEnabledForTest(boolean experimentPref) {
        return experimentPref;
    }
}
