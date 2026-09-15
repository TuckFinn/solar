package com.solar.launcher.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * iPod Classic-style root screen animations — push/pop, vertical player slide, crossfade.
 * Outgoing roots fade out during push/slide so incoming layers do not pop over opaque chrome.
 */
public final class ScreenTransition {

    // 2026-07-18 — Faster iPod push (was 200/220/180/170). Shorter = earlier wheel after OK/Back.
    // Was: longer push felt sticky on MT6572. Reversal: restore 200/220/180/170.
    public static final int PUSH_MS = 155;
    public static final int PLAYER_MS = 175;
    public static final int CROSSFADE_MS = 140;
    /** Global context modal — slightly snappier than root push. */
    public static final int MODAL_MS = 140;

    private static final float MODAL_PANEL_START_SCALE = 0.94f;

    /** Alpha for outgoing *content* on horizontal push/pop — lists fade so incoming chrome does not pop. */
    private static final float OUTGOING_END_ALPHA = 0f;
    /** Vertical player slides keep both panels opaque so wallpapers slide as solid bands. */
    private static final float VERTICAL_INCOMING_START_ALPHA = 1f;

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);
    /** iPod OS S-curve — LUT, no per-frame transcendentals (PR #23). */
    static final Interpolator PUSH_EASE = new MechanicalInterpolator(new float[] {
            0.00000000f,0.00048016f,0.00193233f,0.00437690f,0.00783292f,0.01232058f,0.01785548f,0.02445495f,
            0.03213394f,0.04090475f,0.05077837f,0.06176269f,0.07386338f,0.08708187f,0.10141547f,0.11685863f,
            0.13339950f,0.15102136f,0.16970230f,0.18941259f,0.21011748f,0.23177491f,0.25433520f,0.27774109f,
            0.30192856f,0.32682592f,0.35235531f,0.37843135f,0.40496340f,0.43185568f,0.45900842f,0.48631858f,
            0.51368142f,0.54099158f,0.56814432f,0.59503660f,0.62156865f,0.64764469f,0.67317408f,0.69807144f,
            0.72225891f,0.74566480f,0.76822509f,0.78988252f,0.81058741f,0.83029770f,0.84897864f,0.86660050f,
            0.88314137f,0.89858453f,0.91291813f,0.92613662f,0.93823731f,0.94922163f,0.95909525f,0.96786606f,
            0.97554505f,0.98214452f,0.98767942f,0.99216708f,0.99562310f,0.99806767f,0.99951984f,1.00000000f});
    private static final Interpolator PLAYER_EASE = new MechanicalInterpolator(new float[] {
            0f,.00204130f,.00783758f,.01693070f,.02890396f,.04337882f,.06001181f,.07849167f,
            .09853675f,.11989254f,.14232946f,.16564076f,.18964062f,.21416238f,.23905689f,.26419106f,
            .28944640f,.31471781f,.33991234f,.36494818f,.38975358f,.41426600f,.43843123f,.46220264f,
            .48554041f,.50841097f,.53078630f,.55264345f,.57396401f,.59473364f,.61494166f,.63458064f,
            .65364610f,.67213612f,.69005108f,.70739340f,.72416728f,.74037846f,.75603407f,.77114239f,
            .78571270f,.79975517f,.81328066f,.82630063f,.83882705f,.85087224f,.86244885f,.87356973f,
            .88424788f,.89449637f,.90432832f,.91375680f,.92279483f,.93145530f,.93975098f,.94769446f,
            .95529812f,.96257414f,.96953447f,.97619077f,.98255447f,.98863672f,.99444837f,1f});
    private static final Interpolator MODAL_EASE = new MechanicalInterpolator(new float[] {
            0f,.00433240f,.01627593f,.03441200f,.05751693f,.08453828f,.11457381f,.14685288f,
            .18072002f,.21562029f,.25108650f,.28672781f,.32221973f,.35729525f,.39173715f,.42537107f,
            .45805958f,.48969688f,.52020421f,.54952577f,.57762525f,.60448269f,.63009187f,.65445794f,
            .67759542f,.69952644f,.72027926f,.73988695f,.75838631f,.77581689f,.79222021f,.80763906f,
            .82211693f,.83569756f,.84842447f,.86034070f,.87148850f,.88190917f,.89164280f,.90072822f,
            .90920289f,.91710278f,.92446240f,.93131470f,.93769112f,.94362158f,.94913448f,.95425675f,
            .95901385f,.96342987f,.96752752f,.97132819f,.97485204f,.97811798f,.98114380f,.98394617f,
            .98654071f,.98894207f,.99116393f,.99321908f,.99511947f,.99687627f,.99849988f,1f});

    private static volatile boolean animating;
    private static volatile boolean modalAnimating;
    private static volatile long lastFrameNanos;
    /** 2026-07-19 — Views in the live root transition (abort cancels these). */
    private static View activeOut;
    private static View activeIn;
    private static View activeOutBackdrop;
    private static View activeInBackdrop;
    /** Forward complete from interrupted anim — abort skips this (Back applies its own dest). */
    private static Runnable activeComplete;

    private ScreenTransition() {}

    public static boolean isAnimating() {
        return animating;
    }

    public static boolean isModalAnimating() {
        return modalAnimating;
    }

    public static boolean systemAnimationsDisabled(android.content.Context ctx) {
        if (ctx == null) return false;
        try {
            return Settings.Global.getFloat(ctx.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static float easeOutQuad(float t) {
        if (t >= 1f) return 1f;
        if (t <= 0f) return 0f;
        return 1f - (1f - t) * (1f - t);
    }

    /** @visibleForTesting */
    static float outgoingEndAlpha() {
        return OUTGOING_END_ALPHA;
    }

    static float pushInterpolation(float t) { return PUSH_EASE.getInterpolation(t); }
    static float playerInterpolation(float t) { return PLAYER_EASE.getInterpolation(t); }
    static float modalInterpolation(float t) { return MODAL_EASE.getInterpolation(t); }

    /**
     * 2026-07-19 — Flag-only clear (legacy). Prefer {@link #abort()} when interrupting for Back.
     * Was: sole cancel path. Reversal: callers that only need the flag can keep using this.
     */
    public static void cancel() {
        animating = false;
        modalAnimating = false;
    }

    /**
     * 2026-07-19 — Hard-stop in-flight root slide/crossfade without running the old onComplete.
     * Layman: Back mid-slide must not leave the next screen invisible with a stuck spinner.
     * Technical: cancel ViewPropertyAnimators, reset transforms, drop animating; skip activeComplete
     * so changeScreen can sync-apply the parent. Reversal: restore cancel()-only in changeScreen.
     */
    public static void abort() {
        View o = activeOut;
        View i = activeIn;
        View ob = activeOutBackdrop;
        View ib = activeInBackdrop;
        activeOut = null;
        activeIn = null;
        activeOutBackdrop = null;
        activeInBackdrop = null;
        activeComplete = null;
        animating = false;
        modalAnimating = false;
        resetView(o);
        resetView(i);
        resetView(ob);
        resetView(ib);
    }

    /** Remember views for {@link #abort()} while a root transition runs. */
    private static void trackActive(View outView, View inView, View outBackdrop, View inBackdrop,
            Runnable onComplete) {
        activeOut = outView;
        activeIn = inView;
        activeOutBackdrop = outBackdrop;
        activeInBackdrop = inBackdrop;
        activeComplete = onComplete;
    }

    private static void clearActiveTracking() {
        activeOut = null;
        activeIn = null;
        activeOutBackdrop = null;
        activeInBackdrop = null;
        activeComplete = null;
    }

    public static void animatePushPop(final View outView, final View inView, final boolean forward,
            final int distancePx, final Runnable onComplete) {
        animatePushPop(outView, inView, null, null, forward, distancePx, onComplete);
    }

    public static void animatePushPop(final View outView, final View inView,
            final View outBackdrop, final View inBackdrop, final boolean forward,
            final int distancePx, final Runnable onComplete) {
        if (inView == null) {
            finish(outView, null, onComplete);
            return;
        }
        // PR #23: unequal panel mass — outgoing moves 0.35x for mechanical parallax.
        final float inStart = forward ? distancePx : -0.35f * distancePx;
        final float outEnd = forward ? -0.35f * distancePx : distancePx;

        inView.setVisibility(View.VISIBLE);
        inView.setAlpha(1f);
        inView.setTranslationX(inStart);
        if (outView != null) {
            outView.setVisibility(View.VISIBLE);
            outView.setTranslationX(0f);
            outView.setAlpha(1f);
        }
        prepareBackdropPushPop(outBackdrop, inBackdrop, forward, distancePx, false);
        animating = true;
        enableHardwareLayer(inView);
        enableHardwareLayer(outView);
        enableHardwareLayer(outBackdrop);
        enableHardwareLayer(inBackdrop);
        logAnimStart("pushPop", forward, distancePx);
        if (TransitionPerfLog.ENABLED) attachFrameGapProbe(inView);

        final Runnable done = new Runnable() {
            @Override
            public void run() {
                clearActiveTracking();
                animating = false;
                resetView(outView);
                resetView(inView);
                resetView(outBackdrop);
                resetView(inBackdrop);
                logAnimEnd("pushPop");
                if (onComplete != null) onComplete.run();
            }
        };
        trackActive(outView, inView, outBackdrop, inBackdrop, done);
        inView.animate().translationX(0f).setDuration(PUSH_MS).setInterpolator(PUSH_EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            outView.animate().translationX(outEnd).alpha(OUTGOING_END_ALPHA)
                    .setDuration(forward ? PUSH_MS - 20 : PUSH_MS).setInterpolator(PUSH_EASE).start();
        }
        animateBackdropPushPop(outBackdrop, inBackdrop, forward, distancePx, false);
    }

    public static void animateSlideY(final View outView, final View inView, final boolean entering,
            final int distancePx, final Runnable onComplete) {
        animateSlideY(outView, inView, null, null, entering, distancePx, onComplete);
    }

    public static void animateSlideY(final View outView, final View inView,
            final View outBackdrop, final View inBackdrop, final boolean entering,
            final int distancePx, final Runnable onComplete) {
        if (inView == null) {
            finish(outView, null, onComplete);
            return;
        }
        final float inStart = entering ? distancePx : -distancePx;
        final float outEnd = entering ? -distancePx * 0.15f : distancePx;

        inView.setVisibility(View.VISIBLE);
        inView.setAlpha(VERTICAL_INCOMING_START_ALPHA);
        inView.setTranslationY(inStart);
        if (outView != null) {
            outView.setVisibility(View.VISIBLE);
            outView.setTranslationY(0f);
            outView.setAlpha(1f);
        }
        prepareBackdropPushPop(outBackdrop, inBackdrop, entering, distancePx, true);
        animating = true;
        enableHardwareLayer(inView);
        enableHardwareLayer(outView);
        enableHardwareLayer(outBackdrop);
        enableHardwareLayer(inBackdrop);
        logAnimStart("slideY", entering, distancePx);
        if (TransitionPerfLog.ENABLED) attachFrameGapProbe(inView);

        final Runnable done = new Runnable() {
            @Override
            public void run() {
                clearActiveTracking();
                animating = false;
                resetView(outView);
                resetView(inView);
                resetView(outBackdrop);
                resetView(inBackdrop);
                logAnimEnd("slideY");
                if (onComplete != null) onComplete.run();
            }
        };
        trackActive(outView, inView, outBackdrop, inBackdrop, done);
        inView.animate().translationY(0f).setDuration(PLAYER_MS).setInterpolator(PLAYER_EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            // Translate only — fading the player/menu to transparent exposes the window (black).
            outView.animate().translationY(outEnd)
                    .setDuration(PLAYER_MS).setInterpolator(PLAYER_EASE).start();
        }
        animateBackdropPushPop(outBackdrop, inBackdrop, entering, distancePx, true);
    }

    public static void animateCrossfade(final View outView, final View inView, final Runnable onComplete) {
        animateCrossfade(outView, inView, null, null, onComplete);
    }

    public static void animateCrossfade(final View outView, final View inView,
            final View outBackdrop, final View inBackdrop, final Runnable onComplete) {
        if (inView == null) {
            finish(outView, null, onComplete);
            return;
        }
        inView.setVisibility(View.VISIBLE);
        inView.setAlpha(0f);
        if (outView != null) {
            outView.setVisibility(View.VISIBLE);
            outView.setAlpha(1f);
        }
        if (inBackdrop != null) {
            inBackdrop.setVisibility(View.VISIBLE);
            inBackdrop.setAlpha(0f);
        }
        if (outBackdrop != null) {
            outBackdrop.setVisibility(View.VISIBLE);
            outBackdrop.setAlpha(1f);
        }
        animating = true;
        enableHardwareLayer(inView);
        enableHardwareLayer(outView);
        enableHardwareLayer(outBackdrop);
        enableHardwareLayer(inBackdrop);
        logAnimStart("crossfade", true, 0);
        if (TransitionPerfLog.ENABLED) attachFrameGapProbe(inView);

        final Runnable done = new Runnable() {
            @Override
            public void run() {
                clearActiveTracking();
                animating = false;
                resetView(outView);
                inView.setAlpha(1f);
                resetView(outBackdrop);
                resetView(inBackdrop);
                logAnimEnd("crossfade");
                if (onComplete != null) onComplete.run();
            }
        };
        trackActive(outView, inView, outBackdrop, inBackdrop, done);
        inView.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(MODAL_EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            outView.animate().alpha(0f).setDuration(CROSSFADE_MS).setInterpolator(MODAL_EASE).start();
        }
        if (inBackdrop != null) {
            inBackdrop.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(MODAL_EASE).start();
        }
        if (outBackdrop != null) {
            outBackdrop.animate().alpha(0f).setDuration(CROSSFADE_MS).setInterpolator(MODAL_EASE).start();
        }
    }

    public static void fadeInView(final View view, final Runnable onComplete) {
        if (view == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        animating = true;
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                animating = false;
                view.setAlpha(1f);
                if (onComplete != null) onComplete.run();
            }
        };
        view.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(MODAL_EASE)
                .setListener(endListener(done)).start();
    }

    /** Scrim fade + panel scale/alpha for global context modal — caller must prepare first. */
    public static void animateModalPresent(final View scrim, final View panel, final Runnable onComplete) {
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("hasScrim", scrim != null);
            d.put("hasPanel", panel != null);
            d.put("panelW", panel != null ? panel.getWidth() : -1);
            d.put("panelH", panel != null ? panel.getHeight() : -1);
            d.put("modalMs", MODAL_MS);
            com.solar.launcher.Debug0f5debLog.log(
                    panel != null ? panel.getContext() : (scrim != null ? scrim.getContext() : null),
                    "ScreenTransition.animateModalPresent", "present start", "MOD-B", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (scrim == null && panel == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        modalAnimating = true;
        enableHardwareLayer(scrim);
        enableHardwareLayer(panel);

        final Runnable start = new Runnable() {
            @Override
            public void run() {
                if (panel != null) {
                    panel.setPivotX(panel.getWidth() * 0.5f);
                    panel.setPivotY(panel.getHeight() * 0.5f);
                }
                final Runnable done = new Runnable() {
                    @Override
                    public void run() {
                        modalAnimating = false;
                        if (panel != null) {
                            panel.setScaleX(1f);
                            panel.setScaleY(1f);
                            panel.setAlpha(1f);
                            clearHardwareLayer(panel);
                        }
                        if (scrim != null) {
                            scrim.setAlpha(1f);
                            clearHardwareLayer(scrim);
                        }
                        if (onComplete != null) onComplete.run();
                    }
                };
                if (panel != null) {
                    panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(MODAL_MS).setInterpolator(MODAL_EASE)
                            .setListener(endListener(done)).start();
                } else {
                    done.run();
                    return;
                }
                if (scrim != null) {
                    scrim.animate().alpha(1f).setDuration(MODAL_MS).setInterpolator(MODAL_EASE).start();
                }
            }
        };

        // Next frame after rest state is committed — present was invisible on API 17 without this.
        View anchor = panel != null ? panel : scrim;
        anchor.postOnAnimation(start);
    }

    public static void animateModalDismiss(final View scrim, final View panel, final Runnable onComplete) {
        if (scrim == null && panel == null) {
            modalAnimating = false;
            if (onComplete != null) onComplete.run();
            return;
        }
        modalAnimating = true;
        enableHardwareLayer(scrim);
        enableHardwareLayer(panel);
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                modalAnimating = false;
                clearHardwareLayer(scrim);
                clearHardwareLayer(panel);
                if (onComplete != null) onComplete.run();
            }
        };
        boolean panelStarted = false;
        if (panel != null) {
            panelStarted = true;
            panel.animate().scaleX(MODAL_PANEL_START_SCALE).scaleY(MODAL_PANEL_START_SCALE).alpha(0f)
                    .setDuration(MODAL_MS).setInterpolator(MODAL_EASE)
                    .setListener(endListener(done)).start();
        }
        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(MODAL_MS).setInterpolator(MODAL_EASE).start();
        }
        if (!panelStarted) {
            done.run();
        }
    }

    /** WM overlay — animate panel only; scrim stays transparent (avoids KitKat white flash). */
    public static void prepareModalPresentPanelOnly(View panel) {
        if (panel != null) {
            panel.setPivotX(panel.getWidth() > 0 ? panel.getWidth() * 0.5f : 0f);
            panel.setPivotY(panel.getHeight() > 0 ? panel.getHeight() * 0.5f : 0f);
            panel.setScaleX(MODAL_PANEL_START_SCALE);
            panel.setScaleY(MODAL_PANEL_START_SCALE);
            panel.setAlpha(0f);
        }
    }

    public static void animateModalPresentPanelOnly(final View panel, final Runnable onComplete) {
        if (panel == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        modalAnimating = true;
        enableHardwareLayer(panel);
        final Runnable start = new Runnable() {
            @Override
            public void run() {
                panel.setPivotX(panel.getWidth() * 0.5f);
                panel.setPivotY(panel.getHeight() * 0.5f);
                final Runnable done = new Runnable() {
                    @Override
                    public void run() {
                        modalAnimating = false;
                        panel.setScaleX(1f);
                        panel.setScaleY(1f);
                        panel.setAlpha(1f);
                        clearHardwareLayer(panel);
                        if (onComplete != null) onComplete.run();
                    }
                };
                panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(MODAL_MS).setInterpolator(MODAL_EASE)
                        .setListener(endListener(done)).start();
            }
        };
        panel.postOnAnimation(start);
    }

    public static void animateModalDismissPanelOnly(final View panel, final Runnable onComplete) {
        if (panel == null) {
            modalAnimating = false;
            if (onComplete != null) onComplete.run();
            return;
        }
        modalAnimating = true;
        enableHardwareLayer(panel);
        panel.setPivotX(panel.getWidth() * 0.5f);
        panel.setPivotY(panel.getHeight() * 0.5f);
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                modalAnimating = false;
                clearHardwareLayer(panel);
                if (onComplete != null) onComplete.run();
            }
        };
        panel.animate().scaleX(MODAL_PANEL_START_SCALE).scaleY(MODAL_PANEL_START_SCALE).alpha(0f)
                .setDuration(MODAL_MS).setInterpolator(MODAL_EASE)
                .setListener(endListener(done)).start();
    }

    /** Initial modal state before present animation. */
    public static void prepareModalPresent(View scrim, View panel) {
        if (scrim != null) scrim.setAlpha(0f);
        if (panel != null) {
            panel.setPivotX(panel.getWidth() > 0 ? panel.getWidth() * 0.5f : 0f);
            panel.setPivotY(panel.getHeight() > 0 ? panel.getHeight() * 0.5f : 0f);
            panel.setScaleX(MODAL_PANEL_START_SCALE);
            panel.setScaleY(MODAL_PANEL_START_SCALE);
            panel.setAlpha(0f);
        }
    }

    public static void fadeOutView(final View view, final Runnable onComplete) {
        if (view == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        view.setAlpha(1f);
        animating = true;
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                animating = false;
                view.setAlpha(0f);
                view.setVisibility(View.GONE);
                if (onComplete != null) onComplete.run();
            }
        };
        view.animate().alpha(0f).setDuration(CROSSFADE_MS).setInterpolator(MODAL_EASE)
                .setListener(endListener(done)).start();
    }

    public static void resetView(View v) {
        if (v == null) return;
        // Detach listener before cancel — API 17 onAnimationCancel must not recurse into resetView.
        v.animate().setListener(null);
        v.animate().cancel();
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setAlpha(1f);
        clearHardwareLayer(v);
    }

    /** Promote animating views to GPU layer for smoother slides on MT6572. */
    public static void enableHardwareLayer(View v) {
        if (v == null) return;
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /** Restore default layer after transition — limits VRAM use. */
    public static void clearHardwareLayer(View v) {
        if (v == null) return;
        v.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private static void prepareBackdropPushPop(View outBackdrop, View inBackdrop,
            boolean forward, int distancePx, boolean vertical) {
        if (outBackdrop == null && inBackdrop == null) return;
        final float sign = forward ? 1f : -1f;
        if (vertical) {
            final float inStart = forward ? distancePx : -distancePx;
            if (inBackdrop != null) {
                inBackdrop.setVisibility(View.VISIBLE);
                inBackdrop.setTranslationY(inStart);
                inBackdrop.setAlpha(VERTICAL_INCOMING_START_ALPHA);
            }
            if (outBackdrop != null) {
                outBackdrop.setVisibility(View.VISIBLE);
                outBackdrop.setTranslationY(0f);
                outBackdrop.setAlpha(1f);
            }
        } else {
            final float inStart = sign * distancePx;
            if (inBackdrop != null) {
                inBackdrop.setVisibility(View.VISIBLE);
                inBackdrop.setTranslationX(inStart);
                inBackdrop.setAlpha(1f);
            }
            if (outBackdrop != null) {
                outBackdrop.setVisibility(View.VISIBLE);
                outBackdrop.setTranslationX(0f);
                outBackdrop.setAlpha(1f);
            }
        }
    }

    private static void animateBackdropPushPop(View outBackdrop, View inBackdrop,
            boolean forward, int distancePx, boolean vertical) {
        if (outBackdrop == null && inBackdrop == null) return;
        final int duration = vertical ? PLAYER_MS : PUSH_MS;
        final float sign = forward ? 1f : -1f;
        if (vertical) {
            final float inStart = forward ? distancePx : -distancePx;
            final float outEnd = forward ? -distancePx * 0.15f : distancePx;
            if (inBackdrop != null) {
                inBackdrop.setTranslationY(inStart);
                inBackdrop.animate().translationY(0f)
                        .setDuration(duration).setInterpolator(MODAL_EASE).start();
            }
            if (outBackdrop != null) {
                // Wallpaper layers slide in lockstep — never fade to black between themes.
                outBackdrop.animate().translationY(outEnd)
                        .setDuration(duration).setInterpolator(MODAL_EASE).start();
            }
        } else {
            final float inStart = sign * distancePx;
            final float outEnd = -sign * distancePx;
            if (inBackdrop != null) {
                inBackdrop.setTranslationX(inStart);
                inBackdrop.animate().translationX(0f)
                        .setDuration(duration).setInterpolator(MODAL_EASE).start();
            }
            if (outBackdrop != null) {
                outBackdrop.animate().translationX(outEnd)
                        .setDuration(duration).setInterpolator(MODAL_EASE).start();
            }
        }
    }

    /** Shared end listener for multi-view crossfades (Flow handoff). */
    public static android.animation.Animator.AnimatorListener endListenerFor(final Runnable onComplete) {
        return endListener(onComplete);
    }

    private static Animator.AnimatorListener endListener(final Runnable onComplete) {
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) onComplete.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // 2026-07-06 — Clear animating on cancel so wheel keys are not stuck swallowed.
                animating = false;
                modalAnimating = false;
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("hadComplete", onComplete != null);
                    com.solar.launcher.Debug54d8beLog.log("ScreenTransition.endListener",
                            "anim cancel — onComplete NOT run", "H1", d);
                } catch (Exception ignored) {}
                }
                // #endregion
            }
        };
    }

    private static void finish(View outView, View inView, Runnable onComplete) {
        animating = false;
        resetView(outView);
        resetView(inView);
        if (onComplete != null) onComplete.run();
    }

    private static void attachFrameGapProbe(final View anchor) {
        lastFrameNanos = 0L;
        final Choreographer.FrameCallback probe = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (!animating) return;
                if (lastFrameNanos > 0L) {
                    long gapMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L;
                    if (gapMs > 20L) {
                        // #region agent log
                        if (com.solar.launcher.debug.DebugGate.ON) {
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("gapMs", gapMs);
                            TransitionPerfLog.log("ScreenTransition.frameProbe", "slow frame", "B", d);
                        } catch (Exception ignored) {}
                        }
                        // #endregion
                    }
                }
                lastFrameNanos = frameTimeNanos;
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        anchor.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                Choreographer.getInstance().postFrameCallback(probe);
            }
        });
    }

    private static void logAnimStart(String kind, boolean forward, int distance) {
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("kind", kind);
            d.put("forward", forward);
            d.put("distance", distance);
            TransitionPerfLog.log("ScreenTransition", "anim start", "B", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    private static void logAnimEnd(String kind) {
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("kind", kind);
            TransitionPerfLog.log("ScreenTransition", "anim end", "B", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }
}
