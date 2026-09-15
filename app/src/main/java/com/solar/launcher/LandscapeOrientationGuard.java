package com.solar.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.provider.Settings;
import android.util.DisplayMetrics;

/**
 * 2026-07-06 — Keeps Y1/Y2 locked landscape 480×360 after external apps rotate the panel.
 * 2026-07-11 — A5 uses chosen portrait/landscape instead of always-landscape.
 * Layman: puts the screen back sideways when YouTube or others twist it (Y1/Y2);
 *   on A5 respects the tall/sideways setting.
 * Technical: activity lock + user_rotation restore on resume; A5 branches on pref.
 * Reversal: delete; stock rotation after leaving third-party players.
 */
public final class LandscapeOrientationGuard {

    private static final int MIN_LANDSCAPE_WIDTH_PX = 400;

    /**
     * 2026-07-15 — While true, video playback owns landscape lock (portrait experiment / A5 tall ignored).
     * Layman: watching sideways video? turn the player to match; Solar follows.
     * Reversal: leave false; video stays upright under portrait experiment.
     */
    private static volatile boolean forceLandscapeVideoSession;

    private LandscapeOrientationGuard() {}

    /**
     * 2026-07-15 — Enter/exit forced-landscape video watching.
     * Callers: MediaSuiteHost on player enter/exit; size callback may clear if source is portrait.
     */
    public static void setForceLandscapeVideoSession(boolean on) {
        forceLandscapeVideoSession = on;
    }

    /** True while video UI forced the panel sideways. */
    public static boolean isForceLandscapeVideoSession() {
        return forceLandscapeVideoSession;
    }

    /**
     * 2026-07-11 / 2026-07-14 — Apply device-appropriate orientation lock.
     * 2026-07-15 — Forced video session wins over portrait experiment / A5 portrait.
     * Y1/Y2: landscape, or portrait when {@link Y1PortraitExperiment} is On.
     * A5: portrait or landscape from {@link A5NavigationMode}.
     */
    public static void enforceForDevice(Activity activity) {
        if (activity == null) return;
        if (forceLandscapeVideoSession) {
            enforceForcedLandscape(activity);
            return;
        }
        // 2026-07-20 — Phone chrome is a tall ClassiPod shell; lock portrait.
        // Was: misclassified phones got Y1 landscape. Reversal: drop chrome branch.
        try {
            if (com.solar.launcher.phone.PhoneChromePolicy.active(activity)) {
                activity.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                return;
            }
        } catch (Throwable ignored) {}
        if (DeviceFeatures.isA5()) {
            enforceA5Orientation(activity);
            return;
        }
        if (Y1PortraitExperiment.isEnabled(activity)) {
            enforceY1PortraitExperiment(activity);
            return;
        }
        enforceLandscape(activity);
    }

    /**
     * 2026-07-15 — Hard landscape for landscape-source video on tall UI.
     * Layman: ignore portrait prefs while this clip plays.
     * Tech: SCREEN_ORIENTATION_LANDSCAPE + clear stuck user_rotation.
     */
    public static void enforceForcedLandscape(Activity activity) {
        if (activity == null) return;
        try {
            restoreSystemRotation(activity);
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Exception ignored) {}
    }

    /**
     * 2026-07-14 — Y1/Y2 Portrait experiment: lock activity upright (handedness-aware).
     * Layman: Debug Portrait On — stand like a nano; default wheel on the right.
     * Tech: {@link Y1PortraitExperiment#activityOrientation} → REVERSE_PORTRAIT or PORTRAIT.
     * Was: always SCREEN_ORIENTATION_PORTRAIT (wheel left). Reversal: hardcode PORTRAIT.
     */
    public static void enforceY1PortraitExperiment(Activity activity) {
        if (activity == null) return;
        try {
            activity.setRequestedOrientation(Y1PortraitExperiment.activityOrientation(activity));
        } catch (Exception ignored) {}
    }

    /**
     * 2026-07-14 — Force A5 activity to user-chosen portrait, landscape, or sensor auto.
     * Landscape also resets system user_rotation so the panel can leave a stuck tall buffer.
     * Was: setRequestedOrientation only (Y1-as-A5 lab stayed 240×320 rotation 0).
     * Reversal: drop restoreSystemRotation call under landscape branch.
     */
    public static void enforceA5Orientation(Activity activity) {
        if (activity == null) return;
        try {
            String mode = A5NavigationMode.orientation(activity);
            int before = activity.getRequestedOrientation();
            if (A5NavigationMode.ORIENT_AUTO.equals(mode)) {
                // 2026-07-11 — Sensor rotates between 240×320 and 320×240.
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            } else if (A5NavigationMode.ORIENT_LANDSCAPE.equals(mode)) {
                // Need sideways 320×240 before scaled Y1 chrome; clear stuck 0 rotation.
                restoreSystemRotation(activity);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } else {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("mode", mode);
                d.put("beforeReq", before);
                d.put("afterReq", activity.getRequestedOrientation());
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
                d.put("dispW", dm.widthPixels);
                d.put("dispH", dm.heightPixels);
                DebugB4208eLog.log("LandscapeOrientationGuard.enforceA5Orientation",
                        "A5 orient applied", "B,C", d);
            } catch (Exception ignoredLog) {}
            }
            // #endregion
        } catch (Exception ignored) {}
    }

    /** Force this activity to landscape — call onCreate/onResume (Y1/Y2). */
    public static void enforceLandscape(Activity activity) {
        if (activity == null) return;
        // 2026-07-15 — Video session landscape wins even when called as enforceLandscape.
        if (forceLandscapeVideoSession) {
            enforceForcedLandscape(activity);
            return;
        }
        // 2026-07-20 — Phone chrome stays portrait (do not yank to landscape).
        try {
            if (com.solar.launcher.phone.PhoneChromePolicy.active(activity)) {
                activity.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                return;
            }
        } catch (Throwable ignored) {}
        if (DeviceFeatures.isA5()) {
            enforceA5Orientation(activity);
            return;
        }
        // 2026-07-14 — Portrait experiment owns orientation while On.
        if (Y1PortraitExperiment.isEnabled(activity)) {
            enforceY1PortraitExperiment(activity);
            return;
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("isA5", false);
            d.put("isY1", DeviceFeatures.isY1());
            d.put("branch", "y1_y2_landscape");
            DebugB4208eLog.log("LandscapeOrientationGuard.enforceLandscape",
                    "non-A5 landscape lock", "A", d);
        } catch (Exception ignoredLog) {}
        }
        // #endregion
        try {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Exception ignored) {}
    }

    /** Reset system rotation to landscape home (0) when an app left it twisted. */
    public static void restoreSystemRotation(Context ctx) {
        if (ctx == null) return;
        try {
            int rot = Settings.System.getInt(ctx.getContentResolver(), Settings.System.USER_ROTATION, 0);
            if (rot != 0) {
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.USER_ROTATION, 0);
            }
            int accel = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            if (accel != 0) {
                Settings.System.putInt(ctx.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, 0);
            }
        } catch (Exception e) {
            RootShell.run("settings put system user_rotation 0; settings put system accelerometer_rotation 0");
        }
    }

    /** Width should exceed height on our 4:3 landscape panel. */
    public static boolean isLandscapeCorrect(Activity activity) {
        if (activity == null) return true;
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
        return dm.widthPixels >= dm.heightPixels && dm.widthPixels >= MIN_LANDSCAPE_WIDTH_PX;
    }

    /** Re-apply locked orientation when an external app twisted the panel. */
    public static void recoverIfPortrait(Activity activity) {
        if (activity == null) return;
        // 2026-07-15 — Stay sideways while watching landscape video.
        if (forceLandscapeVideoSession) {
            enforceForcedLandscape(activity);
            return;
        }
        // 2026-07-20 — Phone chrome is meant to stay tall.
        try {
            if (com.solar.launcher.phone.PhoneChromePolicy.active(activity)) {
                activity.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                return;
            }
        } catch (Throwable ignored) {}
        // 2026-07-11 — A5 may be portrait intentionally or via sensor; re-enforce pref.
        if (DeviceFeatures.isA5()) {
            enforceA5Orientation(activity);
            if (A5NavigationMode.ORIENT_LANDSCAPE.equals(A5NavigationMode.orientation(activity))) {
                restoreSystemRotation(activity);
            }
            return;
        }
        // 2026-07-14 — Portrait experiment: stay tall; do not yank back to landscape.
        if (Y1PortraitExperiment.isEnabled(activity)) {
            enforceY1PortraitExperiment(activity);
            return;
        }
        enforceLandscape(activity);
        restoreSystemRotation(activity);
        Configuration c = activity.getResources().getConfiguration();
        if (c != null && c.orientation == Configuration.ORIENTATION_PORTRAIT) {
            try {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } catch (Exception ignored) {}
        }
    }
}
