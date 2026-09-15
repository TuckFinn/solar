package com.solar.launcher;

import android.content.Context;

/**
 * 2026-07-06 — Tear down kernel UMS when Solar owned disk mode without consent.
 * 2026-07-19 — No-op when Android owns USB (Auto-Connect off): do not fight SystemUI / stock UMS.
 * Layman: PC cable stays with Android unless you turned on Auto-Connect or Enable Now.
 * Technical: skip disable when {@link UsbStorageSessionFlags#preferStockUsbUi}.
 * Reversal: restore always-disable-without-auto path (caused Y1 stall when PC-tethered).
 */
public final class UsbUnauthorizedUmsGuard {

    private UsbUnauthorizedUmsGuard() {}

    /** Background-safe — idempotent disable when UMS mode active without user consent. */
    public static void teardownIfUnauthorizedAsync(final Context context) {
        if (context == null) return;
        // Stock path: never spawn root UMS work on every USB_STATE (Y1 startup stall).
        if (UsbStorageSessionFlags.preferStockUsbUi(context)) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                teardownIfUnauthorizedBlocking(app);
            }
        }, "UsbUmsGuard").start();
    }

    /** Blocking teardown for boot / tests. */
    public static boolean teardownIfUnauthorizedBlocking(Context context) {
        if (context == null) return false;
        // Android owns the cable — leave kernel / SystemUI mass storage alone.
        if (UsbStorageSessionFlags.preferStockUsbUi(context)) {
            return false;
        }
        if (!UsbMassStorageExperiment.isEnabled(context)) {
            return UsbMassStorageController.disableIfExported(context);
        }
        if (UsbStorageSessionFlags.isAutoConnectEnabled(context)) {
            return false;
        }
        if (!UsbMassStorageController.isKernelMassStorageMode()) {
            return true;
        }
        boolean ok = UsbMassStorageController.disable(context);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("ok", ok);
            d.put("autoConnect", false);
            d.put("usbConfig", UsbMassStorageController.isKernelMassStorageMode());
            Debug531722Log.log("UsbUnauthorizedUmsGuard.teardownIfUnauthorizedBlocking",
                    "cleared kernel UMS without consent", "H3", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return ok;
    }
}
