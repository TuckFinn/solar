package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.Locale;
import java.util.Random;

public final class WirelessAdbEnabler {
    private static final String PREFS = "SOLAR_SETTINGS";
    private static final String PREF_RANDOMIZED = "adb_id_randomized_v1";

    private WirelessAdbEnabler() {}

    public static void ensureWirelessAdb(final Context context) {
        if (context == null) return;
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("settings.debug.wireless_adb_enabled", true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!canRunSu()) return;
                String randomId = prefs.getString("adb_id_randomized_value_v1", null);
                if (randomId == null) {
                    randomId = generateRandomAdbId();
                    prefs.edit().putString("adb_id_randomized_value_v1", randomId).commit();
                }
                String cmd = "settings put global adb_enabled 1; settings put secure adb_enabled 1; "
                        + "setprop service.adb.tcp.port 5555; "
                        + "setprop persist.service.adb.enable 1; "
                        + "setprop persist.service.debuggable 1; "
                        + "setprop persist.usb.serialno " + randomId + "; "
                        + "setprop sys.usb.serialno " + randomId + "; "
                        + "setprop persist.sys.usb.serialno " + randomId + "; "
                        + "if [ -d /data/property ]; then echo -n " + randomId + " > /data/property/persist.usb.serialno; echo -n " + randomId + " > /data/property/persist.sys.usb.serialno; chmod 600 /data/property/persist.usb.serialno; chmod 600 /data/property/persist.sys.usb.serialno; fi; "
                        + "if [ -f /sys/class/android_usb/android0/iSerial ]; then echo -n " + randomId + " > /sys/class/android_usb/android0/iSerial; fi; "
                        + "if [ -f /sys/devices/virtual/android_usb/android0/iSerial ]; then echo -n " + randomId + " > /sys/devices/virtual/android_usb/android0/iSerial; fi; "
                        + "if [ -f /config/usb_gadget/g1/strings/0x409/serialnumber ]; then echo -n " + randomId + " > /config/usb_gadget/g1/strings/0x409/serialnumber; fi; "
                        + "stop adbd; start adbd; setprop ctl.restart adbd";
                runSu(cmd);
            }
        }, "WirelessAdb").start();
    }

    public static void checkAndRandomizeAdbId(final MainActivity activity) {
        if (activity == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                // 2026-09-15 — This ran once per launcher start and rebooted the device two
                // seconds later. Its "already done" flag lived in SOLAR_SETTINGS, and that
                // reboot lands while Solar is still flushing preferences, so the file came
                // back empty, the flag was gone, and the next start randomized and rebooted
                // again: a self-sustaining reboot loop that also explains the device's adb
                // serial flipping between boots. Record it durably as well — a file in the
                // app's own storage and a persist property, neither of which rides on the
                // preferences file — and honour any of the three.
                java.io.File marker = new java.io.File(activity.getFilesDir(), "adb_id_randomized_v1");
                if (prefs.getBoolean(PREF_RANDOMIZED, false) || marker.exists()
                        || "1".equals(getProp("persist.solar.adb_id_randomized"))) {
                    return;
                }
                if (!canRunSu()) return;
                String randomId = prefs.getString("adb_id_randomized_value_v1", null);
                if (randomId == null) {
                    randomId = generateRandomAdbId();
                    prefs.edit().putString("adb_id_randomized_value_v1", randomId).commit();
                }
                String cmd = "setprop persist.usb.serialno " + randomId + "; "
                        + "setprop sys.usb.serialno " + randomId + "; "
                        + "setprop persist.sys.usb.serialno " + randomId + "; "
                        + "if [ -d /data/property ]; then echo -n " + randomId + " > /data/property/persist.usb.serialno; echo -n " + randomId + " > /data/property/persist.sys.usb.serialno; chmod 600 /data/property/persist.usb.serialno; chmod 600 /data/property/persist.sys.usb.serialno; fi; "
                        + "if [ -f /sys/class/android_usb/android0/iSerial ]; then echo -n " + randomId + " > /sys/class/android_usb/android0/iSerial; fi; "
                        + "if [ -f /sys/devices/virtual/android_usb/android0/iSerial ]; then echo -n " + randomId + " > /sys/devices/virtual/android_usb/android0/iSerial; fi; "
                        + "if [ -f /config/usb_gadget/g1/strings/0x409/serialnumber ]; then echo -n " + randomId + " > /config/usb_gadget/g1/strings/0x409/serialnumber; fi";
                runSu(cmd + "; setprop persist.solar.adb_id_randomized 1");
                // Durable markers first: if anything below loses the preferences file, this
                // must still never run a second time.
                try {
                    if (!marker.exists()) marker.createNewFile();
                } catch (java.io.IOException ignored) {}
                prefs.edit().putBoolean(PREF_RANDOMIZED, true).commit();
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.showAdbRebootOverlay(activity.getString(R.string.adb_setup_reboot_warning));
                    }
                });
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
                runSu("sync; reboot; /system/bin/reboot; /system/xbin/reboot");
            }
        }, "AdbRandomize").start();
    }

    /** Read a system property without a hard dependency on the hidden API. */
    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isPort5555AndRunning() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String port = (String) sp.getMethod("get", String.class, String.class).invoke(null, "service.adb.tcp.port", "");
            String status = (String) sp.getMethod("get", String.class, String.class).invoke(null, "init.svc.adbd", "");
            return "5555".equals(port) && "running".equals(status);
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean canRunSu() {
        for (String su : new String[] {"/system/xbin/su", "su"}) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[] { su, "-c", "id" });
                if (p.waitFor() == 0) return true;
            } catch (Exception ignored) {
            } finally {
                if (p != null) p.destroy();
            }
        }
        return false;
    }

    private static boolean runSu(String command) {
        if (command == null || command.trim().isEmpty()) return false;
        for (String su : new String[] {"/system/xbin/su", "su"}) {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[] { su, "-c", "sh -c '" + command.replace("'", "'\\''") + "'" });
                if (process.waitFor() == 0) return true;
            } catch (Exception ignored) {
            } finally {
                if (process != null) process.destroy();
            }
        }
        return false;
    }

    private static String generateRandomAdbId() {
        StringBuilder sb = new StringBuilder("SL");
        Random r = new Random();
        for (int i = 0; i < 10; i++) {
            sb.append(Integer.toHexString(r.nextInt(16)).toUpperCase(Locale.US));
        }
        return sb.toString();
    }
}
