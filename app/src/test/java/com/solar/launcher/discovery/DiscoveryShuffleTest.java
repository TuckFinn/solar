package com.solar.launcher.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** A Discovery run follows Solar's shuffle setting. */
public class DiscoveryShuffleTest {

    private static List<String> playlist(int n) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < n; i++) out.add("s" + i);
        return out;
    }

    private static Set<String> set(String... v) {
        return new HashSet<String>(Arrays.asList(v));
    }

    @Test
    public void inOrderPlaysWhatFollowsThePick() {
        List<String> p = playlist(6);
        assertEquals(Arrays.asList("s3", "s4", "s5"),
                DiscoveryScreen.pendingOrder(p, p.get(2), set("s2"), false, new Random(1)));
    }

    @Test
    public void shuffledDrawsFromTheWholePlaylist() {
        List<String> p = playlist(20);
        List<String> got = DiscoveryScreen.pendingOrder(p, p.get(5), set("s5"), true, new Random(7));
        assertEquals(19, got.size());
        assertFalse(got.contains("s5"));
        List<String> sorted = new ArrayList<String>(got);
        Collections.sort(sorted, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Integer.parseInt(a.substring(1)) - Integer.parseInt(b.substring(1));
            }
        });
        List<String> expected = new ArrayList<String>(p);
        expected.remove("s5");
        assertEquals(expected, sorted);
        // Not simply the playlist order with the pick taken out.
        assertNotEquals(expected, got);
    }

    @Test
    public void playedSongsAreNotPlayedAgain() {
        List<String> p = playlist(10);
        // Mid-run toggle: s1, s4, s7 already played, s4 playing.
        Set<String> queued = set("s1", "s4", "s7");
        List<String> shuffled = DiscoveryScreen.pendingOrder(p, p.get(4), queued, true, new Random(3));
        assertEquals(7, shuffled.size());
        for (String q : queued) assertFalse(shuffled.contains(q));
        // Switching back off continues in order after the song playing.
        assertEquals(Arrays.asList("s5", "s6", "s8", "s9"),
                DiscoveryScreen.pendingOrder(p, p.get(4), queued, false, new Random(3)));
    }

    @Test
    public void lastSongWithShuffleOffHasNothingAfterIt() {
        List<String> p = playlist(4);
        assertTrue(DiscoveryScreen.pendingOrder(p, p.get(3), set("s3"), false, new Random(1)).isEmpty());
    }
}
