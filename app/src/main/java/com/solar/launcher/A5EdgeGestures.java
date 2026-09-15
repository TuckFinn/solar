package com.solar.launcher;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * 2026-07-14 — A5 touch: edge swipes + hold-still → Solar nav / context.
 * Layman: flick from the side to go back; swipe up from the bottom for Home; hold still for the
 * options menu (same as hold Back / Power).
 * Tech: fed from MainActivity.dispatchTouchEvent so gestures work over lists; Host callbacks.
 * Reversal: stop calling process(); A5 stays button-only for those actions.
 */
public final class A5EdgeGestures {

    /** Outer band (px) that starts edge tracking. Wider so fat-finger hits count as Back. */
    public static final int EDGE_BAND_PX = 28;
    /** Min travel to count as swipe. */
    public static final int SWIPE_MIN_PX = 40;
    /**
     * Hold-still → context on empty chrome (not on list rows).
     * 2026-07-20 — At least system long-press (~500ms); policy alone (~280) felt like tap→options.
     * Was: SOLAR_BACK_CONTEXT_HOLD_MS only. Reversal: return that constant alone.
     */
    public static final long HOLD_CONTEXT_MS = resolveHoldContextMs();
    /** Max jitter while “still” before hold cancels. */
    public static final int HOLD_SLOP_PX = 16;

    private static long resolveHoldContextMs() {
        long policy = com.solar.input.policy.GlobalInputPolicy.SOLAR_BACK_CONTEXT_HOLD_MS;
        long system = 500L;
        try {
            system = ViewConfiguration.getLongPressTimeout();
        } catch (Throwable ignored) {}
        // Prefer the longer of policy hold and system long-press so short taps never open context.
        return system > policy ? system : policy;
    }

    public enum Gesture {
        NONE,
        BACK,
        HOME,
        OPEN_CONTEXT
    }

    public enum Edge {
        NONE,
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }

    /** Callbacks into MainActivity. */
    public interface Host {
        void onA5EdgeBack();
        void onA5EdgeHome();
        void onA5EdgeOpenContext();
        /**
         * 2026-07-20 — Hold-still timer armed — show Options status spinner (parity with Back hold).
         * Layman: start spinning while you keep your finger still for the menu.
         */
        void onA5EdgeContextHoldArm();
        /**
         * 2026-07-20 — Hold cancelled (move / lift) before OPEN_CONTEXT — clear spinner.
         * Layman: finger moved or lifted early → spinner stops.
         */
        void onA5EdgeContextHoldCancel();
        int viewportWidth();
        int viewportHeight();
        /**
         * 2026-07-14 — Decor/content root for long-press hit tests (window coords).
         * Layman: the whole screen tree we poke to see what is under the finger.
         */
        View a5GestureRoot();
        /**
         * 2026-07-14 — True while in-app context menu is open (skip edge hold).
         * Layman: when the options popup is up, holding a finger must not re-fire open.
         */
        boolean a5ContextMenuBlockingHold();
    }

    private final Host host;
    private final Handler handler;
    private float downX;
    private float downY;
    private Edge downEdge = Edge.NONE;
    private boolean tracking;
    private boolean holdFired;
    private boolean consumeNextUp;
    /**
     * 2026-07-14 — L/R edge stream owned by us so NP art cannot start skip/scrub.
     * Layman: finger starting at the bezel side never reaches the album touch handler.
     * Tech: consume DOWN/MOVE/UP while capturingLeftRightEdge; was return-false on DOWN.
     * Reversal: always return false on DOWN; rely on NP isScreenEdgeDown only.
     */
    private boolean capturingLeftRightEdge;
    /** 2026-07-14 — Row OnLongClick owns context; skip edge hold so focus+options stay correct. */
    private boolean deferHoldToChild;
    private final Runnable holdContext = new Runnable() {
        @Override
        public void run() {
            if (!tracking || holdFired || deferHoldToChild || host == null) return;
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("reorderSession", MoveRibbonTouch.isSessionActive());
                d.put("edge", String.valueOf(downEdge));
                android.content.Context c = null;
                try {
                    View root = host.a5GestureRoot();
                    if (root != null) c = root.getContext();
                } catch (Exception ignored) {}
                com.solar.launcher.debug.Debug31d3d8Log.log(c,
                        "A5EdgeGestures.holdContext", "hold-still OPEN_CONTEXT fire", "B", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            // Hold-still anywhere (including edges) opens context — user asked for one-spot hold.
            holdFired = true;
            consumeNextUp = true;
            // 2026-07-20 — Keep spinner through open (arm already on DOWN); Host opens menu.
            host.onA5EdgeOpenContext();
        }
    };

    public A5EdgeGestures(Host host) {
        this.host = host;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Activity-wide touch probe. Return true when this gesture consumed the event
     * (so children do not also fire). L/R edge downs are captured for the whole stroke.
     */
    public boolean process(MotionEvent event) {
        if (event == null || host == null) return false;
        // 2026-07-15 — Reorder owns the finger; edge swipe/hold must not steal vertical drag.
        // Layman: while you rearrange a row, side gestures stay off until you finish.
        // Was: edge L/R capture + hold-context raced MoveRibbonTouch. Reversal: drop early return.
        if (MoveRibbonTouch.isSessionActive()) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("action", event.getActionMasked());
                d.put("skipped", true);
                android.content.Context c = null;
                try {
                    View root = host.a5GestureRoot();
                    if (root != null) c = root.getContext();
                } catch (Exception ignored) {}
                com.solar.launcher.debug.Debug31d3d8Log.log(c,
                        "A5EdgeGestures.process", "skip — reorder session", "A,C", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return false;
        }
        int w = host.viewportWidth();
        int h = host.viewportHeight();
        int action = event.getActionMasked();
        // #region agent log
        boolean reorderSession = false;
        // #endregion
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            downEdge = edgeAt(downX, downY, w, h);
            tracking = true;
            holdFired = false;
            consumeNextUp = false;
            // Steal L/R edge so Now Playing hold-scrub / swipe-skip cannot own the stroke.
            capturingLeftRightEdge = downEdge == Edge.LEFT || downEdge == Edge.RIGHT;
            // 2026-07-14 — List rows already wire OnLongClick → focus + context; do not steal hold.
            // Was: edge hold fired first (~420ms) so context opened for the old focus, not the finger row.
            // Reversal: always postDelayed(holdContext) and ignore childOwnsLongPress.
            deferHoldToChild = host.a5ContextMenuBlockingHold()
                    || shouldDeferHoldToChild(host.a5GestureRoot(), downX, downY);
            handler.removeCallbacks(holdContext);
            // Edge L/R is Back, not hold-context (hold still works from interior).
            if (!capturingLeftRightEdge && shouldArmHoldContext(deferHoldToChild)) {
                // 2026-07-20 — Spinner during hold-still wait (same as Back KEY_DOWN).
                // Reversal: drop onA5EdgeContextHoldArm; spinner only at OPEN_CONTEXT fire.
                if (!host.a5ContextMenuBlockingHold()) {
                    host.onA5EdgeContextHoldArm();
                }
                handler.postDelayed(holdContext, HOLD_CONTEXT_MS);
            }
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON && reorderSession || capturingLeftRightEdge || downEdge != Edge.NONE) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("action", "DOWN");
                    d.put("edge", String.valueOf(downEdge));
                    d.put("captureLR", capturingLeftRightEdge);
                    d.put("reorderSession", reorderSession);
                    d.put("deferHold", deferHoldToChild);
                    d.put("armHold", !capturingLeftRightEdge && shouldArmHoldContext(deferHoldToChild));
                    d.put("x", (int) downX);
                    d.put("y", (int) downY);
                    android.content.Context c = null;
                    try {
                        View root = host.a5GestureRoot();
                        if (root != null) c = root.getContext();
                    } catch (Exception ignored) {}
                    com.solar.launcher.debug.Debug31d3d8Log.log(c,
                            "A5EdgeGestures.process", "edge DOWN", "A,B,C", d);
                } catch (Exception ignored) {}
            }
            // #endregion
            return capturingLeftRightEdge;
        }
        if (!tracking) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (Math.abs(dx) > HOLD_SLOP_PX || Math.abs(dy) > HOLD_SLOP_PX) {
                handler.removeCallbacks(holdContext);
                // 2026-07-20 — Finger moved: cancel Options hold spinner if menu not opened yet.
                if (!holdFired) {
                    host.onA5EdgeContextHoldCancel();
                }
            }
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON && reorderSession && capturingLeftRightEdge) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("action", "MOVE");
                    d.put("captureLR", true);
                    d.put("reorderSession", true);
                    d.put("dx", (int) dx);
                    d.put("dy", (int) dy);
                    android.content.Context c = null;
                    try {
                        View root = host.a5GestureRoot();
                        if (root != null) c = root.getContext();
                    } catch (Exception ignored) {}
                    com.solar.launcher.debug.Debug31d3d8Log.log(c,
                            "A5EdgeGestures.process", "edge steals MOVE during reorder", "A", d);
                } catch (Exception ignored) {}
            }
            // #endregion
            return capturingLeftRightEdge;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(holdContext);
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            boolean cancel = action == MotionEvent.ACTION_CANCEL;
            boolean captured = capturingLeftRightEdge;
            // 2026-07-20 — Early lift: clear spinner unless hold already opened Options.
            if (ContextHoldThrobberGate.shouldClearOnHoldCancel(holdFired)) {
                host.onA5EdgeContextHoldCancel();
            }
            tracking = false;
            deferHoldToChild = false;
            capturingLeftRightEdge = false;
            if (holdFired || consumeNextUp) {
                consumeNextUp = false;
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("action", cancel ? "CANCEL" : "UP");
                    d.put("holdFired", holdFired);
                    d.put("reorderSession", reorderSession);
                    d.put("path", "holdContextConsumed");
                    android.content.Context c = null;
                    try {
                        View root = host.a5GestureRoot();
                        if (root != null) c = root.getContext();
                    } catch (Exception ignored) {}
                    com.solar.launcher.debug.Debug31d3d8Log.log(c,
                            "A5EdgeGestures.process", "hold context ate UP during/after reorder", "B", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                return true;
            }
            if (cancel) return captured;
            Gesture g = classify(downEdge, dx, dy);
            if (g == Gesture.NONE) return captured;
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("action", "UP");
                d.put("gesture", String.valueOf(g));
                d.put("edge", String.valueOf(downEdge));
                d.put("reorderSession", reorderSession);
                d.put("dx", (int) dx);
                d.put("dy", (int) dy);
                android.content.Context c = null;
                try {
                    View root = host.a5GestureRoot();
                    if (root != null) c = root.getContext();
                } catch (Exception ignored) {}
                com.solar.launcher.debug.Debug31d3d8Log.log(c,
                        "A5EdgeGestures.process", "edge gesture applied", "A,C", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            apply(g);
            return true;
        }
        return false;
    }

    /** Pure edge-at-point helper. */
    public static Edge edgeAt(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) return Edge.NONE;
        int band = EDGE_BAND_PX;
        if (x <= band) return Edge.LEFT;
        if (x >= width - band) return Edge.RIGHT;
        if (y <= band) return Edge.TOP;
        if (y >= height - band) return Edge.BOTTOM;
        return Edge.NONE;
    }

    /**
     * 2026-07-14 — Edge hold only when no list row owns long-press under the finger.
     * Layman: if you press on a menu line, that line opens options; empty space still uses hold.
     * Tech: unit-testable gate for arming {@link #HOLD_CONTEXT_MS} timer.
     */
    public static boolean shouldArmHoldContext(boolean childOwnsLongPress) {
        return !childOwnsLongPress;
    }

    /**
     * 2026-07-14 — True when a list row / control under (x,y) owns the press.
     * Layman: poke a menu line → that line handles hold; empty chrome still uses edge hold.
     * 2026-07-15 — Also defer for clickable+focusable rows (two-tap focus) so 420–500ms
     * “almost taps” never open context via the activity hold timer.
     * Reversal: only isLongClickable() check.
     */
    public static boolean shouldDeferHoldToChild(View root, float x, float y) {
        return hitOwnsLongPress(root, x, y);
    }

    /**
     * 2026-07-14 — Walk hit tree; true if an interactive row owns the press.
     * Coordinates are relative to {@code root} (same space as MotionEvent in Activity.dispatchTouchEvent
     * when root is the window decor).
     */
    public static boolean hitOwnsLongPress(View root, float x, float y) {
        if (root == null) return false;
        View hit = findDeepestAt(root, x, y);
        while (hit != null) {
            if (hit.isLongClickable()) return true;
            // Menu / list rows: first-tap focus or activate — never edge-hold context on them.
            if (hit.isClickable()
                    && (hit.isFocusable() || hit.isFocusableInTouchMode())) {
                return true;
            }
            Object p = hit.getParent();
            if (!(p instanceof View) || p == root.getParent()) break;
            hit = (View) p;
        }
        return false;
    }

    /** Depth-first reverse-child hit test in local coords. */
    static View findDeepestAt(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (!(v instanceof ViewGroup)) return v;
        ViewGroup g = (ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE) continue;
            float cx = x - c.getLeft() + c.getScrollX();
            float cy = y - c.getTop() + c.getScrollY();
            if (cx >= 0 && cy >= 0 && cx < c.getWidth() && cy < c.getHeight()) {
                View deeper = findDeepestAt(c, cx, cy);
                return deeper != null ? deeper : c;
            }
        }
        return v;
    }

    /**
     * Pure classifier for unit tests.
     * Edge L/R + horizontal → BACK; bottom + up → HOME; else NONE.
     * OPEN_CONTEXT is timer-based (hold-still).
     */
    public static Gesture classify(Edge edge, float dx, float dy) {
        float adx = Math.abs(dx);
        float ady = Math.abs(dy);
        if (edge == Edge.LEFT || edge == Edge.RIGHT) {
            if (adx >= SWIPE_MIN_PX && adx >= ady) return Gesture.BACK;
            return Gesture.NONE;
        }
        if (edge == Edge.BOTTOM) {
            if (ady >= SWIPE_MIN_PX && ady >= adx && dy < 0) return Gesture.HOME;
            return Gesture.NONE;
        }
        return Gesture.NONE;
    }

    private void apply(Gesture g) {
        if (g == Gesture.BACK) host.onA5EdgeBack();
        else if (g == Gesture.HOME) host.onA5EdgeHome();
        else if (g == Gesture.OPEN_CONTEXT) host.onA5EdgeOpenContext();
    }

    /** Touch-slop from config when available (tests use {@link #HOLD_SLOP_PX}). */
    static int holdSlopPx(View v) {
        if (v == null || v.getContext() == null) return HOLD_SLOP_PX;
        try {
            return ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        } catch (Throwable t) {
            return HOLD_SLOP_PX;
        }
    }
}
