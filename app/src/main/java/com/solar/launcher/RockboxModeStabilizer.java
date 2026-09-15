package com.solar.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 2026-07-05 — Background negotiator: keep saved HOME mode stable after Rockbox crashes.
 * Layman: if you chose Rockbox and it hiccups, this quietly reopens Rockbox — Solar stays out of the way.
 * Technical: polls fg package; relaunch when home.target=rockbox and RB not top; skips home.applying window.
 * Reversal: stop ensureStarted; rely on MainActivity onCreate grace only (crash loops return).
 */
public final class RockboxModeStabilizer implements Runnable {

    private static final long POLL_MS = 2500L;
    private static volatile boolean started;

    private final Context appContext;
    private final Handler handler;

    private RockboxModeStabilizer(Context context) {
        this.appContext = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** @deprecated Use {@link LauncherWatchdogService#ensureStarted(Context)}. */
    @Deprecated
    public static void ensureStarted(Context context) {
        LauncherWatchdogService.ensureStarted(context);
    }

    @Override
    public void run() {
        try {
            maybeStabilizeRockboxHome();
        } finally {
            handler.postDelayed(this, POLL_MS);
        }
    }

    private void maybeStabilizeRockboxHome() {
        if (LauncherPreference.isSolarHome(appContext)) return;
        if (isHomeApplyInProgress()) return;
        if (LauncherSwitch.isRockboxDisabled(appContext)) return;
        if (LauncherSwitch.isRockboxForeground(appContext)) return;
        MainActivity activity = MainActivity.instance;
        if (activity != null && RockboxRestartGrace.isIntentionalSolarEntry(activity)) {
            return;
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = Debug434250Log.rockboxModeSnapshot(appContext);
            Debug434250Log.log("RockboxModeStabilizer", "relaunch rockbox home", "H-E", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        RockboxRestartGrace.arm(appContext);
        LauncherPreference.launchHomeForTarget(appContext, LauncherDefault.TARGET_ROCKBOX);
    }

    private static boolean isHomeApplyInProgress() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, LauncherPreference.PROP_HOME_APPLYING, "0");
            return "1".equals(String.valueOf(v));
        } catch (Exception e) {
            return false;
        }
    }

    /** Test hook — reset poll state between cases. */
    static void resetForTest() {
        started = false;
    }
}
