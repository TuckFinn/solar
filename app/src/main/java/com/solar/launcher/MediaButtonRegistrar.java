package com.solar.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * 2026-07-06 — Register MEDIA_BUTTON receiver without MainActivity on screen.
 * Layman: Solar must hear wheel keys while JJ/Rockbox is your home app.
 * Technical: AudioManager.registerMediaButtonEventReceiver for MediaBtnReceiver.
 * Reversal: register only from MainActivity.onCreate again.
 */
public final class MediaButtonRegistrar {

    /** Last successful register — informational only; slot is re-claimed from JJ on every call. */
    private static volatile boolean registered;
    /**
     * 2026-07-19 — Phase C: longer throttle on wheel bursts (was 500 ms).
     * Layman: do not re-claim the media-button slot on every dial click.
     * Technical: skip registerMediaButtonEventReceiver when recent. Reversal: 500L.
     */
    private static volatile long lastRegisterAtMs;
    private static final long REGISTER_THROTTLE_MS = 2000L;

    private MediaButtonRegistrar() {}

    /**
     * 2026-07-06 — Claim MEDIA_BUTTON slot for Solar wheel handoff while JJ/Rockbox is foreground.
     * Layman: JJ steals the wheel receiver on startup; Solar must take it back for remap to work.
     * Technical: register when stale; skip binder work while throttle window is hot.
     * Reversal: always call registerMediaButtonEventReceiver (no throttle).
     */
    public static void ensureRegistered(Context context) {
        if (context == null) return;
        synchronized (MediaButtonRegistrar.class) {
            long t0 = SystemClock.uptimeMillis();
            if (registered && (t0 - lastRegisterAtMs) < REGISTER_THROTTLE_MS) {
                return;
            }
            try {
                AudioManager am = (AudioManager) context.getApplicationContext()
                        .getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                ComponentName cn = new ComponentName(
                        context.getPackageName(),
                        MainActivity.MediaBtnReceiver.class.getName());
                am.registerMediaButtonEventReceiver(cn);
                registered = true;
                lastRegisterAtMs = t0;
            } catch (Exception e) {
                registered = false;
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("err", e.getMessage());
                    DebugE93bdbLog.log("MediaButtonRegistrar.ensureRegistered",
                            "register failed", "H2", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                android.util.Log.w("MediaButtonRegistrar", "register failed: " + e.getMessage());
            }
        }
    }

    /** Test hook — allow re-register after unregister in unit tests. */
    static void resetForTest() {
        registered = false;
        lastRegisterAtMs = 0L;
    }
}
