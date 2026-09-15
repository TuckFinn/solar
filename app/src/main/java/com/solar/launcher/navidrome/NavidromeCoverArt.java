package com.solar.launcher.navidrome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.solar.launcher.net.SolarHttp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2026-07-06: Async Subsonic coverArt fetch — browse preview + Now Playing art.
 */
public final class NavidromeCoverArt {

    public interface Listener {
        void onBitmap(Bitmap bmp);
        void onFailed();
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    private NavidromeCoverArt() {}

    /** 2026-07-06: Pick song art id, else album id for Subsonic getCoverArt. */
    public static String artIdForSong(NavidromeSong song, NavidromeAlbum album) {
        if (song != null && song.coverArtId != null && !song.coverArtId.isEmpty()) {
            return song.coverArtId;
        }
        if (album != null && album.coverArtId != null && !album.coverArtId.isEmpty()) {
            return album.coverArtId;
        }
        if (album != null && album.id != null && !album.id.isEmpty()) return album.id;
        if (song != null && song.id != null && !song.id.isEmpty()) return song.id;
        return "";
    }

    public static String artIdForAlbum(NavidromeAlbum album) {
        if (album == null) return "";
        if (album.coverArtId != null && !album.coverArtId.isEmpty()) return album.coverArtId;
        return album.id != null ? album.id : "";
    }

    public static String artIdForArtist(NavidromeArtist artist) {
        if (artist == null) return "";
        if (artist.coverArtId != null && !artist.coverArtId.isEmpty()) return artist.coverArtId;
        return artist.id != null ? artist.id : "";
    }

    public static void load(final String coverArtId, final int sizePx, final Listener listener) {
        if (coverArtId == null || coverArtId.isEmpty() || listener == null) {
            if (listener != null) listener.onFailed();
            return;
        }
        final String url = NavidromeClient.getInstance().getCoverArtUrl(coverArtId, sizePx);
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] raw = SolarHttp.getBytes(url, "image/*", "SolarLauncher/1.0");
                    if (raw == null || raw.length == 0) {
                        postFailed(listener);
                        return;
                    }
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    final Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                    if (bmp == null) {
                        postFailed(listener);
                        return;
                    }
                    // #region agent log
                    if (com.solar.launcher.debug.DebugGate.ON) {
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("coverArtId", coverArtId);
                        d.put("bytes", raw.length);
                        d.put("w", bmp.getWidth());
                        d.put("h", bmp.getHeight());
                        com.solar.launcher.debug.AgentDebugLog.log(
                                "NavidromeCoverArt.load", "E", "cover decoded", d);
                    } catch (Exception ignored) {}
                    }
                    // #endregion
                    postBitmap(listener, bmp);
                } catch (Exception e) {
                    postFailed(listener);
                }
            }
        });
    }

    private static void postBitmap(final Listener listener, final Bitmap bmp) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(new Runnable() {
            @Override public void run() {
                listener.onBitmap(bmp);
            }
        });
    }

    private static void postFailed(final Listener listener) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(new Runnable() {
            @Override public void run() {
                listener.onFailed();
            }
        });
    }
}
