package com.solar.launcher.youtube;

import com.solar.launcher.PrefsWriteAudit;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.media.MediaSuiteHost;

/**
 * 2026-07-14 — YouTube kill switch (default on). Off hides Videos → YouTube + Music → YouTube Audio.
 * Layman: YouTube is on by default; Debug can turn the rows off for testing.
 * Technical: was experiment default-false; now default-true soft gate on hubs + screens.
 * Reversal: change default in {@link #isEnabled(SharedPreferences)} back to false.
 */
public final class YouTubeExperiment {

    public static final String PREF_YOUTUBE_EXPERIMENT = "solar_youtube_experiment";
    private static final String PREFS = "SOLAR_SETTINGS";

    private YouTubeExperiment() {}

    /**
     * 2026-07-14 — Default on when pref never written (containsKey missing → true).
     * Explicit false still hides the hub for field debugging.
     */
    public static boolean isEnabled(SharedPreferences prefs) {
        if (prefs == null) return true;
        if (!prefs.contains(PREF_YOUTUBE_EXPERIMENT)) return true;
        return prefs.getBoolean(PREF_YOUTUBE_EXPERIMENT, true);
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return true;
        return isEnabled(PrefsWriteAudit.get(ctx, PREFS));
    }

    public static void setEnabled(Context ctx, boolean enable) {
        if (ctx == null) return;
        PrefsWriteAudit.get(ctx, PREFS)
                .edit()
                .putBoolean(PREF_YOUTUBE_EXPERIMENT, enable)
                .apply();
    }

    /** Block YouTube browse/detail when kill switch off. */
    public static boolean isBlockedScreenState(int state, SharedPreferences prefs) {
        if (isEnabled(prefs)) return false;
        return state == MediaSuiteHost.STATE_YOUTUBE_BROWSE
                || state == MediaSuiteHost.STATE_YOUTUBE_DETAIL;
    }

    /**
     * Test hook — mirrors SharedPreferences semantics: missing pref → on.
     * @param experimentPref null means never written; else explicit boolean
     */
    static boolean isEnabledForTest(Boolean experimentPref) {
        if (experimentPref == null) return true;
        return experimentPref.booleanValue();
    }
}
