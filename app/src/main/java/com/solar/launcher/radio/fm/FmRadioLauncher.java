package com.solar.launcher.radio.fm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.solar.launcher.DebugE47f4cLog;
import com.solar.launcher.ExternalInputHandoff;
import com.solar.launcher.R;

/**
 * Stock MediaTek FM Radio — launch + Y1 wheel handoff (MODE_FM) when Solar experiment is off.
 * 2026-07-06 — same third-party path as Apps menu; overlay keys via solar-context-bridge.
 */
public final class FmRadioLauncher {

    /** Same package as {@link com.solar.launcher.ExternalInputHandoff#FM_RADIO_PACKAGE}. */
    public static final String PACKAGE = "com.mediatek.FMRadio";

    private FmRadioLauncher() {}

    /** True when MTK FM Radio is installed on /system. */
    public static boolean isAvailable(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Open MTK FMRadio and arm FM-specific wheel remap before Solar loses focus.
     * @param onFocusLoss set {@code MainActivity.isIntentionalFocusLoss} when non-null
     */
    public static boolean launch(Activity activity, Runnable onFocusLoss, Runnable onLaunchFailed) {
        if (activity == null) return false;
        PackageManager pm = activity.getPackageManager();
        Intent launch = pm != null ? pm.getLaunchIntentForPackage(PACKAGE) : null;
        if (launch == null) {
            Toast.makeText(activity, R.string.toast_fm_unavailable, Toast.LENGTH_SHORT).show();
            if (onLaunchFailed != null) onLaunchFailed.run();
            return false;
        }
        if (onFocusLoss != null) onFocusLoss.run();
        // 2026-07-06 — Stock MTK FM needs airplane off; restore on Solar focus return.
        FmAirplaneModeHelper.beginMtkFallbackSession(activity);
        ExternalInputHandoff.setDpadMode(ExternalInputHandoff.MODE_FM);
        ExternalInputHandoff.armFastInjector(activity);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("dpadMode", ExternalInputHandoff.getDpadMode());
            d.put("injectReady", com.solar.launcher.RootKeyInjector.isReady());
            d.put("hasWindowFocus", activity.hasWindowFocus());
            DebugE47f4cLog.log("FmRadioLauncher.launch", "pre-startActivity", "H2-H5", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(launch);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("dpadMode", ExternalInputHandoff.getDpadMode());
                d.put("fg", ExternalInputHandoff.getForegroundPackageName(activity));
                DebugE47f4cLog.log("FmRadioLauncher.launch", "post-startActivity ok", "H3-H5", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return true;
        } catch (Exception e) {
            FmAirplaneModeHelper.endMtkFallbackSession(activity);
            ExternalInputHandoff.forceDisarmForSolarFocus();
            Toast.makeText(activity, R.string.toast_fm_unavailable, Toast.LENGTH_SHORT).show();
            if (onLaunchFailed != null) onLaunchFailed.run();
            return false;
        }
    }
}
