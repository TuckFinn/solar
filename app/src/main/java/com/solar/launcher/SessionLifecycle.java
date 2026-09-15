package com.solar.launcher;

import com.solar.launcher.Debug843b96Log;

import org.json.JSONObject;

/**
 * 2026-07-15 — Stop screen-bound work when leaving a feature; keep real sessions that must live on.
 * Layman: leaving Search stops hammering results; leaving Reach still lets messages pop up
 * only when Soulseek service is enabled (opt-in; default off for heat/perf).
 * Technical: cancel search/UI flush on Soulseek leave; keep client when Soulseek is enabled (PM path).
 * Was: full client teardown on leave → no PM context popups off the Reach screen.
 * ponytail: one leave hook, callers already use {@link #onLeaveScreen}.
 */
public final class SessionLifecycle {

    private SessionLifecycle() {}

    /**
     * 2026-07-15 — Whether Reach client stays after leaving the Soulseek UI.
     * Layman: if Soulseek is enabled, stay connected for chat popups.
     * Tech: keep-for-keyboard/stream OR service still active. When service is off (default),
     * always tear down so no background sockets keep the device warm.
     */
    static boolean shouldKeepSoulseekClient(boolean keepForScreen, boolean soulseekServiceActive) {
        return keepForScreen || soulseekServiceActive;
    }

    public static void onLeaveScreen(MainActivity activity, int from, int to) {
        if (from == to) return;

        if (from == MainActivity.STATE_SOULSEEK && to != MainActivity.STATE_SOULSEEK) {
            final boolean keepForScreen = activity.keepSoulseekSessionForScreen(to);
            final boolean serviceOn = activity.soulseekActiveForSession();
            final boolean keepClient = shouldKeepSoulseekClient(keepForScreen, serviceOn);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                JSONObject d = new JSONObject();
                d.put("from", from);
                d.put("to", to);
                d.put("keepForScreen", keepForScreen);
                d.put("serviceOn", serviceOn);
                d.put("keepClient", keepClient);
                Debug843b96Log.log(null, "SessionLifecycle.onLeaveScreen", "soulseek leave", "GM-C", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            // Always cancel search + UI flush (no result spam off-screen).
            activity.pauseSoulseekUiOnly();
            if (!keepClient) {
                activity.teardownSoulseekSession();
            }
        }
        if (from == MainActivity.STATE_BLUETOOTH && to != MainActivity.STATE_BLUETOOTH) {
            activity.teardownBluetoothSession();
        }
        if (from == MainActivity.STATE_WIFI && to != MainActivity.STATE_WIFI) {
            activity.teardownWifiSession();
        }
        if (from == MainActivity.STATE_PODCASTS && to != MainActivity.STATE_PODCASTS
                && to != MainActivity.STATE_PLAYER) {
            activity.teardownPodcastSession();
        }
        if (from == MainActivity.STATE_SETTINGS && to != MainActivity.STATE_SETTINGS
                && to != MainActivity.STATE_WIFI_KEYBOARD) {
            activity.teardownSettingsSession();
        }
        if (from == MainActivity.STATE_BROWSER && to != MainActivity.STATE_BROWSER) {
            activity.teardownBrowserSession();
        }
        if (from == MainActivity.STATE_FLOW && to != MainActivity.STATE_FLOW
                && to != MainActivity.STATE_PLAYER) {
            activity.teardownFlowSession();
        }
        if (from == MainActivity.STATE_DEEZER && to != MainActivity.STATE_DEEZER) {
            activity.teardownDeezerSession();
        }
        activity.sessionSweepHandlers(from, to);
    }
}
