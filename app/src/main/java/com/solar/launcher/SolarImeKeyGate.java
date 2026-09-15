package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * 2026-07-05 — Routes hardware keys to Solar IME tray while sys.solar.ime.active=1.
 * Layman: when the top typing strip is up, wheel and OK go to Solar instead of the app below.
 * Technical: parallel to OverlayKeyGate; forwards ACTION_IME_KEY to SolarInputMethodService.
 */
public final class SolarImeKeyGate {

    public interface Handler {
        boolean onKeyDown(int keyCode);
        boolean onKeyUp(int keyCode);
    }

    private static volatile Handler handler;
    private static volatile int lastDeliverKeyCode;
    private static volatile int lastDeliverAction;
    private static volatile long lastDeliverAt;
    private static final long DELIVER_DEDUPE_MS = 45L;

    private SolarImeKeyGate() {}

    /** IME tray shown — wire key handler in :overlay SolarInputMethodService. */
    public static boolean arm(Handler keyHandler) {
        boolean armed = SolarImeRouteArbiter.armIme();
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("armed", armed);
            d.put("handlerSet", armed);
            DebugImeLog.log(null, "SolarImeKeyGate.arm", "gate arm", "H3", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (!armed) return false;
        handler = keyHandler;
        return true;
    }

    /** IME dismissed — clear props and restore handoff. */
    public static void disarm() {
        handler = null;
        lastDeliverKeyCode = 0;
        lastDeliverAt = 0L;
        pausedForHigherOverlay = false;
        SolarImeRouteArbiter.disarm();
    }

    private static volatile boolean pausedForHigherOverlay;

    /** Global/USB overlay above keyboard — pause key forward, keep shell visible. */
    public static void pauseForHigherOverlay() {
        pausedForHigherOverlay = true;
        handler = null;
    }

    /** Higher overlay dismissed — re-arm if IME session still active. */
    public static void resumeFromHigherOverlay(Handler keyHandler) {
        pausedForHigherOverlay = false;
        if (SolarImeRouteArbiter.isActive() && keyHandler != null) {
            handler = keyHandler;
        }
    }

    /** OverlayKeyGate.disarm — clear pause flag; IME service re-binds handler on next key. */
    public static void clearPausedForHigherOverlay() {
        pausedForHigherOverlay = false;
    }

    public static boolean isPausedForHigherOverlay() {
        return pausedForHigherOverlay;
    }

    public static boolean isActive() {
        if (pausedForHigherOverlay) return SolarImeRouteArbiter.isActive();
        return handler != null || SolarImeRouteArbiter.isActive();
    }

    /** Wheel/back/center/side keys for IME tray navigation. */
    public static boolean isImeNavigationKey(int keyCode) {
        return Y1InputKeys.isWheelKey(keyCode)
                || Y1InputKeys.isBackKey(keyCode)
                || Y1InputKeys.isCenterKey(keyCode)
                || Y1InputKeys.isPlayPauseKey(keyCode)
                || Y1InputKeys.isTrackPreviousKey(keyCode)
                || Y1InputKeys.isTrackNextKey(keyCode);
    }

    /** Re-bind handler to live IME service instance (:overlay process recycle). */
    public static void rebindHandler(Handler keyHandler) {
        if (keyHandler == null) return;
        if (!SolarImeRouteArbiter.isActive() && !SolarImeRouteArbiter.isTrayUiVisible()) return;
        handler = keyHandler;
        pausedForHigherOverlay = false;
    }

    /** Deliver key from Xposed or root daemon into tray controller. */
    public static boolean deliver(int keyCode, int action) {
        if (isDuplicateDeliver(keyCode, action)) {
            return handler != null;
        }
        Handler h = handler;
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("keyCode", keyCode);
            d.put("action", action);
            d.put("hasHandler", h != null);
            d.put("imeActive", SolarImeRouteArbiter.isActive());
            DebugImeLog.log(null, "SolarImeKeyGate.deliver", "deliver", "H3", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (h == null) {
            // 2026-07-06 — Handler stale/missing — pulse root tier-3 IME forward.
            if (SolarImeRouteArbiter.isActive()) {
                SolarImeRouteArbiter.signalXposedMiss();
            }
            return false;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            return h.onKeyDown(keyCode);
        }
        if (action == KeyEvent.ACTION_UP) {
            return h.onKeyUp(keyCode);
        }
        return false;
    }

    /** Start service in :overlay — in-process deliver first, then startService fallback. */
    public static void forwardKeyToIme(Context ctx, int keyCode, int action) {
        if (ctx == null || keyCode == 0) return;
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return;
        if (!isActive() || pausedForHigherOverlay) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("keyCode", keyCode);
                d.put("propActive", SolarImeRouteArbiter.isActive());
                d.put("hasHandler", handler != null);
                d.put("paused", pausedForHigherOverlay);
                DebugImeLog.log(ctx, "SolarImeKeyGate.forwardKeyToIme", "dropped not active", "H3", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return;
        }
        if (deliver(keyCode, action)) return;
        // 2026-07-06 — In-process miss — root daemon may forward on xposed_miss pulse.
        if (SolarImeRouteArbiter.isActive()) {
            SolarImeRouteArbiter.signalXposedMiss();
        }
        try {
            Context app = ctx.getApplicationContext();
            Intent svc = new Intent(app, SolarInputMethodService.class);
            svc.setAction(OverlayTriggers.ACTION_IME_KEY);
            svc.putExtra(OverlayTriggers.EXTRA_KEY_CODE, keyCode);
            svc.putExtra(OverlayTriggers.EXTRA_KEY_ACTION, action);
            app.startService(svc);
        } catch (Exception ignored) {}
    }

    private static boolean isDuplicateDeliver(int keyCode, int action) {
        long now = SystemClock.uptimeMillis();
        if (keyCode == lastDeliverKeyCode && action == lastDeliverAction
                && now - lastDeliverAt < DELIVER_DEDUPE_MS) {
            return true;
        }
        lastDeliverKeyCode = keyCode;
        lastDeliverAction = action;
        lastDeliverAt = now;
        return false;
    }

    static void resetDeliverDedupeForTest() {
        lastDeliverKeyCode = 0;
        lastDeliverAction = 0;
        lastDeliverAt = 0L;
    }
}
