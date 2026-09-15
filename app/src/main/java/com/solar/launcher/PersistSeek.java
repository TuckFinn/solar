package com.solar.launcher;

/**
 * Sanity for the resume position written into play_queue.json.
 *
 * A position is only meaningful if the track could actually contain it. An
 * Android 4.2 MediaPlayer left in Error state, which is what an abandoned
 * prepareAsync produces, answers getCurrentPosition() with nonsense rather
 * than throwing. On 2Y1 that reached disk as seekMs=1376183262, roughly 15.9
 * days, and would have been restored as a resume point on the next open.
 *
 * Its own class so it can be unit-tested: MainActivity cannot be loaded in a
 * plain JVM test.
 */
public final class PersistSeek {

    /** Nothing legitimate on this device runs longer than this. */
    static final int MAX_SANE_MS = 12 * 60 * 60 * 1000;

    private PersistSeek() {}

    /**
     * The seek worth saving.
     *
     * @param seekMs     position as reported, or negative when unknown
     * @param durationMs track length, or 0 when it is not known yet
     * @return seekMs when it can be real, -1 when it was already unknown,
     *         otherwise 0 — start of track, which is always safe to resume
     */
    public static int sane(int seekMs, int durationMs) {
        if (seekMs < 0) return -1;      // unknown, and the writer understands -1
        if (seekMs == 0) return 0;
        if (durationMs > 0) {
            // A little slack: a position may legitimately sit a touch past the
            // reported duration at the very end of a stream.
            return seekMs <= durationMs + 5000 ? seekMs : 0;
        }
        // Duration unknown, so fall back to an absolute ceiling.
        return seekMs <= MAX_SANE_MS ? seekMs : 0;
    }
}
