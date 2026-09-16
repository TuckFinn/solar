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

    /** True in the launcher process; false in {@code :overlay}, {@code :watchdog}, etc. */
    public static boolean isMain(Context context) {
        if (context == null) return true;
        try {
            int pid = android.os.Process.myPid();
            ActivityManager am = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null || am.getRunningAppProcesses() == null) return true;
            for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (info.pid == pid) {
                    return info.processName != null
                            && info.processName.equals(context.getPackageName());
                }
            }
        } catch (Exception ignored) {}
        // Unknown: behave as before rather than silently skipping work.
        return true;
    }
}
