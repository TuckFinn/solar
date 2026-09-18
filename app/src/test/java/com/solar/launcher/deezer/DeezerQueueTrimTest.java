package com.solar.launcher.deezer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeezerQueueTrimTest {

    private static Set<Long> ids(long... v) {
        Set<Long> s = new HashSet<Long>();
        for (long x : v) s.add(Long.valueOf(x));
        return s;
    }

    private static List<Integer> at(Integer... v) {
        return Arrays.asList(v);
    }

    @Test
    public void keepsTheLastFewPlayed() {
        long[] q = {11, 12, 13, 14, 15, 16, 17};
        // Playing 16 (index 5); keep 3 behind (13, 14, 15): only 11 and 12 go.
        assertEquals(at(0, 1), DeezerQueueTrim.releasable(q, 5, 3, ids(11, 12, 13, 14, 15, 16, 17)));
    }

    @Test
    public void nothingToReleaseEarlyInARun() {
        long[] q = {11, 12, 13};
        assertEquals(at(), DeezerQueueTrim.releasable(q, 2, 3, ids(11, 12, 13)));
        assertEquals(at(), DeezerQueueTrim.releasable(q, 3, 3, ids(11, 12, 13)));
    }

    @Test
    public void onlyTouchesEligibleSongs() {
        // Index 0 is a Navidrome item (0), index 1 a Get Music track not Discovery's.
        long[] q = {0, 99, 21, 22, 23, 24, 25, 26};
        assertEquals(at(2), DeezerQueueTrim.releasable(q, 6, 3, ids(21, 22, 23, 24, 25, 26)));
    }

    @Test
    public void neverEmptiesAFileTheHeadStillNeeds() {
        // 31 was played long ago and is queued again just ahead: one file, keep it.
        long[] q = {31, 32, 33, 34, 35, 36, 31};
        assertEquals(at(1), DeezerQueueTrim.releasable(q, 5, 3, ids(31, 32, 33, 34, 35, 36)));
    }

    @Test
    public void emptyEligibleSetReleasesNothing() {
        long[] q = {1, 2, 3, 4, 5, 6};
        assertEquals(at(), DeezerQueueTrim.releasable(q, 5, 1, ids()));
    }
}
