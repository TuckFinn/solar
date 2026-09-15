package com.solar.launcher;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;

/**
 * 2026-07-05: ListView.setupChild casts child layout params to AbsListView.LayoutParams.
 * ScrollView rows from createListButton / MoveRibbonRows use LinearLayout.LayoutParams instead.
 * Reversal: remove once every row builder emits AbsListView params at source.
 */
final class ListViewRowParams {

    private ListViewRowParams() {}

    /** Coerce any row to AbsListView.LayoutParams; preserve height when known. */
    static void ensure(View row) {
        ensure(row, AbsListView.LayoutParams.WRAP_CONTENT);
    }

    static void ensure(View row, int fallbackHeightPx) {
        if (row == null) return;
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp instanceof AbsListView.LayoutParams) return;
        int h = fallbackHeightPx > 0 ? fallbackHeightPx : AbsListView.LayoutParams.WRAP_CONTENT;
        if (lp != null && lp.height > 0) {
            h = lp.height;
        }
        row.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT, h));
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("view", row.getClass().getSimpleName());
            d.put("oldLp", lp != null ? lp.getClass().getSimpleName() : "null");
            d.put("height", h);
            DebugLibraryMenuLog.log("ListViewRowParams.ensure", "coerced row params", "H4", d);
        } catch (Exception ignored) {}
        }
        // #endregion
    }
}
