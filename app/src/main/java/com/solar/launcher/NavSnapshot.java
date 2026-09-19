package com.solar.launcher;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-09-18 — What the screen shows, for the navigation simulation.
 *
 * The simulation reads each screen to check that Back went up exactly one
 * level and put focus back on the row it left. uiautomator cannot do it on
 * Android 4.2: its XML writer throws on any text outside the Basic
 * Multilingual Plane, and the library's rows carry icon emoji, so every
 * library screen came back empty. This asks the app instead:
 *
 *   am broadcast -a com.solar.launcher.action.NAV_SNAPSHOT --es nonce N
 *
 * logs one JSON line under the tag {@link #TAG}: the screen state and title, every
 * visible text with its position, the rows of the list that has focus (a
 * ListView's visible items, or else each focusable row) with the focused
 * one marked, and whether the context overlay is up. Read-only.
 */
final class NavSnapshot {
    static final String ACTION = "com.solar.launcher.action.NAV_SNAPSHOT";
    static final String TAG = "SolarNav";

    private NavSnapshot() {}

    /** On screen only: a long list's off-screen rows are still "shown". */
    private static final int SCREEN_W = 480, SCREEN_H = 360;
    /** Keep one line under logcat's ~4 KB entry limit. */
    private static final int MAX_TEXTS = 30, MAX_ROWS = 12, MAX_CHARS = 48;

    static void log(View root, String title, int screenState, boolean overlayShowing, String nonce) {
        try {
            JSONObject o = new JSONObject();
            o.put("screen", screenState);
            // The status bar's text: the screen's title, or the clock on Home.
            o.put("title", title != null ? cap(title) : "");
            o.put("overlay", overlayShowing);
            JSONArray texts = new JSONArray();
            collectTexts(root, texts);
            o.put("texts", texts);
            View focus = root != null ? root.findFocus() : null;
            o.put("rows", rows(root, focus));
            // The nonce leads, outside the JSON, so a reader can find its line
            // even if the entry were ever cut short.
            Log.i(TAG, "nonce=" + (nonce != null ? nonce : "") + " " + o.toString());
        } catch (Exception e) {
            Log.w(TAG, "snapshot failed", e);
        }
    }

    /** The list the user is in, top to bottom, focused row marked. */
    private static JSONArray rows(View root, View focus) throws Exception {
        JSONArray out = new JSONArray();
        AbsListView list = enclosingList(focus);
        if (list != null) {
            int sel = list.getSelectedItemPosition() - list.getFirstVisiblePosition();
            for (int i = 0; i < list.getChildCount() && out.length() < MAX_ROWS; i++) {
                View c = list.getChildAt(i);
                if (c == null || !c.isShown() || !onScreen(c)) continue;
                out.put(row(c, i == sel));
            }
            return out;
        }
        List<View> focusables = new ArrayList<View>();
        collectFocusables(root, focusables);
        for (View v : focusables) {
            if (!onScreen(v)) continue;
            boolean f = focus != null && (v == focus || isAncestor(v, focus));
            if (out.length() >= MAX_ROWS && !f) continue;
            out.put(row(v, f));
        }
        return out;
    }

    private static boolean onScreen(View v) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return at[1] + v.getHeight() > 0 && at[1] < SCREEN_H && at[0] < SCREEN_W && v.getHeight() > 0;
    }

    private static String cap(String t) {
        return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t;
    }

    private static JSONObject row(View v, boolean focused) throws Exception {
        JSONObject r = box(v);
        r.put("t", cap(firstText(v)));
        if (focused) r.put("focused", true);
        return r;
    }

    private static AbsListView enclosingList(View v) {
        while (v != null) {
            if (v instanceof AbsListView) return (AbsListView) v;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    private static boolean isAncestor(View a, View v) {
        View p = v;
        while (p != null) {
            if (p == a) return true;
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return false;
    }

    /** Focusable rows: shown, row-sized, not containing another focusable row. */
    private static void collectFocusables(View v, List<View> out) {
        if (v == null || !v.isShown()) return;
        boolean rowLike = v.isFocusable() && v.getHeight() > 0 && v.getHeight() < 150
                && !(v instanceof AbsListView);
        if (rowLike) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectFocusables(g.getChildAt(i), out);
        }
    }

    private static String firstText(View v) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            String t = cs != null ? cs.toString().trim() : "";
            if (t.length() > 0) return t;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                String t = firstText(c);
                if (t.length() > 0) return t;
            }
        }
        return "";
    }

    private static void collectTexts(View v, JSONArray out) throws Exception {
        if (v == null || !v.isShown() || out.length() >= MAX_TEXTS) return;
        if (v instanceof TextView && onScreen(v)) {
            CharSequence cs = ((TextView) v).getText();
            String t = cs != null ? cs.toString().trim() : "";
            if (t.length() > 0) {
                JSONObject b = box(v);
                b.put("t", cap(t));
                out.put(b);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), out);
        }
    }

    private static JSONObject box(View v) throws Exception {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        JSONObject b = new JSONObject();
        b.put("x", at[0]);
        b.put("y", at[1]);
        b.put("w", v.getWidth());
        b.put("h", v.getHeight());
        return b;
    }
}
