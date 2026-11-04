package com.solar.launcher.sip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 2026-11-03 — nookLine account for Walkie-Talkie, set from nookman over adb:
 *
 *   am broadcast -a com.solar.launcher.action.SIP_CONFIG --es server 100.126.143.83 \
 *       --es user 6002 --es password ... [--es room 7000]
 *
 * (pushed as a script, so the password never sits on a command line). Guarded by
 * android.permission.DUMP like the other nookTunes receivers. An empty user
 * clears the account. Setting one also adds Walkie-Talkie to the home menu,
 * after Discovery, the first time.
 */
public final class SipConfigReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.solar.launcher.action.SIP_CONFIG";
    static final String PREF_MENU_ADDED = "walkie_menu_added";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        String user = trim(intent.getStringExtra("user"));
        SharedPreferences.Editor e = p.edit();
        if (user.isEmpty()) {
            e.remove(WalkieScreen.PREF_USER).remove(WalkieScreen.PREF_PASSWORD).apply();
            Log.i("SolarWalkie", "nookLine account cleared");
            return;
        }
        e.putString(WalkieScreen.PREF_SERVER, trim(intent.getStringExtra("server")))
                .putString(WalkieScreen.PREF_USER, user)
                .putString(WalkieScreen.PREF_PASSWORD, trim(intent.getStringExtra("password")))
                .putString(WalkieScreen.PREF_ROOM, trim(intent.getStringExtra("room")).isEmpty()
                        ? "7000" : trim(intent.getStringExtra("room")));
        addToHomeMenu(p, e);
        e.commit();
        Log.i("SolarWalkie", "nookLine account set: " + user + " @ " + p.getString(WalkieScreen.PREF_SERVER, ""));
        com.solar.launcher.MainActivity a = com.solar.launcher.MainActivity.instance;
        if (a != null) {
            a.runOnUiThread(new Runnable() {
                @Override public void run() { a.refreshHomeMenuForWalkie(); }
            });
        }
    }

    /** Insert "walkie" after "discovery" (or at the end) in the saved home order, once. */
    static void addToHomeMenu(SharedPreferences p, SharedPreferences.Editor e) {
        if (p.getBoolean(PREF_MENU_ADDED, false)) return;
        String order = p.getString(com.solar.launcher.HomeMenuConfig.PREF_ORDER, "");
        if (!order.isEmpty() && !(("," + order + ",").contains(",walkie,"))) {
            String[] ids = order.split(",");
            StringBuilder sb = new StringBuilder();
            boolean put = false;
            for (String id : ids) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
                if (!put && "discovery".equals(id)) { sb.append(",walkie"); put = true; }
            }
            if (!put) sb.append(",walkie");
            e.putString(com.solar.launcher.HomeMenuConfig.PREF_ORDER, sb.toString());
        }
        e.putBoolean(PREF_MENU_ADDED, true);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
