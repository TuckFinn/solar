package com.solar.launcher;

import android.app.ActivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.List;

/**
 * 2026-07-05 — Root evdev daemon: BACK-long → global modal; forwards keys while overlay active.
 * Tier-3 fallback — fires only when Xposed opening/active props show PWM miss; Rockbox BACK excluded.
 * When changing: DEFAULT_INPUT_DEVICE; align with 99Y1ButtonScript / Y1-Rockbox.kl scancodes.
 * Reversal: delete daemon; long BACK in third-party apps may not open quick menu without Xposed.
 */
public final class GlobalOverlayTriggerMain {

    private static final String TAG = "GlobalOverlayTrig";
    /** Fallback when auto-detect fails — matches 99Y1ButtonScript on most MTK builds. */
    private static final String DEFAULT_INPUT_DEVICE = "/dev/input/event2";
    private static final String DEBUG_LOG = "/storage/sdcard0/.solar/debug-88eb84.log";
    private static final int EV_KEY = 1;
    /** Y1 mtk-kpd BACK scancode — same as {@code 99Y1ButtonScript} menu_down (158). */
    private static final int SCAN_BACK = 158;
    /** Y2 dedicated sleep/power scancode — Y2-Rockbox.kl key 116 POWER WAKE. */
    private static final int SCAN_POWER = 116;
    /** Y1 mtk-kpd wheel scancodes — map to media keys for list navigation without WM focus. */
    private static final int SCAN_WHEEL_UP = 105;
    private static final int SCAN_WHEEL_DOWN = 106;
    /** Y1 side keys — horizontal quick-bar navigation when WM overlay is NOT_FOCUSABLE. */
    private static final int SCAN_SIDE_LEFT = 165;
    private static final int SCAN_SIDE_RIGHT = 163;
    /** Y1 center button — Y1-Rockbox.kl scancode 164 → MEDIA_PLAY_PAUSE (85). */
    private static final int SCAN_CENTER = 164;
    private static final long BACK_RESTART_MS = SolarRescueHoldState.RESCUE_HOLD_MS;
    private static final String RESCUE_EXEC = "/system/etc/solar/solar-rescue-exec.sh";
    /** Root tier fires slightly after Xposed — skip if overlay already opening. */
    private static final long POWER_MISS_GATE_MS = 50L;

    private static Handler handler;
    private static boolean backDown;
    private static long backDownAt;
    private static boolean backLongFired;
    private static boolean backRestartFired;
    /** Y2 power-hold fallback — passive evdev watch; Xposed owns short-tap sleep. */
    private static boolean powerDown;
    private static long powerDownAt;
    private static boolean powerLongFired;
    private static final boolean y2Device = DeviceFeatures.isY2();
    /**
     * 2026-07-14 — Root miss-gate: HOLD BACK → Solar Home (not WM quick menu).
     * Layman: even if Xposed missed the hold, the root watcher still sends you home.
     * Was: am startservice SHOW_OVERLAY_POWER. Reversal: overlayGlobalModalBroadcastCmd().
     */
    private static final Runnable backLongRunnable = new Runnable() {
        @Override
        public void run() {
            if (!backDown || backLongFired || backRestartFired) return;
            if (isOverlayActive() || isOverlayOpening() || isStemMixActive()) return;
            String fg = resolveBackForegroundForRoot();
            if (!GlobalOverlayPolicy.shouldOfferBackLongGlobalModal(fg, isImeActive())) return;
            backLongFired = true;
            long holdMs = com.solar.input.policy.GlobalInputPolicy.backModalHoldMsForPackage(fg);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("fg", fg != null ? fg : "");
                d.put("holdMs", holdMs);
                d.put("tier", "root-evdev");
                d.put("action", "launch_solar_home");
                DebugBee1b8Log.log("GlobalOverlayTriggerMain.backLongRunnable",
                        "launch Solar Home", "c54726-H3", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "am start -n com.solar.launcher/.MainActivity -f 0x34000000"});
                System.out.println(TAG + ": launch Solar Home fg=" + fg);
            } catch (Exception e) {
                System.err.println(TAG + ": launch Solar Home failed: " + e.getMessage());
            }
        }
    };
    /**
     * 2026-07-14 — Y2 root miss-gate: POWER-hold menu only for Solar fg (in-app via broadcast).
     * Outside Solar: do nothing so stock GlobalActions can own the hold.
     */
    private static final Runnable powerLongRunnable = new Runnable() {
        @Override
        public void run() {
            if (!powerDown || powerLongFired) return;
            String fg = resolvePowerForegroundForRoot();
            if (!com.solar.input.policy.GlobalInputPolicy.shouldOfferPowerLongModal(
                    fg, DeviceFeatures.isY2())) {
                return;
            }
            com.solar.input.policy.StaleOverlayGate.clearIfNeeded();
            if (com.solar.input.policy.StaleOverlayGate.isActiveOrOpening()) return;
            powerLongFired = true;
            try {
                // Solar in-app sheet — same intent MainActivity listens for.
                Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "am start -a com.solar.launcher.action.OPEN_CONTEXT_MENU "
                                + "-n com.solar.launcher/.MainActivity "
                                + "--ez context_power_hold true -f 0x34000000"});
                System.out.println(TAG + ": Solar in-app power menu fg=" + fg);
            } catch (Exception e) {
                System.err.println(TAG + ": Solar in-app power menu failed: " + e.getMessage());
            }
        }
    };
    /** Y2 continued power hold — 10s rescue (parallel to modal hold). */
    private static boolean powerRestartFired;
    /** 2026-07-08 — BACK rescue HUD at 7s — deadline anchored to hold DOWN (10s exec). */
    private static final Runnable backRescueArmRunnable = new Runnable() {
        @Override
        public void run() {
            if (!backDown || backRestartFired) return;
            String fg = foregroundPackageName();
            if (GlobalOverlayPolicy.shouldArmRescueHoldForPackage(fg)) {
                SolarRescueHoldState.armFromHoldStart(SolarRescueHoldState.KIND_BACK, backDownAt);
            }
        }
    };
    /** 2026-07-06 — Rescue HUD arms at 7s only; never on short tap DOWN (was fork/setprop storm). */
    private static final Runnable powerRescueArmRunnable = new Runnable() {
        @Override
        public void run() {
            if (!powerDown || powerRestartFired) return;
            String fg = foregroundPackageName();
            if (GlobalOverlayPolicy.shouldArmRescueHoldForPackage(fg, true)) {
                SolarRescueHoldState.armFromHoldStart(SolarRescueHoldState.KIND_POWER, powerDownAt);
            }
        }
    };
    private static final Runnable powerRestartRunnable = new Runnable() {
        @Override
        public void run() {
            if (!powerDown || powerRestartFired) return;
            powerRestartFired = true;
            String fg = foregroundPackageName();
            runRescueExecAfterFlash(fg);
        }
    };
    private static final Runnable backRestartRunnable = new Runnable() {
        @Override
        public void run() {
            if (!backDown || backRestartFired) return;
            backRestartFired = true;
            String fg = foregroundPackageName();
            runRescueExecAfterFlash(fg);
        }
    };

    private static final String PID_FILE = "/data/local/tmp/global-overlay-trigger.pid";

    public static void main(String[] args) {
        if (!acquireSingleton()) {
            System.err.println(TAG + ": duplicate instance — exit");
            System.exit(0);
        }
        String inputPath = resolveKeypadDevice();
        File dev = new File(inputPath);
        if (!dev.canRead()) {
            System.err.println(TAG + ": missing " + inputPath);
            System.exit(1);
        }
        System.out.println(TAG + ": reading " + inputPath);
        System.out.flush();
        HandlerThread ht = new HandlerThread("GlobalOverlayTrigger");
        ht.start();
        handler = new Handler(ht.getLooper());
        System.out.println("READY");
        System.out.flush();
        FileInputStream in = null;
        try {
            in = new FileInputStream(dev);
            byte[] buf = new byte[16];
            int n;
            while ((n = in.read(buf)) == 16) {
                parseEvent(buf);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
        }
    }

    /** One evdev reader per device — duplicate app_process storms CPU (2026-07-06). */
    private static boolean acquireSingleton() {
        File pidFile = new File(PID_FILE);
        if (pidFile.isFile()) {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader(pidFile));
                String line = r.readLine();
                r.close();
                if (line != null && line.trim().length() > 0) {
                    int oldPid = Integer.parseInt(line.trim());
                    if (new File("/proc/" + oldPid).exists()) {
                        return false;
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            java.io.FileWriter w = new java.io.FileWriter(pidFile);
            w.write(String.valueOf(android.os.Process.myPid()));
            w.close();
        } catch (Exception ignored) {}
        return true;
    }

    /** Linux input_event (32-bit ARM): timeval 8B @0, type u16 @8, code u16 @10, value s32 @12. */
    private static void parseEvent(byte[] buf) {
        if (buf == null || buf.length < 16) return;
        if (isOverlayActive() || isOverlayOpening() || isImeActive() || isStemMixActive()) {
            OverlayInputCoordinator.healStaleGatesFromRoot();
        }
        // Stem/Mix jam owns pads — root miss-gate must not open overlay / steal keys. 2026-07-19
        if (isStemMixActive()) return;
        int type = le16(buf, 8);
        int code = le16(buf, 10);
        int value = le32(buf, 12);
        if (type != EV_KEY) return;

        // Swallow BACK tail after overlay dismiss — otherwise stock app receives back navigation.
        if (code == SCAN_BACK && OverlayKeyGate.shouldSwallowBackAfterOverlayDismiss(KeyEvent.KEYCODE_BACK)) {
            if (value == 1) {
                backDown = true;
            } else if (value == 0) {
                backDown = false;
                backLongFired = false;
                backRestartFired = false;
                handler.removeCallbacks(backLongRunnable);
                handler.removeCallbacks(backRestartRunnable);
            }
            return;
        }

        // IME tray up — swallow wheel; BACK runs hold timers (4s modal, 10s rescue).
        if (isImeActive() && !isOverlayActive()) {
            if (code == SCAN_BACK) {
                handleBackScancode(value, true);
                return;
            }
            int keyCode = scancodeToKeyCode(code);
            if (keyCode != 0 && (value == 1 || value == 0)) {
                if (shouldRootForwardImeKeys()) {
                    int action = value == 1 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                    forwardImeKey(keyCode, action);
                }
            }
            return;
        }

        // Overlay open — tier-1 Xposed forwards; tier-2 root pipes keys when PWM miss (2026-07-06).
        if (isOverlayActive()) {
            if (code == SCAN_BACK) {
                // 2026-07-06 — USB/stuck modal: still arm 10s rescue while forwarding keys to :overlay.
                if (value == 1) {
                    backDown = true;
                    backDownAt = SystemClock.uptimeMillis();
                    backLongFired = false;
                    backRestartFired = false;
                    handler.removeCallbacks(backLongRunnable);
                    handler.removeCallbacks(backRestartRunnable);
                    handler.removeCallbacks(backRescueArmRunnable);
                    String fg = foregroundPackageName();
                    if (GlobalOverlayPolicy.shouldArmRescueHoldForPackage(fg)) {
                        handler.postDelayed(backRescueArmRunnable,
                                com.solar.input.policy.GlobalInputPolicy.HUD_COUNTDOWN_START_MS);
                        handler.postDelayed(backRestartRunnable, BACK_RESTART_MS);
                        pingRescueHoldHud();
                    }
                } else if (value == 0) {
                    backDown = false;
                    backDownAt = 0L;
                    handler.removeCallbacks(backLongRunnable);
                    handler.removeCallbacks(backRestartRunnable);
                    handler.removeCallbacks(backRescueArmRunnable);
                    if (!backRestartFired) {
                        SolarRescueHoldState.disarm();
                    }
                    backLongFired = false;
                    backRestartFired = false;
                }
                OverlayInputCoordinator.forwardFromRootTier(code, value);
                return;
            }
            // 2026-07-06 — Y2 sleep button: 10s rescue must work over hung USB/power overlay too.
            if (y2Device && code == SCAN_POWER) {
                handlePowerScancode(value);
                OverlayInputCoordinator.forwardFromRootTier(code, value);
                return;
            }
            OverlayInputCoordinator.forwardFromRootTier(code, value);
            return;
        }

        // Y2 power-hold fallback — Rockbox allowed; arms only when Xposed tier misses.
        if (y2Device && code == SCAN_POWER) {
            handlePowerScancode(value);
            return;
        }

        if (code != SCAN_BACK) return;
        handleBackScancode(value, false);
    }

    /** 2026-07-08 — BACK down: fg modal + 7s HUD arm + continuous 10s rescue; UP disarms. */
    private static void handleBackScancode(int value, boolean imeActive) {
        if (value == 1) {
            backDown = true;
            backDownAt = SystemClock.uptimeMillis();
            backLongFired = false;
            backRestartFired = false;
            handler.removeCallbacks(backLongRunnable);
            handler.removeCallbacks(backRestartRunnable);
            handler.removeCallbacks(backRescueArmRunnable);
            String fg = resolveBackForegroundForRoot();
            if (GlobalOverlayPolicy.shouldArmRescueHoldForPackage(fg)) {
                handler.postDelayed(backRescueArmRunnable,
                        com.solar.input.policy.GlobalInputPolicy.HUD_COUNTDOWN_START_MS);
                handler.postDelayed(backRestartRunnable, BACK_RESTART_MS);
                pingRescueHoldHud();
                if (GlobalOverlayPolicy.shouldOfferBackLongGlobalModal(fg, imeActive)) {
                    long backHoldMs = com.solar.input.policy.GlobalInputPolicy
                            .backModalHoldMsForPackage(fg);
                    handler.postDelayed(backLongRunnable, backHoldMs);
                }
            }
        } else if (value == 0) {
            boolean hadLong = backLongFired || backRestartFired;
            backDown = false;
            backDownAt = 0L;
            handler.removeCallbacks(backLongRunnable);
            handler.removeCallbacks(backRestartRunnable);
            handler.removeCallbacks(backRescueArmRunnable);
            if (!backRestartFired) {
                SolarRescueHoldState.disarm();
            }
            if (!hadLong && imeActive) {
                dismissImeTray();
            }
            backLongFired = false;
            backRestartFired = false;
        }
    }

    /**
     * 2026-07-14 — BACK/POWER hold opens Solar ThemedContextMenu; Chip only if companion_shell=1.
     * Layman: same decorated menu as home — not a second chip look. Was: companion-first.
     */
    private static String overlayGlobalModalBroadcastCmd() {
        // companion_shell=1 → Chip escape hatch; default → Solar ThemedContextMenu.
        if ("1".equals(readSysProp("persist.solar.overlay.companion_shell", "0"))
                && isCompanionInstalled()) {
            return "am startservice -a com.solar.launcher.action.SHOW_OVERLAY_POWER "
                    + "-n com.solar.launcher.globalcontext/.GlobalContextOverlayService";
        }
        if (isSolarInstalled()) {
            return "am startservice -a com.solar.launcher.action.SHOW_OVERLAY_POWER "
                    + "-n com.solar.launcher/.SolarOverlayService";
        }
        if (isCompanionInstalled()) {
            return "am startservice -a com.solar.launcher.action.SHOW_OVERLAY_POWER "
                    + "-n com.solar.launcher.globalcontext/.GlobalContextOverlayService";
        }
        return "am broadcast -a com.solar.launcher.action.SHOW_OVERLAY_POWER "
                + "-n com.solar.launcher/.PowerOverlayOpenReceiver";
    }

    /** @deprecated 600ms HOME-only picker — use {@link #overlayGlobalModalBroadcastCmd()}. */
    private static String overlayLauncherPickerBroadcastCmd() {
        return overlayGlobalModalBroadcastCmd();
    }

    /** Same as global modal — companion owns power tier. */
    private static String overlayPowerBroadcastCmd() {
        return overlayGlobalModalBroadcastCmd();
    }

    private static String readSysProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, def);
            return v != null ? v.toString() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** Solar APK present — full themed overlay host. */
    private static boolean isSolarInstalled() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "pm path com.solar.launcher"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = r.readLine();
            r.close();
            p.waitFor();
            return line != null && line.startsWith("package:");
        } catch (Exception e) {
            return true;
        }
    }

    /** Best-effort companion probe from root daemon — pm path is cheap. */
    private static boolean isCompanionInstalled() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "pm path com.solar.launcher.globalcontext"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = r.readLine();
            r.close();
            return line != null && line.contains("package:");
        } catch (Exception e) {
            return false;
        }
    }

    /** Nudge :hold service — poll loop already runs; skip exec spam (2026-07-05). */
    private static void pingRescueHoldHud() {
        // :hold SolarRescueHoldService polls every 100–500ms — am startservice per BACK forked sh storms.
    }

    /** Short BACK while IME — hide keyboard without sending BACK to foreground app. */
    private static void dismissImeTray() {
        // #region agent log
        DebugImeLog.logRoot("GlobalOverlayTriggerMain.dismissImeTray", "root IME dismiss", "H2", null);
        // #endregion
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "am startservice -a " + OverlayTriggers.ACTION_IME_DISMISS
                            + " -n com.solar.launcher/.SolarInputMethodService"});
        } catch (Exception e) {
            System.err.println(TAG + ": ime dismiss failed: " + e.getMessage());
        }
    }

    /** 2026-07-05 — Flash "Restarting" ~400ms while still holding, then root rescue script. */
    private static void runRescueExecAfterFlash(final String fg) {
        SolarRescueHoldState.signalRestarting();
        pingRescueHoldHud();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                SolarRescueHoldState.disarm();
                runRescueExec(fg);
            }
        }, SolarRescueHoldState.FIRE_FLASH_MS);
    }

    /** Shared rescue script — works when Solar app process is dead. */
    private static void runRescueExec(String fg) {
        try {
            String q = fg != null && fg.length() > 0
                    ? (" '" + fg.replace("'", "'\\''") + "'") : "";
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "sh " + RESCUE_EXEC + q});
            System.out.println(TAG + ": rescue fg=" + fg);
        } catch (Exception e) {
            System.err.println(TAG + ": rescue failed: " + e.getMessage());
        }
    }

    /** True when Solar IME tray is armed — trust sys.solar.ime.active prop. */
    private static boolean isImeActive() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, SolarImeRouteArbiter.ACTIVE_PROPERTY, "0");
            return "1".equals(String.valueOf(v));
        } catch (Exception e) {
            return false;
        }
    }

    /** True when Stem/Mix jam owns pads — trust sys.solar.stemmix.active. 2026-07-19 */
    private static boolean isStemMixActive() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, StemOrMixSession.ACTIVE_PROPERTY, "0");
            return "1".equals(String.valueOf(v));
        } catch (Exception e) {
            return StemOrMixSession.isActive();
        }
    }

    /** Root tier only after PWM miss pulse — prevents double key delivery. */
    private static boolean shouldRootForwardImeKeys() {
        return SolarImeRouteArbiter.shouldRootForwardKeys();
    }

    /** Broadcast to SolarInputMethodService (:overlay) — same contract as ImeKeyForwarder. */
    private static void forwardImeKey(int keyCode, int action) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "am startservice -a " + OverlayTriggers.ACTION_IME_KEY
                            + " -n com.solar.launcher/.SolarInputMethodService"
                            + " --ei " + OverlayTriggers.EXTRA_KEY_CODE + " " + keyCode
                            + " --ei " + OverlayTriggers.EXTRA_KEY_ACTION + " " + action});
        } catch (Exception e) {
            System.err.println(TAG + ": forward IME key failed: " + e.getMessage());
        }
    }

    /**
     * 2026-07-06 — Y2 POWER evdev miss-gated tier only; short tap is zero-work passthrough.
     * Layman: quick sleep button tap does nothing here — stock/Xposed put the screen to sleep.
     * Technical: no getRunningTasks/setprop/rescue on DOWN; long-hold + 7s/10s timers deferred.
     */
    private static void handlePowerScancode(int value) {
        if (value == 1) {
            powerDown = true;
            powerDownAt = SystemClock.uptimeMillis();
            powerLongFired = false;
            powerRestartFired = false;
            handler.removeCallbacks(powerLongRunnable);
            handler.removeCallbacks(powerRestartRunnable);
            handler.removeCallbacks(powerRescueArmRunnable);
            String fgAtDown = resolvePowerForegroundForRoot();
            // 2026-07-20 — Root miss-gate: arm Options spinner on Power DOWN (Solar fg only).
            // Layman: same spinner as hold-Back while waiting for the menu.
            // Reversal: drop am broadcast; spinner only after OPEN_CONTEXT_MENU.
            if (com.solar.input.policy.GlobalInputPolicy.shouldOfferPowerLongModal(
                    fgAtDown, DeviceFeatures.isY2())) {
                try {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c",
                            "am broadcast -a " + OverlayTriggers.ACTION_CONTEXT_HOLD_ARM
                                    + " -p com.solar.launcher >/dev/null 2>&1"});
                } catch (Exception ignored) {}
            }
            handler.postDelayed(powerLongRunnable,
                    com.solar.input.policy.GlobalInputPolicy.powerModalHoldMsForPackage(fgAtDown)
                            + POWER_MISS_GATE_MS);
            handler.postDelayed(powerRescueArmRunnable,
                    com.solar.input.policy.GlobalInputPolicy.HUD_COUNTDOWN_START_MS);
            handler.postDelayed(powerRestartRunnable, BACK_RESTART_MS);
        } else if (value == 0) {
            long held = powerDownAt > 0 ? SystemClock.uptimeMillis() - powerDownAt : 0L;
            boolean menuOpened = powerLongFired;
            powerDown = false;
            powerDownAt = 0L;
            handler.removeCallbacks(powerLongRunnable);
            handler.removeCallbacks(powerRestartRunnable);
            handler.removeCallbacks(powerRescueArmRunnable);
            // 2026-07-20 — Early release: clear hold spinner (mirror ContextHoldThrobberGate).
            if (ContextHoldThrobberGate.shouldClearOnHoldCancel(menuOpened)) {
                try {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c",
                            "am broadcast -a " + OverlayTriggers.ACTION_CONTEXT_HOLD_CANCEL
                                    + " -p com.solar.launcher >/dev/null 2>&1"});
                } catch (Exception ignored) {}
            }
            if (com.solar.input.policy.GlobalInputPolicy.shouldPassthroughPowerTap(held)) {
                if (!powerRestartFired) {
                    SolarRescueHoldState.disarm();
                }
                powerLongFired = false;
                powerRestartFired = false;
                return;
            }
            if (!powerRestartFired) {
                SolarRescueHoldState.disarm();
            }
            powerLongFired = false;
            powerRestartFired = false;
        }
    }

    /** True when interactive global overlay is armed — trust prop (cleared on disarm/boot). */
    private static boolean isOverlayActive() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, OverlayKeyGate.ACTIVE_PROPERTY, "0");
            return "1".equals(String.valueOf(v));
        } catch (Exception e) {
            return false;
        }
    }

    /** True while :overlay paints — auto-clear stale opening=1 after overlay crash. */
    private static boolean isOverlayOpening() {
        clearOpeningGateIfStale();
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, OverlayKeyGate.OPENING_PROPERTY, "0");
            return "1".equals(String.valueOf(v));
        } catch (Exception e) {
            return false;
        }
    }

    /** Delegate to shared policy JAR — one ceiling for opening/active stale heal. */
    private static void clearOpeningGateIfStale() {
        com.solar.input.policy.StaleOverlayGate.clearIfNeeded();
    }

    /** Map Y1 evdev scancodes to Android key codes for overlay navigation. */
    static int scancodeToKeyCode(int scancode) {
        if (scancode == SCAN_BACK) return KeyEvent.KEYCODE_BACK;
        // Physical OK is scancode 164 → PLAY_PAUSE (85) in keylayout; modal treats 85 as OK too.
        if (scancode == SCAN_CENTER) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if (scancode == SCAN_WHEEL_UP) return KeyEvent.KEYCODE_MEDIA_PLAY;
        if (scancode == SCAN_WHEEL_DOWN) return KeyEvent.KEYCODE_MEDIA_PAUSE;
        if (scancode == SCAN_SIDE_LEFT) return KeyEvent.KEYCODE_DPAD_LEFT;
        if (scancode == SCAN_SIDE_RIGHT) return KeyEvent.KEYCODE_DPAD_RIGHT;
        return 0;
    }

    /** Find mtk-kpd / keypad evdev — event index differs between Y1 and Y2 boards. */
    static String resolveKeypadDevice() {
        for (int i = 0; i < 8; i++) {
            String path = "/dev/input/event" + i;
            File dev = new File(path);
            if (!dev.canRead()) continue;
            String name = readInputDeviceName(i);
            if (name == null) continue;
            String lower = name.toLowerCase(java.util.Locale.US);
            if (lower.contains("mtk-kpd") || lower.contains("mtk_kpd") || lower.contains("keypad")) {
                return path;
            }
        }
        return DEFAULT_INPUT_DEVICE;
    }

    /** Read /sys/class/input/eventN/device/name for keypad auto-detect. */
    static String readInputDeviceName(int eventIndex) {
        try {
            File nameFile = new File("/sys/class/input/event" + eventIndex + "/device/name");
            if (!nameFile.isFile()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(nameFile));
            String line = r.readLine();
            r.close();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Broadcast to {@link OverlayKeyReceiver} in :overlay — superseded by {@link OverlayInputCoordinator}. */
    private static void debugLog(String location, String message, String hypothesisId,
            int scancode, int value, int keyCode, int action) {
        // #region agent log
        DebugPerfLog.incOverlayDebugIo();
        DebugPerfLog.emitStandalone(location, message, hypothesisId, null);
        // #endregion
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", "88eb84");
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location);
            o.put("message", message);
            o.put("hypothesisId", hypothesisId);
            JSONObject d = new JSONObject();
            d.put("scancode", scancode);
            d.put("value", value);
            d.put("keyCode", keyCode);
            d.put("action", action);
            o.put("data", d);
            File dir = new File("/storage/sdcard0/.solar");
            if (!dir.exists()) dir.mkdirs();
            FileWriter w = new FileWriter(new File(dir, "debug-88eb84.log"), true);
            w.write(o.toString());
            w.write('\n');
            w.close();
        } catch (Exception ignored) {}
    }

    private static String cachedFg;
    private static long cachedFgAtMs;

    @SuppressWarnings("deprecation")
    private static String foregroundPackageName() {
        long now = SystemClock.uptimeMillis();
        if (cachedFg != null && now - cachedFgAtMs < 300L) {
            return cachedFg;
        }
        cachedFg = foregroundPackageNameUncached();
        cachedFgAtMs = now;
        return cachedFg;
    }

    /** Y2 fail-open when task probe returns systemui/null — matches SystemServerHooks RC5. */
    private static String resolvePowerForegroundForRoot() {
        String live = foregroundPackageName();
        if (live != null && live.length() > 0
                && !com.solar.input.policy.GlobalInputPolicy.isSystemShellPackage(live)) {
            return live;
        }
        if (com.solar.input.policy.GlobalInputPolicy.shouldFailOpenPowerFg(live)) {
            return live;
        }
        return live;
    }

    /**
     * 2026-07-06 — Root tier BACK-long fg — SystemUI shell fail-open matches PWM fast spawn path.
     * Reversal: foregroundPackageName() only — systemui probe blocks root BACK-long modal.
     */
    private static String resolveBackForegroundForRoot() {
        return resolvePowerForegroundForRoot();
    }

    @SuppressWarnings("deprecation")
    private static String foregroundPackageNameUncached() {
        try {
            ActivityManager am = (ActivityManager) Class.forName("android.app.ActivityManagerNative")
                    .getMethod("getDefault").invoke(null);
            if (am == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo top = tasks.get(0);
                if (top.baseActivity != null) return top.baseActivity.getPackageName();
                if (top.topActivity != null) return top.topActivity.getPackageName();
            }
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo proc : procs) {
                    if (proc.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        continue;
                    }
                    if (proc.pkgList != null && proc.pkgList.length > 0) return proc.pkgList[0];
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    /** Test hook — input_event code field offset (must match 99Y1ButtonScript). */
    static int readEventCode(byte[] buf) {
        return buf == null || buf.length < 12 ? -1 : le16(buf, 10);
    }

    /** Test hook — input_event value field offset. */
    static int readEventValue(byte[] buf) {
        return buf == null || buf.length < 16 ? -1 : le32(buf, 12);
    }
}
