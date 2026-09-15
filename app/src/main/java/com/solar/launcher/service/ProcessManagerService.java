package com.solar.launcher.service;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.util.List;

/**
 * Simple foreground service that periodically cleans up unneeded background processes.
 * It keeps Solar Home, the current foreground app, and persistent services (IME, etc.) alive.
 * Users can disable per‑app via Settings (not implemented here).
 */
public class ProcessManagerService extends Service {
    private static final String TAG = "ProcessManagerService";
    private static final long CLEANUP_INTERVAL_MS = 30_000L; // 30 seconds
    private volatile boolean running = false;
    private Thread workerThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
    }

    /**
     * 2026-09-15 — This service can only work with KILL_BACKGROUND_PROCESSES, which a
     * non-system app cannot hold on API 17; the manifest never requested it. Without it
     * every 30 s cycle enumerated the running processes twice and made one binder call
     * per process that always threw SecurityException, and system_server answered each
     * one with a full stack trace on System.err. Check once and stop instead of looping
     * forever on work that cannot succeed.
     */
    private boolean canKillBackgroundProcesses() {
        try {
            return getPackageManager().checkPermission(
                    android.Manifest.permission.KILL_BACKGROUND_PROCESSES, getPackageName())
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!canKillBackgroundProcesses()) {
            Log.i(TAG, "KILL_BACKGROUND_PROCESSES not held — cleanup loop disabled");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            workerThread = new Thread(this::runCleanupLoop);
            workerThread.start();
            Log.i(TAG, "Cleanup loop started");
        }
        // Keep service running until explicitly stopped.
        return START_STICKY;
    }

    private void runCleanupLoop() {
        while (running) {
            try {
                cleanupProcesses();
                Thread.sleep(CLEANUP_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void cleanupProcesses() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        List<ActivityManager.RunningAppProcessInfo> runningApps = am.getRunningAppProcesses();
        if (runningApps == null) return;
        String foregroundPkg = getForegroundPackage();
        for (ActivityManager.RunningAppProcessInfo proc : runningApps) {
            String pkg = proc.processName;
            // Skip our own package, the current foreground, and known persistent services.
            if (pkg.equals(getPackageName())) continue;
            if (pkg.equals(foregroundPkg)) continue;
            if (pkg.endsWith(".ime") || pkg.endsWith(".service")) continue; // simplistic whitelist
            // Attempt to kill background process.
            try {
                am.killBackgroundProcesses(pkg);
                Log.i(TAG, "Killed background process: " + pkg);
            } catch (Exception e) {
                Log.w(TAG, "Failed to kill " + pkg + ": " + e.getMessage());
            }
        }
    }

    // Helper to retrieve the current foreground package via ActivityManager.
    private String getForegroundPackage() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "";
        // Requires API 21+; using getRunningAppProcesses as a fallback.
        List<ActivityManager.RunningAppProcessInfo> apps = am.getRunningAppProcesses();
        if (apps == null || apps.isEmpty()) return "";
        // The process with importance IMPORTANCE_FOREGROUND is foreground.
        for (ActivityManager.RunningAppProcessInfo proc : apps) {
            if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return proc.processName;
            }
        }
        return "";
    }

    @Override
    public void onDestroy() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        Log.i(TAG, "Service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service.
    }
}
