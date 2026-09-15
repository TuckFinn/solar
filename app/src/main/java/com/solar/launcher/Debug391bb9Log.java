package com.solar.launcher;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * 2026-07-19 — Session 391bb9: app-wide slowness hunt (debug-logger cost).
 * Layman: counts how often leftover debug writers run and how long they stall.
 * Tech: sparse Log.e + SD NDJSON; no HTTP. Reversal: delete after fix verified.
 */
public final class Debug391bb9Log {
    private static final String TAG = "SolarDbg391bb9";
    private static final String SESSION = "391bb9";
    private static final String FILE = "debug-391bb9.log";

    private static int calls0f5;
    private static int callsFa;
    private static int calls543;
    private static long cost0f5;
    private static long costFa;
    private static long cost543;

    private Debug391bb9Log() {}

    /**
     * 2026-07-19 — Record one debug-logger body cost for hypothesis {@code hypothesisId}.
     * Layman: notes which leftover logger ate time.
     * Tech: increments counters; flushes every 10 samples to logcat + SD.
     */
    public static void note(String which, String hypothesisId, long costMs) {
        if (!com.solar.launcher.debug.DebugGate.ON) return; // 2026-09-14
        try {
            if ("0f5deb".equals(which)) {
                calls0f5++;
                cost0f5 += costMs;
            } else if ("fa8512".equals(which)) {
                callsFa++;
                costFa += costMs;
            } else if ("543e15".equals(which)) {
                calls543++;
                cost543 += costMs;
            }
            int total = calls0f5 + callsFa + calls543;
            if (total % 10 != 0 && costMs < 5L) return;
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", "Debug391bb9Log.note");
            o.put("message", "debug logger cost");
            o.put("hypothesisId", hypothesisId != null ? hypothesisId : "");
            o.put("runId", "pre-fix");
            JSONObject d = new JSONObject();
            d.put("which", which);
            d.put("costMs", costMs);
            d.put("calls0f5", calls0f5);
            d.put("cost0f5Ms", cost0f5);
            d.put("callsFa", callsFa);
            d.put("costFaMs", costFa);
            d.put("calls543", calls543);
            d.put("cost543Ms", cost543);
            o.put("data", d);
            String line = o.toString();
            Log.e(TAG, line);
            append(new File("/storage/sdcard0/.solar", FILE), line);
        } catch (Exception ignored) {}
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
