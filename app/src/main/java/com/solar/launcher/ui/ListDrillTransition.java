package com.solar.launcher.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

/**
 * Push/pop on clipped list hosts (settings, library browse) — iPod submenu drill.
 * Outgoing content slides off before rebuild; incoming slides on in the same motion family.
 */
public final class ListDrillTransition {

    private static final Interpolator EASE = ScreenTransition.PUSH_EASE;
    private static volatile boolean animating;
    /** 2026-07-19 — Bumps on abort so cancelled phase withEndAction cannot restart a slide. */
    private static volatile int drillGen;

    private ListDrillTransition() {}

    public static boolean isAnimating() {
        return animating || ScreenTransition.isAnimating() || LayoutMorphTransition.isAnimating();
    }

    /**
     * 2026-07-16 — Read menu_transitions from SOLAR_SETTINGS (same store as MainActivity).
     * Was: package _preferences (always defaulted on). Reversal: restore _preferences file.
     */
    public static boolean enabled(Context ctx) {
        if (ctx == null) return false;
        if (ScreenTransition.systemAnimationsDisabled(ctx)) return false;
        SharedPreferences prefs = ctx.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_MENU_TRANSITIONS, true);
    }

    public static final String PREF_MENU_TRANSITIONS = "menu_transitions";

    public static void push(final ViewGroup host, final Runnable build) {
        run(host, build, true);
    }

    public static void pop(final ViewGroup host, final Runnable build) {
        run(host, build, false);
    }

    private static void run(final ViewGroup host, final Runnable build, final boolean forward) {
        if (host == null || build == null) {
            if (build != null) build.run();
            return;
        }
        // 2026-07-19 — Mid-slide Back/Forward must kill the old slide before rebuild.
        // Layman: one Back during a submenu slide must land on the parent, not look ignored.
        // Was: instant build while host still translating → first Back felt like a no-op.
        // Reversal: restore `|| animating` instant branch without abort.
        if (animating) {
            abort(host);
        }
        // 2026-07-18 — Status throbber for every settings/library drill (anim on or off).
        // Layman: spinner while the submenu list is built. Technical: REASON_TRANSITION.
        // Was: no UiBusy on list drill — only root ScreenTransitionCoordinator felt busy.
        // Reversal: drop begin/clear below.
        UiBusy.beginAutoEnd(UiBusy.REASON_TRANSITION, 6_000L);
        // Listener paints instantly (MainActivity syncStatusBarLoadingThrobber — no fade-in).
        if (!enabled(host.getContext())) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("path", "instant");
                d.put("forward", forward);
                d.put("enabled", enabled(host.getContext()));
                d.put("animating", animating);
                d.put("reasons", UiBusy.snapshotReasons());
                com.solar.launcher.DebugCb4747Log.log("ListDrillTransition.run",
                        "instant drill clear-same-stack", "A", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            try {
                build.run();
            } finally {
                // 2026-07-19 — One-frame defer so instant drill still shows spinner once.
                UiBusy.clearNextFrame(UiBusy.REASON_TRANSITION);
            }
            return;
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("path", "animated");
            d.put("forward", forward);
            d.put("reasons", UiBusy.snapshotReasons());
            com.solar.launcher.DebugCb4747Log.log("ListDrillTransition.run",
                    "animated drill", "D", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        final int w = host.getWidth() > 0 ? host.getWidth() : host.getResources()
                .getDimensionPixelSize(com.solar.launcher.R.dimen.y1_screen_width);
        final float outEnd = forward ? -w : w;
        final float inStart = forward ? w : -w;
        final int halfMs = ScreenTransition.PUSH_MS / 2;
        final int gen = ++drillGen;

        host.setVisibility(View.VISIBLE);
        host.setAlpha(1f);
        host.setTranslationX(0f);
        animating = true;
        ScreenTransition.enableHardwareLayer(host);

        // Phase 1 — slide outgoing list off before rebuild.
        host.animate().translationX(outEnd).alpha(0.55f)
                .setDuration(halfMs).setInterpolator(EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // 2026-07-19 — Abort may have bumped drillGen mid-slide.
                        if (gen != drillGen) return;
                        host.setTranslationX(inStart);
                        host.setAlpha(1f);
                        build.run();
                        host.postOnAnimation(new Runnable() {
                            @Override
                            public void run() {
                                if (gen != drillGen) return;
                                // Phase 2 — slide incoming list on.
                                host.animate().translationX(0f).alpha(1f)
                                        .setDuration(ScreenTransition.PUSH_MS).setInterpolator(EASE)
                                        .withEndAction(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (gen != drillGen) return;
                                                animating = false;
                                                host.setTranslationX(0f);
                                                ScreenTransition.clearHardwareLayer(host);
                                                UiBusy.clear(UiBusy.REASON_TRANSITION);
                                            }
                                        }).start();
                            }
                        });
                    }
                }).start();
    }

    /**
     * 2026-07-19 — Hard-stop list drill mid-slide; run build instantly if provided.
     * Layman: Back during a submenu slide finishes the list rebuild without waiting.
     * Technical: bump drillGen + cancel host animate, clear animating + TRANSITION busy.
     * Reversal: resetHost only (no gen bump).
     */
    public static void abort(ViewGroup host) {
        drillGen++;
        if (host != null) {
            host.animate().setListener(null);
            host.animate().cancel();
            host.setTranslationX(0f);
            host.setAlpha(1f);
            ScreenTransition.clearHardwareLayer(host);
        }
        animating = false;
        UiBusy.clearNextFrame(UiBusy.REASON_TRANSITION);
    }

    /**
     * 2026-07-19 — Abort then rebuild parent list synchronously (Back during drill).
     */
    public static void abortAndBuild(ViewGroup host, Runnable build) {
        abort(host);
        if (build != null) {
            try {
                build.run();
            } finally {
                UiBusy.clearNextFrame(UiBusy.REASON_TRANSITION);
            }
        }
    }

    /** Reset drill host after hard escape to home — cancels in-flight slide. */
    public static void resetHost(ViewGroup host) {
        abort(host);
    }
}
