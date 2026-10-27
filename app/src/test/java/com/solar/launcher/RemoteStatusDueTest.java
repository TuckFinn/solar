package com.solar.launcher;

import org.junit.Test;

/** When a Y1 reports its status to nookTunes (2026-10-27). */
public class RemoteStatusDueTest {
    @Test
    public void changeSendsAtOnce() {
        if (!MainActivity.remoteStatusDueForTest(true, false, 0)) throw new AssertionError("change");
    }

    @Test
    public void playingEveryTenSecondsIdleEveryMinute() {
        if (MainActivity.remoteStatusDueForTest(false, true, 9000)) throw new AssertionError("too soon playing");
        if (!MainActivity.remoteStatusDueForTest(false, true, 10000)) throw new AssertionError("playing tick");
        if (MainActivity.remoteStatusDueForTest(false, false, 30000)) throw new AssertionError("too soon idle");
        if (!MainActivity.remoteStatusDueForTest(false, false, 60000)) throw new AssertionError("idle tick");
    }
}
