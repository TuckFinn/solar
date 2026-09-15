package com.solar.launcher;

import android.content.Context;
import android.os.SystemClock;

/**
 * 2026-07-05 — Shared sysprop for ultra-long BACK/power rescue hold progress.
 * Layman: tells the on-screen countdown how long until Solar takes over again.
 * Technical: sys.solar.rescue.hold_deadline (uptime ms) + hold_kind; read by {@link SolarRescueHoldService}.
 */
public final class SolarRescueHoldState {

    /** Uptime millis when 10s rescue fires — 0 means finger up / idle. */
    public static final String HOLD_DEADLINE_PROPERTY = "sys.solar.rescue.hold_deadline";
    /** {@code back} or {@code power} — label for the HUD chip. */
    public static final String HOLD_KIND_PROPERTY = "sys.solar.rescue.hold_kind";
    /**
     * 2026-07-05 — HUD phase: 0 hide, 1–3 countdown, {@link #HUD_RESTARTING} = "Restarting" flash.
     * Layman: what text the bottom chip shows during hold-to-restart.
     */
    public static final String HUD_SECOND_PROPERTY = "sys.solar.rescue.hud_second";
    /** Total hold before solar-rescue-exec.sh — keep in sync with GlobalInputPolicy + Xposed. */
    public static final long RESCUE_HOLD_MS =
            com.solar.input.policy.GlobalInputPolicy.RESCUE_EXECUTE_MS;
    /** On-screen 3, 2, 1 only inside this window before restart. */
    public static final int HUD_COUNTDOWN_SECONDS = 3;
    /** sysprop value for brief "Restarting" label before exec — not a countdown digit. */
    public static final int HUD_RESTARTING = -1;
    /** 2026-07-06 — solar-rescue-exec.sh sets while painting fullscreen pre-reboot message. */
    public static final String FULLSCREEN_PROPERTY = "sys.solar.rescue.fullscreen";
    /** How long "Restarting" stays visible before rescue script runs. */
    public static final long FIRE_FLASH_MS = 400L;

    public static final String KIND_BACK = "back";
    public static final String KIND_POWER = "power";

    /** Unit tests — when non-null, overrides sysprop reads/writes. */
    private static volatile Long testDeadlineOverride;
    private static volatile String testKindOverride;
    private static volatile Long testNowOverride;
    private static volatile Integer testHudSecondOverride;

    private SolarRescueHoldState() {}

    /** 2026-07-08 — BACK down outside Solar — 10s rescue track (HUD arms at 7s). */
    public static void armBack() {
        arm(KIND_BACK, 0L);
    }

    /** Y2 power down — same rescue countdown tier. */
    public static void armPower() {
        arm(KIND_POWER, 0L);
    }

    /**
     * 2026-07-08 — Arm rescue HUD with deadline anchored to hold DOWN (7s preview, 10s exec).
     * Layman: countdown stays synced when HUD arms mid-hold, not a fresh 10s from that moment.
     */
    public static void armFromHoldStart(String kind, long holdDownAtUptime) {
        arm(kind, holdDownAtUptime);
    }

    private static void arm(String kind) {
        arm(kind, 0L);
    }

    private static void arm(String kind, long holdDownAtUptime) {
        long deadline = holdDownAtUptime > 0L
                ? holdDownAtUptime + RESCUE_HOLD_MS
                : SystemClock.uptimeMillis() + RESCUE_HOLD_MS;
        if (testDeadlineOverride != null) {
            testDeadlineOverride = deadline;
            testKindOverride = kind;
            testHudSecondOverride = 0;
            return;
        }
        String dl = String.valueOf(deadline);
        if (!writeProperty(HOLD_DEADLINE_PROPERTY, dl)) {
            RootShell.run("setprop " + HOLD_DEADLINE_PROPERTY + " " + dl);
        }
        String k = kind != null ? kind : KIND_BACK;
        if (!writeProperty(HOLD_KIND_PROPERTY, k)) {
            RootShell.run("setprop " + HOLD_KIND_PROPERTY + " " + k);
        }
        writeHudSecond(0);
    }

    /** Finger up or rescue finished — hide HUD and clear deadline. */
    public static void disarm() {
        if (testDeadlineOverride != null) {
            testDeadlineOverride = 0L;
            testKindOverride = "";
            testHudSecondOverride = 0;
            return;
        }
        writeProperty(HOLD_DEADLINE_PROPERTY, "0");
        writeProperty(HOLD_KIND_PROPERTY, "");
        writeHudSecond(0);
        writeProperty(FULLSCREEN_PROPERTY, "0");
        RootShell.run("setprop " + HOLD_DEADLINE_PROPERTY + " 0; setprop "
                + HUD_SECOND_PROPERTY + " 0; setprop " + FULLSCREEN_PROPERTY + " 0");
    }

    /**
     * 2026-07-05 — Brief "Restarting" flash at 10s while finger may still be down.
     * Layman: tells every HUD tier to show the final label before Linux rescue + reboot.
     */
    public static void signalRestarting() {
        writeHudSecond(HUD_RESTARTING);
    }

    /** 2026-07-06 — Arm fullscreen Restarting for root exec — display frame persists through reboot. */
    public static void signalFullscreenRestart() {
        if (!writeProperty(FULLSCREEN_PROPERTY, "1")) {
            RootShell.run("setprop " + FULLSCREEN_PROPERTY + " 1");
        }
        signalRestarting();
    }

    /** True when HUD should show {@link R.string#rescue_hold_restarting}. */
    public static boolean isHudRestarting() {
        return readHudSecondProp() == HUD_RESTARTING;
    }

    /** True when any rescue HUD label should paint (countdown or restarting). */
    public static boolean shouldShowHud() {
        return isHudRestarting() || hudCountdownValue() > 0;
    }

    /**
     * 2026-07-05 — Unified HUD copy for APK service, root HudMain, and tests.
     * Layman: "Restarting in: 3…" or "Restarting"; null when hidden.
     */
    public static String hudText(Context ctx) {
        healStaleHudProps();
        if (ctx == null) return null;
        if (isHudRestarting()) {
            return ctx.getString(R.string.rescue_hold_restarting);
        }
        int sec = hudCountdownValue();
        if (sec > 0) {
            return ctx.getString(R.string.rescue_hold_countdown, sec);
        }
        return null;
    }

    /** Clear orphan hud_second when hold deadline already expired — stops "Restarting in: 1" forever. 2026-07-05 */
    private static void healStaleHudProps() {
        long deadline = readDeadlineUptime();
        int prop = readHudSecondProp();
        if (deadline <= 0L && prop != 0) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("deadline", deadline);
                d.put("hudSecond", prop);
                Debug434250Log.log("SolarRescueHoldState.healStaleHudProps",
                        "clear orphan hud prop", "H-A", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            writeHudSecond(0);
        }
    }

    /** Seconds left on the rescue hold (ceil), or 0 when idle/expired. */
    public static int secondsRemaining() {
        long deadline = readDeadlineUptime();
        if (deadline <= 0L) return 0;
        long remain = deadline - nowUptime();
        if (remain <= 0L) return 0;
        return (int) ((remain + 999L) / 1000L);
    }

    public static long readDeadlineUptime() {
        if (testDeadlineOverride != null) return testDeadlineOverride.longValue();
        try {
            return Long.parseLong(readProperty(HOLD_DEADLINE_PROPERTY, "0"));
        } catch (Exception e) {
            return 0L;
        }
    }

    public static String readKind() {
        if (testKindOverride != null) return testKindOverride;
        String k = readProperty(HOLD_KIND_PROPERTY, "");
        return k != null ? k : "";
    }

    public static boolean isHoldActive() {
        return secondsRemaining() > 0 || isHudRestarting();
    }

    /**
     * Countdown digit only — 0 during restarting flash or outside final 3s window.
     * Layman: nothing on screen for the first ~7s, then 3, 2, 1 to 10s.
     */
    public static int hudCountdownValue() {
        if (isHudRestarting()) return 0;
        long deadline = readDeadlineUptime();
        int prop = readHudSecondProp();
        if (prop >= 1 && prop <= HUD_COUNTDOWN_SECONDS) {
            if (deadline > 0L) return prop;
            return 0;
        }
        int remain = secondsRemaining();
        if (remain <= 0 || remain > HUD_COUNTDOWN_SECONDS) return 0;
        return remain;
    }

    /**
     * @deprecated use {@link #hudCountdownValue()} — kept for callers expecting digit-only API.
     */
    public static int hudSecondsToShow() {
        return hudCountdownValue();
    }

    private static int readHudSecondProp() {
        if (testHudSecondOverride != null) return testHudSecondOverride.intValue();
        try {
            return Integer.parseInt(readProperty(HUD_SECOND_PROPERTY, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeHudSecond(int value) {
        if (testHudSecondOverride != null) {
            testHudSecondOverride = value;
            return;
        }
        String s = String.valueOf(value);
        if (!writeProperty(HUD_SECOND_PROPERTY, s)) {
            RootShell.run("setprop " + HUD_SECOND_PROPERTY + " " + s);
        }
    }

    /** Test hook — simulate hold without setprop. */
    static void setHoldForTest(long deadlineUptime, String kind) {
        testDeadlineOverride = deadlineUptime;
        testKindOverride = kind;
        testHudSecondOverride = 0;
    }

    static void setHudSecondForTest(int value) {
        testHudSecondOverride = value;
    }

    static void setNowForTest(long uptimeMs) {
        testNowOverride = uptimeMs;
    }

    static void resetHoldForTest() {
        testDeadlineOverride = null;
        testKindOverride = null;
        testNowOverride = null;
        testHudSecondOverride = null;
    }

    private static long nowUptime() {
        if (testNowOverride != null) return testNowOverride.longValue();
        return SystemClock.uptimeMillis();
    }

    static boolean writeProperty(String key, String value) {
        return OverlayKeyGate.writeProperty(key, value);
    }

    private static String readProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, def);
            return v != null ? v.toString() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
