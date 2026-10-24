package com.solar.launcher;

import org.junit.Test;

/** Stuck-at-end rescue timing (2Y1 sat on a finished song, 2026-10-24). */
public class StuckEndRescueTest {
    @Test
    public void startsTimingOnlyWhenStillAtTheEnd() {
        // At the end and not moving: timing starts now.
        if (MainActivity.stuckAtEndSinceForTest(125500, 125571, 125500, 0L, 1000L) != 1000L)
            throw new AssertionError("still at end should start the clock");
        // Keeps the original start while it stays stuck.
        if (MainActivity.stuckAtEndSinceForTest(125500, 125571, 125500, 1000L, 7000L) != 1000L)
            throw new AssertionError("should keep the first stuck time");
    }

    @Test
    public void movingOrMidSongIsNotStuck() {
        // Still moving near the end: the song is just finishing.
        if (MainActivity.stuckAtEndSinceForTest(125000, 125571, 124500, 1000L, 2000L) != 0L)
            throw new AssertionError("moving clock is not stuck");
        // Stopped mid-song (buffering): not this rescue's business.
        if (MainActivity.stuckAtEndSinceForTest(60000, 125571, 60000, 1000L, 9000L) != 0L)
            throw new AssertionError("mid-song stall is not end-of-song");
        // Unknown duration or first sample.
        if (MainActivity.stuckAtEndSinceForTest(0, 0, 0, 0L, 1000L) != 0L)
            throw new AssertionError("no duration");
        if (MainActivity.stuckAtEndSinceForTest(125500, 125571, -1, 0L, 1000L) != 0L)
            throw new AssertionError("first sample has nothing to compare");
    }
}
