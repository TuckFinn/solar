package com.solar.launcher.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A run song that gives way to the next is judged skipped or heard out, so the
 * rotation can tell a song turned off after three seconds from one played
 * through.
 */
public class DiscoveryOutcomeTest {

    private static final String SKIP = DiscoveryClient.OUTCOME_SKIP;
    private static final String FULL = DiscoveryClient.OUTCOME_FULL;

    @Test
    public void aFewSecondsIsASkip() {
        assertEquals(SKIP, DiscoveryScreen.outcome(3000L, 200));
    }

    @Test
    public void underThirtySecondsIsASkipEvenForAShortSong() {
        // 29 s of a 60 s song is past 35%, but still under the 30 s floor.
        assertEquals(SKIP, DiscoveryScreen.outcome(29000L, 60));
    }

    @Test
    public void underAThirdOfALongSongIsASkip() {
        // 60 s of a 240 s song: past 30 s, but only 25%.
        assertEquals(SKIP, DiscoveryScreen.outcome(60000L, 240));
    }

    @Test
    public void pastBothThresholdsIsHeardOut() {
        assertEquals(FULL, DiscoveryScreen.outcome(90000L, 240));
    }

    @Test
    public void anUnknownLengthFallsBackToTheThirtySecondFloor() {
        assertEquals(SKIP, DiscoveryScreen.outcome(20000L, 0));
        assertEquals(FULL, DiscoveryScreen.outcome(31000L, 0));
    }
}
