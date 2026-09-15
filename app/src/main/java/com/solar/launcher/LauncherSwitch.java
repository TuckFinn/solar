package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.solar.home.policy.HomeTargetPolicy;

import java.io.File;
import java.util.List;

/**
 * 2026-07-05 — No-reboot Solar ↔ Rockbox handoff via /data/data/switch-to-stock.sh.
 * Rockbox APK invokes this script directly — must stay rebootless with no .kl swap on return.
 * When changing: assets/y1/switch-to-stock.sh + ROM /system/etc/solar/ copy; Y1RomPrep seeds.
 * Reversal: revert to stock rockbox-y1 script that reboots and swaps keylayouts.
 */
public final class LauncherSwitch {

    public static final String ROCKBOX_PACKAGE = "org.rockbox";
    private static final String SWITCH_SCRIPT = "sh /data/data/switch-to-stock.sh";
    private static final String SWITCH_TO_ROCKBOX = SWITCH_SCRIPT + " --rockbox";
    private static final String SWITCH_TO_JJ = SWITCH_SCRIPT + " --jj";
    /** 2026-07-08 — Factory Innioasis HOME via solar-launcher-exec stock target. */
    private static final String SWITCH_TO_INNIOASIS = SWITCH_SCRIPT + " --innioasis";

    private LauncherSwitch() {}

    /**
     * 2026-07-08 — Device-family stock Innioasis package (y1 on Y1, y2 on Y2).
     * Reversal: hardcode com.innioasis.y1 only.
     */
    public static String stockPackageForDevice(Context context) {
        if (context == null) return HomeTargetPolicy.INNIOASIS_Y1_PKG;
        boolean y2 = DeviceFeatures.isY2();
        String preferred = HomeTargetPolicy.stockPackageForDevice(y2);
        if (isPackagePresent(context, preferred)) return preferred;
        // Fail-open: other family APK may be sideloaded on this device.
        String other = y2 ? HomeTargetPolicy.INNIOASIS_Y1_PKG : HomeTargetPolicy.INNIOASIS_Y2_PKG;
        if (isPackagePresent(context, other)) return other;
        return preferred;
    }

    /** True when either factory Innioasis HOME package is registered with PM. */
    public static boolean isStockInstalled(Context context) {
        if (context == null) return false;
        return isPackagePresent(context, HomeTargetPolicy.INNIOASIS_Y1_PKG)
                || isPackagePresent(context, HomeTargetPolicy.INNIOASIS_Y2_PKG);
    }

    /** Offer Stock row when package installed — no OTA fetch (unlike JJ). */
    public static boolean isStockOfferVisible(Context context) {
        return isStockInstalled(context);
    }

    /** True when preferred stock pkg is installed but pm-disabled. */
    public static boolean isStockDisabled(Context context) {
        String pkg = stockPackageForDevice(context);
        if (pkg == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0 && !info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True when stock Innioasis HOME owns the foreground task. */
    public static boolean isStockForeground(Context context) {
        String fg = ExternalInputHandoff.getForegroundPackageName(context);
        return HomeTargetPolicy.isInnioasisStockPackage(fg);
    }

    /**
     * 2026-07-08 — PM-resolved MAIN/LAUNCHER activity for stock pkg; policy default on miss.
     * Layman: find the real screen that opens when you pick factory home.
     * Reversal: return HomeTargetPolicy.stockActivityForPackage(pkg) only.
     */
    public static String resolveStockLaunchActivity(Context context, String pkg) {
        if (pkg == null) return null;
        String fallback = HomeTargetPolicy.stockActivityForPackage(pkg);
        if (context == null) return fallback;
        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            main.setPackage(pkg);
            List<ResolveInfo> list = context.getPackageManager()
                    .queryIntentActivities(main, 0);
            if (list != null) {
                for (ResolveInfo info : list) {
                    if (info != null && info.activityInfo != null) {
                        return info.activityInfo.name;
                    }
                }
            }
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setPackage(pkg);
            list = context.getPackageManager().queryIntentActivities(home, 0);
            if (list != null) {
                for (ResolveInfo info : list) {
                    if (info != null && info.activityInfo != null) {
                        return info.activityInfo.name;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean isPackagePresent(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when com.themoon.y1 (JJ Launcher) is registered with Package Manager. */
    public static boolean isJjInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(LauncherDefault.JJ_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** JJ on /system/app or already in PM — used for availability before OTA install. */
    public static boolean isJjAvailable(Context context) {
        if (isJjInstalled(context)) return true;
        return new File("/system/app/com.themoon.y1.apk").exists();
    }

    /** True when JJ is installed but pm-disabled (typical when Solar/Rockbox is HOME). */
    public static boolean isJjDisabled(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(LauncherDefault.JJ_PACKAGE, 0);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0 && !info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True when JJ Launcher owns the foreground task. */
    public static boolean isJjForeground(Context context) {
        return LauncherDefault.JJ_PACKAGE.equals(
                ExternalInputHandoff.getForegroundPackageName(context));
    }

    /** True when org.rockbox is registered with Package Manager (enabled or disabled). */
    public static boolean isRockboxInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(ROCKBOX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Register org.rockbox with PM when the ROM ships /system/app/org.rockbox.apk but first boot
     * did not scan it (Y2 unpatched APK fails with SHARED_USER_INCOMPATIBLE until ROM is rebuilt).
     */
    public static boolean ensureRockboxRegistered(Context context) {
        if (isRockboxInstalled(context)) return true;
        File sysApk = new File("/system/app/org.rockbox.apk");
        if (!sysApk.isFile()) return false;
        // #region agent log
        DebugSessionLog.logAlways("LauncherSwitch.ensureRockboxRegistered", "attempt pm install", "H-E",
                debugState(context));
        // #endregion
        String out = RootShell.runCapture("pm install -r " + sysApk.getAbsolutePath());
        boolean ok = isRockboxInstalled(context);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = debugState(context);
            d.put("installOutput", out != null && out.length() > 200 ? out.substring(0, 200) : out);
            d.put("registered", ok);
            DebugSessionLog.logAlways("LauncherSwitch.ensureRockboxRegistered",
                    ok ? "registered" : "failed", "H-E", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return ok;
    }

    /** Solar ROM with co-installed Rockbox APK (package or /system/app blob). */
    public static boolean isRockboxAvailable(Context context) {
        if (isRockboxInstalled(context)) return true;
        return new File("/system/app/org.rockbox.apk").exists();
    }

    /** Launcher switch script shipped in Solar ROM (seeded to /data/data on boot). */
    public static boolean isSwitchScriptAvailable() {
        return new File("/data/data/switch-to-stock.sh").exists()
                || new File("/system/etc/solar/switch-to-stock.sh").exists();
    }

    /** True when Rockbox is installed but currently disabled (typical when Solar is home). */
    public static boolean isRockboxDisabled(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(ROCKBOX_PACKAGE, 0);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0
                    && !info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True when Rockbox is installed and currently enabled — should NOT be the case while Solar is home. */
    public static boolean isRockboxEnabled(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(ROCKBOX_PACKAGE, 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * While {@link MainActivity} is visible, keep preferred HOME on Solar.
     * Does not override a Rockbox HOME choice on boot — use {@link #ensurePreferredHome} there.
     */
    public static void ensureRockboxDisabled(final Context context) {
        ensureSolarPreferredHome(context);
    }

    /** Re-apply stored HOME target on boot — respects Switch to Rockbox / switch-to-stock script. */
    public static void ensurePreferredHome(final Context context) {
        if (context == null) return;
        if (!RockboxDisable.isSolarEnabled(context)) return;
        final String target = LauncherPreference.getHomeTarget(context);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pm enable com.android.systemui/com.android.systemui.usb.UsbStorageActivity"}).waitFor();
                } catch (Exception ignored) {}
                LauncherDefault.ensureBothLaunchersEnabled(context);
                LauncherDefault.ensureHomeTarget(context, target);
            }
        }, "SolarPreferredHome").start();
    }

    /**
     * While Solar MainActivity is active, prefer Solar HOME and pm-disable Rockbox.
     * 2026-07-05 — skip via {@link RockboxRestartGrace} when Rockbox HOME and crash fallback.
     */
    public static void ensureSolarPreferredHome(final Context context) {
        if (context == null) return;
        if (!RockboxDisable.isSolarEnabled(context)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pm enable com.android.systemui/com.android.systemui.usb.UsbStorageActivity"}).waitFor();
                } catch (Exception ignored) {}
                LauncherDefault.ensureBothLaunchersEnabled(context);
                LauncherDefault.ensureHomeTarget(context, LauncherDefault.TARGET_SOLAR);
            }
        }, "SolarPreferredHome").start();
    }

    /** True when Rockbox owns the foreground task — overlay queue chip stays hidden. */
    public static boolean isRockboxForeground(Context context) {
        return ROCKBOX_PACKAGE.equals(ExternalInputHandoff.getForegroundPackageName(context));
    }

    /**
     * 2026-07-06 — Only pm-disable alternate launchers while Solar is the saved HOME target.
     * Layman: respect JJ/Rockbox mode until the user picks Switch to Solar.
     */
    public static void assertInactiveLaunchersDisabledForHomeTarget(final Context context) {
        assertRockboxDisabledWhileSolarHome(context);
    }

    /**
     * 2026-07-05 — Only pm-disable Rockbox while Solar is the saved HOME target.
     * Layman: respect Rockbox mode until the user picks Switch to Solar.
     * Technical: no-op when persist.solar.home.target=rockbox — fixes onResume fighting crash recovery.
     */
    public static void assertRockboxDisabledWhileSolarHome(final Context context) {
        if (context == null) return;
        if (!LauncherPreference.isSolarHome(context)) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("homeTarget", LauncherPreference.getHomeTarget(context));
                d.put("rockboxFg", isRockboxForeground(context));
                Debug434250Log.log("LauncherSwitch.assertRockboxDisabledWhileSolarHome",
                        "skipped rockbox home", "H-A", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return;
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("homeTarget", LauncherPreference.getHomeTarget(context));
            Debug434250Log.log("LauncherSwitch.assertRockboxDisabledWhileSolarHome",
                    "solar home enforce", "H-A", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        ensureSolarPreferredHome(context);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("homeTarget", LauncherPreference.getHomeTarget(context));
            d.put("homeProp", LauncherPreference.readHomeTargetProperty());
            DebugD68c5cLog.log("LauncherSwitch.assertRockboxDisabledWhileSolarHome",
                    "solar home enforce", "B", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /** Switch back to Solar from Rockbox — preferred HOME + start MainActivity. */
    public static boolean switchToSolar(Context context) {
        boolean ok = RootShell.run(SWITCH_SCRIPT);
        DebugSessionLog.logAlways("LauncherSwitch.switchToSolar", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }

    /** Switch to Rockbox — sync keymap/codecs, enable Rockbox, start it, then disable Solar. */
    public static boolean switchToRockbox(Context context) {
        // #region agent log
        DebugSessionLog.logAlways("LauncherSwitch.switchToRockbox", "entry", "H-D",
                debugState(context));
        // #endregion
        if (context != null) {
            RockboxCoexistence.prepareForRockboxSwitch(context);
        }
        boolean ok = RootShell.run(SWITCH_TO_ROCKBOX);
        // #region agent log
        DebugSessionLog.logAlways("LauncherSwitch.switchToRockbox", ok ? "ok" : "failed", "H-D",
                debugState(context));
        // #endregion
        return ok;
    }

    /** Switch to JJ Launcher — enable JJ, disable Rockbox, start JJ. */
    public static boolean switchToJj(Context context) {
        boolean ok = RootShell.run(SWITCH_TO_JJ);
        DebugSessionLog.logAlways("LauncherSwitch.switchToJj", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }

    /**
     * 2026-07-08 — Switch to Stock Innioasis HOME — MODE_JJ inject while foreground.
     * Reversal: delete; use TARGET_CUSTOM + applyCustomHome only.
     */
    public static boolean switchToStock(Context context) {
        boolean ok = RootShell.run(SWITCH_TO_INNIOASIS);
        DebugSessionLog.logAlways("LauncherSwitch.switchToStock", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }

    // #region agent log
    private static org.json.JSONObject debugState(Context context) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("canRunSu", RootShell.canRun());
            o.put("rockboxInstalled", isRockboxInstalled(context));
            o.put("switchScript", isSwitchScriptAvailable());
            o.put("xbinSu", new File("/system/xbin/su").exists());
            o.put("binSu", new File("/system/bin/su").exists());
        } catch (Exception ignored) {}
        return o;
    }
    // #endregion
}
