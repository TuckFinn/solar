package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 2026-07-06 — Opt-in infinite (wrap) wheel navigation for Solar main lists.
 * Layman: at the bottom of a menu, wheel down jumps to the top — off by default.
 * Technical: prefs + persist.solar.nav.infinite_scroll; overlay modal excluded (chips need edges).
 * 2026-07-11 — ThemedContextMenu / context overlay never wrap; see ListNavigationPolicy.appliesToContextModal.
 * Reversal: delete pref row; lists stop at edges with edge glow again.
 */
public final class NavigationPreferences {

    public static final String PREF_INFINITE_SCROLL = "infinite_scroll";
    /** SharedPreferences file — same as MainActivity SOLAR_SETTINGS. */
    private static final String PREFS_NAME = "SOLAR_SETTINGS";
    /** Cross-process read — overlay :overlay, companion, future IME host (no MainActivity). */
    public static final String PROP_INFINITE_SCROLL =
            com.solar.input.policy.GlobalInputPolicy.PROP_INFINITE_SCROLL;

    private NavigationPreferences() {}

    /** True when user opted into wrap-around list focus (default off). */
    public static boolean isInfiniteScrollEnabled(Context context) {
        if (context == null) return false;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP_INFINITE_SCROLL, "");
            if ("1".equals(String.valueOf(v))) return true;
            if ("0".equals(String.valueOf(v))) return false;
        } catch (Exception ignored) {}
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_INFINITE_SCROLL, false);
    }

    /** Persist toggle and mirror to sysprop for externally hosted overlay / IME consumers. */
    public static void setInfiniteScrollEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_INFINITE_SCROLL, enabled)
                .commit();
        writeProp(enabled ? "1" : "0");
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject d = new JSONObject();
            d.put("enabled", enabled);
            DebugE93bdbLog.log("NavigationPreferences.setInfiniteScrollEnabled",
                    "infinite scroll pref", "H-WRAP", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /** 2026-07-06 — Boot/overlay reconcile: prefs → persist for companion :overlay without MainActivity. */
    public static void syncPropertyFromPrefs(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        writeProp(prefs.getBoolean(PREF_INFINITE_SCROLL, false) ? "1" : "0");
    }

    /**
     * Wrap linear index when infinite scroll is on; otherwise clamp to [0, count).
     * Returns -1 when count &lt;= 0 or delta == 0.
     */
    public static int advanceIndex(int current, int delta, int count, boolean infinite) {
        return com.solar.input.policy.ListNavigationPolicy.wrapIndex(
                current, delta, count, infinite);
    }

    private static void writeProp(String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, PROP_INFINITE_SCROLL, value);
        } catch (Exception ignored) {}
    }
}
