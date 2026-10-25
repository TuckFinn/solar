package com.solar.launcher.deezer;

import org.junit.Test;

/** Stand-in ids for nookTunes-served Discovery songs (2026-10-25). */
public class DirectStreamsTest {
    @Test
    public void idIsNegativeAndStable() {
        long a = DirectStreams.idForKey("35211bb50db6bfba");
        if (a >= 0) throw new AssertionError("must never collide with a Deezer id");
        if (a != DirectStreams.idForKey("35211bb50db6bfba")) throw new AssertionError("stable");
        if (a == DirectStreams.idForKey("4d7474d4dbfec229")) throw new AssertionError("distinct");
        if (DirectStreams.idForKey("") >= 0) throw new AssertionError("empty key still negative");
    }

    @Test
    public void onlyStandInIdsMapToUrls() {
        DirectStreams.put(-5L, "http://x/a.mp3");
        DirectStreams.put(5L, "http://x/b.mp3");
        if (!"http://x/a.mp3".equals(DirectStreams.get(-5L))) throw new AssertionError("lookup");
        if (DirectStreams.get(5L) != null) throw new AssertionError("real Deezer ids never redirect");
    }
}
