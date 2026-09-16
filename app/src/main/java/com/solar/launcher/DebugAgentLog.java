package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/** Debug-mode NDJSON logger — session 5c5611; pull via adb from SD .solar/ or app files. */
public final class DebugAgentLog {
    private static final String TAG = "SolarDbg5c5611";
    private static final String FILE = "debug-5c5611.log";
    private static final String SESSION = "5c5611";
    /**
     * Emit to logcat. Follows the agent-log build flag, so an agent-log build reports itself
     * through {@code logcat -s SolarDbg5c5611:I} with no extra step.
     *
     * 2026-09-15 — This defaulted to false, so "home menu built" and its rowLabels never
     * emitted even in a build with AGENT_LOGS on. A home row that silently refused to render
     * then had to be diagnosed by reading overload chains instead of asking the launcher.
     */
    public static volatile boolean ENABLED = BuildConfig.AGENT_LOGS;
    /**
     * Also persist each line to SD and app files. Off by default, opt-in per debug session:
     * {@code adb shell am start -n com.solar.launcher/.MainActivity --ez solar_adb_debug_5c5611 true}
     *
     * ponytail: hot-path sync file I/O was freezing the UI. Logcat is cheap; two synchronous
     * FileWriter round-trips across 114 call sites are not, so the two are separate switches.
     */
    public static volatile boolean FILES = false;

    private DebugAgentLog() {}

    public static void log(Context ctx, String location, String message, String hypothesisId, JSONObject data) {
        if (!ENABLED) return;
        try {
            long ts = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", ts);
            o.put("location", location);
            o.put("message", message);
            o.put("hypothesisId", hypothesisId);
            if (data != null) o.put("data", data);
            String line = o.toString();
            Log.i(TAG, line);
            // Logcat is the default report. Disk is the expensive half, so it is opt-in.
            if (!FILES) return;
            File sdRoot = DeviceFeatures.getPrimaryStorageRoot();
            if (sdRoot != null) {
                try {
                    File dir = new File(sdRoot, ".solar");
                    if (!dir.exists()) dir.mkdirs();
                    FileWriter w = new FileWriter(new File(dir, FILE), true);
                    w.write(line);
                    w.write('\n');
                    w.close();
                } catch (Exception ignored2) {}
            }
            if (ctx != null) {
                try {
                    File f = new File(ctx.getFilesDir(), FILE);
                    FileWriter w = new FileWriter(f, true);
                    w.write(line);
                    w.write('\n');
                    w.close();
                } catch (Exception ignored3) {}
            }
        } catch (Exception ignored) {}
    }
}
