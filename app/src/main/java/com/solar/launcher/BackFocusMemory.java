package com.solar.launcher;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 2026-09-19 — Where Back puts focus: on the row the list was left from.
 *
 * Back goes up one level. Leave a playlist and you land on the playlists
 * list with that playlist focused, not on the top row. Every list level
 * remembers the label and position of the row that had focus when it was
 * left. When Back rebuilds that level, the level looks up its row and
 * focuses it.
 *
 * Entries are keyed by level, not kept as a stack. Going Home through the
 * overlay skips levels, and a stack would then pop a stale entry into the
 * wrong list. Here each level only ever gets its own row back.
 */
final class BackFocusMemory {

    static final class Row {
        final String label;
        final int index;

        Row(String label, int index) {
            this.label = label;
            this.index = index;
        }
    }

    private final HashMap<String, Row> rows = new HashMap<String, Row>();

    void remember(String level, String label, int index) {
        if (level == null || index < 0) return;
        rows.put(level, new Row(label, index));
    }

    Row recall(String level) {
        return level != null ? rows.get(level) : null;
    }

    /**
     * Which of {@code labels} to focus for {@code row}. The label wins, because
     * a list can shift under it (Now Playing appears on Home once something
     * plays). If the label is gone, use the old position clamped to the list.
     * Returns -1 when there is nothing to focus.
     */
    static int resolve(List<String> labels, Row row) {
        if (row == null || labels == null || labels.isEmpty()) return -1;
        int at = indexOfLabel(labels, row.label, row.index);
        if (at >= 0) return at;
        return Math.max(0, Math.min(row.index, labels.size() - 1));
    }

    /**
     * Where {@code label} is in {@code labels}, the copy nearest {@code near} when it
     * repeats; -1 when it is not there (or there is no label to look for).
     */
    static int indexOfLabel(List<String> labels, String label, int near) {
        if (labels == null || label == null || label.length() == 0) return -1;
        int best = -1;
        for (int i = 0; i < labels.size(); i++) {
            if (!label.equals(labels.get(i))) continue;
            if (best < 0 || Math.abs(i - near) < Math.abs(best - near)) best = i;
        }
        return best;
    }

    /** First visible text in a row, which is how the row names itself on screen. */
    static String labelOf(View v) {
        if (v == null) return "";
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
                String t = labelOf(c);
                if (t.length() > 0) return t;
            }
        }
        return "";
    }

    /** Labels of a list's rows, top to bottom. */
    static List<String> labelsOf(ViewGroup list) {
        List<String> out = new ArrayList<String>();
        if (list == null) return out;
        for (int i = 0; i < list.getChildCount(); i++) out.add(labelOf(list.getChildAt(i)));
        return out;
    }

    /** Index of the row of {@code list} that holds {@code focus}, or -1. */
    static int rowIndexOf(ViewGroup list, View focus) {
        if (list == null || focus == null) return -1;
        View v = focus;
        while (v != null) {
            if (v.getParent() == list) return list.indexOfChild(v);
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return -1;
    }
}
