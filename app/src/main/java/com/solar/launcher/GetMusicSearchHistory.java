package com.solar.launcher;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Recent queries for unified Get Music search. */
public final class GetMusicSearchHistory {
    private static final String PREF_KEY = "get_music_search_history";
    // 2026-09-16 — a newline, not the Record Separator this used to use.
    // 0x1E cannot appear in an XML 1.0 document at all, not raw and not as a
    // numeric reference, so the first Get Music search made
    // SOLAR_SETTINGS.xml unreadable to every conforming parser. Android's own
    // parser is lenient and never complained, which is why this went
    // unnoticed; provision-y1.py could not read the file at all and refused
    // to touch a device that had ever been searched. A query comes from a
    // single-line field and cannot contain a newline, so this separator is
    // just as safe and is legal to write down.
    private static final String ENTRY_SEP = "\n";
    /** Splits on the new separator and on the 0x1E already sitting in prefs. */
    private static final String ENTRY_SPLIT = "[\\n\\u001e]";
    private static final int MAX_ENTRIES = 50;

    private GetMusicSearchHistory() {}

    public static void remember(SharedPreferences prefs, String query) {
        if (prefs == null || query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        List<String> list = load(prefs);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (q.equalsIgnoreCase(list.get(i))) list.remove(i);
        }
        list.add(0, q);
        while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
        try {
            prefs.edit().putString(PREF_KEY, join(list)).commit();
        } catch (Exception ignored) {}
    }

    /** The stored form of a history list. Package-private so a test can see it. */
    static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(ENTRY_SEP);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    /** Reads either separator, so history written by an older build survives. */
    static List<String> split(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split(ENTRY_SPLIT, -1);
        for (String p : parts) {
            if (p != null) {
                String t = p.trim();
                if (t.length() > 0) out.add(t);
            }
        }
        return out;
    }

    public static List<String> load(SharedPreferences prefs) {
        List<String> out = new ArrayList<String>();
        if (prefs == null) return out;
        try {
            return split(prefs.getString(PREF_KEY, ""));
        } catch (Exception ignored) {}
        return out;
    }
}
