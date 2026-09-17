package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

/**
 * 2026-07-14 — Y1/Y2 Portrait mode behind Debug → Experiments (off by default).
 * Layman: stand the player upright like an iPod nano — tall menus, remapped side keys.
 * Tech: tristate mode off|right|left; {@link LandscapeOrientationGuard} locks reverse or normal portrait.
 * Default On = wheel on the right (reverse portrait). Left-handed = former PORTRAIT (wheel left).
 * Reversal: drop helper; always landscape via guard; or return MODE_OFF from mode().
 */
public final class Y1PortraitExperiment {

    /** Legacy boolean — true migrates to left-handed (old SCREEN_ORIENTATION_PORTRAIT). */
    public static final String PREF_Y1_PORTRAIT_EXPERIMENT = "solar_y1_portrait_experiment";
    /** off | right | left — preferred store after 2026-07-14 handedness. */
    public static final String PREF_Y1_PORTRAIT_MODE = "solar_y1_portrait_mode";

    public static final String MODE_OFF = "off";
    /** Wheel to the right of the tall screen — default when enabling. */
    public static final String MODE_RIGHT = "right";
    /** Wheel to the left — previous portrait experiment orientation. */
    public static final String MODE_LEFT = "left";

    private static final String PREFS = "SOLAR_SETTINGS";

    private Y1PortraitExperiment() {}

    /** Row only on Y1/Y2 — A5 already has its own tall/sideways experiment. */
    public static boolean isAvailable() {
        return DeviceFeatures.isY1() || DeviceFeatures.isY2();
    }

    /**
     * 2026-07-14 — Resolved mode: off, right (wheel right), or left (wheel left).
     * Layman: Off = sideways; Right = default tall; Left = left-handed tall.
     * Tech: string pref; legacy boolean true → left; missing → off.
     */
    public static String mode(SharedPreferences prefs) {
        if (prefs == null) return MODE_OFF;
        String v = prefs.getString(PREF_Y1_PORTRAIT_MODE, null);
        if (MODE_RIGHT.equals(v) || MODE_LEFT.equals(v) || MODE_OFF.equals(v)) return v;
        // Legacy On → left-handed so existing users keep the old rotate.
        if (prefs.getBoolean(PREF_Y1_PORTRAIT_EXPERIMENT, false)) return MODE_LEFT;
        return MODE_OFF;
    }

    public static String mode(Context ctx) {
        if (ctx == null || !isAvailable()) return MODE_OFF;
        return mode(PrefsWriteAudit.get(ctx, PREFS));
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        String m = mode(prefs);
        return MODE_RIGHT.equals(m) || MODE_LEFT.equals(m);
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null || !isAvailable()) return false;
        return isEnabled(PrefsWriteAudit.get(ctx, PREFS));
    }

    /** True when wheel sits left of the tall display (former PORTRAIT). */
    public static boolean isLeftHanded(Context ctx) {
        return MODE_LEFT.equals(mode(ctx));
    }

    /** True when wheel sits right of the tall display (default On). */
    public static boolean isRightHanded(Context ctx) {
        return MODE_RIGHT.equals(mode(ctx));
    }

    /**
     * 2026-07-14 — ActivityInfo lock for current handedness.
     * Layman: right = flip the other way so the dial is on the right; left = old upright.
     * Tech: REVERSE_PORTRAIT vs PORTRAIT. Off callers use landscape.
     * Reversal: always SCREEN_ORIENTATION_PORTRAIT.
     */
    public static int activityOrientation(Context ctx) {
        if (isLeftHanded(ctx)) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        // Default On and any non-left enabled path → reverse (wheel right).
        return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
    }

    /**
     * 2026-07-14 — Persist mode; orientation follows via LandscapeOrientationGuard.
     * Was: boolean On/Off only (always PORTRAIT). Now: right default + left-handed.
     * Reversal: write PREF_Y1_PORTRAIT_EXPERIMENT boolean alone.
     */
    public static void setMode(Context ctx, String mode) {
        if (ctx == null || !isAvailable()) return;
        String m = MODE_RIGHT.equals(mode) || MODE_LEFT.equals(mode) ? mode : MODE_OFF;
        SharedPreferences.Editor ed = PrefsWriteAudit.get(ctx, PREFS).edit();
        ed.putString(PREF_Y1_PORTRAIT_MODE, m);
        // Keep legacy boolean in sync for older readers / dumps.
        ed.putBoolean(PREF_Y1_PORTRAIT_EXPERIMENT, !MODE_OFF.equals(m));
        ed.apply();
    }

    /**
     * 2026-07-14 — Enable/disable helper. On → wheel-right default; Off → landscape.
     * Was: boolean only. Reversal: putBoolean(PREF_Y1_PORTRAIT_EXPERIMENT, enable).
     */
    public static void setEnabled(Context ctx, boolean enable) {
        setMode(ctx, enable ? MODE_RIGHT : MODE_OFF);
    }

    /**
     * 2026-07-14 — Cycle Off → Right → Left → Off for the Settings row.
     * Layman: each click flips landscape / wheel-right / wheel-left.
     */
    public static void cycleMode(Context ctx) {
        String cur = mode(ctx);
        if (MODE_OFF.equals(cur)) setMode(ctx, MODE_RIGHT);
        else if (MODE_RIGHT.equals(cur)) setMode(ctx, MODE_LEFT);
        else setMode(ctx, MODE_OFF);
    }

    /** Test hook — mode string only (ignores family). */
    static String modeForTest(String modePref, boolean legacyBool) {
        if (MODE_RIGHT.equals(modePref) || MODE_LEFT.equals(modePref) || MODE_OFF.equals(modePref)) {
            return modePref;
        }
        if (legacyBool) return MODE_LEFT;
        return MODE_OFF;
    }

    /** Test hook — pref enabled when mode is left or right. */
    static boolean isEnabledForTest(boolean experimentPref) {
        return experimentPref;
    }

    static boolean isEnabledForTest(String modePref, boolean legacyBool) {
        String m = modeForTest(modePref, legacyBool);
        return MODE_RIGHT.equals(m) || MODE_LEFT.equals(m);
    }
}
