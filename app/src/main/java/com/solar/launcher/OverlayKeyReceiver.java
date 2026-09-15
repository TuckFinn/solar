package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import android.view.KeyEvent;

/**
 * Receives hardware keys forwarded from system_server while {@link OverlayKeyGate} is armed.
 * Runs in {@code :overlay} so keys reach the same process as {@link SolarOverlayService}.
 */
public final class OverlayKeyReceiver extends BroadcastReceiver {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!OverlayTriggers.ACTION_OVERLAY_KEY.equals(intent.getAction())) return;
        if (!OverlayKeyGate.isOverlayKeysActive()) return;
        final int keyCode = intent.getIntExtra(OverlayTriggers.EXTRA_KEY_CODE, 0);
        if (keyCode == 0) return;
        final int action = intent.getIntExtra(OverlayTriggers.EXTRA_KEY_ACTION, KeyEvent.ACTION_DOWN);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("keyCode", keyCode);
            d.put("action", action);
            d.put("prop", readProp());
            DebugOverlayKeyLog.log("OverlayKeyReceiver.onReceive", "xposed forward", "H2", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (action == KeyEvent.ACTION_UP) {
                    OverlayKeyGate.deliverUp(keyCode);
                } else {
                    OverlayKeyGate.deliver(keyCode);
                }
            }
        });
    }

    private static String readProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, OverlayKeyGate.ACTIVE_PROPERTY, "0");
            return v != null ? v.toString() : "?";
        } catch (Exception e) {
            return "?";
        }
    }
}
