package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * Debug session 050a40 — stock Android USB mass storage prompts missing on Y1.
 * Writes NDJSON to SD + app files; pull to .cursor/debug-050a40.log on host.
 * 2026-07-19
 */
public final class Debug050a40Log {
    private static final String TAG = "SolarDbg050a40";
    private static final String FILE = "debug-050a40.log";
    private static final String SESSION = "050a40";
    /** 2026-07-19 — on for stock-USB diagnosis; flip false after verified fix. */
    public static final boolean ENABLED = false; // 2026-07-20 — off: no SD/HTTP metering on device

    private Debug050a40Log() {}

    /** Append one NDJSON line for hypothesis testing. */
    public static void log(Context ctx, String location, String message, String hypothesisId,
            JSONObject data) {
        if (!ENABLED) return;
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location != null ? location : "");
            o.put("message", message != null ? message : "");
            o.put("hypothesisId", hypothesisId != null ? hypothesisId : "");
            if (data != null) o.put("data", data);
            String line = o.toString();
            Log.e(TAG, line);
            append(new File("/storage/sdcard0/.solar", FILE), line);
            append(new File("/storage/sdcard0/solar", FILE), line);
            append(new File("/sdcard/.solar", FILE), line);
            if (ctx != null) {
                try {
                    append(new File(ctx.getFilesDir(), FILE), line);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    private static void append(File f, String line) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Exception ignored) {}
    }
}
