package com.solar.launcher.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 2026-10-21 — Q9: the Play/Pause hold menu's nookTunes calls. Not for me
 * goes by the Discovery key when there is one, else by artist; the hearted
 * lookup always sends both artist and title, encoded.
 */
public class DiscoveryHoldMenuTest {

    @Test
    public void heartedEncodesArtistAndTitle() {
        assertEquals("/api/y1/hearted?artist=Tegan+and+Sara&title=Walking+with+a+Ghost+%26+More",
                DiscoveryClient.heartedPath(" Tegan and Sara ", "Walking with a Ghost & More"));
    }

    @Test
    public void heartedWithNoTitleStillSendsTheField() {
        assertEquals("/api/y1/hearted?artist=Robyn&title=",
                DiscoveryClient.heartedPath("Robyn", null));
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
