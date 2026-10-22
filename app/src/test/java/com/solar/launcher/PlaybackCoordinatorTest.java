package com.solar.launcher;

import com.solar.launcher.navidrome.NavidromeSong;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class PlaybackCoordinatorTest {

    private static List<File> files(String... names) {
        List<File> out = new ArrayList<File>();
        for (String n : names) out.add(new File("/music/" + n));
        return out;
    }

    @Test
    public void moveMusicTrack_updatesIndexAndSyncsOriginalOrder() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<File> playlist = files("a", "b", "c", "d");
        pc.activateMusic(playlist, 2, false);
        assertEquals(2, pc.musicIndex());

        pc.moveMusicTrack(2, 0);
        assertEquals(0, pc.musicIndex());
        assertSame(playlist.get(2), pc.musicPlaylist().get(0));
        assertEquals(pc.musicPlaylist(), pc.musicOriginal());

        pc.moveMusicTrack(0, 3);
        assertEquals(3, pc.musicIndex());
        assertEquals(pc.musicPlaylist(), pc.musicOriginal());
    }

    @Test
    public void formatTrackPosition_neverInfinity() {
        if (!"— / —".equals(PlaybackCoordinator.formatTrackPosition(0, 0))) {
            throw new AssertionError("empty queue");
        }
        if (!"01 / 03".equals(PlaybackCoordinator.formatTrackPosition(0, 3))) {
            throw new AssertionError("first track");
        }
        if (!"03 / 03".equals(PlaybackCoordinator.formatTrackPosition(99, 3))) {
            throw new AssertionError("clamp high index");
        }
    }

    @Test
    public void formatTrackPositionPlain_noLeadingZeros() {
        if (!"— / —".equals(PlaybackCoordinator.formatTrackPositionPlain(0, 0))) {
            throw new AssertionError("empty queue");
        }
        if (!"1 / 3".equals(PlaybackCoordinator.formatTrackPositionPlain(0, 3))) {
            throw new AssertionError("first track");
        }
        if (!"3 / 3".equals(PlaybackCoordinator.formatTrackPositionPlain(99, 3))) {
            throw new AssertionError("clamp high index");
        }
    }

    @Test
    public void activateMusic_emptyClearsMode() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        pc.activateMusic(files("a"), 0, false);
        pc.activateMusic(new ArrayList<File>(), 0, false);
        if (pc.isMusicActive()) throw new AssertionError("empty should clear music mode");
    }

    /** 2026-07-06: Navidrome album play replaces queue — mirrors activateMusic contract. */
    @Test
    public void activateNavidrome_replacesQueueAndStartIndex() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<NavidromeSong> songs = new ArrayList<NavidromeSong>();
        NavidromeSong a = new NavidromeSong();
        a.id = "1";
        a.title = "One";
        NavidromeSong b = new NavidromeSong();
        b.id = "2";
        b.title = "Two";
        NavidromeSong c = new NavidromeSong();
        c.id = "3";
        c.title = "Three";
        songs.add(a);
        songs.add(b);
        songs.add(c);
        pc.activateNavidrome(songs, 1, false, "Album");
        assertEquals(3, pc.unifiedQueue().size());
        assertEquals(1, pc.musicIndex());
        assertEquals(3, pc.musicSlotCount());
        assertEquals("Album", pc.musicActivePlaylistName());
        PlayQueue.QueueItem cur = pc.currentItem();
        if (cur == null || cur.kind != PlayQueue.ItemKind.NAVIDROME_STREAM) {
            throw new AssertionError("expected navidrome stream head");
        }
        assertEquals("2", cur.navidromeSongId);
    }

    /**
     * 2026-07-20 — musicIndex must follow the play-head slot, not the first equal file.
     * Layman: two copies of the same song in a queue; skip to the second — NP says track 2.
     * Was: musicLikeSame first-match pinned index to the earlier twin (stale NP / wrong prepare).
     */
    @Test
    public void musicIndex_usesQueueSlotNotFirstFileMatch() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        File twin = new File("/music/same.mp3");
        List<File> playlist = new ArrayList<File>();
        playlist.add(twin);
        playlist.add(new File("/music/other.mp3"));
        playlist.add(twin);
        pc.activateMusic(playlist, 0, false);
        assertEquals(0, pc.musicIndex());
        assertEquals(0, pc.unifiedQueue().index());
        pc.setQueueIndex(2);
        assertEquals("second twin must be music slot 2, not 0", 2, pc.musicIndex());
        assertSame(twin, pc.musicPlaylist().get(pc.musicIndex()));
    }

    /**
     * 2026-07-20 — activateMusic(startIndex) must land on that row when files are duplicated.
     * Was: equals() last-match set qStart to the later twin.
     */
    @Test
    public void activateMusic_startIndexHonorsSlotWithDuplicateFiles() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        File twin = new File("/music/same.mp3");
        List<File> playlist = new ArrayList<File>();
        playlist.add(twin);
        playlist.add(new File("/music/other.mp3"));
        playlist.add(twin);
        pc.activateMusic(playlist, 0, false);
        assertEquals(0, pc.unifiedQueue().index());
        pc.activateMusic(playlist, 2, false);
        assertEquals(2, pc.unifiedQueue().index());
        assertEquals(2, pc.musicIndex());
    }

    /** 2026-07-06 — FM replaces queue with saved-station mirror when presets exist. */
    @Test
    public void syncFmQueue_multiStationRadioQueue() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        List<PlayQueue.QueueItem> stations = new ArrayList<PlayQueue.QueueItem>();
        stations.add(PlayQueue.QueueItem.fmStation(101100, "101.1"));
        stations.add(PlayQueue.QueueItem.fmStation(102300, "102.3"));
        pc.syncFmQueue(stations, 1);
        if (!pc.isFmActive()) throw new AssertionError("expected FM active");
        if (pc.unifiedQueue().size() != 2) throw new AssertionError("two stations");
        if (pc.unifiedQueue().index() != 1) throw new AssertionError("play index");
        PlayQueue.QueueItem prev = pc.fmItemAtWrappedIndex(-1);
        if (prev == null || prev.fmFreqKhz != 101100) throw new AssertionError("wrap prev");
        PlayQueue.QueueItem next = pc.fmItemAtWrappedIndex(1);
        if (next == null || next.fmFreqKhz != 102300) throw new AssertionError("wrap next");
    }

    /** 2026-07-06 — FM replaces queue with a single station row for NP / context chip. */
    @Test
    public void startFmStation_singleItemRadioQueue() {
        PlaybackCoordinator pc = new PlaybackCoordinator();
        pc.startRadioStation(PlayQueue.QueueItem.fmStation(101100, "101.1"));
        if (!pc.isFmActive()) throw new AssertionError("expected FM active");
        if (pc.unifiedQueue().size() != 1) throw new AssertionError("FM is one station");
        if (!pc.hasAnyQueue()) throw new AssertionError("NP chip should see a queue");
    }

    @Test
    public void activateNavidrome_shuffleStartsAtTheTopAndPlaysEverySong() {
        // 2026-10-22 — shuffling a 48-song playlist from its last song played 12:
        // the start song kept its shuffled slot and play ran from there.
        for (int run = 0; run < 25; run++) {
            PlaybackCoordinator pc = new PlaybackCoordinator();
            List<NavidromeSong> songs = new ArrayList<NavidromeSong>();
            for (int i = 0; i < 48; i++) {
                NavidromeSong s = new NavidromeSong();
                s.id = "s" + i;
                s.title = "Song " + i;
                songs.add(s);
            }
            pc.activateNavidrome(songs, 47, true, "Hearts");
            assertEquals(48, pc.unifiedQueue().size());
            assertEquals(0, pc.musicIndex());
            assertEquals("s47", pc.currentItem().navidromeSongId);
            java.util.Set<String> ids = new java.util.HashSet<String>();
            for (PlayQueue.QueueItem q : pc.unifiedQueue().items()) ids.add(q.navidromeSongId);
            assertEquals(48, ids.size());
        }
    }
}
