package com.solar.launcher.deezer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-09-18 — Which played Deezer songs give up their audio.
 *
 * A played song stays in the queue so Previous still reaches it, and its file
 * stayed in the stream cache until the queue was replaced: ~9 MB a song on the
 * Y1's 1 GB /data, so an afternoon of a 100-song Discovery playlist would have
 * filled it. Songs more than {@code keep} behind the play head are emptied
 * instead; going back to one downloads it again.
 */
public final class DeezerQueueTrim {

    private DeezerQueueTrim() {}

    /**
     * Queue positions whose audio can go.
     *
     * @param ids      the Deezer id at each queue position, 0 for anything else
     * @param head     the play head
     * @param keep     played songs left untouched directly behind the head
     * @param eligible the ids this may touch at all
     * @return positions more than {@code keep} behind the head holding an
     *         eligible id that is not also at or near the head -- the cache file
     *         is named by id, so a song queued twice shares one file, and
     *         emptying the old copy would empty the one about to play
     */
    public static List<Integer> releasable(long[] ids, int head, int keep, Set<Long> eligible) {
        List<Integer> out = new ArrayList<Integer>();
        if (ids == null || eligible == null || eligible.isEmpty()) return out;
        int cut = head - keep;
        if (cut <= 0) return out;
        Set<Long> near = new HashSet<Long>();
        for (int i = Math.max(0, cut); i < ids.length; i++) {
            if (ids[i] > 0) near.add(Long.valueOf(ids[i]));
        }
        for (int i = 0; i < cut && i < ids.length; i++) {
            Long id = Long.valueOf(ids[i]);
            if (ids[i] > 0 && eligible.contains(id) && !near.contains(id)) out.add(Integer.valueOf(i));
        }
        return out;
    }
}
