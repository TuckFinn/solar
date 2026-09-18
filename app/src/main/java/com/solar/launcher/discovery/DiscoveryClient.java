package com.solar.launcher.discovery;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * nookTunes Discovery, read straight from its own API rather than through
 * Subsonic.
 *
 * The Subsonic route made nookTunes fetch a whole track before it could send a
 * byte, which is why a song took 28-77 s to start. These rows carry a Deezer
 * track id where nookTunes has one, so the player can hand the existing Deezer
 * path a result and start playing from a file that is still downloading.
 */
public final class DiscoveryClient {

    /** One Discovery row: a genre, a country, or a seed artist. */
    public static final class Playlist {
        public final String id;
        public final String name;
        public final String kind;
        public final int count;

        Playlist(String id, String name, String kind, int count) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.count = count;
        }

        /**
         * True for a Deezer genre chart, whose tracks all carry an exact id.
         * Country rows come from Apple and seed rows from Spotify, so those
         * have to be resolved by searching for artist and title.
         */
        public boolean resolvesExactly() {
            return id != null && id.matches("nd-chart-g\\d+");
        }
    }

    public static final class Track {
        public final String key;
        public final String artist;
        public final String title;
        public final String album;
        public final String cover;
        /** 0 when nookTunes has no Deezer id for this row. */
        public final long deezerId;
        public final int durationSec;

        Track(String key, String artist, String title, String album, String cover,
                long deezerId, int durationSec) {
            this.key = key;
            this.artist = artist;
            this.title = title;
            this.album = album;
            this.cover = cover;
            this.deezerId = deezerId;
            this.durationSec = durationSec;
        }

        public String displayTitle() {
            if (TextUtils.isEmpty(artist)) return title;
            return artist + " - " + title;
        }

        public String searchQuery() {
            return (artist + " " + title).trim();
        }
    }

    /** Opening a playlist can mean fetching a chart upstream, so allow for it. */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 90000;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private DiscoveryClient() {}

    public static List<Playlist> playlists(String base) throws Exception {
        JSONObject doc = getJson(url(base, "/api/discovery/native/playlists"));
        JSONArray arr = doc.optJSONArray("playlists");
        List<Playlist> out = new ArrayList<Playlist>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (TextUtils.isEmpty(id)) continue;
            out.add(new Playlist(id, o.optString("name", id), o.optString("kind", ""),
                    o.optInt("count", 0)));
        }
        return out;
    }

    public static List<Track> tracks(String base, String playlistId) throws Exception {
        JSONObject doc = getJson(url(base,
                "/api/discovery/native/playlist/" + enc(playlistId)));
        JSONArray arr = doc.optJSONArray("tracks");
        List<Track> out = new ArrayList<Track>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key", "");
            if (TextUtils.isEmpty(key)) continue;
            // deezerId is JSON null for an Apple or Spotify row, and optString
            // would hand back the string "null" for it.
            long did = 0L;
            if (!o.isNull("deezerId")) {
                try {
                    did = Long.parseLong(o.optString("deezerId", "0").trim());
                } catch (NumberFormatException ignored) {}
            }
            out.add(new Track(key, o.optString("artist", ""), o.optString("title", ""),
                    o.optString("album", ""), o.optString("cover", ""), did,
                    (int) (o.optLong("durationMs", 0L) / 1000L)));
        }
        return out;
    }

    /**
     * Keep a song. nookTunes graduates the lossless original into its library
     * even though the device only ever played a compressed copy, so hearting
     * is what the archive is built from.
     */
    public static boolean heart(String base, String key) {
        try {
            getJson(url(base, "/api/y1/favorite-current?key=" + enc(key)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 2026-09-18 — A song on the device's own storage became a favourite (or
     * stopped being one): nookTunes favourites it like any heart -- history,
     * and the other player's copy when its library has the song -- or takes
     * the favourite back. Solar has already updated Hearts.m3u.
     */
    public static boolean heartLocal(String base, String artist, String title, String album,
            boolean on) {
        try {
            getJson(url(base, "/api/y1/heart-local?artist=" + enc(artist)
                    + "&title=" + enc(title) + "&album=" + enc(album) + "&on=" + (on ? "1" : "0")));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String url(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + path;
    }

    private static JSONObject getJson(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " from " + spec);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY_BYTES) {
                    throw new IllegalStateException("Discovery response too large");
                }
            }
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
