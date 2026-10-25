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
        /** 2026-10-21 — Q18: the folder it sits in; "" from an older nookTunes. */
        public final String group;

        Playlist(String id, String name, String kind, int count) {
            this(id, name, kind, count, "");
        }

        Playlist(String id, String name, String kind, int count, String group) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.count = count;
            this.group = group == null ? "" : group;
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
        /** 2026-10-25 — nookTunes URL for a song Deezer lacks (Bandcamp), else "". */
        public final String streamUrl;

        Track(String key, String artist, String title, String album, String cover,
                long deezerId, int durationSec) {
            this(key, artist, title, album, cover, deezerId, durationSec, "");
        }

        Track(String key, String artist, String title, String album, String cover,
                long deezerId, int durationSec, String streamUrl) {
            this.streamUrl = streamUrl != null ? streamUrl : "";
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
        return catalog(base).all();
    }

    /**
     * 2026-10-21 — Q18: the lists and the folders they sit in. Parsing is in
     * {@link DiscoveryFolders}, which falls back to no folders for a
     * nookTunes that sends none.
     */
    public static DiscoveryFolders catalog(String base) throws Exception {
        JSONObject doc = getJson(url(base, "/api/discovery/native/playlists"));
        flushPendingAsync(base);
        return DiscoveryFolders.parse(doc);
    }

    public static List<Track> tracks(String base, String playlistId) throws Exception {
        JSONObject doc = getJson(url(base,
                "/api/discovery/native/playlist/" + enc(playlistId)));
        flushPendingAsync(base);
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
            String stream = o.isNull("stream") ? "" : o.optString("stream", "");
            if (stream.startsWith("/")) stream = url(base, stream);
            out.add(new Track(key, o.optString("artist", ""), o.optString("title", ""),
                    o.optString("album", ""), o.optString("cover", ""), did,
                    (int) (o.optLong("durationMs", 0L) / 1000L), stream));
        }
        return out;
    }

    /**
     * Keep a song. nookTunes graduates the lossless original into its library
     * even though the device only ever played a compressed copy, so hearting
     * is what the archive is built from.
     */
    public static boolean heart(String base, String key) {
        return report(base, "/api/y1/favorite-current?key=" + enc(key));
    }

    /**
     * 2026-09-18 — A song on the device's own storage became a favourite (or
     * stopped being one): nookTunes favourites it like any heart -- history,
     * and the other player's copy when its library has the song -- or takes
     * the favourite back. Solar has already updated Hearts.m3u.
     */
    public static boolean heartLocal(String base, String artist, String title, String album,
            boolean on) {
        return report(base, "/api/y1/heart-local?artist=" + enc(artist)
                + "&title=" + enc(title) + "&album=" + enc(album) + "&on=" + (on ? "1" : "0"));
    }

    /**
     * 2026-09-19 — A Discovery song started playing here. The Y1 plays from its
     * own Deezer download, so nookTunes never sees the stream; this is how its
     * daily rotation learns which songs have been heard and moves them back.
     */
    public static boolean played(String base, String key) {
        return report(base, "/api/discovery/played?key=" + enc(key));
    }

    /** Whether a song that just gave way to the next was skipped or heard out. */
    public static final String OUTCOME_SKIP = "skip";
    public static final String OUTCOME_FULL = "full";

    /**
     * 2026-10-20 — How a song that was playing ended: skipped, or heard out.
     * Sent when the next one starts, so the rotation can tell a song turned
     * off after three seconds from one played through; the bare played ping
     * above fires at the start and cannot.
     */
    public static boolean playedOutcome(String base, String key, String outcome) {
        return report(base, "/api/discovery/played?key=" + enc(key)
                + "&outcome=" + enc(outcome));
    }

    /**
     * 2026-10-21 — Tucker (Q7): a song played long enough to count (the
     * Last.fm rule ScrobbleManager applies) goes to nookTunes' listening
     * history, so For Me and its stats learn from the Y1 and not only from
     * Navidrome. Kept offline like the rest; ts is when it was played.
     */
    public static boolean listen(String base, String artist, String title, String album,
                                 long tsSec, long durationSec) {
        return report(base, "/api/y1/listen?artist=" + enc(artist) + "&title=" + enc(title)
                + "&album=" + enc(album == null ? "" : album) + "&ts=" + tsSec + "&dur=" + durationSec);
    }

    // ─── The Play/Pause hold menu (Q9) ─────────────────────────────────────
    //
    // 2026-10-21 — Tucker: holding Play/Pause opens Heart / Heart album / Not
    // for me. Both new calls are reports like the rest, so one made with the
    // Wi-Fi asleep goes when it wakes.

    /** What nookTunes said to a heart-album, when it answered. */
    public static final class AlbumAnswer {
        /** True when nookTunes took it; false with {@link #error} when it refused. */
        public final boolean ok;
        /** True when it could not be reached and the report waits to be sent. */
        public final boolean pending;
        public final String error;
        public final int queued;

        AlbumAnswer(boolean ok, boolean pending, String error, int queued) {
            this.ok = ok;
            this.pending = pending;
            this.error = error;
            this.queued = queued;
        }
    }

    static String heartAlbumPath(String artist, String album) {
        return "/api/albums/heart?artist=" + enc(artist == null ? "" : artist)
                + "&album=" + enc(album == null ? "" : album);
    }

    /**
     * A Discovery song goes by its key, which is exact; anything else by its
     * artist, which is all nookTunes can sink for a library song.
     */
    static String notForMePath(String key, String artist) {
        if (key != null && key.trim().length() > 0) {
            return "/api/discovery/not-for-me?key=" + enc(key.trim());
        }
        return "/api/discovery/not-for-me?artist=" + enc(artist == null ? "" : artist.trim());
    }

    /**
     * Heart the whole album: nookTunes marks it a favourite and downloads all
     * of it lossless.
     */
    public static boolean heartAlbum(String base, String artist, String album) {
        return report(base, heartAlbumPath(artist, album));
    }

    /**
     * Heart the album and say what nookTunes answered, for the toast. When it
     * cannot be reached the request is kept like any report and goes when the
     * Wi-Fi is back; the answer then says {@link AlbumAnswer#pending}. Blocks:
     * call it off the UI thread.
     */
    public static AlbumAnswer heartAlbumNow(String base, String artist, String album) {
        if (base == null || base.trim().length() == 0) {
            return new AlbumAnswer(false, false, "nookTunes is not set up", 0);
        }
        String path = heartAlbumPath(artist, album);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url(base, path)).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            JSONObject doc = null;
            try {
                doc = in == null ? null : new JSONObject(readBody(in));
            } catch (org.json.JSONException ignored) {}
            boolean ok = code >= 200 && code < 300 && (doc == null || doc.optBoolean("ok", true));
            if (ok) flushPendingAsync(base);
            String error = doc != null ? doc.optString("error", "") : "";
            if (!ok && error.length() == 0) error = "HTTP " + code;
            return new AlbumAnswer(ok, false, ok ? null : error,
                    doc != null ? doc.optInt("queued", 0) : 0);
        } catch (java.io.IOException e) {
            // Unreachable, as report() would find it: keep it for later.
            keepPending(path);
            return new AlbumAnswer(false, true, null, 0);
        } catch (Exception e) {
            return new AlbumAnswer(false, false, String.valueOf(e.getMessage()), 0);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "Not for me": the song leaves Discovery for good and its artist sinks. */
    public static boolean notForMe(String base, String key, String artist) {
        return report(base, notForMePath(key, artist));
    }

    // ─── Reports that survive Wi-Fi being off ──────────────────────────────
    //
    // 2026-10-20 — Hearts and played pings were fire-and-forget: one made while
    // the Y1's Wi-Fi slept only showed a failure toast and was gone. Now a
    // report that cannot reach nookTunes at all is written to a small file and
    // sent, in order, after the next call that does get through. Only network
    // failures are kept; an answer from nookTunes -- even an error -- means it
    // heard the report, and sending it again would not change that. Replaying is
    // safe: a heart keeps (it never toggles), heart-local carries its on/off,
    // and a played ping only moves a song back in the rotation.

    private static final String PENDING_FILE = "discovery_pending.txt";
    /** Oldest reports are dropped beyond this; a week away is a few hundred. */
    private static final int PENDING_MAX = 500;
    private static final Object PENDING_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean FLUSHING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile java.io.File pendingDir;

    /** Where pending reports are kept: the app's files dir. Call once at startup. */
    public static void init(java.io.File filesDir) {
        pendingDir = filesDir;
    }

    /** How many reports are waiting to be sent. */
    public static int pendingCount() {
        synchronized (PENDING_LOCK) {
            return readPending().size();
        }
    }

    /**
     * Send one report. On a network failure it is kept for later and false is
     * returned, as before, since it has not landed yet.
     */
    private static boolean report(String base, String pathAndQuery) {
        if (base == null || base.trim().length() == 0) return false;   // not set up: nothing to keep
        try {
            getJson(url(base, pathAndQuery));
        } catch (java.io.IOException e) {
            keepPending(pathAndQuery);
            return false;
        } catch (Exception e) {
            return false;
        }
        flushPendingAsync(base);
        return true;
    }

    private static void keepPending(String pathAndQuery) {
        if (pendingDir == null) return;
        synchronized (PENDING_LOCK) {
            List<String> lines = readPending();
            lines.add(pathAndQuery);
            while (lines.size() > PENDING_MAX) lines.remove(0);
            writePending(lines);
        }
    }

    /**
     * After a call got through, send what is waiting, oldest first, off the
     * caller's thread. Stops at the first network failure and keeps the rest.
     */
    private static void flushPendingAsync(final String base) {
        if (pendingDir == null || TextUtils.isEmpty(base)) return;
        synchronized (PENDING_LOCK) {
            if (readPending().isEmpty()) return;
        }
        if (!FLUSHING.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    flushPending(base);
                } finally {
                    FLUSHING.set(false);
                }
            }
        }, "discovery-pending");
        t.setDaemon(true);
        t.start();
    }

    private static void flushPending(String base) {
        while (true) {
            String next;
            synchronized (PENDING_LOCK) {
                List<String> lines = readPending();
                if (lines.isEmpty()) return;
                next = lines.get(0);
            }
            try {
                getJson(url(base, next));
            } catch (java.io.IOException e) {
                android.util.Log.w("SolarDiscovery", "pending reports wait: " + e);
                return;
            } catch (Exception e) {
                android.util.Log.w("SolarDiscovery", "pending report dropped: " + next + " " + e);
            }
            synchronized (PENDING_LOCK) {
                List<String> lines = readPending();
                if (!lines.isEmpty() && lines.get(0).equals(next)) lines.remove(0);
                writePending(lines);
            }
        }
    }

    private static List<String> readPending() {
        List<String> out = new ArrayList<String>();
        java.io.File f = pendingDir == null ? null : new java.io.File(pendingDir, PENDING_FILE);
        if (f == null || !f.exists()) return out;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 0) out.add(line);
            }
        } catch (Exception e) {
            android.util.Log.w("SolarDiscovery", "pending reports unreadable", e);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Write via a temp file and rename, so a crash mid-write keeps the old list. */
    private static void writePending(List<String> lines) {
        java.io.File f = new java.io.File(pendingDir, PENDING_FILE);
        if (lines.isEmpty()) {
            f.delete();
            return;
        }
        java.io.File tmp = new java.io.File(pendingDir, PENDING_FILE + ".tmp");
        java.io.Writer w = null;
        try {
            w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp), "UTF-8");
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
            w.close();
            w = null;
            if (!tmp.renameTo(f)) {
                android.util.Log.w("SolarDiscovery", "pending reports: rename failed");
            }
        } catch (Exception e) {
            android.util.Log.w("SolarDiscovery", "pending reports not saved", e);
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
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
            return new JSONObject(readBody(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String readBody(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BODY_BYTES) {
                throw new IllegalStateException("Discovery response too large");
            }
        }
        return buf.toString("UTF-8");
    }
}
