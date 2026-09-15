package com.solar.launcher.debug;

import com.solar.launcher.BuildConfig;

/**
 * 2026-09-14 — Compile-time switch for the agent-log instrumentation blocks
 * ({@code // #region agent log … // #endregion}, ~1,000 of them) and the loggers they
 * feed. Every block builds a JSONObject and formats strings before the (mostly gated)
 * logger call, on paths such as key dispatch, list binding and screen changes; on an
 * MT6572 that is allocation, GC and logcat churn on every wheel tick. With
 * {@link #ON} a constant {@code false} javac drops the guarded blocks entirely.
 * Upstream builds keep {@code true}; set {@code SOLAR_AGENT_LOGS_OFF=1} at build time
 * (see app/build.gradle) for a quiet build.
 */
public final class DebugGate {
    public static final boolean ON = BuildConfig.AGENT_LOGS;
    private DebugGate() {}
}
