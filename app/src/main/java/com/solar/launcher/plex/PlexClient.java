package com.solar.launcher.plex;

import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.debug.AgentDebugLog;
import com.solar.launcher.net.SolarHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2026-07-14: Plex Media Server REST — music browse + token auth.
 * Replaces broken /file.mp3 stream stub with Part.key direct or MP3 transcoder.
 */
public final class PlexClient {

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private static PlexClient instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private String serverUrl = "";
    private String token = "";
    /** Cached music library section key (e.g. "1"). */
    private String musicSectionKey = "";

    private PlexClient() {}

    public static synchronized PlexClient getInstance() {
        if (instance == null) instance = new PlexClient();
        return instance;
    }

    /** 2026-07-14: Apply URL + X-Plex-Token from prefs. */
    public void applySettings(String url, String token) {
        serverUrl = normalizeServerUrl(url);
        this.token = token != null ? token.trim() : "";
        musicSectionKey = "";
        try {
            JSONObject d = new JSONObject();
            d.put("normalizedUrl", serverUrl);
            AgentDebugLog.log("PlexClient.applySettings", "B", "settings applied", d);
        } catch (JSONException ignored) {}
    }

    /**
     * 2026-07-14: Bare LAN host → http://host:32400 (Plex default).
     * Had: no port default → often hit :80 and fail.
     */
    static String normalizeServerUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return "";
        if (u.indexOf("://") < 0) u = "http://" + u;
        try {
            URL parsed = new URL(u);
            String protocol = parsed.getProtocol().toLowerCase(Locale.US);
            String host = parsed.getHost();
            int port = parsed.getPort();
            if (port == -1 && host != null && isLanHost(host)) {
                String file = parsed.getFile();
                if (file == null) file = "";
                u = new URL(protocol, host, 32400, file).toExternalForm();
                while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            }
        } catch (Exception ignored) {}
        return u;
    }

    private static boolean isLanHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.US);
        if ("localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h)) return true;
        if (h.matches("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) return true;
        if (h.matches("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$")) return true;
        return h.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\.\\d{1,3}\\.\\d{1,3}$");
    }

    public boolean isConfigured() {
        return serverUrl.length() > 0 && token.length() > 0;
    }

    /**
     * 2026-07-14: Playable URL for MediaPlayer — MP3-friendly container direct, else transcoder.
     * Had: /library/metadata/{id}/file.mp3 which PMS does not serve.
     */
    public String getStreamUrl(String songId) {
        return getStreamUrl(songId, null, null);
    }

    public String getStreamUrl(String songId, String mediaPartKey, String container) {
        return buildStreamUrl(serverUrl, token, songId, mediaPartKey, container);
    }

    /**
     * 2026-07-14: Pure stream URL builder for MediaPlayer (direct Part.key or MP3 transcoder).
     * Had: /file.mp3 stub. Now: part path when MP3-friendly, else universal transcoder.
     */
    static String buildStreamUrl(String server, String tok, String songId,
            String mediaPartKey, String container) {
        if (songId == null || songId.isEmpty() || server == null || server.isEmpty()) return "";
        String c = container != null ? container.toLowerCase(Locale.US) : "";
        boolean directOk = "mp3".equals(c) || "mpeg".equals(c) || "m4a".equals(c) || "aac".equals(c)
                || "mp4".equals(c);
        String t = tok != null ? tok : "";
        if (directOk && mediaPartKey != null && mediaPartKey.length() > 0) {
            String path = mediaPartKey.startsWith("/") ? mediaPartKey : ("/" + mediaPartKey);
            String direct = server + path + (path.contains("?") ? "&" : "?") + "X-Plex-Token=" + enc(t);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("branch", "direct");
                d.put("container", c);
                d.put("partKeyLen", mediaPartKey.length());
                d.put("songIdLen", songId.length());
                com.solar.launcher.Debug8cf8b0Log.log(
                        "PlexClient.buildStreamUrl", "direct part branch", "C", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return direct;
        }
        // Universal music transcoder → MP3 for API 17 MediaPlayer (same idea as Navidrome format=mp3).
        String transcode = server + "/music/:/transcode/universal?X-Plex-Token=" + enc(t)
                + "&path=" + enc("/library/metadata/" + songId)
                + "&mediaIndex=0&partIndex=0&protocol=http"
                + "&audioCodec=mp3&maxAudioBitrate=192"
                + "&directPlay=0&directStream=0&session=solar";
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("branch", "transcode");
            d.put("container", c);
            d.put("partKeyLen", mediaPartKey != null ? mediaPartKey.length() : 0);
            d.put("partKeyNull", mediaPartKey == null);
            d.put("songIdLen", songId.length());
            d.put("directOkWould", directOk);
            com.solar.launcher.Debug8cf8b0Log.log(
                    "PlexClient.buildStreamUrl", "transcode branch", "C", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return transcode;
    }

    public String getCoverArtUrl(String id, int size) {
        if (id == null || id.isEmpty()) return "";
        return buildUrl("/photo/:/transcode",
                "width=" + size + "&height=" + size + "&minSize=1&url="
                        + enc("/library/metadata/" + id + "/thumb"));
    }

    public String getDownloadUrl(String songId) {
        return getStreamUrl(songId, null, null);
    }

    public String getDownloadUrl(PlexSong song) {
        if (song == null) return "";
        return getStreamUrl(song.id, song.mediaPartKey, song.container);
    }

    private String buildUrl(String endpoint, String extra) {
        String base = serverUrl + endpoint + (endpoint.contains("?") ? "&" : "?")
                + "X-Plex-Token=" + enc(token);
        return extra != null && extra.length() > 0 ? base + "&" + extra : base;
    }

    public void ping(final Callback<Boolean> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("/", null));
                    JSONObject mc = root.optJSONObject("MediaContainer");
                    postSuccess(cb, mc != null);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /** Resolve type=artist section key once per settings session. */
    private String resolveMusicSectionKey() throws Exception {
        if (musicSectionKey != null && musicSectionKey.length() > 0) return musicSectionKey;
        JSONObject sectionsRoot = fetchJson(buildUrl("/library/sections", null));
        JSONArray directories = sectionsRoot.getJSONObject("MediaContainer").optJSONArray("Directory");
        if (directories != null) {
            for (int i = 0; i < directories.length(); i++) {
                JSONObject d = directories.getJSONObject(i);
                if ("artist".equals(d.optString("type"))) {
                    musicSectionKey = d.optString("key");
                    break;
                }
            }
        }
        if (musicSectionKey == null || musicSectionKey.isEmpty()) {
            throw new Exception("No music section found");
        }
        return musicSectionKey;
    }

    public void getArtists(final android.content.Context ctx, final Callback<List<PlexArtist>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                final PlexCacheStore cache = PlexCacheStore.getInstance(ctx);
                final List<PlexArtist> cached = cache.loadArtists();
                final boolean hadCache = cached != null && !cached.isEmpty();
                if (hadCache) postSuccess(cb, cached);
                try {
                    String section = resolveMusicSectionKey();
                    JSONObject root = fetchJson(buildUrl("/library/sections/" + section + "/all", "type=8"));
                    JSONArray metadata = root.getJSONObject("MediaContainer").optJSONArray("Metadata");
                    List<PlexArtist> artists = new ArrayList<PlexArtist>();
                    if (metadata != null) {
                        for (int i = 0; i < metadata.length(); i++) {
                            JSONObject o = metadata.getJSONObject(i);
                            PlexArtist a = new PlexArtist();
                            a.id = o.optString("ratingKey");
                            a.name = o.optString("title");
                            a.albumCount = o.optInt("childCount", 0);
                            a.coverArtId = o.optString("ratingKey");
                            a.indexLetter = indexLetter(a.name);
                            artists.add(a);
                        }
                    }
                    cache.saveArtists(artists);
                    if (!hadCache || !artistListsEqual(cached, artists)) postSuccess(cb, artists);
                } catch (final Exception e) {
                    if (!hadCache) postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getArtistAlbums(final String artistId, final Callback<List<PlexAlbum>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("/library/metadata/" + artistId + "/children", null));
                    JSONArray metadata = root.getJSONObject("MediaContainer").optJSONArray("Metadata");
                    List<PlexAlbum> albums = new ArrayList<PlexAlbum>();
                    if (metadata != null) {
                        for (int i = 0; i < metadata.length(); i++) {
                            albums.add(parseAlbum(metadata.getJSONObject(i)));
                        }
                    }
                    postSuccess(cb, albums);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /** 2026-07-14: All albums A–Z from music section (type=9). Was empty stub. */
    public void getAlbumList(final Callback<List<PlexAlbum>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    String section = resolveMusicSectionKey();
                    JSONObject root = fetchJson(buildUrl("/library/sections/" + section + "/all",
                            "type=9&sort=titleSort"));
                    JSONArray metadata = root.getJSONObject("MediaContainer").optJSONArray("Metadata");
                    List<PlexAlbum> albums = new ArrayList<PlexAlbum>();
                    if (metadata != null) {
                        for (int i = 0; i < metadata.length(); i++) {
                            albums.add(parseAlbum(metadata.getJSONObject(i)));
                        }
                    }
                    postSuccess(cb, albums);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /** 2026-07-14: Audio playlists. Was empty stub. */
    public void getPlaylists(final Callback<List<PlexPlaylist>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("/playlists/all", "playlistType=audio"));
                    JSONArray metadata = root.getJSONObject("MediaContainer").optJSONArray("Metadata");
                    List<PlexPlaylist> playlists = new ArrayList<PlexPlaylist>();
                    if (metadata != null) {
                        for (int i = 0; i < metadata.length(); i++) {
                            JSONObject o = metadata.getJSONObject(i);
                            PlexPlaylist p = new PlexPlaylist();
                            p.id = o.optString("ratingKey");
                            p.name = o.optString("title");
                            p.songCount = o.optInt("leafCount", 0);
                            playlists.add(p);
                        }
                    }
                    postSuccess(cb, playlists);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getPlaylistSongs(final String playlistId, final Callback<List<PlexSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("/playlists/" + playlistId + "/items", null));
                    JSONArray metadata = root.getJSONObject("MediaContainer").optJSONArray("Metadata");
                    List<PlexSong> songs = new ArrayList<PlexSong>();
                    if (metadata != null) {
                        for (int i = 0; i < metadata.length(); i++) {
                            songs.add(parseSong(metadata.getJSONObject(i)));
                        }
                    }
                    postSuccess(cb, songs);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /** 2026-07-14: Cap all-songs at 500 via section tracks. Was empty stub. */
    public void getAllTracks(final Callback<List<PlexSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    String section = resolveMusicSectionKey();
                    JSONObject root = fetchJson(buildUrl("/library/sections/" + section + "/all",
                            "type=10&sort=titleSort&X-Plex-Container-Start=0&X-Plex-Container-Size=500"));
                    JSONArray metadata = root.getJSONObject("MediaContainer").optJSONArray("Metadata");
                    List<PlexSong> songs = new ArrayList<PlexSong>();
                    if (metadata != null) {
                        int n = Math.min(metadata.length(), 500);
                        for (int i = 0; i < n; i++) {
                            songs.add(parseSong(metadata.getJSONObject(i)));
                        }
                    }
                    postSuccess(cb, songs);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void search(final String query, final Callback<PlexSearchResult> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("/hubs/search", "query=" + enc(query)
                            + "&limit=40"));
                    PlexSearchResult result = new PlexSearchResult();
                    JSONArray hubs = root.getJSONObject("MediaContainer").optJSONArray("Hub");
                    if (hubs != null) {
                        for (int i = 0; i < hubs.length(); i++) {
                            JSONObject hub = hubs.getJSONObject(i);
                            String type = hub.optString("type");
                            JSONArray metadata = hub.optJSONArray("Metadata");
                            if (metadata == null) continue;
                            for (int j = 0; j < metadata.length(); j++) {
                                JSONObject o = metadata.getJSONObject(j);
                                if ("artist".equals(type)) {
                                    PlexArtist a = new PlexArtist();
                                    a.id = o.optString("ratingKey");
                                    a.name = o.optString("title");
                                    result.artists.add(a);
                                } else if ("album".equals(type)) {
                                    result.albums.add(parseAlbum(o));
                                } else if ("track".equals(type)) {
                                    result.songs.add(parseSong(o));
                                }
                            }
                        }
                    }
                    postSuccess(cb, result);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getAlbumSongs(final String albumId, final Callback<List<PlexSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("/library/metadata/" + albumId + "/children", null));
                    JSONArray metadata = root.getJSONObject("MediaContainer").optJSONArray("Metadata");
                    List<PlexSong> songs = new ArrayList<PlexSong>();
                    if (metadata != null) {
                        for (int i = 0; i < metadata.length(); i++) {
                            songs.add(parseSong(metadata.getJSONObject(i)));
                        }
                    }
                    postSuccess(cb, songs);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    private static PlexAlbum parseAlbum(JSONObject o) {
        PlexAlbum al = new PlexAlbum();
        al.id = o.optString("ratingKey");
        al.name = o.optString("title");
        al.artist = o.optString("parentTitle", "");
        al.year = o.optInt("year", 0);
        al.songCount = o.optInt("leafCount", 0);
        al.coverArtId = o.optString("ratingKey");
        return al;
    }

    /** 2026-07-14: Pull Part.key + container so stream URL can direct-play when safe. */
    static PlexSong parseSong(JSONObject o) {
        PlexSong s = new PlexSong();
        s.id = o.optString("ratingKey");
        s.title = o.optString("title", "");
        s.artist = o.optString("grandparentTitle", "");
        s.album = o.optString("parentTitle", "");
        s.durationSec = o.optInt("duration", 0) / 1000;
        s.coverArtId = o.optString("parentRatingKey");
        JSONArray media = o.optJSONArray("Media");
        if (media != null && media.length() > 0) {
            JSONObject m0 = media.optJSONObject(0);
            if (m0 != null) {
                s.container = m0.optString("container", "");
                JSONArray parts = m0.optJSONArray("Part");
                if (parts != null && parts.length() > 0) {
                    JSONObject p0 = parts.optJSONObject(0);
                    if (p0 != null) {
                        s.mediaPartKey = p0.optString("key", "");
                        String container = p0.optString("container", "");
                        if (container.length() > 0) s.container = container;
                    }
                }
            }
        }
        if (s.container != null && s.container.length() > 0) {
            s.suffix = s.container;
        } else {
            s.suffix = "mp3";
        }
        return s;
    }

    private static String indexLetter(String name) {
        if (name == null || name.isEmpty()) return "#";
        char c = Character.toUpperCase(name.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }

    private static boolean artistListsEqual(List<PlexArtist> a, List<PlexArtist> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String idA = a.get(i) != null ? a.get(i).id : "";
            String idB = b.get(i) != null ? b.get(i).id : "";
            if (!idA.equals(idB)) return false;
        }
        return true;
    }

    private static JSONObject fetchJson(String urlStr) throws Exception {
        byte[] bytes = SolarHttp.getBytes(urlStr, "application/json", "SolarLauncher/1.0");
        return new JSONObject(new String(bytes, "UTF-8"));
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s != null ? s : "", "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private <T> void postSuccess(final Callback<T> cb, final T value) {
        main.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onSuccess(value);
            }
        });
    }

    private void postError(final Callback<?> cb, final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onError(msg != null ? msg : "error");
            }
        });
    }
}
