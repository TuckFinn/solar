package com.solar.launcher;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * 2026-07-15 — A5 / touchscreen list UX.
 * Layman: short screens — slide to move the highlight; a short tap opens the row.
 * Long scrollable lists — scroll freely; short tap opens (hold still for context options).
 * Tech: {@link #followFinger} only when content cannot scroll; touch UP within slop activates.
 * 2026-07-15 — Single-tap open (was Storm two-tap — home/settings felt dead).
 * Was: followFinger on every MOVE (fought ScrollView / ListView flings).
 * Reversal: drop {@link #isShortNonScrollSurface} gate; restore two-tap in attachTouchConfirm.
 */
public final class A5FocusConfirm {

    private A5FocusConfirm() {}

    /** True when this device should use two-tap list confirm. */
    public static boolean enabled() {
        return DeviceFeatures.isA5() || DeviceFeatures.hasTouchscreen();
    }

    /**
     * Enable focus-on-first-touch chrome for a list row shell.
     * Layman: make the row able to take highlight from a finger poke.
     */
    public static void prepareRow(View row) {
        if (row == null || !enabled()) return;
        row.setClickable(true);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        if (row instanceof ViewGroup) {
            ((ViewGroup) row).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    /**
     * Wrap an activate listener for views that override setOnClickListener → super.
     * Layman: short finger tap opens the row; wheel OK still needs focus then confirm.
     * Tech: touch UP path activates; suppress blocks duplicate framework onClick after touch.
     * Key/wheel: unfocused performClick only focuses; focused performClick activates.
     */
    public static View.OnClickListener wrap(final View row, final View.OnClickListener activate) {
        if (activate == null) return null;
        if (!enabled()) return activate;
        prepareRow(row);
        attachTouchConfirm(row, activate);
        final boolean[] suppressNextClick = new boolean[] { false };
        tagSuppress(row, suppressNextClick);
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View target = v != null ? v : row;
                if (target == null) {
                    activate.onClick(v);
                    return;
                }
                if (consumeSuppress(target)) {
                    // #region agent log
                    if (com.solar.launcher.debug.DebugGate.ON) {
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("suppressed", true);
                        d.put("cls", target.getClass().getSimpleName());
                        com.solar.launcher.debug.Debug31d3d8Log.log(target.getContext(),
                                "A5FocusConfirm.wrap", "skip click after touch UP", "F2", d);
                    } catch (Exception ignored) {}
                    }
                    // #endregion
                    return;
                }
                if (target.isFocused()) {
                    activate.onClick(target);
                } else {
                    // Hardware OK / programmatic click without focus — light the row first.
                    target.requestFocus();
                }
            }
        };
    }

    /**
     * Bind touch-friendly activate via public setOnClickListener (no override on the view).
     * Layman: plug in the action; A5 gets short-tap open + hold-for-options elsewhere.
     */
    public static void setOnClickListener(View row, View.OnClickListener activate) {
        if (row == null) return;
        if (!enabled()) {
            row.setOnClickListener(activate);
            return;
        }
        row.setOnClickListener(wrap(row, activate));
    }

    /**
     * 2026-07-15 — Slide finger to move focus only on short, non-scrollable surfaces.
     * Layman: short menu — drag the blue bar with your finger; long list — scroll then peck/tap.
     * Tech: skip when a ScrollView/AbsListView under the tip can scroll; never consumes.
     * Skip while {@link MoveRibbonTouch#isSessionActive()}.
     */
    public static void followFinger(View root, MotionEvent event) {
        if (!enabled() || root == null || event == null) return;
        if (MoveRibbonTouch.isSessionActive()) return;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return;
        // Scrollable screens: leave MOVE to fling/scroll; peck/tap stays on per-row listeners.
        if (!isShortNonScrollSurface(root, event.getX(), event.getY())) return;
        View hit = findFocusableRowAt(root, event.getX(), event.getY());
        if (hit == null || hit.isFocused()) return;
        boolean ok = hit.requestFocus();
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON && action == MotionEvent.ACTION_DOWN || ok) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("action", action);
                d.put("ok", ok);
                d.put("cls", hit.getClass().getSimpleName());
                d.put("x", (int) event.getX());
                d.put("y", (int) event.getY());
                d.put("shortScreen", true);
                com.solar.launcher.debug.Debug31d3d8Log.log(root.getContext(),
                        "A5FocusConfirm.followFinger", "focus followed finger", "F1", d);
            } catch (Exception ignored) {}
        }
        // #endregion
    }

    /**
     * 2026-07-15 — True when drag-to-focus is safe (no scrollable host, or host content fits).
     * Layman: short list that doesn’t scroll → finger can walk the highlight.
     * Technical: ScrollView / AbsListView / HorizontalScrollView under (x,y) with canScroll* false.
     */
    public static boolean isShortNonScrollSurface(View root, float x, float y) {
        if (root == null) return true;
        View scrollHost = findScrollHostAt(root, x, y);
        if (scrollHost == null) return true;
        return !canScrollContent(scrollHost);
    }

    /** Deepest ScrollView / AbsListView / HorizontalScrollView under point, or null. */
    static View findScrollHostAt(View root, float x, float y) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE) continue;
            float cx = x - c.getLeft() + c.getScrollX();
            float cy = y - c.getTop() + c.getScrollY();
            if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight()) continue;
            View deeper = findScrollHostAt(c, cx, cy);
            if (deeper != null) return deeper;
            if (isScrollHost(c)) return c;
        }
        if (isScrollHost(root)) return root;
        return null;
    }

    private static boolean isScrollHost(View v) {
        return v instanceof ScrollView
                || v instanceof AbsListView
                || v instanceof HorizontalScrollView;
    }

    /**
     * True when the host can move content with a finger fling/drag.
     * Layman: more rows (or wider content) than fit on screen.
     */
    static boolean canScrollContent(View host) {
        if (host == null) return false;
        try {
            if (host instanceof ScrollView) {
                ScrollView sv = (ScrollView) host;
                if (sv.canScrollVertically(1) || sv.canScrollVertically(-1)) return true;
                if (sv.getChildCount() == 0) return false;
                View child = sv.getChildAt(0);
                int avail = sv.getHeight() - sv.getPaddingTop() - sv.getPaddingBottom();
                return child != null && child.getHeight() > avail + 1;
            }
            if (host instanceof HorizontalScrollView) {
                HorizontalScrollView hsv = (HorizontalScrollView) host;
                if (hsv.canScrollHorizontally(1) || hsv.canScrollHorizontally(-1)) return true;
                if (hsv.getChildCount() == 0) return false;
                View child = hsv.getChildAt(0);
                int avail = hsv.getWidth() - hsv.getPaddingLeft() - hsv.getPaddingRight();
                return child != null && child.getWidth() > avail + 1;
            }
            if (host instanceof AbsListView) {
                AbsListView list = (AbsListView) host;
                if (list.canScrollVertically(1) || list.canScrollVertically(-1)) return true;
                if (list.getCount() <= 0 || list.getChildCount() == 0) return false;
                // Last row bottom past list bottom, or first not at top → more content than viewport.
                if (list.getFirstVisiblePosition() > 0) return true;
                if (list.getLastVisiblePosition() < list.getCount() - 1) return true;
                View last = list.getChildAt(list.getChildCount() - 1);
                View first = list.getChildAt(0);
                if (first != null && first.getTop() < list.getPaddingTop()) return true;
                if (last != null
                        && last.getBottom() > list.getHeight() - list.getPaddingBottom()) {
                    return true;
                }
                return false;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Deepest focusable+clickable view under (x,y) in root-local coords. */
    public static View findFocusableRowAt(View root, float x, float y) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (!(root instanceof ViewGroup)) {
            return (root.isFocusable() && root.isClickable()) ? root : null;
        }
        ViewGroup g = (ViewGroup) root;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE) continue;
            float cx = x - c.getLeft() + c.getScrollX();
            float cy = y - c.getTop() + c.getScrollY();
            if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight()) continue;
            View deeper = findFocusableRowAt(c, cx, cy);
            if (deeper != null) return deeper;
            if (c.isFocusable() && c.isClickable()) return c;
        }
        if (root.isFocusable() && root.isClickable()) return root;
        return null;
    }

    /**
     * 2026-07-15 — Short-tap open: focus on DOWN if needed, activate on UP within slop.
     * Layman: poke Music once and it opens — no second tap. Drag past slop = scroll, no open.
     * Hold still on a focused row still uses OnLongClick → context (attachA5RowLongPress).
     * Safe if MoveRibbonTouch later replaces OnTouchListener — followFinger still moves focus.
     */
    static void attachTouchConfirm(final View row, final View.OnClickListener activate) {
        if (row == null || activate == null || !enabled()) return;
        final float[] downXY = new float[2];
        final long[] downUptime = new long[] { 0L };
        final boolean[] focusedAtDown = new boolean[] { false };
        final int[] slop = new int[] { 16 };
        final int[] longPressMs = new int[] { 500 };
        try {
            slop[0] = ViewConfiguration.get(row.getContext()).getScaledTouchSlop();
            longPressMs[0] = ViewConfiguration.getLongPressTimeout();
        } catch (Throwable ignored) {}
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null || MoveRibbonTouch.isSessionActive()) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        focusedAtDown[0] = v.isFocused();
                        downXY[0] = event.getX();
                        downXY[1] = event.getY();
                        downUptime[0] = event.getDownTime();
                        if (!focusedAtDown[0]) {
                            boolean ok = v.requestFocus();
                            // #region agent log
                            if (com.solar.launcher.debug.DebugGate.ON) {
                            try {
                                org.json.JSONObject d = new org.json.JSONObject();
                                d.put("requestOk", ok);
                                d.put("cls", v.getClass().getSimpleName());
                                com.solar.launcher.debug.Debug31d3d8Log.log(v.getContext(),
                                        "A5FocusConfirm.touch", "row DOWN focus", "F1", d);
                            } catch (Exception ignored) {}
                            }
                            // #endregion
                            // Unfocused DOWN: cancel long-press so first-tap does not open context.
                            // Focused DOWN: leave long-press armed for hold → options.
                            final View target = v;
                            target.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        target.cancelLongPress();
                                    } catch (Throwable ignored) {}
                                }
                            });
                        }
                        return false;
                    case MotionEvent.ACTION_UP: {
                        float dx = event.getX() - downXY[0];
                        float dy = event.getY() - downXY[1];
                        if (dx * dx + dy * dy > slop[0] * slop[0]) {
                            clearSuppress(v);
                            return false;
                        }
                        // Held long enough for context long-press — do not also open the row.
                        long held = event.getEventTime() - downUptime[0];
                        if (held >= longPressMs[0]) {
                            clearSuppress(v);
                            return false;
                        }
                        if (!v.isFocused()) {
                            v.requestFocus();
                        }
                        markSuppress(v);
                        // #region agent log
                        if (com.solar.launcher.debug.DebugGate.ON) {
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("activate", true);
                            d.put("cls", v.getClass().getSimpleName());
                            d.put("heldMs", held);
                            d.put("focusedAtDown", focusedAtDown[0]);
                            com.solar.launcher.debug.Debug31d3d8Log.log(v.getContext(),
                                    "A5FocusConfirm.touch", "short-tap activate UP", "F2", d);
                        } catch (Exception ignored) {}
                        }
                        // #endregion
                        activate.onClick(v);
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        clearSuppress(v);
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    private static final int TAG_SUPPRESS = 0x31d3d8f1;

    private static void tagSuppress(View row, boolean[] box) {
        if (row != null) row.setTag(TAG_SUPPRESS, box);
    }

    private static void markSuppress(View v) {
        if (v == null) return;
        Object o = v.getTag(TAG_SUPPRESS);
        if (o instanceof boolean[]) {
            ((boolean[]) o)[0] = true;
        }
    }

    private static void clearSuppress(View v) {
        if (v == null) return;
        Object o = v.getTag(TAG_SUPPRESS);
        if (o instanceof boolean[]) {
            ((boolean[]) o)[0] = false;
        }
    }

    private static boolean consumeSuppress(View v) {
        if (v == null) return false;
        Object o = v.getTag(TAG_SUPPRESS);
        if (o instanceof boolean[]) {
            boolean[] b = (boolean[]) o;
            if (b[0]) {
                b[0] = false;
                return true;
            }
        }
        return false;
    }
}
