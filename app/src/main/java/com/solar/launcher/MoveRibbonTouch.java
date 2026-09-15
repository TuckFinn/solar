package com.solar.launcher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * 2026-07-15 — Touch long-press lift + vertical drag for ribbon / strip reorder.
 * Layman: hold a row, then drag it up/down like sliding a paper strip or a Spotify queue track.
 * Technical: same step/confirm callbacks as OK-hold + wheel; does not replace key paths.
 * Reversal: remove attach* call sites; OK-hold/wheel remain.
 */
public final class MoveRibbonTouch {

    /** Match MainActivity CENTER_MOVE_HOLD_MS — lift after ~450ms. */
    public static final long LIFT_HOLD_MS = 450L;

    /**
     * 2026-07-15 — True while touch lift/drag reorder owns the finger (A5 edge gestures must yield).
     * Layman: “I’m moving a strip — don’t open Home or the options menu from this drag.”
     * Tech: set on lift / active-drag DOWN; clear on confirm/cancel/clear(). Instrumentation + suppress gate.
     */
    private static volatile boolean sessionActive;
    private static volatile int sessionGen;

    /**
     * Host implements step/confirm for queue, playlist, FM, or home strip.
     * delta is +1 (down the list) or -1 (up).
     */
    public interface Callbacks {
        /** Called when long-press completes and move should begin (browse only). */
        void onLift();

        /** One slot step while dragged. */
        void onStep(int delta);

        /** Finger up after active drag — drop / confirm. */
        void onConfirm();
    }

    private MoveRibbonTouch() {}

    /** True while a touch reorder gesture owns input. */
    public static boolean isSessionActive() {
        return sessionActive;
    }

    /** Begin / end touch-reorder ownership (idempotent). */
    public static void setSessionActive(boolean active) {
        sessionActive = active;
        if (active) sessionGen++;
    }

    /**
     * Long-press on a browse row to enter move (OK-hold equivalent).
     * Does nothing after lift if callbacks.onLift starts move mode elsewhere.
     */
    public static void attachBrowseLift(final View row, final long holdMs, final Callbacks callbacks) {
        if (row == null || callbacks == null) return;
        if (!touchReorderEnabled()) return;
        final Handler handler = new Handler(Looper.getMainLooper());
        final long[] downAt = new long[] { 0L };
        final float[] downXY = new float[2];
        final boolean[] lifted = new boolean[] { false };
        final int touchSlop = ViewConfiguration.get(row.getContext()).getScaledTouchSlop();
        final Runnable liftRun = new Runnable() {
            @Override
            public void run() {
                if (lifted[0]) return;
                lifted[0] = true;
                disallowParentIntercept(row, true);
                setSessionActive(true);
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("gen", sessionGen);
                    d.put("holdMs", holdMs);
                    com.solar.launcher.debug.Debug31d3d8Log.log(row.getContext(),
                            "MoveRibbonTouch.lift", "touch reorder lift", "B", d);
                } catch (Exception ignored) {}
                }
                // #endregion
                callbacks.onLift();
            }
        };
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lifted[0] = false;
                        downAt[0] = SystemClock.uptimeMillis();
                        downXY[0] = event.getX();
                        downXY[1] = event.getY();
                        handler.removeCallbacks(liftRun);
                        handler.postDelayed(liftRun, Math.max(200L, holdMs));
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (!lifted[0]) {
                            float dx = event.getX() - downXY[0];
                            float dy = event.getY() - downXY[1];
                            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                handler.removeCallbacks(liftRun);
                            }
                        }
                        return lifted[0];
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(liftRun);
                        disallowParentIntercept(v, false);
                        // After lift, browse row is usually replaced by ribbon — drag attaches there.
                        // Do not auto-confirm; OK / second gesture drops (same as wheel move).
                        return lifted[0];
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Vertical drag on the already-lifted mover row — step slots like the scroll wheel.
     * Attach after ribbon/strip enter; remove via clearListener when move ends.
     */
    public static void attachActiveDrag(final View moverRow, final int slotHeightPx,
            final Callbacks callbacks) {
        if (moverRow == null || callbacks == null) return;
        if (!touchReorderEnabled()) return;
        final int slotH = Math.max(24, slotHeightPx);
        final float[] accY = new float[] { 0f };
        final float[] lastY = new float[] { 0f };
        final boolean[] tracking = new boolean[] { false };
        // Ribbon already in move mode — keep session armed for edge-gesture suppress.
        setSessionActive(true);
        moverRow.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        tracking[0] = true;
                        accY[0] = 0f;
                        lastY[0] = event.getRawY();
                        setSessionActive(true);
                        disallowParentIntercept(v, true);
                        // #region agent log
                        if (com.solar.launcher.debug.DebugGate.ON) {
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("gen", sessionGen);
                            d.put("slotH", slotH);
                            com.solar.launcher.debug.Debug31d3d8Log.log(v.getContext(),
                                    "MoveRibbonTouch.dragDown", "active drag start", "A,C", d);
                        } catch (Exception ignored) {}
                        }
                        // #endregion
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!tracking[0]) return false;
                        float y = event.getRawY();
                        float dy = y - lastY[0];
                        lastY[0] = y;
                        accY[0] += dy;
                        while (accY[0] <= -slotH) {
                            accY[0] += slotH;
                            callbacks.onStep(-1);
                        }
                        while (accY[0] >= slotH) {
                            accY[0] -= slotH;
                            callbacks.onStep(1);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (tracking[0]) {
                            tracking[0] = false;
                            disallowParentIntercept(v, false);
                            // #region agent log
                            if (com.solar.launcher.debug.DebugGate.ON) {
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("gen", sessionGen);
                                com.solar.launcher.debug.Debug31d3d8Log.log(v.getContext(),
                                        "MoveRibbonTouch.dragUp", "active drag confirm", "A,B", d);
                            } catch (Exception ignored) {}
                            }
                            // #endregion
                            callbacks.onConfirm();
                            setSessionActive(false);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_CANCEL:
                        tracking[0] = false;
                        disallowParentIntercept(v, false);
                        setSessionActive(false);
                        // #region agent log
                        if (com.solar.launcher.debug.DebugGate.ON) {
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("gen", sessionGen);
                            com.solar.launcher.debug.Debug31d3d8Log.log(v.getContext(),
                                    "MoveRibbonTouch.dragCancel", "active drag cancel", "A,C", d);
                        } catch (Exception ignored) {}
                        }
                        // #endregion
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Clear a previously attached OnTouchListener (end of move). */
    public static void clear(View view) {
        setSessionActive(false);
        if (view != null) view.setOnTouchListener(null);
    }

    /**
     * 2026-07-15 — Touch reorder on A5 / touchscreen devices (and emulator).
     * Y1/Y2 wheel-only keep false so OK-hold stays primary.
     */
    public static boolean touchReorderEnabled() {
        return DeviceFeatures.hasTouchscreen() || DeviceFeatures.isEmulator();
    }

    /** Self-check: slot step threshold math (one runnable assert from tests). */
    public static int stepsFromAccumulatedDy(float accumulatedDy, int slotHeightPx) {
        int slotH = Math.max(1, slotHeightPx);
        return (int) (accumulatedDy / slotH);
    }

    private static void disallowParentIntercept(View v, boolean disallow) {
        if (v == null) return;
        ViewParent p = v.getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
    }
}
