package com.solar.launcher.navidrome;

import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.debug.AgentDebugLog;
import com.solar.launcher.net.SolarHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2026-07-06: Subsonic REST client for Navidrome — stream, browse, download.
 */
public final class NavidromeClient {

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private static NavidromeClient instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private String serverUrl = "";
    private String username = "";
    private String password = "";

    private NavidromeClient() {}

    public static synchronized NavidromeClient getInstance() {
        if (instance == null) instance = new NavidromeClient();
        return instance;
    }

    /** 2026-07-06: Normalize stored URL so ping/browse hit the real Navidrome port. */
    public void applySettings(String url, String user, String pass) {
        serverUrl = normalizeServerUrl(url);
        username = user != null ? user.trim() : "";
        password = pass != null ? pass : "";
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("rawUrl", url != null ? url : "");
            d.put("normalizedUrl", serverUrl);
            d.put("scheme", serverUrl.contains("://") ? serverUrl.substring(0, serverUrl.indexOf("://")) : "");
            AgentDebugLog.log("NavidromeClient.applySettings", "B", "settings applied", d);
        } catch (JSONException ignored) {}
        // #endregion
    }

    /**
     * 2026-07-06: Bare "192.168.0.5" or "192.168.0.5:4533" used to open port 80/443 → connection refused.
     * LAN installs: add http:// when missing; default Navidrome port 4533 when host is private IP.
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
                u = new URL(protocol, host, 4533, file).toExternalForm();
                while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            }
        } catch (Exception ignored) {
            // keep trimmed u — fetch will surface a clearer error
        }
        return u;
    }

    /** 2026-07-06: Private/LAN host — safe to assume default Navidrome listen port 4533. */
    private static boolean isLanHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.US);
        if ("localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h)) return true;
        if (h.matches("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) return true;
        if (h.matches("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$")) return true;
        return h.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\.\\d{1,3}\\.\\d{1,3}$");
    }

    public boolean isConfigured() {
        return serverUrl.length() > 0 && username.length() > 0 && password != null;
    }

    public String getStreamUrl(String songId) {
        return buildUrl("stream", "id=" + enc(songId) + "&format=mp3&maxBitRate=192&estimateContentLength=true");
    }

    public String getCoverArtUrl(String id, int size) {
        return buildUrl("getCoverArt", "id=" + enc(id) + "&size=" + size);
    }

    public String getDownloadUrl(String songId) {
        return buildUrl("download", "id=" + enc(songId));
    }

    private String buildUrl(String endpoint, String extra) {
        String salt = String.valueOf(System.currentTimeMillis());
        String token = md5(password + salt);
        String base = serverUrl + "/rest/" + endpoint
                + "?u=" + enc(username) + "&t=" + token + "&s=" + salt
                + "&v=1.16.1&c=SolarPlayer&f=json";
        return extra != null && extra.length() > 0 ? base + "&" + extra : base;
    }

    public void ping(final Callback<Boolean> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("ping", null));
                    final boolean ok = "ok".equals(root.getJSONObject("subsonic-response").getString("status"));
                    postSuccess(cb, ok);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getArtists(final android.content.Context ctx, final Callback<List<NavidromeArtist>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                final NavidromeCacheStore cache = NavidromeCacheStore.getInstance(ctx);
                final List<NavidromeArtist> cached = cache.loadArtists();
                final boolean hadCache = cached != null && !cached.isEmpty();
                if (hadCache) postSuccess(cb, cached);
                try {
                    JSONObject root = fetchJson(buildUrl("getArtists", null));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        if (!hadCache) postError(cb, extractError(root));
                        return;
                    }
                    final List<NavidromeArtist> artists = NavidromeCacheStore.parseArtistsJson(root);
                    cache.saveArtists(artists);
                    if (!hadCache || !artistListsEqual(cached, artists)) postSuccess(cb, artists);
                } catch (final Exception e) {
                    if (!hadCache) postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getArtistAlbums(final String artistId, final Callback<List<NavidromeAlbum>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("getArtist", "id=" + enc(artistId)));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    List<NavidromeAlbum> albums = new ArrayList<NavidromeAlbum>();
                    JSONObject artist = sr.getJSONObject("artist");
                    String artistName = artist.optString("name", "");
                    JSONArray arr = artist.optJSONArray("album");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            NavidromeAlbum al = new NavidromeAlbum();
                            al.id = o.getString("id");
                            al.name = o.getString("name");
                            al.artist = artistName;
                            al.year = o.optInt("year", 0);
                            al.songCount = o.optInt("songCount", 0);
                            al.coverArtId = o.optString("coverArt", null);
                            albums.add(al);
                        }
                    }
                    postSuccess(cb, albums);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /** 2026-07-06: All albums A–Z — library Albums browse parity. */
    public void getAlbumList(final Callback<List<NavidromeAlbum>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("getAlbumList2", "type=alphabeticalByName"));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    List<NavidromeAlbum> albums = new ArrayList<NavidromeAlbum>();
                    JSONObject list = sr.optJSONObject("albumList2");
                    JSONArray arr = list != null ? list.optJSONArray("album") : null;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            NavidromeAlbum al = new NavidromeAlbum();
                            al.id = o.getString("id");
                            al.name = o.optString("name", "");
                            al.artist = o.optString("artist", "");
                            al.year = o.optInt("year", 0);
                            al.songCount = o.optInt("songCount", 0);
                            al.coverArtId = o.optString("coverArt", null);
                            albums.add(al);
                        }
                    }
                    postSuccess(cb, albums);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /** 2026-07-06: Server playlists — library Playlists browse parity. */
    public void getPlaylists(final Callback<List<NavidromePlaylist>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("getPlaylists", null));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    List<NavidromePlaylist> out = new ArrayList<NavidromePlaylist>();
                    JSONObject playlists = sr.optJSONObject("playlists");
                    JSONArray arr = playlists != null ? playlists.optJSONArray("playlist") : null;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            NavidromePlaylist p = new NavidromePlaylist();
                            p.id = o.getString("id");
                            p.name = o.optString("name", "");
                            p.songCount = o.optInt("songCount", 0);
                            out.add(p);
                        }
                    }
                    postSuccess(cb, out);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getPlaylistSongs(final String playlistId, final Callback<List<NavidromeSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("getPlaylist", "id=" + enc(playlistId)));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    List<NavidromeSong> songs = new ArrayList<NavidromeSong>();
                    JSONObject playlist = sr.optJSONObject("playlist");
                    JSONArray arr = playlist != null ? playlist.optJSONArray("entry") : null;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            songs.add(parseSong(arr.getJSONObject(i)));
                        }
                    }
                    postSuccess(cb, songs);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /**
     * 2026-09-14: Heart the song — Subsonic star.view. Used by the Play/Pause hold on Now
     * Playing while a Navidrome stream is active. The server decides what a star means
     * (Navidrome: starred; nookTunes' shim: favourite + Get Music history + Hearts playlist).
     */
    public void star(final String songId, final Callback<Boolean> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("star", "id=" + enc(songId)));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    postSuccess(cb, Boolean.TRUE);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }
    /** 2026-07-06: Flat song catalog — search3 wildcard, songs only (cap 500). */
    public void getAllTracks(final Callback<List<NavidromeSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("search3", "query=" + enc("*")
                            + "&songCount=500&albumCount=0&artistCount=0"));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    List<NavidromeSong> songs = new ArrayList<NavidromeSong>();
                    JSONObject found = sr.optJSONObject("searchResult3");
                    JSONArray arr = found != null ? found.optJSONArray("song") : null;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            songs.add(parseSong(arr.getJSONObject(i)));
                        }
                    }
                    postSuccess(cb, songs);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    /** 2026-07-06: Unified search for library / Get Music append. */
    public void search(final String query, final Callback<NavidromeSearchResult> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("search3", "query=" + enc(query)
                            + "&songCount=40&albumCount=20&artistCount=20"));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    NavidromeSearchResult result = new NavidromeSearchResult();
                    JSONObject found = sr.optJSONObject("searchResult3");
                    if (found != null) {
                        JSONArray artists = found.optJSONArray("artist");
                        if (artists != null) {
                            for (int i = 0; i < artists.length(); i++) {
                                JSONObject o = artists.getJSONObject(i);
                                NavidromeArtist a = new NavidromeArtist();
                                a.id = o.optString("id", "");
                                a.name = o.optString("name", "");
                                a.albumCount = o.optInt("albumCount", 0);
                                a.coverArtId = o.optString("coverArt", null);
                                result.artists.add(a);
                            }
                        }
                        JSONArray albums = found.optJSONArray("album");
                        if (albums != null) {
                            for (int i = 0; i < albums.length(); i++) {
                                JSONObject o = albums.getJSONObject(i);
                                NavidromeAlbum al = new NavidromeAlbum();
                                al.id = o.optString("id", "");
                                al.name = o.optString("name", "");
                                al.artist = o.optString("artist", "");
                                al.year = o.optInt("year", 0);
                                al.songCount = o.optInt("songCount", 0);
                                al.coverArtId = o.optString("coverArt", null);
                                result.albums.add(al);
                            }
                        }
                        JSONArray songs = found.optJSONArray("song");
                        if (songs != null) {
                            for (int i = 0; i < songs.length(); i++) {
                                result.songs.add(parseSong(songs.getJSONObject(i)));
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

    private static NavidromeSong parseSong(JSONObject o) throws org.json.JSONException {
        NavidromeSong s = new NavidromeSong();
        s.id = o.getString("id");
        s.title = o.optString("title", "");
        s.artist = o.optString("artist", "");
        s.album = o.optString("album", "");
        s.durationSec = o.optInt("duration", 0);
        s.suffix = o.optString("suffix", "mp3");
        s.coverArtId = o.optString("coverArt", null);
        return s;
    }

    public void getAlbumSongs(final String albumId, final Callback<List<NavidromeSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("getAlbum", "id=" + enc(albumId)));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        postError(cb, extractError(root));
                        return;
                    }
                    List<NavidromeSong> songs = new ArrayList<NavidromeSong>();
                    JSONObject album = sr.getJSONObject("album");
                    String albumName = album.optString("name", "");
                    String artistName = album.optString("artist", "");
                    String albumCover = album.optString("coverArt", album.optString("id", ""));
                    JSONArray arr = album.optJSONArray("song");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            NavidromeSong s = new NavidromeSong();
                            s.id = o.getString("id");
                            s.title = o.optString("title", "");
                            s.artist = o.optString("artist", artistName);
                            s.album = o.optString("album", albumName);
                            s.durationSec = o.optInt("duration", 0);
                            s.suffix = o.optString("suffix", "mp3");
                            s.coverArtId = o.optString("coverArt", albumCover);
                            songs.add(s);
                        }
                    }
                    postSuccess(cb, songs);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    private static boolean artistListsEqual(List<NavidromeArtist> a, List<NavidromeArtist> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id.equals(b.get(i).id)) return false;
        }
        return true;
    }

    /** 2026-07-06: Shared Solar HTTP stack — TLS 1.2 + bundled CAs for https Navidrome hosts. */
    private static JSONObject fetchJson(String urlStr) throws Exception {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("url", urlStr != null ? urlStr : "");
            d.put("isHttp", urlStr != null && urlStr.startsWith("http://"));
            AgentDebugLog.log("NavidromeClient.fetchJson", "A", "before SolarHttp.getText", d);
        } catch (JSONException ignored) {}
        // #endregion
        try {
            return new JSONObject(SolarHttp.getText(urlStr));
        } catch (Exception e) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("url", urlStr != null ? urlStr : "");
                d.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                d.put("errorClass", e.getClass().getName());
                AgentDebugLog.log("NavidromeClient.fetchJson", "A", "SolarHttp.getText failed", d);
            } catch (JSONException ignored) {}
            // #endregion
            throw e;
        }
    }

    private static String extractError(JSONObject root) {
        try {
            return root.getJSONObject("subsonic-response").optString("error", "Navidrome error");
        } catch (Exception e) {
            return "Navidrome error";
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String enc(String s) {
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
