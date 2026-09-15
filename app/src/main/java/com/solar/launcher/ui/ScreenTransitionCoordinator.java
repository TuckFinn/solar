package com.solar.launcher.ui;

import android.view.View;

import com.solar.launcher.flow.FlowPlayerHandoff;

import org.json.JSONObject;

/**
 * Orchestrates root-level screen transitions before {@code applyScreenChange} finalizes visibility.
 * Backdrop slots animate in lockstep with content roots when the host prepares them.
 */
public final class ScreenTransitionCoordinator {

    public interface Host {
        android.content.Context context();
        int screenWidthPx();
        int screenHeightPx();
        boolean menuTransitionsEnabled();
        View rootViewForState(int state);
        void applyScreenChange(int state, boolean deferOutgoingHide);
        void finalizeScreenVisibility(int state);
        void prepareTransitionBackdrop(int from, int to);
        View backdropOutSlot();
        View backdropInSlot();
        void commitTransitionBackdrop(int to);
        boolean backdropCrossfadeOnly();
    }

    private ScreenTransitionCoordinator() {}

    public static boolean shouldRun(Host host, int from, int to, boolean isBack) {
        if (host == null || from == to) return false;
        if (!host.menuTransitionsEnabled()) return false;
        if (ScreenTransition.systemAnimationsDisabled(host.context())) return false;
        if (ScreenTransition.isAnimating() || FlowPlayerHandoff.isHandoffAnimating()) return false;
        ScreenTransitionMap.Kind kind = ScreenTransitionMap.resolve(from, to, isBack);
        if (kind == ScreenTransitionMap.Kind.NONE
                || kind == ScreenTransitionMap.Kind.DELEGATE_FLOW) {
            return false;
        }
        View outView = host.rootViewForState(from);
        View inView = host.rootViewForState(to);
        if (inView == null) return false;
        if (ScreenTransitionMap.sharesRoot(from, to)
                && kind != ScreenTransitionMap.Kind.CROSSFADE) {
            return false;
        }
        return kind != ScreenTransitionMap.Kind.NONE;
    }

    /**
     * 2026-07-18 — Run push/pop/slide after one frame so status throbber can paint first.
     * Layman: spinner shows, then the new screen builds and slides in.
     * Technical: post apply+anim; UiBusy TRANSITION armed by caller (changeScreen).
     * Was: applyScreenChange on calling thread before post — throbber never painted mid-build.
     * Reversal: call applyScreenChange synchronously then outView.post(startAnim).
     */
    public static void run(final Host host, final int from, final int to, final boolean isBack) {
        if (host == null) {
            return;
        }
        final ScreenTransitionMap.Kind kind = ScreenTransitionMap.resolve(from, to, isBack);
        final View outView = host.rootViewForState(from);
        final View inView = host.rootViewForState(to);
        final int w = host.screenWidthPx();
        final int h = host.screenHeightPx();

        // #region agent log
        final long scheduleMs = System.currentTimeMillis();
        try {
            JSONObject d = new JSONObject();
            d.put("from", from);
            d.put("to", to);
            d.put("kind", kind.name());
            d.put("busyTransition", UiBusy.isBusy(UiBusy.REASON_TRANSITION));
            TransitionPerfLog.log("ScreenTransitionCoordinator.run", "scheduled post-frame", "H1-H4", d);
        } catch (Exception ignored) {}
        // #endregion

        final Runnable complete = new Runnable() {
            @Override
            public void run() {
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("from", from);
                    d.put("to", to);
                    d.put("outVis", outView != null ? outView.getVisibility() : -1);
                    d.put("inVis", inView != null ? inView.getVisibility() : -1);
                    com.solar.launcher.Debug54d8beLog.log("ScreenTransitionCoordinator.complete",
                            "transition complete", "H1", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                if (outView != null && outView != inView) {
                    outView.setVisibility(View.GONE);
                    ScreenTransition.resetView(outView);
                }
                host.commitTransitionBackdrop(to);
                host.finalizeScreenVisibility(to);
                // Destination interactive — drop transition spinner (library may re-arm library_load).
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("from", from);
                    d.put("to", to);
                    d.put("reasons", UiBusy.snapshotReasons());
                    com.solar.launcher.DebugCb4747Log.log("ScreenTransitionCoordinator.complete",
                            "clear TRANSITION after anim", "D", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                UiBusy.clearNextFrame(UiBusy.REASON_TRANSITION);
            }
        };

        final Runnable startAnim = new Runnable() {
            @Override
            public void run() {
                if (host.backdropCrossfadeOnly() && kind != ScreenTransitionMap.Kind.SLIDE_UP
                        && kind != ScreenTransitionMap.Kind.SLIDE_DOWN) {
                    ScreenTransition.animateCrossfade(outView, inView, outBackdropOrNull(host),
                            inBackdropOrNull(host), complete);
                    return;
                }
                switch (kind) {
                    case PUSH_FORWARD:
                        ScreenTransition.animatePushPop(outView, inView,
                                outBackdropOrNull(host), inBackdropOrNull(host),
                                true, w, complete);
                        break;
                    case POP_BACK:
                        ScreenTransition.animatePushPop(outView, inView,
                                outBackdropOrNull(host), inBackdropOrNull(host),
                                false, w, complete);
                        break;
                    case SLIDE_UP:
                        ScreenTransition.animateSlideY(outView, inView,
                                outBackdropOrNull(host), inBackdropOrNull(host),
                                true, h, complete);
                        break;
                    case SLIDE_DOWN:
                        ScreenTransition.animateSlideY(outView, inView,
                                outBackdropOrNull(host), inBackdropOrNull(host),
                                false, h, complete);
                        break;
                    case CROSSFADE:
                        ScreenTransition.animateCrossfade(outView, inView,
                                outBackdropOrNull(host), inBackdropOrNull(host), complete);
                        break;
                    default:
                        host.applyScreenChange(to, false);
                        host.commitTransitionBackdrop(to);
                        host.finalizeScreenVisibility(to);
                        UiBusy.clearNextFrame(UiBusy.REASON_TRANSITION);
                        break;
                }
            }
        };

        // One frame for UiBusy paint, then build destination + kick anim.
        final Runnable buildThenAnim = new Runnable() {
            @Override
            public void run() {
                // #region agent log
                final long buildStartMs = System.currentTimeMillis();
                // #endregion
                host.applyScreenChange(to, true);
                host.prepareTransitionBackdrop(from, to);
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("from", from);
                    d.put("to", to);
                    d.put("kind", kind.name());
                    d.put("buildMs", System.currentTimeMillis() - buildStartMs);
                    d.put("scheduleGapMs", System.currentTimeMillis() - scheduleMs);
                    TransitionPerfLog.log("ScreenTransitionCoordinator.run",
                            "applyScreenChange done", "H4", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                // Layout pass for incoming root before translate/alpha anim.
                if (inView != null) {
                    inView.post(startAnim);
                } else {
                    startAnim.run();
                }
            }
        };

        View scheduleOn = outView != null ? outView : inView;
        if (scheduleOn != null) {
            scheduleOn.post(buildThenAnim);
        } else {
            buildThenAnim.run();
        }
    }

    /** 2026-07-18 — Backdrop out slot or null when host has none. */
    private static View outBackdropOrNull(Host host) {
        return host.backdropOutSlot();
    }

    /** 2026-07-18 — Backdrop in slot or null when host has none. */
    private static View inBackdropOrNull(Host host) {
        return host.backdropInSlot();
    }
}
