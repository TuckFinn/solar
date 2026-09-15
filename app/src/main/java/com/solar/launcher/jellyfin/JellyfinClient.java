package com.solar.launcher.jellyfin;

import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.debug.AgentDebugLog;
import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.net.TlsHelper;

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

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 2026-07-14: Jellyfin REST client — AuthenticateByName + Items browse + MP3 universal stream.
 */
public final class JellyfinClient {

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private static final String DEVICE_ID = "solar-launcher";
    private static final String AUTH_HEADER =
            "MediaBrowser Client=\"Solar\", Device=\"SolarPlayer\", DeviceId=\""
                    + DEVICE_ID + "\", Version=\"1.0\"";

    private static JellyfinClient instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private String serverUrl = "";
    private String username = "";
    private String password = "";
    private String accessToken = "";
    private String userId = "";

    private JellyfinClient() {}

    public static synchronized JellyfinClient getInstance() {
        if (instance == null) instance = new JellyfinClient();
        return instance;
    }

    /** 2026-07-14: Apply URL/user/pass and drop cached session token. */
    public void applySettings(String url, String user, String pass) {
        serverUrl = normalizeServerUrl(url);
        username = user != null ? user.trim() : "";
        password = pass != null ? pass : "";
        accessToken = "";
        userId = "";
        try {
            JSONObject d = new JSONObject();
            d.put("normalizedUrl", serverUrl);
            AgentDebugLog.log("JellyfinClient.applySettings", "B", "settings applied", d);
        } catch (JSONException ignored) {}
    }

    /**
     * 2026-07-14: Bare LAN host → http://host:8096 (Jellyfin default).
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
                u = new URL(protocol, host, 8096, file).toExternalForm();
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
        return serverUrl.length() > 0 && username.length() > 0;
    }

    public String getStreamUrl(String songId) {
        if (songId == null || songId.isEmpty()) return "";
        try {
            ensureAuth();
        } catch (Exception e) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("songIdLen", songId != null ? songId.length() : 0);
                d.put("authEx", e.getClass().getSimpleName());
                d.put("authMsg", e.getMessage() != null ? e.getMessage() : "");
                d.put("configured", isConfigured());
                d.put("serverLen", serverUrl != null ? serverUrl.length() : 0);
                com.solar.launcher.Debug8cf8b0Log.log(
                        "JellyfinClient.getStreamUrl", "auth failed → empty url", "A", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return "";
        }
        if (accessToken.isEmpty()) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("songIdLen", songId.length());
                d.put("userIdLen", userId != null ? userId.length() : 0);
                com.solar.launcher.Debug8cf8b0Log.log(
                        "JellyfinClient.getStreamUrl", "empty token after auth", "A", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            return "";
        }
        String url = buildUniversalStreamUrl(serverUrl, userId, accessToken, songId);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("songIdLen", songId.length());
            d.put("userIdLen", userId != null ? userId.length() : 0);
            d.put("tokenLen", accessToken.length());
            d.put("urlLen", url != null ? url.length() : 0);
            d.put("hasUniversal", url != null && url.contains("/universal"));
            d.put("hasMp3", url != null && url.contains("AudioCodec=mp3"));
            int schemeEnd = url != null ? url.indexOf("://") : -1;
            int pathStart = url != null ? url.indexOf('/', Math.max(schemeEnd + 3, 0)) : -1;
            d.put("urlHostPath", url != null && pathStart > 0
                    ? url.substring(0, Math.min(pathStart + 40, url.length())) : "");
            com.solar.launcher.Debug8cf8b0Log.log(
                    "JellyfinClient.getStreamUrl", "built universal url", "B", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return url;
    }

    /**
     * 2026-07-14: MP3 universal Audio URL (MediaPlayer-safe) without hitting the network.
     * Had: none. Now: mirrors /Audio/{id}/universal query used at play time.
     */
    static String buildUniversalStreamUrl(String server, String uid, String token, String songId) {
        if (songId == null || songId.isEmpty() || server == null || server.isEmpty()) return "";
        String t = token != null ? token : "";
        String u = uid != null ? uid : "";
        return server + "/Audio/" + encPath(songId) + "/universal"
                + "?UserId=" + enc(u)
                + "&DeviceId=" + enc(DEVICE_ID)
                + "&Container=mp3&AudioCodec=mp3&MaxStreamingBitrate=192000"
                + "&api_key=" + enc(t);
    }

    public String getCoverArtUrl(String id, int size) {
        if (id == null || id.isEmpty()) return "";
        try {
            ensureAuth();
        } catch (Exception e) {
            return "";
        }
        if (accessToken.isEmpty()) return "";
        return serverUrl + "/Items/" + encPath(id) + "/Images/Primary"
                + "?maxWidth=" + size + "&api_key=" + enc(accessToken);
    }

    public String getDownloadUrl(String songId) {
        return getStreamUrl(songId);
    }

    public String getDownloadUrl(JellyfinSong song) {
        return song != null ? getDownloadUrl(song.id) : "";
    }

    public void ping(final Callback<Boolean> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    fetchJson(apiUrl("/Users/Me", null));
                    postSuccess(cb, true);
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getArtists(final android.content.Context ctx, final Callback<List<JellyfinArtist>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                final JellyfinCacheStore cache = JellyfinCacheStore.getInstance(ctx);
                final List<JellyfinArtist> cached = cache.loadArtists();
                final boolean hadCache = cached != null && !cached.isEmpty();
                if (hadCache) postSuccess(cb, cached);
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Artists",
                            "userId=" + enc(userId) + "&Limit=500&SortBy=SortName"));
                    List<JellyfinArtist> artists = parseArtistItems(root);
                    cache.saveArtists(artists);
                    if (!hadCache || !artistListsEqual(cached, artists)) postSuccess(cb, artists);
                } catch (final Exception e) {
                    if (!hadCache) postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getArtistAlbums(final String artistId, final Callback<List<JellyfinAlbum>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Items",
                            "ParentId=" + enc(artistId)
                                    + "&IncludeItemTypes=MusicAlbum&Recursive=true"
                                    + "&SortBy=SortName&UserId=" + enc(userId)));
                    postSuccess(cb, parseAlbums(root));
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getAlbumList(final Callback<List<JellyfinAlbum>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Items",
                            "IncludeItemTypes=MusicAlbum&Recursive=true"
                                    + "&SortBy=SortName&Limit=500&UserId=" + enc(userId)));
                    postSuccess(cb, parseAlbums(root));
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getAlbumSongs(final String albumId, final Callback<List<JellyfinSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Items",
                            "ParentId=" + enc(albumId)
                                    + "&IncludeItemTypes=Audio&SortBy=IndexNumber"
                                    + "&UserId=" + enc(userId)));
                    postSuccess(cb, parseSongs(root));
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getPlaylists(final Callback<List<JellyfinPlaylist>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Users/" + encPath(userId) + "/Items",
                            "IncludeItemTypes=Playlist&Recursive=true&SortBy=SortName"));
                    List<JellyfinPlaylist> out = new ArrayList<JellyfinPlaylist>();
                    JSONArray items = root.optJSONArray("Items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject o = items.getJSONObject(i);
                            JellyfinPlaylist p = new JellyfinPlaylist();
                            p.id = o.optString("Id");
                            p.name = o.optString("Name");
                            p.songCount = o.optInt("ChildCount", 0);
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

    public void getPlaylistSongs(final String playlistId, final Callback<List<JellyfinSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Playlists/" + encPath(playlistId) + "/Items",
                            "UserId=" + enc(userId)));
                    postSuccess(cb, parseSongs(root));
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void getAllTracks(final Callback<List<JellyfinSong>> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Items",
                            "IncludeItemTypes=Audio&Recursive=true"
                                    + "&SortBy=SortName&Limit=500&UserId=" + enc(userId)));
                    postSuccess(cb, parseSongs(root));
                } catch (final Exception e) {
                    postError(cb, e.getMessage());
                }
            }
        });
    }

    public void search(final String query, final Callback<JellyfinSearchResult> cb) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureAuth();
                    JSONObject root = fetchJson(apiUrl("/Users/" + encPath(userId) + "/Items",
                            "SearchTerm=" + enc(query)
                                    + "&IncludeItemTypes=Audio,MusicAlbum,MusicArtist"
                                    + "&Recursive=true&Limit=40"));
                    JellyfinSearchResult result = new JellyfinSearchResult();
                    JSONArray items = root.optJSONArray("Items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject o = items.getJSONObject(i);
                            String type = o.optString("Type");
                            if ("MusicArtist".equals(type)) {
                                JellyfinArtist a = new JellyfinArtist();
                                a.id = o.optString("Id");
                                a.name = o.optString("Name");
                                a.coverArtId = a.id;
                                result.artists.add(a);
                            } else if ("MusicAlbum".equals(type)) {
                                result.albums.add(parseAlbum(o));
                            } else if ("Audio".equals(type)) {
                                result.songs.add(parseSong(o));
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

    /** Login when token missing — POST AuthenticateByName with Emby auth header. */
    private synchronized void ensureAuth() throws Exception {
        if (accessToken.length() > 0 && userId.length() > 0) return;
        if (!isConfigured()) throw new Exception("Jellyfin not configured");
        JSONObject body = new JSONObject();
        body.put("Username", username);
        body.put("Pw", password != null ? password : "");
        TlsHelper.ensureSecurityProvider();
        OkHttpClient client = TlsHelper.client();
        Request req = new Request.Builder()
                .url(serverUrl + "/Users/AuthenticateByName")
                .header("Content-Type", "application/json")
                .header("X-Emby-Authorization", AUTH_HEADER)
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),
                        body.toString()))
                .build();
        Response resp = client.newCall(req).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("Auth failed HTTP " + resp.code());
            }
            JSONObject root = new JSONObject(new String(resp.body().bytes(), "UTF-8"));
            accessToken = root.optString("AccessToken", "");
            JSONObject user = root.optJSONObject("User");
            userId = user != null ? user.optString("Id", "") : "";
            if (accessToken.isEmpty() || userId.isEmpty()) {
                throw new Exception("Auth response missing token");
            }
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    private String apiUrl(String path, String extra) {
        String base = serverUrl + path + (path.contains("?") ? "&" : "?")
                + "api_key=" + enc(accessToken);
        return extra != null && extra.length() > 0 ? base + "&" + extra : base;
    }

    private static List<JellyfinArtist> parseArtistItems(JSONObject root) throws Exception {
        List<JellyfinArtist> artists = new ArrayList<JellyfinArtist>();
        JSONArray items = root.optJSONArray("Items");
        if (items == null) return artists;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            JellyfinArtist a = new JellyfinArtist();
            a.id = o.optString("Id");
            a.name = o.optString("Name");
            a.albumCount = o.optInt("AlbumCount", 0);
            a.coverArtId = a.id;
            String name = a.name != null ? a.name : "";
            char c = name.length() > 0 ? Character.toUpperCase(name.charAt(0)) : '#';
            a.indexLetter = (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
            artists.add(a);
        }
        return artists;
    }

    private static List<JellyfinAlbum> parseAlbums(JSONObject root) throws Exception {
        List<JellyfinAlbum> albums = new ArrayList<JellyfinAlbum>();
        JSONArray items = root.optJSONArray("Items");
        if (items == null) return albums;
        for (int i = 0; i < items.length(); i++) {
            albums.add(parseAlbum(items.getJSONObject(i)));
        }
        return albums;
    }

    private static JellyfinAlbum parseAlbum(JSONObject o) {
        JellyfinAlbum al = new JellyfinAlbum();
        al.id = o.optString("Id");
        al.name = o.optString("Name");
        al.artist = o.optString("AlbumArtist", "");
        if (al.artist.isEmpty()) {
            JSONArray aa = o.optJSONArray("AlbumArtists");
            if (aa != null && aa.length() > 0) {
                al.artist = aa.optJSONObject(0).optString("Name", "");
            }
        }
        al.year = o.optInt("ProductionYear", 0);
        al.songCount = o.optInt("ChildCount", 0);
        al.coverArtId = al.id;
        return al;
    }

    private static List<JellyfinSong> parseSongs(JSONObject root) throws Exception {
        List<JellyfinSong> songs = new ArrayList<JellyfinSong>();
        JSONArray items = root.optJSONArray("Items");
        if (items == null) return songs;
        for (int i = 0; i < items.length(); i++) {
            songs.add(parseSong(items.getJSONObject(i)));
        }
        return songs;
    }

    private static JellyfinSong parseSong(JSONObject o) {
        JellyfinSong s = new JellyfinSong();
        s.id = o.optString("Id");
        s.title = o.optString("Name", "");
        // 2026-07-14: Artists is a JSON string array on Jellyfin Audio items.
        JSONArray artistsArr = o.optJSONArray("Artists");
        if (artistsArr != null && artistsArr.length() > 0) {
            s.artist = artistsArr.optString(0, "");
        } else {
            s.artist = o.optString("AlbumArtist", "");
        }
        s.album = o.optString("Album", "");
        long ticks = o.optLong("RunTimeTicks", 0);
        s.durationSec = (int) (ticks / 10000000L);
        s.coverArtId = o.optString("AlbumId", s.id);
        s.suffix = "mp3";
        return s;
    }

    private static boolean artistListsEqual(List<JellyfinArtist> a, List<JellyfinArtist> b) {
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

    /** Path segment encode — keep UUID slashes unencoded by using URLEncoder then fixing. */
    private static String encPath(String s) {
        return enc(s).replace("+", "%20");
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
