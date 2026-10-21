package com.solar.launcher.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 2026-10-21 — Q9: the Play/Pause hold menu's two nookTunes calls. Not for me
 * goes by the Discovery key when there is one, else by artist; Heart album
 * always sends both artist and album, encoded.
 */
public class DiscoveryHoldMenuTest {

    @Test
    public void heartAlbumEncodesArtistAndAlbum() {
        assertEquals("/api/albums/heart?artist=Tegan+and+Sara&album=So+Jealous+%26+More",
                DiscoveryClient.heartAlbumPath("Tegan and Sara", "So Jealous & More"));
    }

    @Test
    public void heartAlbumWithNoAlbumStillSendsTheField() {
        assertEquals("/api/albums/heart?artist=Robyn&album=",
                DiscoveryClient.heartAlbumPath("Robyn", null));
    }

    @Test
    public void notForMeUsesTheDiscoveryKeyWhenThereIsOne() {
        assertEquals("/api/discovery/not-for-me?key=abc%7C123",
                DiscoveryClient.notForMePath("abc|123", "Robyn"));
    }

    @Test
    public void notForMeFallsBackToTheArtist() {
        assertEquals("/api/discovery/not-for-me?artist=Bj%C3%B6rk",
                DiscoveryClient.notForMePath(null, "Björk"));
        assertEquals("/api/discovery/not-for-me?artist=Bj%C3%B6rk",
                DiscoveryClient.notForMePath("  ", " Björk "));
    }
}
