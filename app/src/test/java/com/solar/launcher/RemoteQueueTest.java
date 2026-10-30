package com.solar.launcher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 2026-10-30 — The Flipper's Queue edits a song by its key, found nearest the
 * place it was shown at: the queue can move between seeing it and editing it.
 */
public class RemoteQueueTest {

    private static PlayQueue.QueueItem nd(String id) {
        return PlayQueue.QueueItem.navidrome(id, "T" + id, "A", "B", null);
    }

    private static PlayQueue queue(String... ids) {
        PlayQueue q = new PlayQueue();
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        for (String id : ids) items.add(nd(id));
        q.setAll(items, 0);
        return q;
    }

    @Test
    public void keysNameTheSong() {
        assertEquals("nd:abc", MainActivity.remoteQueueKey(nd("abc")));
        assertEquals("dz:42", MainActivity.remoteQueueKey(PlayQueue.QueueItem.deezer(null, "A - T", 42L)));
    }

    @Test
    public void findsTheSongWhereverItMoved() {
        PlayQueue q = queue("a", "b", "c", "d");
        assertEquals(2, MainActivity.remoteQueueFind(q, "nd:c", 0));
        q.move(2, 0);                                   // c went to the top meanwhile
        assertEquals(0, MainActivity.remoteQueueFind(q, "nd:c", 2));
    }

    @Test
    public void aRepeatedSongIsTheOneNearestWhereItWasShown() {
        PlayQueue q = queue("a", "x", "b", "c", "x");
        assertEquals(4, MainActivity.remoteQueueFind(q, "nd:x", 4));
        assertEquals(1, MainActivity.remoteQueueFind(q, "nd:x", 0));
    }

    @Test
    public void aSongThatLeftIsNotFound() {
        assertEquals(-1, MainActivity.remoteQueueFind(queue("a", "b"), "nd:z", 1));
        assertEquals(-1, MainActivity.remoteQueueFind(queue("a"), "", 0));
    }
}
