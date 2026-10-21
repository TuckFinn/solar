package com.solar.launcher.remote;

import com.solar.launcher.deezer.DeezerResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2026-10-21 — "Play on Y1" from nookTunes: what a PLAY_REMOTE broadcast asks
 * for, as the songs to play in order.
 *
 * <pre>
 *   adb -s &lt;ip&gt;:5555 shell am broadcast -a com.solar.launcher.action.PLAY_REMOTE \
 *     --es title "Dreams" --es artist "Fleetwood Mac" --es album "Rumours" --es deezer_id "3135556" \
 *     [--es queue_json '[{"title":..,"artist":..,"album":..,"deezer_id":..}, ...]']
 * </pre>
 *
 * Pure Java (org.json only) so the mapping is unit-tested off the device. A
 * song with neither a Deezer id nor a title cannot be played or searched for,
 * and is dropped here; one with a title and no id is kept for a search.
 */
public final class RemotePlayRequest {

    public static final String ACTION = "com.solar.launcher.action.PLAY_REMOTE";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_ALBUM = "album";
    public static final String EXTRA_DEEZER_ID = "deezer_id";
    public static final String EXTRA_QUEUE_JSON = "queue_json";
    /**
     * 2026-10-21 — the whole request as base64 of a JSON array of songs (the
     * first plays now). The Y1's am wrapper splits arguments at spaces, so
     * "--es artist 'Fleetwood Mac'" arrived as pkg=Mac; base64 has no spaces.
     * When present it replaces the other extras.
     */
    public static final String EXTRA_PAYLOAD_B64 = "payload_b64";

    /**
     * Songs kept from one request, the first included. Every one is queued and
     * downloaded in the background (~9 MB each into the stream cache on the
     * Y1's 1 GB /data), so a whole library sent by mistake must not fill it.
     */
    public static final int MAX_TRACKS = 50;

    public static final class Track {
        public final String title;
        public final String artist;
        public final String album;
        /** 0 when the sender had none; the song is then searched for. */
        public final long deezerId;

        public Track(String title, String artist, String album, long deezerId) {
            this.title = clean(title);
            this.artist = clean(artist);
            this.album = clean(album);
            this.deezerId = deezerId > 0 ? deezerId : 0L;
        }

        public boolean hasDeezerId() {
            return deezerId > 0;
        }

        /** Same query Discovery searches a row without an id by. */
        public String searchQuery() {
            return (artist + " " + title).trim();
        }

        /** As Discovery builds a row it has an id for: no album id, length or cover. */
        public DeezerResult toDeezerResult() {
            return new DeezerResult(deezerId, title, artist, album, 0L, 0, "", "");
        }

        boolean playable() {
            return deezerId > 0 || title.length() > 0;
        }
    }

    private RemotePlayRequest() {}

    /**
     * The broadcast's extras as songs: the one named first, then queue_json's.
     * {@code deezerId} is whatever the extra held -- a String from --es, a
     * Long from --el, an Integer from --ei. A queue_json that is not a JSON
     * array is ignored, leaving the first song alone.
     */
    public static List<Track> fromExtras(String title, String artist, String album,
            Object deezerId, String queueJson) {
        List<Track> out = new ArrayList<Track>();
        add(out, new Track(title, artist, album, parseId(deezerId)));
        for (Track t : parseQueue(queueJson)) {
            if (out.size() >= MAX_TRACKS) break;
            add(out, t);
        }
        return out;
    }

    /** The songs in a payload_b64 extra; empty when it is missing or not valid. */
    public static List<Track> fromPayload(String b64) {
        if (b64 == null || b64.trim().length() == 0) return new ArrayList<Track>();
        try {
            byte[] raw = android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT);
            return fromExtras(null, null, null, null, new String(raw, "UTF-8"));
        } catch (Exception | Error e) {
            try {   // unit tests run without android.util
                byte[] raw = java.util.Base64.getMimeDecoder().decode(b64.trim());
                return fromExtras(null, null, null, null, new String(raw, "UTF-8"));
            } catch (Exception e2) {
                return new ArrayList<Track>();
            }
        }
    }

    /** queue_json's songs, in order; unplayable entries dropped. Never null. */
    public static List<Track> parseQueue(String queueJson) {
        if (queueJson == null || queueJson.trim().length() == 0) {
            return Collections.<Track>emptyList();
        }
        JSONArray arr;
        try {
            arr = new JSONArray(queueJson.trim());
        } catch (Exception e) {
            return Collections.<Track>emptyList();
        }
        List<Track> out = new ArrayList<Track>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            add(out, new Track(str(o, EXTRA_TITLE), str(o, EXTRA_ARTIST), str(o, EXTRA_ALBUM),
                    parseId(o.opt(EXTRA_DEEZER_ID))));
        }
        return out;
    }

    /** A positive Deezer id from a String or a Number; 0 for anything else. */
    public static long parseId(Object v) {
        if (v == null || v == JSONObject.NULL) return 0L;
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return n > 0 ? n : 0L;
        }
        String s = v.toString().trim();
        if (s.length() == 0) return 0L;
        try {
            long n = Long.parseLong(s);
            return n > 0 ? n : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void add(List<Track> out, Track t) {
        if (t.playable()) out.add(t);
    }

    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? "" : v.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
