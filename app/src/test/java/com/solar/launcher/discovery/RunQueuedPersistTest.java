package com.solar.launcher.discovery;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** The run's queued ids round-trip through prefs (2026-10-26). */
public class RunQueuedPersistTest {
    @Test
    public void roundTripKeepsDeezerAndStandInIds() {
        Set<Long> ids = new HashSet<Long>();
        ids.add(3879751601L);
        ids.add(-123456789L);   // a nookTunes-served (Bandcamp) song
        Set<Long> back = DiscoveryScreen.decodeIds(DiscoveryScreen.encodeIds(ids));
        if (!back.equals(ids)) throw new AssertionError("round trip: " + back);
    }

    @Test
    public void junkAndEmptyAreIgnored() {
        if (!DiscoveryScreen.decodeIds("").isEmpty()) throw new AssertionError("empty");
        if (!DiscoveryScreen.decodeIds(null).isEmpty()) throw new AssertionError("null");
        Set<Long> got = DiscoveryScreen.decodeIds("12, x,0,,-7");
        if (got.size() != 2 || !got.contains(12L) || !got.contains(-7L)) {
            throw new AssertionError("parsed " + got);
        }
    }
}
