package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.solar.launcher.overlay.OverlayShellRouter;

/**
 * Open entry for power/volume overlay — no signature permission (system_server + shell am start).
 * 2026-07-14 — Routes to OverlayShellRouter (Solar ThemedContextMenu by default).
 * Was: companion Chip primary → dual chrome. Reversal: companion_shell=1.
 */
public final class PowerOverlayOpenReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!OverlayTriggers.ACTION_SHOW_OVERLAY_POWER.equals(action)
                && !OverlayTriggers.ACTION_SHOW_OVERLAY_VOLUME.equals(action)) {
            return;
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("action", action);
            d.put("process", processName(context));
            d.put("shell", OverlayShellRouter.overlayPackage());
            d.put("sessionId", "083511");
            DebugMenuLog.log("PowerOverlayOpenReceiver.onReceive", "open overlay", "H-RBX", d);
            Debug383b4eLog.log(context, "PowerOverlayOpenReceiver.onReceive",
                    "open overlay via router", "DUAL", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        OverlayHandoffRestoreReceiver.notifyPause(context);
        // One shell — Solar ThemedContextMenu unless companion_shell=1.
        // Drop the other shell first so Chip + Solar never stack.
        OverlayShellRouter.dismissPeerOverlayShell(context);
        ComponentName shell = OverlayShellRouter.overlayComponent();
        Intent svc = new Intent(action);
        svc.setComponent(shell);
        try {
            context.startService(svc);
        } catch (Exception e) {
            android.util.Log.w("PowerOverlayOpen", "startService failed: " + e.getMessage());
            // Fail-open: Solar :overlay if companion missing.
            try {
                Intent legacy = new Intent(context, SolarOverlayService.class);
                legacy.setAction(action);
                context.startService(legacy);
            } catch (Exception ignored) {}
        }
    }

    /** Debug: confirm overlay open runs in :overlay / companion, not main Solar process. */
    private static String processName(Context context) {
        try {
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getRunningAppProcesses() != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo info
                        : am.getRunningAppProcesses()) {
                    if (info.pid == pid) return info.processName;
                }
            }
        } catch (Exception ignored) {}
        return "?";
    }
}
