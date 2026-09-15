package com.solar.launcher;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * thesolarproject/solar#77 — "Invalid index N, size is M" when skipping a mixed queue.
 *
 * musicIndex() counts every music-like slot, server streams included, while musicFiles()
 * used to list file-backed slots only. Any queue mixing a Navidrome/Plex/Jellyfin stream
 * with a file-backed track therefore left the two lists misaligned: musicPlaylist().get(
 * musicIndex()) read the wrong file, then threw once the ordinal ran past the shorter list.
 * Server-stream slots now contribute a placeholder File so the lists stay index-aligned.
 *
 * These run on the JVM: the bug is pure index arithmetic, so it needs no device and no audio.
 */
public class PlayQueueMixedIndexTest {

    /** Navidrome, file, Navidrome, file — the shape that crashed. */
    private static List<PlayQueue.QueueItem> mixed() {
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.navidrome("nd-1", "Stream One", "Artist", "Album", "c1"));
        items.add(PlayQueue.QueueItem.music(new File("/storage/sdcard0/Music/a.mp3")));
        items.add(PlayQueue.QueueItem.navidrome("nd-2", "Stream Two", "Artist", "Album", "c2"));
        items.add(PlayQueue.QueueItem.music(new File("/storage/sdcard0/Music/b.mp3")));
        return items;
    }

    private static boolean isStreamKind(PlayQueue.QueueItem q) {
        return q.kind == PlayQueue.ItemKind.NAVIDROME_STREAM
                || q.kind == PlayQueue.ItemKind.PLEX_STREAM
                || q.kind == PlayQueue.ItemKind.JELLYFIN_STREAM;
    }

    @Test
    public void musicFilesHasOneEntryPerMusicLikeSlot() {
        PlayQueue q = new PlayQueue();
        q.setAll(mixed(), 0);
        assertEquals("every music-like slot must contribute exactly one entry",
                q.musicLikeCount(), q.musicFiles().size());
        assertEquals(4, q.musicFiles().size());
    }

    /**
     * The minimal #77 repro. Before the fix musicFiles() held one entry while the play-head
     * sat at music ordinal 1, so this get() threw "Invalid index 1, size is 1".
     */
    @Test
    public void streamThenFile_playHeadOnTheFileResolves() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.navidrome("nd-1", "Stream", "Artist", "Album", "c"));
        File onDisk = new File("/storage/sdcard0/Music/a.mp3");
        items.add(PlayQueue.QueueItem.music(onDisk));
        pc.unifiedQueue().setAll(items, 1);

        assertEquals(1, pc.musicIndex());
        assertEquals(2, pc.musicPlaylist().size());
        assertEquals(onDisk, pc.musicPlaylist().get(pc.musicIndex()));
    }

    /** Walking the whole queue must never run the music ordinal past the list. */
    @Test
    public void skippingThroughAMixedQueueStaysInRange() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<PlayQueue.QueueItem> items = mixed();
        pc.unifiedQueue().setAll(items, 0);

        for (int i = 0; i < items.size(); i++) {
            pc.unifiedQueue().setIndex(i);
            List<File> playlist = pc.musicPlaylist();
            int mi = pc.musicIndex();
            assertTrue("music ordinal " + mi + " out of range for size " + playlist.size()
                    + " at queue slot " + i, mi >= 0 && mi < playlist.size());
        }
    }

    /** Each file-backed slot must resolve to its OWN file, not merely to something in range. */
    @Test
    public void everyFileSlotResolvesToItsOwnFile() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<PlayQueue.QueueItem> items = mixed();
        pc.unifiedQueue().setAll(items, 0);

        for (int i = 0; i < items.size(); i++) {
            PlayQueue.QueueItem q = items.get(i);
            if (q.kind != PlayQueue.ItemKind.MUSIC_FILE) continue;
            pc.unifiedQueue().setIndex(i);
            assertEquals("queue slot " + i + " resolved to the wrong file",
                    q.file, pc.musicPlaylist().get(pc.musicIndex()));
        }
    }

    /** Stream slots resolve to placeholders: recognisable, and never a real path on disk. */
    @Test
    public void streamSlotsResolveToPlaceholdersThatAreNotRealFiles() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<PlayQueue.QueueItem> items = mixed();
        pc.unifiedQueue().setAll(items, 0);

        for (int i = 0; i < items.size(); i++) {
            pc.unifiedQueue().setIndex(i);
            File resolved = pc.musicPlaylist().get(pc.musicIndex());
            if (isStreamKind(items.get(i))) {
                assertTrue("stream slot " + i + " should hold a placeholder: " + resolved,
                        PlayQueue.isStreamPlaceholder(resolved));
                assertFalse("a placeholder must not exist on disk: " + resolved,
                        resolved.exists());
            } else {
                assertFalse("file slot " + i + " must not look like a placeholder: " + resolved,
                        PlayQueue.isStreamPlaceholder(resolved));
            }
        }
    }

    /** Two streams must not collapse onto one placeholder, or skip would land on the wrong row. */
    @Test
    public void distinctStreamsGetDistinctPlaceholders() {
        PlayQueue q = new PlayQueue();
        q.setAll(mixed(), 0);
        List<File> playlist = q.musicFiles();
        assertFalse("separate Navidrome songs share a placeholder path",
                playlist.get(0).equals(playlist.get(2)));
    }

    /** Non-music slots stay out of the music list, and out of the ordinal. */
    @Test
    public void nonMusicSlotsDoNotContribute() {
        PlayQueue q = new PlayQueue();
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.navidrome("nd-1", "Stream", "Artist", "Album", "c"));
        items.add(PlayQueue.QueueItem.fmStation(94900, "FM 94.9"));
        items.add(PlayQueue.QueueItem.music(new File("/storage/sdcard0/Music/a.mp3")));
        q.setAll(items, 0);

        assertEquals(2, q.musicLikeCount());
        assertEquals(2, q.musicFiles().size());
    }

    /** An all-stream queue is still index-aligned; nothing here is file-backed. */
    @Test
    public void allStreamQueueIsAligned() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<PlayQueue.QueueItem> items = new ArrayList<PlayQueue.QueueItem>();
        items.add(PlayQueue.QueueItem.navidrome("nd-1", "One", "Artist", "Album", "c1"));
        items.add(PlayQueue.QueueItem.navidrome("nd-2", "Two", "Artist", "Album", "c2"));
        items.add(PlayQueue.QueueItem.navidrome("nd-3", "Three", "Artist", "Album", "c3"));
        pc.unifiedQueue().setAll(items, 2);

        assertEquals(3, pc.musicPlaylist().size());
        assertEquals(2, pc.musicIndex());
        assertTrue(PlayQueue.isStreamPlaceholder(pc.musicPlaylist().get(pc.musicIndex())));
    }
}
