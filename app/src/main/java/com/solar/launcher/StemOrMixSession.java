package com.solar.launcher;

/**
 * Exclusive Stem Player / Mix session gate — sole heavy audio jam owner.
 * Layman: while Stem or Mix is open, Solar pauses library scans and other busywork
 * so the pads stay responsive; other input tiers must not steal the wheel.
 * Technical: volatile flag + sole writer for sys.solar.stemmix.active;
 * mutex order: stemmix > overlay > IME > handoff > stock.
 * Was: in-process flag only (Xposed/IME could still steal). Reversal: ignore ACTIVE_PROPERTY.
 * 2026-07-19
 */
public final class StemOrMixSession {
    /** Read by Xposed / root / IME / handoff — jam owns keys while "1". */
    public static final String ACTIVE_PROPERTY = "sys.solar.stemmix.active";

    private static volatile boolean active;
    private static volatile Boolean testActiveOverride;

    private StemOrMixSession() {}

    public static boolean isActive() {
        if (testActiveOverride != null) return testActiveOverride.booleanValue();
        return active;
    }

    public static void setActive(boolean on) {
        active = on;
        OverlayKeyGate.writeProperty(ACTIVE_PROPERTY, on ? "1" : "0");
        if (on) {
            ExternalInputHandoff.pauseForStemMix();
            if (SolarImeRouteArbiter.isActive()) {
                SolarImeRouteArbiter.disarm();
            }
        } else {
            ExternalInputHandoff.resumeFromStemMix();
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("active", on);
            Debug8b0481Log.log("StemOrMixSession.setActive", "exclusive gate", "H3", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    static void setActiveForTest(boolean on) {
        testActiveOverride = Boolean.valueOf(on);
    }

    static void resetActiveForTest() {
        testActiveOverride = null;
    }

    /**
     * 2026-07-19 — NP Back target must never be Stem/Mix (would reopen the jam).
     * Layman: leaving Now Playing must not dump you back into Stem/Mix by accident.
     * Reversal: assign raw screen without this clamp.
     */
    public static int sanitizePlayerReturnScreen(int screen, int stemState, int mixState, int menuState) {
        if (screen == stemState || screen == mixState) return menuState;
        return screen;
    }
}
