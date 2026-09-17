package com.solar.launcher;

import android.content.Context;

/**
 * 2026-09-16 — Stopped calling {@link SolarRescueHoldService}.
 *
 * Was: idempotent START_STICKY keepalive for the in-app rescue countdown HUD, from
 * boot, the daemon, and every hold-arm writer.
 *
 * The service is not declared in AndroidManifest.xml and never has been --
 * {@code git log -S SolarRescueHoldService} on the manifest comes back empty -- so
 * every one of those startService calls failed. ActivityManager answered "not
 * found" a hundred and eighty times in thirty-five minutes, about one every twelve
 * seconds, for the life of the app, and this class swallowed the exception so
 * nothing ever said so.
 *
 * Declaring it was the other option and the worse one: it would revive a fallback
 * HUD nobody has been without, and put an eighth process on a device that has been
 * down to seventeen megabytes free. The countdown is already drawn by the rooted
 * ROM path, solar-rescue-hud-watch.sh into {@link SolarRescueHudMain}.
 *
 * The methods stay, so the six call sites still read as intent and reversing this
 * is one file rather than seven. {@link SolarRescueHoldState} is untouched: the
 * sysprops it manages are what the ROM HUD reads.
 *
 * Reversal: restore the two startService bodies and add to AndroidManifest.xml
 *   &lt;service android:name=".SolarRescueHoldService" android:exported="false"
 *            android:process=":hold" /&gt;
 */
public final class SolarRescueHoldHost {

    private SolarRescueHoldHost() {}

    /** No-op: the service this started is not declared, so it only ever failed. */
    public static void ensureStarted(Context context) {
    }

    /** No-op: see {@link #ensureStarted}. */
    public static void ping(Context context) {
    }
}
