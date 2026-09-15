package com.solar.launcher.globalcontext;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 2026-07-05 — Root evdev tier-3 bootstrap from companion boot (Phase 2c).
 * Layman: starts the BACK/power hold watcher so overlays work even if Solar is stopped.
 * Technical: app_process runs companion entry with companion+Solar classpath (Phase 2c).
 * Reversal: delete; 99SolarInit.sh + Solar GlobalOverlayTrigger own daemon again.
 */
public final class CompanionGlobalOverlayTrigger {

    private static final String TAG = "CompanionOverlayTrig";
    private static final String COMPANION_APK_SYS = "/system/app/SolarGlobalContextModal.apk";
    private static final String DAEMON_MAIN = "com.solar.launcher.globalcontext.CompanionRootInputDaemon";
    private static final String SOLAR_DAEMON_FALLBACK = "com.solar.launcher.GlobalOverlayTriggerMain";
    private static final String[] SU_PATHS = {"/system/xbin/su", "/system/bin/su", "su"};
    private static volatile boolean started;

    private CompanionGlobalOverlayTrigger() {}

    /** Idempotent boot hook — companion BootReceiver calls this after services start. */
    public static void ensureStarted(Context context) {
        if (context == null) return;
        if (started) return;
        synchronized (CompanionGlobalOverlayTrigger.class) {
            if (started) return;
            started = true;
        }
        final String solarApk = resolveSolarClasspath(context);
        final String companionApk = resolveCompanionClasspath(context);
        new Thread(new Runnable() {
            @Override
            public void run() {
                bootDaemon(companionApk, solarApk);
            }
        }, "CompanionOverlayTrigBoot").start();
    }

    private static String resolveCompanionClasspath(Context context) {
        if (new File(COMPANION_APK_SYS).isFile()) return COMPANION_APK_SYS;
        try {
            return context.getPackageManager()
                    .getApplicationInfo("com.solar.launcher.globalcontext", 0)
                    .sourceDir;
        } catch (Exception e) {
            return context.getPackageCodePath();
        }
    }

    private static String resolveSolarClasspath(Context context) {
        String sysApk = "/system/app/com.solar.launcher.apk";
        if (new File(sysApk).isFile()) return sysApk;
        try {
            return context.getPackageManager()
                    .getApplicationInfo(com.solar.input.policy.GlobalInputPolicy.SOLAR_PKG, 0)
                    .sourceDir;
        } catch (Exception e) {
            return context.getPackageCodePath();
        }
    }

    private static void bootDaemon(String companionApk, String solarApk) {
        if (solarApk == null || solarApk.length() == 0) {
            started = false;
            return;
        }
        for (String suPath : SU_PATHS) {
            if (tryBoot(suPath, companionApk, solarApk)) return;
        }
        Log.w(TAG, "daemon boot failed — root tier-3 inactive");
        started = false;
    }

    private static boolean tryBoot(String suPath, String companionApk, String solarApk) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{suPath});
            OutputStream stdin = proc.getOutputStream();
            String mainClass = (companionApk != null && companionApk.length() > 0)
                    ? DAEMON_MAIN : SOLAR_DAEMON_FALLBACK;
            String cp = (companionApk != null && companionApk.length() > 0)
                    ? companionApk + ":" + solarApk : solarApk;
            // 2026-09-14 — Do not spawn a second root input daemon. `started` only lives as
            // long as this process; after a low-memory kill the companion came back, spawned
            // again, and the rescue supervisor pruned the duplicate every pass — a VM start
            // and a kill per minute, forever. One grep over /proc/*/cmdline in the same su
            // shell settles it; a live daemon is reported READY exactly like a fresh one.
            String cmd = "if grep -l '" + mainClass + "' /proc/[0-9]*/cmdline >/dev/null 2>&1; then"
                    + " echo READY; exit 0; fi\n"
                    + "export CLASSPATH='" + cp.replace("'", "'\\''") + "'\n"
                    + "exec app_process /system/bin " + mainClass + "\n";
            stdin.write(cmd.getBytes("UTF-8"));
            stdin.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"));
            String line;
            long deadline = System.currentTimeMillis() + 8000L;
            while ((line = reader.readLine()) != null) {
                if (line.contains("READY")) {
                    Log.i(TAG, "root overlay daemon ready");
                    drainQuietly(proc);
                    return true;
                }
                if (System.currentTimeMillis() > deadline) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "boot via " + suPath + ": " + e.getMessage());
        } finally {
            if (proc != null) proc.destroy();
        }
        return false;
    }

    private static void drainQuietly(final Process proc) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), "UTF-8"));
                    while (r.readLine() != null) {}
                } catch (Exception ignored) {}
            }
        }, "CompanionOverlayTrigDrain").start();
    }
}
