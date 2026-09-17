package com.solar.launcher.podcast;

import com.solar.launcher.PrefsWriteAudit;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 2026-07-15 — Followed podcast shows (feed URL + title + art).
 * Layman: remember the shows you follow so My Shows opens quickly.
 * Reversal: clear SharedPreferences podcast_subscriptions.
 */
public final class PodcastSubscriptions {

    private static final String PREFS = "podcast_subscriptions";
    private static final String KEY_JSON = "shows_json";

    public static final class Show {
        public final String feedUrl;
        public final String title;
        public final String artUrl;

        public Show(String feedUrl, String title, String artUrl) {
            this.feedUrl = feedUrl != null ? feedUrl.trim() : "";
            this.title = title != null ? title.trim() : "";
            this.artUrl = artUrl != null ? artUrl.trim() : "";
        }
    }

    private PodcastSubscriptions() {}

    public static List<Show> list(Context ctx) {
        List<Show> out = new ArrayList<Show>();
        if (ctx == null) return out;
        String raw = PrefsWriteAudit.get(ctx, PREFS).getString(KEY_JSON, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Show(o.optString("feed"), o.optString("title"), o.optString("art")));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, new Comparator<Show>() {
            @Override
            public int compare(Show a, Show b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return out;
    }

    public static boolean isFollowed(Context ctx, String feedUrl) {
        if (feedUrl == null) return false;
        String norm = feedUrl.trim();
        for (Show s : list(ctx)) {
            if (norm.equalsIgnoreCase(s.feedUrl)) return true;
        }
        return false;
    }

    public static void follow(Context ctx, String feedUrl, String title, String artUrl) {
        if (ctx == null || feedUrl == null || feedUrl.trim().isEmpty()) return;
        List<Show> cur = list(ctx);
        List<Show> next = new ArrayList<Show>();
        boolean found = false;
        for (Show s : cur) {
            if (feedUrl.trim().equalsIgnoreCase(s.feedUrl)) {
                next.add(new Show(feedUrl, title != null ? title : s.title, artUrl != null ? artUrl : s.artUrl));
                found = true;
            } else {
                next.add(s);
            }
        }
        if (!found) next.add(new Show(feedUrl, title, artUrl));
        save(ctx, next);
    }

    public static void unfollow(Context ctx, String feedUrl) {
        if (ctx == null || feedUrl == null) return;
        List<Show> next = new ArrayList<Show>();
        for (Show s : list(ctx)) {
            if (!feedUrl.trim().equalsIgnoreCase(s.feedUrl)) next.add(s);
        }
        save(ctx, next);
    }

    private static void save(Context ctx, List<Show> shows) {
        try {
            JSONArray arr = new JSONArray();
            for (Show s : shows) {
                JSONObject o = new JSONObject();
                o.put("feed", s.feedUrl);
                o.put("title", s.title);
                o.put("art", s.artUrl);
                arr.put(o);
            }
            PrefsWriteAudit.get(ctx, PREFS).edit()
                    .putString(KEY_JSON, arr.toString())
                    .commit();
        } catch (Exception ignored) {
        }
    }

    public static void selfCheck() {
        // Round-trip without Context not required; empty list sanity.
        List<Show> empty = list(null);
        if (empty == null || !empty.isEmpty()) throw new AssertionError("null ctx");
    }
}
