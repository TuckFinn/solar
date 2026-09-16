package com.solar.launcher;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Which process this code is running in.
 *
 * SOLAR_SETTINGS is opened MODE_PRIVATE, so every process keeps its own cached
 * copy of it and a commit rewrites the whole file from that copy. A helper
 * process that loaded the file before someone else wrote to it, and commits
 * after, silently reverts everything in between -- which is how provisioned
 * settings kept disappearing and home_menu_order kept reverting to an older
 * value (2026-09-16). SolarApplication.onCreate runs in every process, so
 * anything it reaches has to decide whether it really means to write.
 *
 * Device-wide state belongs to the launcher's own process. The helpers read it
 * through the sysprop mirrors instead.
 */
public final class SolarProcess {

    private SolarProcess() {}

    private static volatile String cachedName;

    /**
     * This process's name, from {@code /proc/self/cmdline}.
     *
     * ActivityManager.getRunningAppProcesses() was the first attempt and it is
     * not dependable here: when it returns nothing the caller cannot tell a
     * helper from the launcher, and failing open meant the helper wrote anyway
     * and the clobber continued. The kernel always knows the name.
     */
    public static String name() {
        String cached = cachedName;
        if (cached != null) return cached;
        String found = "";
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n > 0) {
                int end = 0;
                while (end < n && buf[end] != 0) end++;   // NUL-terminated
                found = new String(buf, 0, end, "UTF-8").trim();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored2) {}
        }
        cachedName = found;
        return found;
    }

    /** True in the launcher process; false in {@code :overlay}, {@code :watchdog}, etc. */
    public static boolean isMain(Context context) {
        if (context == null) return true;
        String self = name();
        // A process name with a colon is always a helper, whatever else is
        // true, so that alone is enough to refuse a write.
        if (self.indexOf(':') >= 0) return false;
        if (self.isEmpty()) {
            // Fall back to ActivityManager rather than guess.
            try {
                int pid = android.os.Process.myPid();
                ActivityManager am = (ActivityManager)
                        context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && am.getRunningAppProcesses() != null) {
                    for (ActivityManager.RunningAppProcessInfo info
                            : am.getRunningAppProcesses()) {
                        if (info.pid == pid && info.processName != null) {
                            return info.processName.equals(context.getPackageName());
                        }
                    }
                }
            } catch (Exception ignored) {}
            return true;
        }
        return self.equals(context.getPackageName());
    }
}
