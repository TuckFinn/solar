package com.solar.launcher;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * 2026-07-05 — Y2 UMS debug session a3510d (Solar app + app_process UmsEnabler).
 * Pull: adb shell su -c 'cat /data/local/tmp/debug-a3510d.log'
 */
public final class DebugA3510dLog {

    private static final String SESSION = "a3510d";
    private static final String FILE = "debug-a3510d.log";

    /** Off by default — avoids observer-effect I/O under adb (2026-07-11). */
    public static volatile boolean ENABLED = false;

    private DebugA3510dLog() {}

    /** NDJSON line — works from app context and app_process (no Context). */
    public static void log(String location, String message, String hypothesisId, JSONObject data) {
        if (!ENABLED) return;
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location);
            o.put("message", message);
            o.put("hypothesisId", hypothesisId);
            if (data != null) o.put("data", data);
            String line = o.toString();
            android.util.Log.i("SolarDbgA3510d", line);
            append(new File("/data/local/tmp", FILE), line);
            File sd = DeviceFeatures.getPrimaryStorageRoot();
            if (sd != null) {
                File dir = new File(sd, ".solar");
                if (!dir.exists()) dir.mkdirs();
                append(new File(dir, FILE), line);
            }
            append(new File("/data/data/com.solar.launcher/files", FILE), line);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /** app_process-safe — no DeviceFeatures. */
    public static void logStandalone(String location, String message, String hypothesisId,
            JSONObject data) {
        if (!ENABLED) return;
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location);
            o.put("message", message);
            o.put("hypothesisId", hypothesisId);
            if (data != null) o.put("data", data);
            String line = o.toString();
            append(new File("/data/local/tmp", FILE), line);
            append(new File("/storage/sdcard1/.solar", FILE), line);
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
