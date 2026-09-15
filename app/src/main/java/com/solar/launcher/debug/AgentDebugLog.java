package com.solar.launcher.debug;

import android.util.Log;

import org.json.JSONObject;

/**
 * 2026-07-06: Foldable agent debug NDJSON — logcat tag DBG53fa55 for adb harvest.
 */
public final class AgentDebugLog {
    private static final String TAG = "DBG53fa55";
    private static final String SESSION = "53fa55";

    private AgentDebugLog() {}

    /** 2026-07-06: One NDJSON line to logcat for post-run adb grep. */
    public static void log(String location, String hypothesisId, String message, JSONObject data) {
        if (!com.solar.launcher.debug.DebugGate.ON) return; // 2026-09-14
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("hypothesisId", hypothesisId);
            o.put("location", location);
            o.put("message", message);
            o.put("data", data != null ? data : new JSONObject());
            o.put("timestamp", System.currentTimeMillis());
            Log.i(TAG, o.toString());
        } catch (Exception ignored) {
        }
    }
}
