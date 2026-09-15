package com.solar.launcher;

import android.content.Context;

/**
 * 2026-07-19 — Intentionally idle: never dismiss stock {@code UsbStorageActivity}.
 * Layman: Android’s USB storage screen must stay when the cable is plugged in.
 * Was: su BACK+HOME to reclaim focus for Solar. Reversal: restore runDismiss from git.
 * 2026-07-06 history: tier-2 fallback when Xposed concierge missed.
 */
public final class SystemUiUsbSuppressor {

    private SystemUiUsbSuppressor() {}

    /**
     * No-op — stock SystemUI owns the USB dialog / Turn off screen.
     * Call sites kept so UMS lock paths still compile.
     */
    public static void dismissIfNeeded(final Context context) {
        if (context == null) return;
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("noop", true);
            Debug050a40Log.log(context, "SystemUiUsbSuppressor.dismissIfNeeded",
                    "no-op — never dismiss stock USB", "H4", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /** Same as {@link #dismissIfNeeded} — no SystemUI fight. */
    public static void dismissNow(final Context context) {
        dismissIfNeeded(context);
    }
}
