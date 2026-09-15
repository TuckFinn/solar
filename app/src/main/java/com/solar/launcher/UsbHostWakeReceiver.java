package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Wakes Solar when a USB host (PC) connects while the process is not running.
 * 2026-07-10 — Restored July-2 monlith: start MainActivity HOME; USB UI is in-app.
 * ponytail: dynamic receivers in MainActivity miss USB_STATE if SystemUI wins
 * the race and Solar was force-stopped; this manifest receiver starts HOME.
 * Reversal: companion/Xposed-only concierge without MainActivity wake.
 */
public final class UsbHostWakeReceiver extends BroadcastReceiver {

    static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";

    static boolean isUsbHostIntent(Intent intent) {
        if (intent == null || !ACTION_USB_STATE.equals(intent.getAction())) return false;
        if (!intent.getBooleanExtra("connected", false)) return false;
        // Prefer host signals when present (July-2); fall back to connected-only for stock variance.
        return intent.getBooleanExtra("host_connected", false)
                || intent.getBooleanExtra("mass_storage", false)
                || intent.getBooleanExtra("USB_IS_PC_KNOW_ME", false)
                || intent.getBooleanExtra("connected", false);
    }

    /** Cable unplug — unlock MainActivity flags / tear down UMS without launching Solar. */
    static boolean isUsbDisconnectIntent(Intent intent) {
        return intent != null
                && ACTION_USB_STATE.equals(intent.getAction())
                && !intent.getBooleanExtra("connected", false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // 2026-07-16 — Defensive: OEM may deliver null context (historical instantiate NPE path).
        if (context == null) return;
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = Debug705932Log.usbSnapshot();
            d.put("action", intent != null ? intent.getAction() : null);
            d.put("connected", intent != null && intent.getBooleanExtra("connected", false));
            d.put("hostConnected", intent != null && intent.getBooleanExtra("host_connected", false));
            d.put("massStorageExtra", intent != null && intent.getBooleanExtra("mass_storage", false));
            Debug705932Log.log("UsbHostWakeReceiver.onReceive", "usb broadcast", "H1,H4", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (isUsbDisconnectIntent(intent)) {
            // 2026-07-16 — Armed UMS session: re-enum looks like unplug — wait then re-check sticky USB_STATE.
            // Layman: Turn on storage stays on until the cable is really out.
            // Tech: MainActivity helper also debounces; this path covers process-alive manifest receiver races.
            final android.content.BroadcastReceiver.PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            final boolean defer = UsbMassStorageController.shouldDeferDisconnectTeardown();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (defer) {
                            try {
                                Thread.sleep(UsbMassStorageController.DISCONNECT_CONFIRM_MS);
                            } catch (InterruptedException ignored) {}
                            try {
                                Intent sticky = app.registerReceiver(null,
                                        new android.content.IntentFilter(ACTION_USB_STATE));
                                if (sticky != null && sticky.getBooleanExtra("connected", false)) {
                                    // Re-enum blip — keep UMS session.
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                        // #region agent log
                        // 2026-07-18 — Manifest disconnect path vs charge-loss (H1/H4).
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("deferred", defer);
                            d.put("userSession", UsbMassStorageController.isUserSessionActive());
                            d.put("kernelUms", UsbMassStorageController.isKernelMassStorageMode());
                            Debug0f5debLog.log(app, "UsbHostWakeReceiver.disconnect",
                                    "USB_STATE teardown starting", "H1,H4", d);
                        } catch (Exception ignored) {}
                        // #endregion
                        UsbStorageOverlayReceiver.dismissGlobalOverlayIfActive(app);
                        UsbStorageConcierge.clearOnUsbDisconnect();
                        UsbHostSessionPolicy.onUsbHostDisconnected(app);
                        UsbMassStorageController.teardownAfterConfirmedUnplug(app);
                    } finally {
                        pending.finish();
                    }
                }
            }, "UsbDisconnectUmsOff").start();
            return;
        }
        if (!isUsbHostIntent(intent)) return;
        boolean dismissed = UsbHostSessionPolicy.hasUserDismissedThisSession(context);
        boolean evaluated = UsbHostSessionPolicy.hasPromptEvaluatedThisSession(context);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("hostIntent", true);
            d.put("dismissed", dismissed);
            d.put("evaluated", evaluated);
            d.put("extraHost", intent.getBooleanExtra("host_connected", false));
            d.put("extraMs", intent.getBooleanExtra("mass_storage", false));
            d.put("extraPcKnow", intent.getBooleanExtra("USB_IS_PC_KNOW_ME", false));
            d.put("connectedOnlyFallback", intent.getBooleanExtra("connected", false)
                    && !intent.getBooleanExtra("host_connected", false)
                    && !intent.getBooleanExtra("mass_storage", false)
                    && !intent.getBooleanExtra("USB_IS_PC_KNOW_ME", false));
            Debug02fc83Log.log(context, "UsbHostWakeReceiver.onReceive",
                    "host path", "H3", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        // 2026-07-14 — Prompt already shown/dismissed this plug: idle until cable unplug (H3 logs).
        // Was: every USB_STATE started MainActivity with evaluate_host while cable stayed in.
        if (dismissed || evaluated) {
            return;
        }
        // Mid-cable USB_STATE storm: session already armed — do not relaunch before evaluated sticks.
        boolean wasActive = UsbHostSessionPolicy.hasActiveHostSession(context);
        UsbHostSessionPolicy.onUsbHostConnected(context);
        if (wasActive) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("wasActive", true);
                d.put("skipRelaunch", true);
                Debug02fc83Log.log(context, "UsbHostWakeReceiver.onReceive",
                        "skip relaunch — session active", "H3", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return;
        }
        // Stock Android USB dialog — do not wake Solar MainActivity (2026-07-19).
        if (UsbStorageSessionFlags.preferStockUsbUi(context)) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("stockUi", true);
                d.put("skip", UsbStorageSessionFlags.shouldOfferUsbConnectPrompt(context));
                Debug02fc83Log.log(context, "UsbHostWakeReceiver.onReceive",
                        "stock USB UI — no Solar wake", "H-USB", d);
                Debug543e15Log.log("UsbHostWakeReceiver.onReceive",
                        "stock USB UI — no Solar wake", "H2", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return;
        }
        // Auto-Connect only — Solar silent UMS; no plug-in prompt path (2026-07-19).
        if (!UsbStorageSessionFlags.isAutoConnectEnabled(context)) {
            return;
        }
        if (!UsbStorageSessionFlags.isAutoConnectAllowedAfterBootSettle(context)) {
            return;
        }
        try {
            if (!context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0).enabled) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        // July-2: bring MainActivity once per host session; USB prompt/lock is in-process.
        Intent home = new Intent(context, MainActivity.class);
        home.setAction(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        home.putExtra(MainActivity.EXTRA_USB_EVALUATE_HOST, true);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("startActivity", true);
            d.put("autoConnect", true);
            d.put("evaluatedBeforeStart", evaluated);
            d.put("wasActive", wasActive);
            Debug02fc83Log.log(context, "UsbHostWakeReceiver.onReceive",
                    "start MainActivity auto-connect", "H3", d);
            Debug543e15Log.log("UsbHostWakeReceiver.onReceive",
                    "start MainActivity auto-connect", "H2", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        try {
            context.startActivity(home);
        } catch (Exception e) {
            android.util.Log.w("UsbHostWake", "start MainActivity failed", e);
            UsbStorageOverlayReceiver.routeToSolar(context, true, false, "UsbHostWakeReceiver");
        }
    }

    /**
     * True when USB host connect should wake MainActivity.
     * 2026-07-19 — Auto-Connect only; stock path never wakes Solar.
     */
    static boolean shouldLaunchMainActivityForUsbHost(Context context) {
        if (context == null) return false;
        if (UsbStorageSessionFlags.preferStockUsbUi(context)) return false;
        if (!UsbStorageSessionFlags.isAutoConnectEnabled(context)) return false;
        if (UsbHostSessionPolicy.hasUserDismissedThisSession(context)) return false;
        if (!UsbStorageSessionFlags.isAutoConnectAllowedAfterBootSettle(context)) return false;
        return UsbMassStorageExperiment.isEnabled(context);
    }
}
