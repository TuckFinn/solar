package com.solar.launcher.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.solar.launcher.discovery.PackRunner;
import com.solar.launcher.discovery.PackStore;

/**
 * 2026-11-03 — Q22 Pack for the road, started from nookTunes (Library ›
 * Stations › Pack ▾):
 *
 *   adb shell am broadcast -a com.solar.launcher.action.PACK --es list nd-chart-g132 --es hours 5
 *
 * PACKS_CHANGED: nookTunes removed a pack; forget what was cached about Packed/.
 * Guarded by android.permission.DUMP like {@link RemotePlayReceiver}. Works
 * without a live MainActivity: the pack downloads on its own thread.
 */
public final class PackReceiver extends BroadcastReceiver {
    public static final String ACTION_PACK = "com.solar.launcher.action.PACK";
    public static final String ACTION_CHANGED = "com.solar.launcher.action.PACKS_CHANGED";
    private static final String TAG = "SolarPack";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_CHANGED.equals(intent.getAction())) {
            PackStore.invalidate();
            return;
        }
        if (!ACTION_PACK.equals(intent.getAction())) return;
        String list = intent.getStringExtra("list");
        double hours = 5;
        try {
            hours = Double.parseDouble(String.valueOf(intent.getStringExtra("hours")));
        } catch (NumberFormatException ignored) {}
        SharedPreferences prefs = context.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        String base = prefs.getString("navidrome_url", "");
        if (list == null || list.isEmpty() || base.trim().isEmpty()) {
            Log.w(TAG, "dropped pack request: list=" + list + " base=" + base);
            return;
        }
        Log.i(TAG, "pack " + list + " for " + hours + " h (from nookTunes)");
        PackRunner.start(context, prefs, base, list, hours);
    }
}
