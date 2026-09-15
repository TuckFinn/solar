package com.solar.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * 2026-07-06 — Event-driven USB host intercept: USB_STATE + Xposed concierge; no HOME poll storm.
 * Layman: PC plug-in shows Solar's USB dialog once; after Dismiss we stay idle until unplug.
 * Tech: tier-1 {@code UsbStorageHooks}, tier-2 sticky USB_STATE fallback; slow UMS watchdog only.
 * Reversal: restore {@code pollRunnable} 1.5s loop + {@code bringToFrontFull} on every tick.
 */
public final class Y1UsbFocusHelper {

    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_USB_CONNECTED = "connected";
    private static final String EXTRA_HOST_CONNECTED = "host_connected";

    /** Slow watchdog while UMS exported — SystemUI lock screen replaced by Solar, not fought at 50ms. */
    private static final int UMS_WATCHDOG_MS = 3000;
    /** Min gap between one-shot HOME reclaim attempts on host connect (ms). */
    private static final long BRING_FULL_MIN_INTERVAL_MS = 500L;
    /**
     * Host must stay disconnected at least this long before a dismissed session may intercept again.
     * Shorter reconnects are cable flaps — keep {@link #userDeclinedHostSession} and stay idle.
     */
    public static final long USB_HOST_SESSION_RESET_MS = 3000L;

    public interface UsbListener {
        void onUsbStateChanged(boolean connected);
        /** Charger-only USB cable — no PC host; Solar still offers storage enable prompt. */
        void onChargerOnlyConnected();
        /** Solar lost window focus to SystemUI — show/replace USB intercept UI (UMS lock only). */
        void onUsbFocusIntercept();
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver usbReceiver;
    private boolean usbRegistered;
    private final UsbListener listener;
    private boolean usbConnected = false;
    private boolean umsWatchdog = false;
    private boolean hostConnected = false;
    /** Use HOME + moveTaskToFront when SystemUI USB lock appears while UMS is exported. */
    private boolean massStorageIntercept = false;
    /** User dismissed Solar's enable dialog — no in-app intercept until cable unplug. */
    private boolean interceptPaused = false;
    /** Dismissed this host plug — no reclaim until cable off longer than session reset. */
    private boolean userDeclinedHostSession = false;
    /** USB lock screen: global context menu open — skip one-shot HOME reclaim. */
    private boolean reclaimSuspended = false;
    private long lastBringToFrontFullMs = 0L;
    private long lastHostDisconnectAtMs = 0L;
    /** Pending confirm that cable really left (not mass_storage re-enum). */
    private Runnable pendingDisconnectConfirm;

    public Y1UsbFocusHelper(Activity activity, UsbListener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void onResume() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                registerIfNeeded();
                // 2026-07-06 — UMS lock watchdog only; enable prompt is event-driven (no poll resume).
                if (massStorageIntercept && hostConnected && !isSessionIdle()) {
                    startUmsWatchdogIfNeeded();
                }
            }
        });
    }

    public void onDestroy() {
        stopUmsWatchdog();
        if (pendingDisconnectConfirm != null) {
            handler.removeCallbacks(pendingDisconnectConfirm);
            pendingDisconnectConfirm = null;
        }
        if (usbRegistered && usbReceiver != null) {
            try { activity.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
            usbRegistered = false;
        }
    }

    private void registerIfNeeded() {
        if (usbRegistered) return;
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleUsbStateIntent(intent);
            }
        };
        activity.registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_STATE));
        usbRegistered = true;
        // 2026-07-06 — Sticky USB_STATE may predate MainActivity; defer if boot-settle not ready.
        Intent sticky = activity.registerReceiver(null, new IntentFilter(ACTION_USB_STATE));
        if (sticky != null) {
            UsbHostSessionPolicy.runWhenPromptAllowed(activity.getApplicationContext(),
                    new Runnable() {
                        @Override
                        public void run() {
                            handleUsbStateIntent(sticky);
                        }
                    });
        }
    }

    private void handleUsbStateIntent(Intent intent) {
        if (intent == null || !ACTION_USB_STATE.equals(intent.getAction())) return;
        final boolean connected = intent.getBooleanExtra(EXTRA_USB_CONNECTED, false);
        final boolean extraHost = intent.getBooleanExtra(EXTRA_HOST_CONNECTED, false);
        final boolean extraMassStorage = intent.getBooleanExtra("mass_storage", false);
        final boolean extraPcKnow = intent.getBooleanExtra("USB_IS_PC_KNOW_ME", false);
        // 2026-07-16 — Prefer OEM host signals; configured+connected = PC finished enumerating.
        // Wall chargers are often connected without configured → charger-only path.
        final boolean configured = intent.getBooleanExtra("configured", false);
        final boolean host = extraHost || extraMassStorage || extraPcKnow
                || (connected && configured);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject d = new JSONObject();
            d.put("extraHost", extraHost);
            d.put("extraMassStorage", extraMassStorage);
            d.put("extraPcKnow", extraPcKnow);
            d.put("hostDerived", host);
            d.put("fg", ExternalInputHandoff.getForegroundPackageName(activity));
            Debug266f21Log.log("Y1UsbFocusHelper.handleUsbStateIntent", "host derivation", "H2", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        android.util.Log.d("UsbFocus", "onReceive: connected=" + connected + ", host=" + host
                + ", hostConnected=" + hostConnected + ", usbConnected=" + usbConnected);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject d = new JSONObject();
            d.put("connected", connected);
            d.put("host", host);
            d.put("hostConnected", hostConnected);
            d.put("usbConnected", usbConnected);
            d.put("interceptPaused", interceptPaused);
            d.put("umsWatchdog", umsWatchdog);
            d.put("sessionIdle", isSessionIdle());
            DebugSessionLog.log("Y1UsbFocusHelper.onReceive", "USB_STATE", "H1", d);
        } catch (Exception ignored) {}
        }
        // #endregion

        if (!connected && usbConnected) {
            // 2026-07-16 — mass_storage enable re-enums USB (connected=false for a beat).
            // Layman: Turn on storage must not drop the eject screen or kill disk mode mid-switch.
            // Tech: debounce while user UMS session / lock intercept is armed; confirm via sticky USB_STATE.
            if (massStorageIntercept || UsbMassStorageController.shouldDeferDisconnectTeardown()) {
                scheduleDisconnectConfirm();
                return;
            }
            applyConfirmedCableDisconnect();
            return;
        }

        // Re-enum came back before debounce fired — cancel false unplug.
        if (connected && pendingDisconnectConfirm != null) {
            handler.removeCallbacks(pendingDisconnectConfirm);
            pendingDisconnectConfirm = null;
        }

        if (connected && host && !hostConnected) {
            long offMs = lastHostDisconnectAtMs > 0L
                    ? System.currentTimeMillis() - lastHostDisconnectAtMs
                    : Long.MAX_VALUE;
            boolean flapWhileDeclined = userDeclinedHostSession
                    && offMs < USB_HOST_SESSION_RESET_MS;
            if (userDeclinedHostSession && offMs >= USB_HOST_SESSION_RESET_MS) {
                userDeclinedHostSession = false;
                interceptPaused = false;
            }
            android.util.Log.d("UsbFocus", "USB host (PC) connected offMs=" + offMs
                    + " flapWhileDeclined=" + flapWhileDeclined);
            hostConnected = true;
            usbConnected = true;
            UsbHostSessionPolicy.onUsbHostConnected(activity.getApplicationContext());
            // Fresh PC plug — export may drive eject UI again (cleared after prior unplug).
            UsbMassStorageController.clearExportUiIgnoreAfterUnplug();
            // Stock USB UI — pause Solar reclaim/prompt so Android dialog stays (2026-07-19).
            boolean stockUi = UsbStorageSessionFlags.preferStockUsbUi(
                    activity.getApplicationContext());
            if (stockUi || flapWhileDeclined || isSessionIdle()) {
                interceptPaused = true;
                stopUmsWatchdog();
                // Stock: do not notify MainActivity — avoids UnauthorizedUmsGuard / route on PC plug.
                if (stockUi) {
                    // #region agent log
                    if (com.solar.launcher.debug.DebugGate.ON) {
                    try {
                        JSONObject d = new JSONObject();
                        d.put("stockUi", true);
                        d.put("skipListener", true);
                        d.put("auto", UsbStorageSessionFlags.isAutoConnectEnabled(
                                activity.getApplicationContext()));
                        DebugSessionLog.log("Y1UsbFocusHelper.handleUsbStateIntent",
                                "stock host — no Solar notify", "H-USB", d);
                        Debug050a40Log.log(activity, "Y1UsbFocusHelper.handleUsbStateIntent",
                                "stock host — Solar idle (Android should own UI)", "H1,H3", d);
                    } catch (Exception ignored) {}
                    }
                    // #endregion
                    return;
                }
            } else if (!userDeclinedHostSession) {
                interceptPaused = false;
                if (massStorageIntercept && isSystemUiUsbOnTop()) {
                    bringToFrontFullOnce();
                } else {
                    evaluateHostConnectOnce("Y1UsbFocusHelper.hostConnect");
                }
            }
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                JSONObject d = new JSONObject();
                d.put("offMs", offMs);
                d.put("flapWhileDeclined", flapWhileDeclined);
                d.put("userDeclinedHostSession", userDeclinedHostSession);
                d.put("interceptPaused", interceptPaused);
                d.put("stockUi", stockUi);
                DebugSessionLog.log("Y1UsbFocusHelper.handleUsbStateIntent",
                        "host connect", "H-USB-SESSION", d);
                Debug543e15Log.log("Y1UsbFocusHelper.handleUsbStateIntent",
                        "host connect", "H3", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            if (listener != null) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        listener.onUsbStateChanged(true);
                    }
                }, 200);
            }
        } else if (connected && !host) {
            android.util.Log.d("UsbFocus", "Charger-only connection");
            usbConnected = true;
            stopUmsWatchdog();
            if (listener != null) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        listener.onChargerOnlyConnected();
                    }
                }, 200);
            }
        }
    }

    /**
     * 2026-07-16 — Wait for stable unplug before clearing UMS lock / session.
     * Layman: only leave the eject screen after the cable has really been pulled.
     */
    private void scheduleDisconnectConfirm() {
        if (pendingDisconnectConfirm != null) {
            handler.removeCallbacks(pendingDisconnectConfirm);
        }
        pendingDisconnectConfirm = new Runnable() {
            @Override
            public void run() {
                pendingDisconnectConfirm = null;
                try {
                    Intent sticky = activity.registerReceiver(null, new IntentFilter(ACTION_USB_STATE));
                    if (sticky != null && sticky.getBooleanExtra(EXTRA_USB_CONNECTED, false)) {
                        // Still plugged — re-enum blip only.
                        usbConnected = true;
                        final boolean extraHost = sticky.getBooleanExtra(EXTRA_HOST_CONNECTED, false);
                        final boolean extraMassStorage = sticky.getBooleanExtra("mass_storage", false);
                        final boolean extraPcKnow = sticky.getBooleanExtra("USB_IS_PC_KNOW_ME", false);
                        final boolean configured = sticky.getBooleanExtra("configured", false);
                        hostConnected = extraHost || extraMassStorage || extraPcKnow
                                || configured
                                || hostConnected
                                || massStorageIntercept;
                        android.util.Log.d("UsbFocus", "disconnect confirm cancelled — still connected");
                        return;
                    }
                } catch (Exception ignored) {}
                applyConfirmedCableDisconnect();
            }
        };
        handler.postDelayed(pendingDisconnectConfirm, UsbMassStorageController.DISCONNECT_CONFIRM_MS);
        android.util.Log.d("UsbFocus", "USB disconnect deferred "
                + UsbMassStorageController.DISCONNECT_CONFIRM_MS + "ms (UMS session)");
    }

    /** Real cable out — clear intercept state and notify MainActivity once. */
    private void applyConfirmedCableDisconnect() {
        android.util.Log.d("UsbFocus", "USB cable disconnected (confirmed)");
        lastHostDisconnectAtMs = System.currentTimeMillis();
        usbConnected = false;
        hostConnected = false;
        reclaimSuspended = false;
        massStorageIntercept = false;
        userDeclinedHostSession = false;
        interceptPaused = false;
        if (pendingDisconnectConfirm != null) {
            handler.removeCallbacks(pendingDisconnectConfirm);
            pendingDisconnectConfirm = null;
        }
        UsbStorageOverlayReceiver.dismissGlobalOverlayIfActive(activity);
        UsbStorageConcierge.clearOnUsbDisconnect();
        UsbHostSessionPolicy.onUsbHostDisconnected(activity.getApplicationContext());
        stopUmsWatchdog();
        // Confirmed unplug: drop kernel UMS then unlock UI via listener.
        final Context app = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                UsbMassStorageController.teardownAfterConfirmedUnplug(app);
            }
        }, "UsbConfirmedUnplugUms").start();
        if (listener != null) listener.onUsbStateChanged(false);
    }

    /**
     * 2026-07-06 — One evaluation per host session: Xposed concierge, then USB_STATE overlay fallback.
     * Reversal: restore immediate {@code routeEnablePromptOverlay} + 1.5s poll loop on every connect.
     */
    private void evaluateHostConnectOnce(final String caller) {
        if (!hostConnected || isSessionIdle()) return;
        if (massStorageIntercept) return;
        UsbHostSessionPolicy.runWhenPromptAllowed(activity.getApplicationContext(),
                new Runnable() {
                    @Override
                    public void run() {
                        scheduleUsbConnectFallback(caller);
                    }
                });
    }

    /**
     * 2026-07-06 — Fallback only when Xposed concierge did not intercept UsbStorageActivity.
     * Reversal: restore immediate {@code routeEnablePromptOverlay} on host connect above.
     */
    private void scheduleUsbConnectFallback(final String caller) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!hostConnected || isSessionIdle()) return;
                if (massStorageIntercept) return;
                if (!UsbHostSessionPolicy.shouldEvaluatePromptThisSession(
                        activity.getApplicationContext())) {
                    return;
                }
                boolean concierge = UsbStorageConcierge.isXposedConciergeActive();
                UsbStorageConcierge.logFallbackDecision(caller, concierge);
                if (concierge) {
                    UsbHostSessionPolicy.markPromptEvaluated(activity.getApplicationContext());
                    return;
                }
                if (OverlayTierScheduler.shouldDeferUsbSpawn()) {
                    OverlayTierScheduler.queuePendingUsbPrompt();
                    return;
                }
                // 2026-07-16 — Do not mark evaluated here; routeToSolar / showUsbMassStorageDialog
                // mark only when the enable sheet actually paints (defer must leave session open).
                // 2026-07-10 — Sole funnel: Solar MainActivity (never companion overlay race).
                UsbStorageOverlayReceiver.routeToSolar(
                        activity.getApplicationContext(), true, false, "Y1UsbFocusHelper.fallback");
            }
        }, UsbStorageConcierge.fallbackDelayMs());
    }

    /** True when user dismissed this plug or Xposed owns USB — no poll/HOME/recovery (2026-07-06). */
    private boolean isSessionIdle() {
        return userDeclinedHostSession
                || interceptPaused
                || UsbHostSessionPolicy.isAggressiveUsbWorkSuppressed(
                        activity.getApplicationContext());
    }

    /** Slow UMS lock watchdog — only while mass storage exported and session not idle. */
    private final Runnable umsWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!umsWatchdog) return;
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                JSONObject d = new JSONObject();
                d.put("concierge", UsbStorageConcierge.isXposedConciergeActive());
                d.put("reclaimSuspended", reclaimSuspended);
                d.put("hasWindowFocus", activity.hasWindowFocus());
                d.put("hostConnected", hostConnected);
                d.put("massStorageIntercept", massStorageIntercept);
                Debug86bbe0Log.log("Y1UsbFocusHelper.umsWatchdog", "tick", "H1", d);
                Debug02fc83Log.log(activity, "Y1UsbFocusHelper.umsWatchdog",
                        "tick", "H5", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            if (OverlayKeyGate.isOverlayKeysActive() || isSessionIdle() || reclaimSuspended) {
                stopUmsWatchdog();
                return;
            }
            if (!massStorageIntercept || !hostConnected) {
                stopUmsWatchdog();
                return;
            }
            if (!activity.hasWindowFocus() && isSystemUiUsbOnTop()) {
                bringToFrontFullOnce();
                SystemUiUsbSuppressor.dismissIfNeeded(activity);
                notifyFocusIntercept();
            }
            if (umsWatchdog) {
                handler.postDelayed(this, UMS_WATCHDOG_MS);
            }
        }
    };

    private void notifyFocusIntercept() {
        if (listener == null || isSessionIdle() || !massStorageIntercept) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isSessionIdle() && massStorageIntercept) {
                    listener.onUsbFocusIntercept();
                }
            }
        });
    }

    private void startUmsWatchdogIfNeeded() {
        if (isSessionIdle() || !massStorageIntercept) return;
        if (umsWatchdog) return;
        umsWatchdog = true;
        handler.post(umsWatchdogRunnable);
    }

    private void stopUmsWatchdog() {
        umsWatchdog = false;
        handler.removeCallbacks(umsWatchdogRunnable);
    }

    public boolean isUsbConnected() {
        return usbConnected;
    }

    public boolean isHostConnected() {
        return hostConnected;
    }

    /** @deprecated Use {@link #isUmsWatchdogActive()} — enable prompt no longer polls. */
    public boolean isPolling() {
        return umsWatchdog;
    }

    /** True while slow UMS lock watchdog runs (not enable-prompt poll). */
    public boolean isUmsWatchdogActive() {
        return umsWatchdog;
    }

    public boolean isInterceptPaused() {
        return interceptPaused;
    }

    public boolean isUserDeclinedHostSession() {
        return userDeclinedHostSession;
    }

    /**
     * User declined USB storage — stop reclaim loops; manual enable via Settings only (2026-07-06).
     * Reversal: re-arm {@code pollRunnable} and {@link UsbRecoveryAgent#ensureRunning}.
     */
    public void setInterceptPaused(boolean paused) {
        interceptPaused = paused;
        if (paused) {
            userDeclinedHostSession = true;
            massStorageIntercept = false;
            stopUmsWatchdog();
            UsbHostSessionPolicy.markUserDismissed(activity.getApplicationContext());
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                JSONObject d = new JSONObject();
                d.put("umsWatchdog", umsWatchdog);
                DebugSessionLog.log("Y1UsbFocusHelper.setInterceptPaused",
                        "declined — idle until disconnect", "H-USB-IDLE", d);
            } catch (Exception ignored) {}
            }
            // #endregion
        }
    }

    /** User chose Turn on USB storage — resume UMS intercept for this plug. */
    public void clearHostInterceptDecline() {
        userDeclinedHostSession = false;
        interceptPaused = false;
        UsbHostSessionPolicy.clearUserDismissed(activity.getApplicationContext());
        if (hostConnected) {
            bringToFrontFullOnce();
            if (massStorageIntercept) {
                startUmsWatchdogIfNeeded();
            }
        }
    }

    /** While USB enable prompt or global menu owns keys, skip one-shot HOME reclaim. */
    public void setReclaimSuspended(boolean suspended) {
        reclaimSuspended = suspended;
        if (suspended) {
            stopUmsWatchdog();
        } else if (hostConnected && massStorageIntercept && !isSessionIdle()) {
            startUmsWatchdogIfNeeded();
        }
    }

    public boolean isReclaimSuspended() {
        return reclaimSuspended;
    }

    /** While UMS is exported, slow-watch SystemUI lock screen and replace with Solar's. */
    public void setMassStorageInterceptActive(boolean active) {
        if (interceptPaused && active) return;
        massStorageIntercept = active;
        if (active && hostConnected && !isSessionIdle()) {
            SystemUiUsbSuppressor.dismissNow(activity);
            startUmsWatchdogIfNeeded();
        } else if (!active) {
            stopUmsWatchdog();
        }
    }

    public boolean isMassStorageInterceptActive() {
        return massStorageIntercept;
    }

    /** One-shot HOME reclaim — not a poll loop (2026-07-06 session 86bbe0). */
    private void bringToFrontFullOnce() {
        long now = System.currentTimeMillis();
        if (now - lastBringToFrontFullMs < BRING_FULL_MIN_INTERVAL_MS) {
            bringToFrontLightPublic();
            return;
        }
        bringToFrontFull();
    }

    private void bringToFrontFull() {
        if (OverlayKeyGate.isOverlayKeysActive()) return;
        if (reclaimSuspended || isSessionIdle()) return;
        if (isNonSolarAppForeground()) return;
        lastBringToFrontFullMs = System.currentTimeMillis();
        String topBefore = topResumedActivityName();
        // #region agent log
        if (DebugSessionLog.ENABLED) {
            try {
                JSONObject d = new JSONObject();
                d.put("topBefore", topBefore);
                d.put("massStorageIntercept", massStorageIntercept);
                d.put("interceptPaused", interceptPaused);
                DebugSessionLog.log("Y1UsbFocusHelper.bringToFrontFull", "HOME once", "H2", d);
            } catch (Exception ignored) {}
        }
        // #endregion
        bringToFrontLightPublic();
        try {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(startMain);
        } catch (Exception ignored) {}
    }

    private boolean isSystemUiUsbOnTop() {
        String top = topResumedActivityName();
        return top != null && top.contains("UsbStorageActivity");
    }

    /** True when another app (not Solar, not SystemUI USB) owns the screen. */
    private boolean isNonSolarAppForeground() {
        String top = topResumedActivityName();
        if (top == null || top.startsWith("?") || top.startsWith("err:")) return false;
        if (top.contains("com.solar.launcher")) return false;
        if (top.contains("UsbStorageActivity")) return false;
        return true;
    }

    public void bringToFrontLightPublic() {
        if (reclaimSuspended || isSessionIdle()) return;
        if (isNonSolarAppForeground()) return;
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(activity.getTaskId(), 0);
            }
        } catch (Exception ignored) {}
        try {
            activity.getWindow().getDecorView().requestFocus();
        } catch (Exception ignored) {}
    }

    private String topResumedActivityName() {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "?";
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return "?";
            ActivityManager.RunningTaskInfo t = tasks.get(0);
            if (t.topActivity != null) return t.topActivity.flattenToShortString();
            return "?";
        } catch (Exception e) {
            return "err:" + e.getClass().getSimpleName();
        }
    }
}
