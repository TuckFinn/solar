package com.solar.launcher;

import com.solar.launcher.platform.PlatformProbe;

/**
 * 2026-07-06 — Force platform APK installs onto internal flash, never MicroSD (Y1 auto=0).
 * Layman: Solar hook apps and notPipe must live on the player, not the SD card.
 * Technical: pm set-install-location 1 + pm install -r -f on KitKat when root available.
 * Reversal: delete; stock auto install location may push apps to /storage/sdcard0 again.
 */
public final class PmInstallPolicy {

    private static final String TAG = "PmInstallPolicy";
    private static volatile boolean locationPinned;

    private PmInstallPolicy() {}

    /** One-shot per process — pin PM to internal-only before any platform pm install. */
    public static void enforceInternalInstallLocation() {
        if (!RootShell.canRun()) return;
        if (locationPinned) return;
        locationPinned = true;
        RootShell.run("pm set-install-location 1");
        SolarLog.i(TAG, "pm set-install-location 1 (internal only)");
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("location", RootShell.runCapture("pm get-install-location"));
            Debug531722Log.log("PmInstallPolicy.enforceInternalInstallLocation",
                    "pinned internal install", "H1", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /**
     * pm install -r -f from /system/app — Solar platform contract (2026-07-06).
     * Layman: hook apps live on the system partition like a ROM flash, not /data/app copies.
     * Technical: wipe /data/app drift, then pm install from baked /system/app path.
     */
    public static boolean installSystemApp(String systemApkPath, String pkg) {
        if (systemApkPath == null || !systemApkPath.startsWith("/system/")
                || !RootShell.canRun()) {
            return false;
        }
        if (!PlatformProbe.fileExists(systemApkPath)) {
            return false;
        }
        enforceInternalInstallLocation();
        if (pkg != null && !pkg.isEmpty()) {
            RootShell.run("rm -rf /data/app/" + pkg + "* /data/app-lib/" + pkg + "*");
        }
        String pmOut = RootShell.runCapture(
                "pm install -r -f " + PlatformProbe.shellQuote(systemApkPath));
        boolean ok = pmOut != null && pmOut.contains("Success");
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("ok", ok);
            d.put("path", systemApkPath);
            d.put("pkg", pkg != null ? pkg : "");
            d.put("pmOut", pmOut != null ? pmOut.trim() : "");
            Debug531722Log.log("PmInstallPolicy.installSystemApp", "system/app pm install", "H3", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return ok;
    }

    /**
     * pm install -r -f — internal flash, allow replace (2026-07-06).
     * Layman: fallback when staging to /system/app is not possible yet.
     * Prefer {@link #installSystemApp} for Solar helpers and Xposed modules.
     */
    public static boolean installInternal(String apkPath) {
        if (apkPath == null || apkPath.isEmpty() || !RootShell.canRun()) return false;
        enforceInternalInstallLocation();
        String pmOut = RootShell.runCapture(
                "pm install -r -f " + PlatformProbe.shellQuote(apkPath));
        boolean ok = pmOut != null && pmOut.contains("Success");
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("ok", ok);
            d.put("path", apkPath);
            d.put("pmOut", pmOut != null ? pmOut.trim() : "");
            Debug531722Log.log("PmInstallPolicy.installInternal", "pm install -r -f", "H2", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return ok;
    }

    /** Test hook — parse Success without shell. */
    static boolean isPmSuccess(String pmOut) {
        return pmOut != null && pmOut.contains("Success");
    }
}
