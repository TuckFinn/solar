package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 2026-07-10 — USB mass storage is Y1-only product path; Y2 stays MTP (UMS not working on 4.4.2).
 * Layman: Y1 can show the PC as a disk after Turn on; Y2 always uses normal phone file transfer.
 * Tech: gates UI + {@link UsbMassStorageController}; {@link #SYSPROP} stays 0 on Y2 for Xposed.
 * Was: Y2 opt-in experiment. Now: Y2 hard-off until a real MT6582 UMS path exists.
 * Reversal: restore experiment pref path on Y2 when hooks+LUN bind are proven.
 */
public final class UsbMassStorageExperiment {

    public static final String PREF_USB_MASS_STORAGE_EXPERIMENT = "solar_y2_usb_mass_storage_experiment";
    /** Read by SolarContextBridgeY2 {@code UsbMassStorageServerHooks} — always 0 on Y2 product. */
    public static final String SYSPROP = "sys.solar.ums.experiment";

    private UsbMassStorageExperiment() {}

    /**
     * Debug UMS experiment row — no longer shown (Y2 UMS retired for now).
     * Kept for pref migration / old menus.
     */
    public static boolean isAvailableOnDevice() {
        return false;
    }

    /** Y1 always; Y2 never (MTP-only product policy 2026-07-10). */
    public static boolean isEnabled(SharedPreferences prefs) {
        return !DeviceFeatures.isY2();
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return !DeviceFeatures.isY2();
        return isEnabled(PrefsWriteAudit.get(ctx, "SOLAR_SETTINGS"));
    }

    /** Force sysprop 0 on Y2 so system_server UMS hooks stay off; no-op on Y1. */
    public static void syncExperimentSysprop(Context ctx) {
        if (ctx == null || !DeviceFeatures.isY2()) return;
        if (!RootShell.canRun()) return;
        RootShell.run("setprop " + SYSPROP + " 0");
        UsbMassStorageController.ensureStockMtpWhenExperimentOff(ctx);
    }

    /** No-op — Y2 UMS cannot be enabled in product builds. */
    public static void setEnabled(Context ctx, boolean enable) {
        if (ctx == null || !DeviceFeatures.isY2()) return;
        PrefsWriteAudit.get(ctx, "SOLAR_SETTINGS")
                .edit()
                .putBoolean(PREF_USB_MASS_STORAGE_EXPERIMENT, false)
                .apply();
        syncExperimentSysprop(ctx);
    }

    /** Test hook — family only (Y2 always blocked regardless of pref). */
    static boolean isEnabledForFamily(String family, boolean experimentPref) {
        if (family != null && "y2".equals(family)) return false;
        return true;
    }

    /** Connections → USB (UMS) submenu: Y1 only. */
    static boolean connectionsUsbMenuVisibleForFamily(String family, boolean experimentPref) {
        if (family != null && "y2".equals(family)) return false;
        return true;
    }
}
