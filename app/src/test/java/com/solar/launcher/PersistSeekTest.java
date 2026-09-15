package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The resume position must never be one the track cannot contain.
 *
 * Regression for a reproduced 2Y1 run: skipping twice in quick succession
 * abandoned a Navidrome prepare, the shared MediaPlayer was left in Error
 * state, and getCurrentPosition() returned 1376183262 ms, about 15.9 days,
 * which was persisted as the queue's seekMs.
 */
public class PersistSeekTest {

    private static final int GARBAGE = 1376183262;   // the value actually saved

    @Test
    public void theReproducedGarbageNeverReachesDisk() {
        assertEquals(0, PersistSeek.sane(GARBAGE, 175000));
        assertEquals(0, PersistSeek.sane(GARBAGE, 0));
    }

    @Test
    public void ordinaryPositionsSurvive() {
        assertEquals(42000, PersistSeek.sane(42000, 175000));
        assertEquals(174999, PersistSeek.sane(174999, 175000));
    }

    @Test
    public void unknownStaysUnknown() {
        assertEquals(-1, PersistSeek.sane(-1, 175000));
        assertEquals(-1, PersistSeek.sane(-99, 0));
    }

    @Test
    public void startOfTrackIsAlwaysFine() {
        assertEquals(0, PersistSeek.sane(0, 0));
        assertEquals(0, PersistSeek.sane(0, 175000));
    }

    @Test
    public void aLittlePastTheEndIsToleratedButFarPastIsNot() {
        assertEquals(176000, PersistSeek.sane(176000, 175000));
        assertEquals(0, PersistSeek.sane(175000 + 60000, 175000));
    }

    @Test
    public void withoutADurationAnAbsoluteCeilingStillApplies() {
        assertEquals(3600000, PersistSeek.sane(3600000, 0));
        assertEquals(0, PersistSeek.sane(PersistSeek.MAX_SANE_MS + 1, 0));
    }
}
