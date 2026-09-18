package com.solar.launcher.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A Discovery run keeps a few songs queued behind the one playing, never the
 * whole playlist: at 100 songs that was minutes of searching before the second
 * song was queued and ~900 MB written to the Y1's /data.
 */
public class DiscoveryQueueAheadTest {

    private static final int AHEAD = DiscoveryScreen.QUEUE_AHEAD;

    @Test
    public void aFreshRunFillsTheWholeLookahead() {
        // Only the picked song is queued, and it is playing.
        assertEquals(AHEAD, DiscoveryScreen.songsWanted(1, 0));
    }

    @Test
    public void aFullLookaheadWantsNothing() {
        assertEquals(0, DiscoveryScreen.songsWanted(1 + AHEAD, 0));
    }

    @Test
    public void eachNewSongWantsOneMore() {
        // Picked song plus AHEAD queued; the next one starts.
        assertEquals(1, DiscoveryScreen.songsWanted(1 + AHEAD, 1));
    }

    @Test
    public void skippingAheadWantsTheGapBack() {
        // Jumped from the first song straight to the last one queued.
        assertEquals(AHEAD, DiscoveryScreen.songsWanted(1 + AHEAD, AHEAD));
    }

    @Test
    public void somethingElsePlayingLeavesTheQueueAlone() {
        assertEquals(0, DiscoveryScreen.songsWanted(1 + AHEAD, -1));
        assertEquals(0, DiscoveryScreen.songsWanted(1, -1));
    }

    @Test
    public void neverNegative() {
        assertEquals(0, DiscoveryScreen.songsWanted(1 + 2 * AHEAD, 0));
    }
}
