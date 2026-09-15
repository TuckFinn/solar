package com.solar.launcher.platform;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * 2026-07-05 — Platform prep entry; first-boot uses visible wizard via SolarLaunchActivity.
 * 2026-07-16 — Silent prep remains for gap-heal after UI is already up (not cold blank screen).
 * Reversal: only ensureAsync silent; delete first-boot wizard route in SolarLaunchActivity.
 */
public final class PlatformPrepLauncher {

    private static volatile boolean silentPrepScheduled;

    private PlatformPrepLauncher() {}

    /**
     * Schedule silent prep when prepVersion ahead.
     * 2026-07-16 — When first-session gate is still open, prefer visible wizard (already launched
     * from SolarLaunchActivity); silent path is a safety net if MainActivity was entered directly.
     */
    public static void ensureAsync(Context ctx) {
        if (ctx == null) return;
        try {
            PlatformPrepManifest manifest = PlatformPrepManifest.load(ctx);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                JSONObject d = new JSONObject();
                d.put("prepVersion", manifest.prepVersion);
                d.put("applied", PlatformPrepState.getAppliedVersion(ctx));
                d.put("needsSilent", PlatformPrepState.needsSilentPrep(ctx, manifest));
                d.put("gettingReady",
                        com.solar.launcher.FirstSessionReadyGate.shouldShowGettingReady(ctx));
                d.put("uiReady",
                        com.solar.launcher.FirstSessionReadyGate.isUiReadyComplete(ctx));
                d.put("rebootPending", PlatformPrepState.isRebootPending(ctx));
                com.solar.launcher.DebugA1f293Log.log(ctx, "PlatformPrepLauncher.ensureAsync",
                        "prep gate", "A", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            if (!PlatformPrepState.needsSilentPrep(ctx, manifest)) {
                silentPrepScheduled = false;
                // #region agent log
                JSONObject d = new JSONObject();
                try {
                    d.put("prepVersion", manifest.prepVersion);
                    d.put("applied", PlatformPrepState.getAppliedVersion(ctx));
                    d.put("skipped", true);
                } catch (Exception ignored) {}
                PlatformPrepDebugLog.log(ctx, "PlatformPrepLauncher.ensureAsync",
                        "ROM at prepVersion — skip", "H1", d);
                // #endregion
                return;
            }
        } catch (Exception e) {
            return;
        }
        // 2026-07-16 — If first UI not ready yet, open wizard once instead of silent-only blank.
        if (com.solar.launcher.FirstSessionReadyGate.shouldShowGettingReady(ctx)
                && !com.solar.launcher.DeviceFeatures.isA5()) {
            try {
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("launchWizard", true);
                    com.solar.launcher.DebugA1f293Log.log(ctx, "PlatformPrepLauncher.ensureAsync",
                            "launch first-boot wizard", "A", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                Intent i = new Intent(ctx, PlatformPrepWizardActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra(PlatformPrepWizardActivity.EXTRA_FIRST_BOOT, true);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        if (silentPrepScheduled) return;
        silentPrepScheduled = true;
        SolarPlatformPrep.ensureAsync(ctx.getApplicationContext());
    }

    /** Manual repair from Settings — opens blocking wizard. */
    public static void launchManualWizard(Context ctx) {
        if (ctx == null) return;
        Intent i = new Intent(ctx, PlatformPrepWizardActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(PlatformPrepWizardActivity.EXTRA_MANUAL_REPAIR, true);
        ctx.startActivity(i);
    }

    /**
     * 2026-07-19 — After module install/enable: show getting-ready face and auto-reboot.
     * Layman: no “press Restart” — Solar restarts the player while the ready message stays up.
     * Was: reboot confirm button. Reversal: restore EXTRA_REBOOT_ONLY confirm UI.
     */
    public static void launchRebootWizard(Context ctx) {
        if (ctx == null) return;
        Intent i = new Intent(ctx, PlatformPrepWizardActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(PlatformPrepWizardActivity.EXTRA_REBOOT_ONLY, true);
        ctx.startActivity(i);
    }

    /** @deprecated Use {@link #ensureAsync} — blocking launch caused prep loops. */
    public static boolean launchIfRequired(Context ctx) {
        ensureAsync(ctx);
        return false;
    }
}
