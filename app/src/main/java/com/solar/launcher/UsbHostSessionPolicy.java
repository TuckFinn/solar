package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 2026-07-06 — USB host session boot gate: no enable prompt while PC cable was in at boot
 * until settle window passes or user unplugged once.
 * Layman: reboot with USB already plugged in waits before nagging about disk mode.
 * Tech: prefs + sysprops for :overlay/Xposed; {@link #onBootCompleted} records sticky USB_STATE.
 * Reversal: delete and call {@link UsbStorageSessionFlags#shouldOfferUsbConnectPrompt} only.
 */
public final class UsbHostSessionPolicy {

    /** Same store as {@link UsbStorageSessionFlags}. */
    static final String PREFS = UsbStorageSessionFlags.PREFS;
    static final String PREF_BOOT_HOST_AT_BOOT = "usb_boot_host_at_boot";
    static final String PREF_HOST_DISC_SINCE_BOOT = "usb_host_disc_since_boot";
    static final String PREF_BOOT_ELAPSED_RT = "usb_boot_elapsed_rt";
    /** PC host session — one prompt evaluation per plug; cleared on cable unplug (2026-07-06). */
    static final String PREF_SESSION_ACTIVE = "usb_host_session_active";
    static final String PREF_SESSION_DISMISSED = "usb_host_session_dismissed";
    static final String PREF_SESSION_PROMPT_EVALUATED = "usb_host_session_prompt_evaluated";

    /** Xposed / :overlay read without SharedPreferences staleness (2026-07-06). */
    public static final String SYSPROP_BOOT_HOST_AT_BOOT = "sys.solar.usb.boot_host_at_boot";
    public static final String SYSPROP_BOOT_SETTLE_READY = "sys.solar.usb.boot_settle_ready";
    /** 1 while user dismissed enable prompt this host session — stops poll/recovery (2026-07-06). */
    public static final String SYSPROP_SESSION_DISMISSED = "sys.solar.usb.session_dismissed";

    /** Post-boot quiet window before prompting when host was connected at boot (ms). */
    private static final long BOOT_SETTLE_MS = 60_000L;
    /**
     * 2026-07-08 — Fresh plug while Solar already running — do not wait full boot settle.
     * Layman: cable in after the player is up asks about disk mode right away.
     * Tech: clears PREF_BOOT_HOST_AT_BOOT on host connect when process uptime > this.
     * Reversal: delete markFreshHostUnlockBootSettle call — 60s settle applies to every plug.
     */
    private static final long FRESH_HOST_BOOT_UNLOCK_UPTIME_MS = 15_000L;

    private static Runnable pendingSettleRunnable;
    /** Lazily created — unit tests must not touch Looper at class init (2026-07-08). */
    private static Handler settleHandler;

    private UsbHostSessionPolicy() {}

    /** Main-thread settle/eval posts — created on first schedule only. */
    private static Handler settleHandler() {
        if (settleHandler == null) {
            settleHandler = new Handler(Looper.getMainLooper());
        }
        return settleHandler;
    }

    /**
     * Call from {@link BootReceiver} and {@link SolarApplication} once per boot.
     * Records whether PC host was already connected and arms settle timer if needed.
     */
    public static void onBootCompleted(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        // 2026-07-16 — Drop sticky dismiss/evaluated from prior process so a PC cable
        // can show USB Connection after Solar restarts (ADB left session flags stuck).
        clearStaleSessionForNewProcess(app);
        boolean hostAtBoot = probeHostConnectedAtBoot(app);
        long elapsed = SystemClock.elapsedRealtime();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(PREF_BOOT_HOST_AT_BOOT, hostAtBoot)
                .putBoolean(PREF_HOST_DISC_SINCE_BOOT, false)
                .putLong(PREF_BOOT_ELAPSED_RT, elapsed)
                .commit();
        syncSysprops(app, hostAtBoot, false, elapsed);
        scheduleSettleUnlock(app, hostAtBoot, elapsed);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("hostAtBoot", hostAtBoot);
            d.put("bootElapsedRt", elapsed);
            d.put("settleMs", BOOT_SETTLE_MS);
            DebugAf054eLog.log(app, "UsbHostSessionPolicy.onBootCompleted",
                    "boot USB session recorded", "USB-SETTLE", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /**
     * 2026-07-06 — Fresh PC host plug after disconnect/charger-only; arms one-shot prompt evaluation.
     * Layman: new USB session starts — we may ask about disk mode once.
     * Tech: session flags reset on prior {@link #onUsbHostDisconnected}; idempotent if already active.
     * Reversal: delete and rely on MainActivity {@code usbDialog*} booleans only (process-local).
     */
    public static void onUsbHostConnected(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastDisc = prefs.getLong("usb_last_disconnect_time", 0L);
        long elapsed = SystemClock.elapsedRealtime();
        boolean isFlap = lastDisc > 0L && (elapsed - lastDisc) < 3000L;
        boolean wasActive = prefs.getBoolean(PREF_SESSION_ACTIVE, false);
        boolean wasEvaluated = prefs.getBoolean(PREF_SESSION_PROMPT_EVALUATED, false);
        boolean wasDismissed = prefs.getBoolean(PREF_SESSION_DISMISSED, false);
        if (isFlap) {
            // 2026-07-14/16 — Short unplug/replug: re-apply user dismiss if they dismissed this plug.
            // Disconnect now clears dismissed; flap restores it from usb_flap_keep_dismiss.
            boolean keepDismiss = prefs.getBoolean("usb_flap_keep_dismiss", false);
            SharedPreferences.Editor ed = prefs.edit().putBoolean(PREF_SESSION_ACTIVE, true);
            if (keepDismiss) {
                ed.putBoolean(PREF_SESSION_DISMISSED, true)
                        .putBoolean(PREF_SESSION_PROMPT_EVALUATED, true);
            } else {
                ed.putBoolean(PREF_SESSION_DISMISSED, false)
                        .putBoolean(PREF_SESSION_PROMPT_EVALUATED, false);
            }
            ed.commit();
            syncSessionSysprop(app, keepDismiss);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("isFlap", true);
                d.put("wasActive", wasActive);
                d.put("keepDismiss", keepDismiss);
                Debug02fc83Log.log(app, "UsbHostSessionPolicy.onUsbHostConnected",
                        "flap — restore session", "H2", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return;
        }
        // 2026-07-14 — Mid-cable USB_STATE storms must not re-arm; one session until unplug.
        // Was: every USB_STATE cleared evaluated → WakeReceiver + route storm (H2 logs).
        // Reversal: remove wasActive early-return to restore always-reset connect.
        if (wasActive) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("wasActive", true);
                d.put("wasEvaluated", wasEvaluated);
                d.put("wasDismissed", wasDismissed);
                Debug02fc83Log.log(app, "UsbHostSessionPolicy.onUsbHostConnected",
                        "already active — skip re-arm", "H2", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return;
        }
        // 2026-07-08 — Fresh PC plug after Solar is already up: unlock boot-settle immediately.
        markFreshHostUnlockBootSettle(app, prefs, elapsed);
        // Fresh plug after real disconnect — arm one evaluation this session.
        prefs.edit()
                .putBoolean(PREF_SESSION_ACTIVE, true)
                .putBoolean(PREF_SESSION_DISMISSED, false)
                .putBoolean(PREF_SESSION_PROMPT_EVALUATED, false)
                .commit();
        syncSessionSysprop(app, false);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("sessionActive", true);
            d.put("bootSettleReady", isPromptAllowedAfterBootSettle(app));
            d.put("wasActive", wasActive);
            d.put("wasEvaluated", wasEvaluated);
            d.put("wasDismissed", wasDismissed);
            d.put("resetEvaluated", false);
            DebugAf054eLog.log(app, "UsbHostSessionPolicy.onUsbHostConnected",
                    "host session armed — evaluate prompt once", "USB-SESSION", d);
            Debug02fc83Log.log(app, "UsbHostSessionPolicy.onUsbHostConnected",
                    "session armed once", "H2", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /**
     * 2026-07-08 — If Solar process has been up past unlock uptime, drop boot-host settle gate.
     * Layman: plugging USB into an already-running player should prompt now, not after a minute.
     * Tech: boot-at-boot still waits full BOOT_SETTLE_MS until disconnect or settle timer.
     * Reversal: no-op method — reconnect still blocked by stale PREF_BOOT_HOST_AT_BOOT.
     */
    private static void markFreshHostUnlockBootSettle(Context app, SharedPreferences prefs,
            long elapsed) {
        if (app == null || prefs == null) return;
        if (!prefs.getBoolean(PREF_BOOT_HOST_AT_BOOT, false)) return;
        if (prefs.getBoolean(PREF_HOST_DISC_SINCE_BOOT, false)) return;
        long bootElapsed = prefs.getLong(PREF_BOOT_ELAPSED_RT, 0L);
        if (bootElapsed <= 0L) return;
        // Still inside early boot window — keep settle (cable was in at reboot).
        if (elapsed - bootElapsed < FRESH_HOST_BOOT_UNLOCK_UPTIME_MS) return;
        prefs.edit()
                .putBoolean(PREF_BOOT_HOST_AT_BOOT, false)
                .putBoolean(PREF_HOST_DISC_SINCE_BOOT, true)
                .commit();
        cancelPendingSettle();
        syncSysprops(app, false, true, bootElapsed);
        writeSysprop(SYSPROP_BOOT_SETTLE_READY, "1");
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("uptimeSinceBootRt", elapsed - bootElapsed);
            d.put("unlockMs", FRESH_HOST_BOOT_UNLOCK_UPTIME_MS);
            DebugAf054eLog.log(app, "UsbHostSessionPolicy.markFreshHostUnlockBootSettle",
                    "fresh host unlock — prompt allowed now", "USB-SETTLE", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /**
     * Cable unplug — plug gone; next long reconnect can ask again (2026-07-14 / 2026-07-16).
     * Layman: unplug forgets "this plug" so the next PC connect can show USB Connection.
     * Tech: clear active + dismissed + evaluated. Flap (&lt;3s) reconnect re-applies dismiss via
     * {@link #onUsbHostConnected} isFlap path reading last disconnect time only when
     * PREF_FLAP_KEEP_DISMISS was set.
     * Reversal: leave dismissed/evaluated set on disconnect (stuck prompts with ADB).
     */
    public static void onUsbHostDisconnected(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean wasDismissed = prefs.getBoolean(PREF_SESSION_DISMISSED, false);
        // apply() — unplug path must not fsync SharedPreferences on the UI thread (jank).
        prefs.edit()
                .putLong("usb_last_disconnect_time", SystemClock.elapsedRealtime())
                .putBoolean(PREF_SESSION_ACTIVE, false)
                .putBoolean(PREF_SESSION_DISMISSED, false)
                .putBoolean(PREF_SESSION_PROMPT_EVALUATED, false)
                // Remember user dismiss for short flap only.
                .putBoolean("usb_flap_keep_dismiss", wasDismissed)
                .apply();
        syncSessionSysprop(app, false);
        if (!prefs.getBoolean(PREF_HOST_DISC_SINCE_BOOT, false)) {
            long bootElapsed = prefs.getLong(PREF_BOOT_ELAPSED_RT, 0L);
            prefs.edit().putBoolean(PREF_HOST_DISC_SINCE_BOOT, true).apply();
            syncSysprops(app, prefs.getBoolean(PREF_BOOT_HOST_AT_BOOT, false), true, bootElapsed);
        }
        cancelPendingSettle();
        cancelPendingHostEval();
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("reason", "session_reset");
            d.put("wasDismissed", wasDismissed);
            DebugAf054eLog.log(app, "UsbHostSessionPolicy.onUsbHostDisconnected",
                    "host disconnect — session cleared", "USB-SESSION", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /**
     * 2026-07-16 — New process: forget plug session so the first host detect can evaluate.
     * Layman: restarting Solar does not inherit a permanent "don't ask about USB".
     * Tech: clears active/dismissed/evaluated; boot settle still delays reboot-with-cable.
     * Reversal: no-op; sticky prefs blocked prompts until manual unplug forever.
     */
    public static void clearStaleSessionForNewProcess(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(PREF_SESSION_ACTIVE, false)
                .putBoolean(PREF_SESSION_DISMISSED, false)
                .putBoolean(PREF_SESSION_PROMPT_EVALUATED, false)
                .putBoolean("usb_flap_keep_dismiss", false)
                .commit();
        syncSessionSysprop(app, false);
    }

    /** User chose Turn on USB storage — clear dismiss idle for this plug (2026-07-06). */
    public static void clearUserDismissed(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SESSION_DISMISSED, false)
                .commit();
        syncSessionSysprop(app, false);
    }

    /** User dismissed USB enable modal — idle until cable unplug (2026-07-06). */
    public static void markUserDismissed(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(PREF_SESSION_DISMISSED, true)
                .putBoolean(PREF_SESSION_PROMPT_EVALUATED, true)
                .commit();
        syncSessionSysprop(app, true);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("dismissed", true);
            DebugAf054eLog.log(app, "UsbHostSessionPolicy.markUserDismissed",
                    "USB session idle until disconnect", "USB-SESSION", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    /** Prompt shown, auto-connect, or policy skip — no re-evaluation this plug (2026-07-06). */
    public static void markPromptEvaluated(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SESSION_PROMPT_EVALUATED, true)
                .commit();
    }

    /** True while this PC plug session is live (until unplug clears active) (2026-07-14). */
    public static boolean hasActiveHostSession(Context context) {
        if (context == null) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_SESSION_ACTIVE, false);
    }

    /** True after user taps Dismiss on USB enable tier this host session. */
    public static boolean hasUserDismissedThisSession(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_SESSION_DISMISSED, false);
    }

    /** True once this plug's prompt decision ran (show, skip, auto-connect, or dismiss). */
    public static boolean hasPromptEvaluatedThisSession(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_SESSION_PROMPT_EVALUATED, false);
    }

    /**
     * One evaluation per host session — connect event only, not timer/resume/focus (2026-07-06).
     * Reversal: always return true and restore poll-driven {@code routeUsbHostInterceptUi}.
     */
    /**
     * 2026-07-14 — One evaluation per plug: host + settle + not dismissed + not already evaluated.
     * Was: host+settle only — kept allowing route after prompt shown (H1 logs vs testHelperWouldAllow).
     * Reversal: {@code return isPcHostConnected(context) && isPromptAllowedAfterBootSettle(context);}
     */
    public static boolean shouldEvaluatePromptThisSession(Context context) {
        if (context == null) return false;
        boolean host = isPcHostConnected(context);
        boolean settle = isPromptAllowedAfterBootSettle(context);
        boolean dismissed = hasUserDismissedThisSession(context);
        boolean evaluated = hasPromptEvaluatedThisSession(context);
        boolean allow = host && !dismissed && !evaluated && settle;
        // #region agent log
        // Throttle: gate is hot; log only when decision would allow (first plug) or flips deny.
        if (com.solar.launcher.debug.DebugGate.ON && allow || evaluated || dismissed) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("host", host);
                d.put("settle", settle);
                d.put("dismissed", dismissed);
                d.put("evaluated", evaluated);
                d.put("allow", allow);
                Debug02fc83Log.log(context, "UsbHostSessionPolicy.shouldEvaluatePromptThisSession",
                        "gate", "H1", d);
            } catch (Exception ignored) {}
        }
        // #endregion
        return allow;
    }

    /** Poll/HOME/recovery agent must stay off after dismiss, stock UI, or when Xposed owns USB. */
    public static boolean isAggressiveUsbWorkSuppressed(Context context) {
        if (context == null) return false;
        // 2026-07-19 — Android owns USB: never BACK/HOME reclaim against SystemUI.
        if (UsbStorageSessionFlags.preferStockUsbUi(context)) return true;
        if (hasUserDismissedThisSession(context)) return true;
        return UsbStorageConcierge.isXposedConciergeActive();
    }

    /**
     * Run {@code work} now or after boot-settle when PC was connected at cold start (2026-07-06).
     * Layman: reboot with USB plugged in waits before fighting SystemUI.
     */
    public static void runWhenPromptAllowed(final Context context, final Runnable work) {
        if (context == null || work == null) return;
        final Context app = context.getApplicationContext();
        if (isPromptAllowedAfterBootSettle(app)) {
            work.run();
            return;
        }
        cancelPendingHostEval();
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long bootElapsed = prefs.getLong(PREF_BOOT_ELAPSED_RT, 0L);
        long delay = bootElapsed > 0L
                ? Math.max(0L, bootElapsed + BOOT_SETTLE_MS - SystemClock.elapsedRealtime())
                : BOOT_SETTLE_MS;
        pendingHostEvalRunnable = new Runnable() {
            @Override
            public void run() {
                pendingHostEvalRunnable = null;
                if (isPromptAllowedAfterBootSettle(app) && isPcHostConnected(app)) {
                    work.run();
                }
            }
        };
        settleHandler().postDelayed(pendingHostEvalRunnable, delay);
    }

    private static Runnable pendingHostEvalRunnable;

    private static void cancelPendingHostEval() {
        if (pendingHostEvalRunnable != null) {
            settleHandler().removeCallbacks(pendingHostEvalRunnable);
            pendingHostEvalRunnable = null;
        }
    }

    private static void clearHostSessionPrefs(SharedPreferences prefs) {
        prefs.edit()
                .putBoolean(PREF_SESSION_ACTIVE, false)
                .putBoolean(PREF_SESSION_DISMISSED, false)
                .putBoolean(PREF_SESSION_PROMPT_EVALUATED, false)
                .commit();
    }

    private static void writeSysprop(String key, String val) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, val);
        } catch (Exception e) {
            // Never block unplug/session paths on su — async only.
            RootShell.runAsync("setprop " + key + " " + val);
        }
    }

    private static void syncSessionSysprop(Context context, boolean dismissed) {
        writeSysprop(SYSPROP_SESSION_DISMISSED, dismissed ? "1" : "0");
    }

    /**
     * True when USB enable prompt / auto-connect may run (boot settle satisfied).
     * Layman: OK to ask about disk mode now.
     * Tech: not boot-host-at-boot, or disconnect seen, or settle deadline passed.
     */
    public static boolean isPromptAllowedAfterBootSettle(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_BOOT_HOST_AT_BOOT, false)) {
            return true;
        }
        if (prefs.getBoolean(PREF_HOST_DISC_SINCE_BOOT, false)) {
            return true;
        }
        long bootElapsed = prefs.getLong(PREF_BOOT_ELAPSED_RT, 0L);
        if (bootElapsed <= 0L) {
            return true;
        }
        boolean settled = SystemClock.elapsedRealtime() >= bootElapsed + BOOT_SETTLE_MS;
        if (settled) {
            return true;
        }
        logPromptSuppressed(context, "boot_settle");
        return false;
    }

    /** Sync sysprops so SystemUI Xposed sees boot-settle + session dismiss without SP (2026-07-06). */
    public static void syncSysprops(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        syncSysprops(context,
                prefs.getBoolean(PREF_BOOT_HOST_AT_BOOT, false),
                prefs.getBoolean(PREF_HOST_DISC_SINCE_BOOT, false),
                prefs.getLong(PREF_BOOT_ELAPSED_RT, 0L));
        syncSessionSysprop(context, prefs.getBoolean(PREF_SESSION_DISMISSED, false));
    }

    private static void syncSysprops(Context context, boolean hostAtBoot,
            boolean discSinceBoot, long bootElapsedRt) {
        writeSysprop(SYSPROP_BOOT_HOST_AT_BOOT, hostAtBoot ? "1" : "0");
        boolean ready = !hostAtBoot || discSinceBoot
                || (bootElapsedRt > 0L
                && SystemClock.elapsedRealtime() >= bootElapsedRt + BOOT_SETTLE_MS);
        writeSysprop(SYSPROP_BOOT_SETTLE_READY, ready ? "1" : "0");
    }

    private static void scheduleSettleUnlock(final Context app, boolean hostAtBoot, final long bootElapsed) {
        cancelPendingSettle();
        if (!hostAtBoot) return;
        pendingSettleRunnable = new Runnable() {
            @Override
            public void run() {
                pendingSettleRunnable = null;
                SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                syncSysprops(app,
                        prefs.getBoolean(PREF_BOOT_HOST_AT_BOOT, false),
                        prefs.getBoolean(PREF_HOST_DISC_SINCE_BOOT, false),
                        bootElapsed);
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("reason", "boot_settle");
                    d.put("settleMs", BOOT_SETTLE_MS);
                    DebugAf054eLog.log(app, "UsbHostSessionPolicy.scheduleSettleUnlock",
                            "boot settle window elapsed — prompts allowed", "USB-SETTLE", d);
                } catch (Exception ignored) {}
                }
                // #endregion
            }
        };
        settleHandler().postDelayed(pendingSettleRunnable, BOOT_SETTLE_MS);
    }

    private static void cancelPendingSettle() {
        if (pendingSettleRunnable != null) {
            settleHandler().removeCallbacks(pendingSettleRunnable);
            pendingSettleRunnable = null;
        }
    }

    private static void logPromptSuppressed(Context context, String reason) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("reason", reason);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            d.put("hostAtBoot", prefs.getBoolean(PREF_BOOT_HOST_AT_BOOT, false));
            d.put("discSinceBoot", prefs.getBoolean(PREF_HOST_DISC_SINCE_BOOT, false));
            long bootElapsed = prefs.getLong(PREF_BOOT_ELAPSED_RT, 0L);
            d.put("msUntilSettle", bootElapsed > 0L
                    ? Math.max(0L, bootElapsed + BOOT_SETTLE_MS - SystemClock.elapsedRealtime())
                    : 0L);
            DebugAf054eLog.log(context, "UsbHostSessionPolicy.isPromptAllowedAfterBootSettle",
                    "USB prompt suppressed", "USB-SETTLE", d);
        } catch (Exception ignored) {}
    }

    /** Sticky USB_STATE — host (PC) connected when Solar boots (2026-07-06). */
    static boolean probeHostConnectedAtBoot(Context context) {
        return isPcHostConnected(context);
    }

    /**
     * 2026-07-06 — Sticky USB_STATE host probe for :overlay pending-USB flush after ANR dismiss.
     * Layman: only show deferred USB prompt if the PC cable is still plugged in.
     */
    public static boolean isPcHostConnected(Context context) {
        if (context == null) return false;
        try {
            Intent sticky = context.registerReceiver(null,
                    new IntentFilter(UsbHostWakeReceiver.ACTION_USB_STATE));
            if (sticky == null) return false;
            return sticky.getBooleanExtra("connected", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Test hook — boot gate without Android context clock. */
    static boolean isPromptAllowedForTest(boolean hostAtBoot, boolean discSinceBoot,
            long bootElapsedRt, long nowElapsedRt) {
        if (!hostAtBoot) return true;
        if (discSinceBoot) return true;
        if (bootElapsedRt <= 0L) return true;
        return nowElapsedRt >= bootElapsedRt + BOOT_SETTLE_MS;
    }

    /** Test hook — session prompt evaluation gate without Android context. */
    static boolean shouldEvaluatePromptForTest(boolean hostConnected, boolean dismissed,
            boolean evaluated, boolean bootSettleReady) {
        if (!hostConnected) return false;
        if (dismissed) return false;
        if (evaluated) return false;
        return bootSettleReady;
    }

    /** Test hook — aggressive USB poll/recovery suppressed after dismiss. */
    static boolean isAggressiveWorkSuppressedForTest(boolean dismissed, boolean conciergeActive) {
        return dismissed || conciergeActive;
    }

    /** Test hook — session reset clears dismiss + evaluated flags. */
    static SessionStateForTest resetSessionForTest(SessionStateForTest state) {
        return new SessionStateForTest(false, false, false);
    }

    /** Test hook — host connect arms fresh session. */
    static SessionStateForTest onHostConnectForTest(SessionStateForTest state) {
        if (state.sessionActive) return state;
        return new SessionStateForTest(true, false, false);
    }

    /** Test hook — user dismiss marks session idle. */
    static SessionStateForTest onUserDismissForTest(SessionStateForTest state) {
        return new SessionStateForTest(state.sessionActive, true, true);
    }

    static final class SessionStateForTest {
        final boolean sessionActive;
        final boolean dismissed;
        final boolean promptEvaluated;

        SessionStateForTest(boolean sessionActive, boolean dismissed, boolean promptEvaluated) {
            this.sessionActive = sessionActive;
            this.dismissed = dismissed;
            this.promptEvaluated = promptEvaluated;
        }
    }
}
