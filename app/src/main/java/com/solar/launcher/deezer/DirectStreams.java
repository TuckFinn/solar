package com.solar.launcher.deezer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-10-25 — Discovery songs Deezer does not have (Bandcamp-only picks), played
 * from nookTunes instead. They travel through the Deezer queue as a DeezerResult
 * with a negative stand-in id, so a run, its queue, the heart and play reports
 * work unchanged; only the download looks here and fetches the plain mp3 from
 * nookTunes rather than decrypting one from Deezer. In memory only: after a
 * restart an unfinished one fails like any download and the queue moves on.
 */
public final class DirectStreams {
    private static final ConcurrentHashMap<Long, String> URLS = new ConcurrentHashMap<Long, String>();

    private DirectStreams() {}

    /** A stable negative id for a Discovery track key (16 hex chars). */
    public static long idForKey(String key) {
        String hex = key == null ? "" : key.replaceAll("[^0-9a-fA-F]", "");
        if (hex.length() > 15) hex = hex.substring(0, 15);
        long v = hex.isEmpty() ? 1L : Long.parseLong(hex, 16);
        return -Math.max(1L, v);
    }

    public static void put(long id, String url) {
        if (id < 0 && url != null && !url.isEmpty()) URLS.put(Long.valueOf(id), url);
    }

    /** The nookTunes URL for a stand-in id, or null for a real Deezer track. */
    public static String get(long id) {
        return id < 0 ? URLS.get(Long.valueOf(id)) : null;
    }
}
