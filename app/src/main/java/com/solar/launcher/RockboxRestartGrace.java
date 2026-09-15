package com.solar.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 2026-07-05 — Wait for Rockbox self-restart before Solar pm-disables org.rockbox.
 * Layman: if Rockbox is your home app and it hiccups on launch, Solar steps back
 * instead of locking Rockbox out while it is still rebooting itself.
 * Technical: crash-fallback MainActivity finish + poll/relaunch until grace expires;
 * reversal: delete class and restore unconditional ensureRockboxDisabled in onCreate.
 */
public final class RockboxRestartGrace {

    private static final String TAG = "RockboxRestartGrace";
    /** Housekeeping restart window — Rockbox relaunches itself once per cold start. */
    static final long GRACE_MS = 20_000L;
    static final long POLL_MS = 500L;
    /** Nudge Rockbox HOME if still not foreground after these offsets. */
    private static final long[] RELAUNCH_AT_MS = {2_000L, 6_000L, 12_000L};

    private static volatile boolean armed;

    private RockboxRestartGrace() {}

    /**
     * MainActivity onCreate hook — returns true when Solar should exit without painting UI
     * (Rockbox owns HOME and this start is an accidental crash fallback).
     */
    public static boolean onMainActivityCreate(Activity activity) {
        if (activity == null) return false;
        Context app = activity.getApplicationContext();
        boolean solarHome = isSolarHome(app);
        boolean intentional = isIntentionalSolarEntry(activity);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = Debug434250Log.rockboxModeSnapshot(app);
            d.put("solarHome", solarHome);
            d.put("intentionalEntry", intentional);
            d.put("phase", "onCreate");
            Debug434250Log.log("RockboxRestartGrace.onMainActivityCreate", "entry", "H-B", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (shouldDisableRockboxOnSolarStart(solarHome, intentional)) {
            LauncherSwitch.ensureRockboxDisabled(app);
            return false;
        }
        arm(app);
        return true;
    }

    /**
     * 2026-07-05 — Resume hook when MainActivity was paused mid-switch or Rockbox crashed.
     * Layman: drop the stuck “Switching to Rockbox” panel and nudge Rockbox back if that is your mode.
     * Technical: onResume used to pm-disable Rockbox unconditionally; this relaunches RB HOME instead.
     */
    public static void onMainActivityResume(Activity activity) {
        if (activity == null) return;
        Context app = activity.getApplicationContext();
        boolean solarHome = isSolarHome(app);
        boolean intentional = isIntentionalSolarEntry(activity);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = Debug434250Log.rockboxModeSnapshot(app);
            d.put("solarHome", solarHome);
            d.put("intentionalEntry", intentional);
            d.put("phase", "onResume");
            Debug434250Log.log("RockboxRestartGrace.onMainActivityResume", "entry", "H-C", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).dismissRockboxSwitchOverlayIfNeeded();
        }
        if (solarHome) return;
        if (isAlternateHomeForeground(app, LauncherPreference.getHomeTarget(app))) {
            activity.finish();
            return;
        }
        if (!intentional) {
            arm(app);
            activity.finish();
        }
    }

    /** True when Solar may pm-disable Rockbox on MainActivity entry (Solar HOME or explicit nav). */
    static boolean shouldDisableRockboxOnSolarStart(boolean solarHome, boolean intentionalEntry) {
        return solarHome || intentionalEntry;
    }

    /** True when Rockbox HOME user did not ask for Solar — likely crash fallback during RB restart. */
    static boolean shouldExitEarlyForRockboxGrace(boolean solarHome, boolean intentionalEntry) {
        return !solarHome && !intentionalEntry;
    }

    /** Background grace — keep Rockbox enabled, poll fg, relaunch if needed; never pm disable. */
    static void arm(final Context context) {
        if (context == null) return;
        if (armed) return;
        synchronized (RockboxRestartGrace.class) {
            if (armed) return;
            armed = true;
        }
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runGrace(app);
                } finally {
                    armed = false;
                }
            }
        }, "RockboxRestartGrace").start();
    }

    private static void runGrace(Context app) {
        final String target = LauncherPreference.getHomeTarget(app);
        if (LauncherDefault.TARGET_SOLAR.equals(target)) return;
        if (LauncherDefault.TARGET_ROCKBOX.equals(target)
                && LauncherSwitch.isRockboxDisabled(app)) {
            return;
        }
        if (LauncherDefault.TARGET_JJ.equals(target) && LauncherSwitch.isJjDisabled(app)) {
            return;
        }
        final long start = System.currentTimeMillis();
        int relaunchIdx = 0;
        Log.i(TAG, "grace armed ms=" + GRACE_MS + " target=" + target);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = Debug434250Log.rockboxModeSnapshot(app);
            d.put("graceMs", GRACE_MS);
            d.put("homeTarget", target);
            Debug434250Log.log("RockboxRestartGrace.runGrace", "armed", "H-D", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        while (System.currentTimeMillis() - start < GRACE_MS) {
            if (isAlternateHomeForeground(app, target)) {
                Log.i(TAG, "preferred home foreground — grace complete");
                // #region agent log
                Debug434250Log.log("RockboxRestartGrace.runGrace", "alternate fg ok", "H-D", null);
                // #endregion
                return;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (relaunchIdx < RELAUNCH_AT_MS.length && elapsed >= RELAUNCH_AT_MS[relaunchIdx]) {
                LauncherPreference.launchHomeForTarget(app, target);
                Log.i(TAG, "relaunch nudge at " + elapsed + "ms target=" + target);
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("elapsedMs", elapsed);
                    d.put("target", target);
                    d.put("rockboxFg", LauncherSwitch.isRockboxForeground(app));
                    DebugD68c5cLog.log("RockboxRestartGrace.runGrace", "relaunch nudge", "E", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                relaunchIdx++;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        if (isAlternateHomeForeground(app, target)) {
            Log.i(TAG, "preferred home foreground at grace end");
        } else {
            Log.w(TAG, "grace expired without preferred fg — left enabled for manual recovery");
            // #region agent log
            Debug434250Log.log("RockboxRestartGrace.runGrace", "grace expired no fg", "H-D", null);
            // #endregion
        }
    }

    private static boolean isAlternateHomeForeground(Context app, String target) {
        if (LauncherDefault.TARGET_ROCKBOX.equals(target)) {
            return LauncherSwitch.isRockboxForeground(app);
        }
        if (LauncherDefault.TARGET_JJ.equals(target)) {
            return LauncherSwitch.isJjForeground(app);
        }
        return false;
    }

    private static boolean isSolarHome(Context context) {
        return LauncherPreference.isSolarHome(context);
    }

    /** Overlay Go Home / Now Playing / Wi‑Fi password — user asked for Solar, not crash fallback. */
    static boolean isIntentionalSolarEntry(Activity activity) {
        if (OverlayForegroundGuard.isUserRequestedSolarNavigationForTest()) return true;
        Intent intent = activity.getIntent();
        if (intent == null) return false;
        if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, false)) return true;
        String ssid = intent.getStringExtra(MainActivity.EXTRA_WIFI_OVERLAY_PASSWORD_SSID);
        return ssid != null && ssid.length() > 0;
    }

    /** Test hook — reset singleton arm between cases. */
    static void resetForTest() {
        armed = false;
    }
}
