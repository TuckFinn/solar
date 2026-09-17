package com.solar.launcher.deezer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 2026-09-16 — Cache paths are the Deezer id.
 *
 * A queue row and the download that fills it used to derive the filename
 * separately, each walking " (1)", " (2)" past whatever already existed. They
 * could disagree, and then a row pointed at an empty sibling of a finished
 * download and playback sat at "[0%] Loading..." until the user gave up.
 */
public class DeezerCacheNamingTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static DeezerResult track(long id, String artist, String title) {
        return new DeezerResult(id, title, artist, "Album", 0L, 180, "", "");
    }

    @Test public void cachePathIsTheTrackId() {
        File dir = tmp.getRoot();
        File f = DeezerBackgroundQueue.cacheFileFor(dir, track(3125744L, "UB40", "Red Red Wine"), "mp3");
        assertEquals("3125744.mp3", f.getName());
    }

    @Test public void sameTrackAlwaysResolvesToTheSameFile() throws Exception {
        File dir = tmp.getRoot();
        DeezerResult r = track(3125744L, "UB40", "Red Red Wine");
        File first = DeezerBackgroundQueue.reservePlaceholder(dir, r, "mp3");
        File second = DeezerBackgroundQueue.reservePlaceholder(dir, r, "mp3");
        assertEquals(first.getAbsolutePath(), second.getAbsolutePath());
        assertEquals(1, dir.listFiles().length);
    }

    /** The old bug: reserving again must not mint an empty sibling of real audio. */
    @Test public void reservingOverACompleteFileKeepsTheAudio() throws Exception {
        File dir = tmp.getRoot();
        DeezerResult r = track(3125744L, "UB40", "Red Red Wine");
        File dest = DeezerBackgroundQueue.reservePlaceholder(dir, r, "mp3");
        FileOutputStream out = new FileOutputStream(dest);
        out.write(new byte[4096]);
        out.close();

        File again = DeezerBackgroundQueue.reservePlaceholder(dir, r, "mp3");
        assertEquals(dest.getAbsolutePath(), again.getAbsolutePath());
        assertEquals(4096L, again.length());
        assertEquals(1, dir.listFiles().length);
    }

    /** Two recordings that share artist and title are two ids, so two files. */
    @Test public void anEditDoesNotCollideWithTheSingle() throws Exception {
        File dir = tmp.getRoot();
        File single = DeezerBackgroundQueue.reservePlaceholder(
                dir, track(3125744L, "UB40", "Red Red Wine"), "mp3");
        File edit = DeezerBackgroundQueue.reservePlaceholder(
                dir, track(3125745L, "UB40", "Red Red Wine"), "mp3");
        assertTrue(!single.getAbsolutePath().equals(edit.getAbsolutePath()));
        assertEquals(2, dir.listFiles().length);
    }

    /** No id (a hand-built row): fall back to the readable name rather than "0.mp3". */
    @Test public void idlessTrackFallsBackToItsName() {
        File dir = tmp.getRoot();
        File f = DeezerBackgroundQueue.cacheFileFor(dir, track(0L, "UB40", "Red Red Wine"), "mp3");
        assertEquals("UB40 - Red Red Wine.mp3", f.getName());
    }
}
