package com.solar.launcher;

import android.os.SystemClock;

/**
 * 2026-07-05 — Sole writer for sys.solar.ime.*; mutex: stemmix > overlay > IME > handoff > stock.
 * Tier-1 text commit lives in SolarInputMethodService; Xposed/root/a11y read-only, escalate on miss.
 * Refuses IME arm when sys.solar.overlay.active=1; ExternalInputHandoff pauses while IME owns keys.
 * When changing: keep Xposed ImeKeyForwarder and root daemon as read-only consumers only.
 * Reversal: delete arbiter; tiers fight in parallel and wheel keys may double-fire.
 */
public final class SolarImeRouteArbiter {

    /** Read by solar-context-bridge ImeKeyForwarder + root evdev daemon. */
    public static final String ACTIVE_PROPERTY = "sys.solar.ime.active";
    /** Current text route: {@link #ROUTE_IME}, {@link #ROUTE_XPOSED}, {@link #ROUTE_A11Y}. */
    public static final String ROUTE_PROPERTY = "sys.solar.ime.route";
    /** Tray painted on screen — distinguishes live IME from stale active=1 after crash. */
    public static final String UI_PROPERTY = "sys.solar.ime.ui";
    /** Uptime millis pulse when PWM miss detected — root daemon gates on this. */
    public static final String XPOSED_MISS_PROPERTY = "sys.solar.ime.xposed_miss";

    public static final String ROUTE_IME = "ime";
    public static final String ROUTE_XPOSED = "xposed_session";
    public static final String ROUTE_A11Y = "a11y";

    /** Credential keyboard inside overlay — mutex exception; overlay WM stays painted. */
    public static final String CREDENTIAL_PROPERTY = "sys.solar.overlay.credential";

    private static volatile boolean overlayCredentialActive;
    private static volatile String currentRoute = ROUTE_IME;
    /** Unit tests — when non-null, overrides {@link #isActive()} sysprop read. */
    private static volatile Boolean testActiveOverride;

    private SolarImeRouteArbiter() {}

    /** Overlay Wi-Fi/BT keyboard tier — publish so Xposed skips menu wheel while typing. */
    public static void setOverlayCredentialActive(boolean active) {
        overlayCredentialActive = active;
        writeProperty(CREDENTIAL_PROPERTY, active ? "1" : "0");
    }

    public static boolean isOverlayCredentialActive() {
        return overlayCredentialActive || "1".equals(readProperty(CREDENTIAL_PROPERTY, "0"));
    }

    /** True when IME tray may arm — overlay / Stem-Mix jam blocks IME (mutex order). */
    public static boolean canArm() {
        if (StemOrMixSession.isActive()) return false;
        if (OverlayKeyGate.isOverlayKeysActive()) return false;
        if (OverlayKeyGate.isOverlayOpening()) return false;
        return true;
    }

    /** IME session start — arm tier-1 route and pause stock-app handoff inject. */
    public static boolean armIme() {
        boolean can = canArm();
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("canArm", can);
            d.put("overlayActive", OverlayKeyGate.isOverlayKeysActive());
            DebugImeLog.log(null, "SolarImeRouteArbiter.armIme", "arm attempt", "H1", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (!can) return false;
        currentRoute = ROUTE_IME;
        writeProperty(ROUTE_PROPERTY, ROUTE_IME);
        boolean propOk = writeProperty(ACTIVE_PROPERTY, "1");
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("propOk", propOk);
            d.put("activeRead", isActive());
            DebugImeLog.log(null, "SolarImeRouteArbiter.armIme", "armed", "H1", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        ExternalInputHandoff.pauseForIme();
        return true;
    }

    /** Tray visible — publish ui prop so stale recovery can distinguish crash from live tray. */
    public static void setTrayUiVisible(boolean visible) {
        writeProperty(UI_PROPERTY, visible ? "1" : "0");
    }

    /** Escalate text commit to app-process Xposed session (tier 2). */
    public static void escalateToXposedSession() {
        if (!isActive()) return;
        currentRoute = ROUTE_XPOSED;
        writeProperty(ROUTE_PROPERTY, ROUTE_XPOSED);
    }

    /** Escalate text commit to accessibility paste path (tier 3). */
    public static void escalateToAccessibility() {
        if (!isActive()) return;
        currentRoute = ROUTE_A11Y;
        writeProperty(ROUTE_PROPERTY, ROUTE_A11Y);
    }

    /** IME dismissed — disarm all tiers and restore handoff. */
    public static void disarm() {
        currentRoute = ROUTE_IME;
        writeProperty(ACTIVE_PROPERTY, "0");
        writeProperty(ROUTE_PROPERTY, "");
        setTrayUiVisible(false);
        writeProperty(XPOSED_MISS_PROPERTY, "0");
        ExternalInputHandoff.resumeFromIme();
    }

    /** PWM failed to swallow — pulse miss prop for root evdev tier (200ms window). */
    public static void signalXposedMiss() {
        writeProperty(XPOSED_MISS_PROPERTY, String.valueOf(SystemClock.uptimeMillis()));
    }

    /** Root daemon — forward only when miss pulse is fresh (prevents double delivery). */
    public static boolean shouldRootForwardKeys() {
        if (!isActive()) return false;
        if (OverlayKeyGate.isOverlayKeysActive()) return false;
        try {
            long pulse = Long.parseLong(readProperty(XPOSED_MISS_PROPERTY, "0"));
            return pulse > 0 && SystemClock.uptimeMillis() - pulse < 200L;
        } catch (Exception e) {
            return false;
        }
    }

    /** Quick check — IME owns keys (property or in-process tray). */
    public static boolean isActive() {
        if (testActiveOverride != null) return testActiveOverride.booleanValue();
        return "1".equals(readProperty(ACTIVE_PROPERTY, "0"));
    }

    public static boolean isTrayUiVisible() {
        return "1".equals(readProperty(UI_PROPERTY, "0"));
    }

    public static String getRoute() {
        String prop = readProperty(ROUTE_PROPERTY, "");
        return prop != null && prop.length() > 0 ? prop : currentRoute;
    }

    public static boolean isRouteAccessibility() {
        return ROUTE_A11Y.equals(getRoute());
    }

    /** Test hook — route string constants. */
    public static void selfCheck() {
        if (!ROUTE_IME.equals("ime")) throw new AssertionError("route ime");
        if (!ROUTE_XPOSED.equals("xposed_session")) throw new AssertionError("route xposed");
        if (!ROUTE_A11Y.equals("a11y")) throw new AssertionError("route a11y");
        if (canArm() && OverlayKeyGate.isOverlayKeysActive()) throw new AssertionError("mutex");
    }

    /** Test hook — simulate sys.solar.ime.active without setprop. */
    static void setActiveForTest(boolean active) {
        testActiveOverride = active ? Boolean.TRUE : Boolean.FALSE;
    }

    static void resetActiveForTest() {
        testActiveOverride = null;
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
