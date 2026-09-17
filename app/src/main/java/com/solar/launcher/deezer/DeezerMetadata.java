package com.solar.launcher.deezer;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import com.solar.launcher.net.TlsHelper;

/** Persists Deezer API metadata for temp and saved files (keyed by absolute path). */
public final class DeezerMetadata {
    private static final String KEY_TITLE = "meta_title_";
    private static final String KEY_ARTIST = "meta_artist_";
    private static final String KEY_ALBUM = "meta_album_";
    private static final String KEY_COVER_URL = "meta_cover_url_";

    private DeezerMetadata() {}

    public static void saveForResult(Context ctx, File file, DeezerResult r) {
        if (ctx == null || file == null || r == null) return;
        saveMerged(ctx, file, r.title, r.artist, r.album, r.coverUrl);
    }

    public static void saveForTrackData(Context ctx, File file, DeezerTrackData track) {
        if (ctx == null || file == null || track == null) return;
        saveMerged(ctx, file, track.title, track.artist, track.album, track.albumPicture);
    }

    /** After download: merge scraped track fields with search result (non-empty wins). */
    public static void saveForTrackComplete(Context ctx, File file, DeezerResult result,
            DeezerTrackData track) {
        if (ctx == null || file == null) return;
        String title = pick(result != null ? result.title : "", track != null ? track.title : "");
        String artist = pick(result != null ? result.artist : "", track != null ? track.artist : "");
        String album = pick(result != null ? result.album : "", track != null ? track.album : "");
        String cover = "";
        if (track != null && track.albumPicture != null && !track.albumPicture.isEmpty()) {
            cover = track.albumPicture;
        } else if (result != null && result.coverUrl != null) {
            cover = result.coverUrl;
        }
        saveMerged(ctx, file, title, artist, album, cover);
    }

    private static void saveMerged(Context ctx, File file, String title, String artist,
            String album, String coverOrHash) {
        SharedPreferences prefs = prefs(ctx);
        String path = file.getAbsolutePath();
        String existingCover = prefs.getString(KEY_COVER_URL + path, "");
        String cover = DeezerCoverArt.bestCoverUrl(coverOrHash);
        if (cover.isEmpty()) cover = DeezerCoverArt.bestCoverUrl(existingCover);
        prefs.edit()
                .putString(KEY_TITLE + path, title != null ? title : "")
                .putString(KEY_ARTIST + path, artist != null ? artist : "")
                .putString(KEY_ALBUM + path, album != null ? album : "")
                .putString(KEY_COVER_URL + path, cover)
                .commit();
    }

    private static String pick(String fromResult, String fromTrack) {
        if (fromTrack != null && !fromTrack.trim().isEmpty()) return fromTrack.trim();
        if (fromResult != null && !fromResult.trim().isEmpty()) return fromResult.trim();
        return "";
    }

    /** Overlay Deezer prefs onto ID3-derived strings for library / browse. */
    public static void overlayFromPrefs(SharedPreferences prefs, String absolutePath,
            String[] titleArtistAlbum) {
        if (prefs == null || absolutePath == null || titleArtistAlbum == null
                || titleArtistAlbum.length < 3) return;
        if (!hasMetadata(prefs, absolutePath)) return;
        String t = title(prefs, absolutePath, "");
        String a = artist(prefs, absolutePath, "");
        String al = album(prefs, absolutePath, "");
        if (!t.isEmpty()) titleArtistAlbum[0] = t;
        if (!a.isEmpty()) titleArtistAlbum[1] = a;
        if (!al.isEmpty()) titleArtistAlbum[2] = al;
    }

    public static boolean hasMetadata(SharedPreferences prefs, String absolutePath) {
        if (prefs == null || absolutePath == null) return false;
        return prefs.contains(KEY_TITLE + absolutePath)
                || prefs.contains(KEY_ARTIST + absolutePath);
    }

    public static String title(SharedPreferences prefs, String path, String fallback) {
        if (prefs == null || path == null) return fallback;
        return prefs.getString(KEY_TITLE + path, fallback);
    }

    public static String artist(SharedPreferences prefs, String path, String fallback) {
        if (prefs == null || path == null) return fallback;
        return prefs.getString(KEY_ARTIST + path, fallback);
    }

    public static String album(SharedPreferences prefs, String path, String fallback) {
        if (prefs == null || path == null) return fallback;
        return prefs.getString(KEY_ALBUM + path, fallback);
    }

    public static String coverUrl(SharedPreferences prefs, String path) {
        if (prefs == null || path == null) return "";
        return DeezerCoverArt.bestCoverUrl(prefs.getString(KEY_COVER_URL + path, ""));
    }

    /**
     * Forget metadata for cached files that are no longer on disk.
     *
     * 2026-09-16 — this cache had no eviction of any kind. Four keys are
     * written per track, named after the file's absolute path, and nothing
     * ever removed them: DeezerCache.purgeUnreferenced deletes the audio and
     * leaves the metadata behind. On 2Y1 that was 480 of the 533 keys in
     * SOLAR_SETTINGS.xml, 89% of the file, and 82 of the 120 cached paths
     * named files that no longer exist at all -- most of them stranded when
     * the cache started naming files by Deezer id (40d9a73) rather than by
     * title. SharedPreferences rewrites the entire file on every commit, so
     * every future metadata write was copying 64 KB of dead entries, and a
     * settings wipe had that much more to destroy.
     *
     * Only paths directly inside cacheDir are considered. Metadata for saved
     * music must never be pruned this way: it lives on the sdcard, which can
     * be unmounted, and "the file is not there" would then mean "forget
     * everything the user saved".
     *
     * @return how many keys were dropped.
     */
    public static int pruneMissing(Context ctx, File cacheDir) {
        if (ctx == null || cacheDir == null) return 0;
        java.util.Set<String> present = new java.util.HashSet<String>();
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f != null) present.add(f.getAbsolutePath());
            }
        }
        SharedPreferences prefs = prefs(ctx);
        java.util.List<String> drop;
        try {
            drop = staleKeys(prefs.getAll().keySet(), cacheDir.getAbsolutePath(), present);
        } catch (Exception e) {
            return 0;
        }
        if (drop.isEmpty()) return 0;
        SharedPreferences.Editor ed = prefs.edit();
        for (String key : drop) ed.remove(key);
        ed.commit();
        return drop.size();
    }

    /**
     * Which of {@code allKeys} are metadata for a file directly inside
     * {@code cacheDir} that is not in {@code present}. Pure, so the decision
     * can be tested without a device.
     */
    static java.util.List<String> staleKeys(java.util.Set<String> allKeys,
            String cacheDir, java.util.Set<String> present) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (allKeys == null || cacheDir == null || present == null) return out;
        String prefix = cacheDir.endsWith(File.separator)
                ? cacheDir : cacheDir + File.separator;
        String[] keyPrefixes = {KEY_TITLE, KEY_ARTIST, KEY_ALBUM, KEY_COVER_URL};
        for (String key : allKeys) {
            if (key == null) continue;
            for (String kp : keyPrefixes) {
                if (!key.startsWith(kp)) continue;
                String path = key.substring(kp.length());
                // Directly inside the cache directory, not in a subdirectory
                // of it: a nested path is not something purgeUnreferenced
                // manages, so leave it alone.
                if (!path.startsWith(prefix)) break;
                if (path.indexOf(File.separatorChar, prefix.length()) != -1) break;
                if (!present.contains(path)) out.add(key);
                break;
            }
        }
        return out;
    }

    /** Drop prefs overlay after tags are embedded in the audio file. */
    public static void clearOverlay(Context ctx, String absolutePath) {
        if (ctx == null || absolutePath == null) return;
        SharedPreferences prefs = prefs(ctx);
        prefs.edit()
                .remove(KEY_TITLE + absolutePath)
                .remove(KEY_ARTIST + absolutePath)
                .remove(KEY_ALBUM + absolutePath)
                .remove(KEY_COVER_URL + absolutePath)
                .commit();
    }

    /** Cache high-res cover next to other album art for library + player. */
    public static void prefetchCoverFile(SharedPreferences prefs, File track, File coverFile) {
        if (prefs == null || track == null || coverFile == null) return;
        String url = coverUrl(prefs, track.getAbsolutePath());
        if (url.isEmpty()) return;
        if (coverFile.isFile() && coverFile.length() > 4096) return;
        try {
            File parent = coverFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            TlsHelper.ensureSecurityProvider();
            HttpURLConnection conn = (HttpURLConnection) new URL(url.replace("https://", "http://"))
                    .openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", DeezerClient.USER_AGENT);
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(coverFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.close();
            in.close();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        // 2026-09-16 — through PrefsWriteAudit like every other SOLAR_SETTINGS
        // caller. Going to ctx directly returns the same underlying instance
        // but not the wrapper, so four writes per cached track were invisible
        // to the audit that exists to name whoever rewrites this file.
        return com.solar.launcher.PrefsWriteAudit.get(ctx, DeezerAccount.PREFS_NAME);
    }
}
