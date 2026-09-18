package com.solar.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/** A song hearted from the device's own storage lands in Hearts.m3u. */
public class HeartsPlaylistTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File track(File music, String rel) throws Exception {
        File f = new File(music, rel);
        f.getParentFile().mkdirs();
        f.createNewFile();
        return f;
    }

    @Test
    public void addsTheMusicRelativePathOnce() throws Exception {
        File music = tmp.newFolder("Music");
        File t = track(music, "artists/Candy Dulfer/We Never Stop/02 - Lily Was Here.flac");
        assertTrue(HeartsPlaylist.add(music, t));
        assertFalse(HeartsPlaylist.add(music, t));
        assertEquals(Arrays.asList("artists/Candy Dulfer/We Never Stop/02 - Lily Was Here.flac"),
                HeartsPlaylist.read(HeartsPlaylist.file(music)));
    }

    @Test
    public void appendsToWhatY1syncWrote() throws Exception {
        File music = tmp.newFolder("Music");
        File pl = HeartsPlaylist.file(music);
        pl.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(pl);
        // y1sync's form: CRLF lines; a hand edit may leave no final newline.
        out.write("artists/Tems/Me & U/01 - Me & U.mp3\r\nartists/Libianca/People/01 - People.mp3".getBytes("UTF-8"));
        out.close();
        File t = track(music, "artists/Arlo Parks/Black Dog/01 - Black Dog.mp3");
        assertTrue(HeartsPlaylist.add(music, t));
        assertEquals(Arrays.asList(
                "artists/Tems/Me & U/01 - Me & U.mp3",
                "artists/Libianca/People/01 - People.mp3",
                "artists/Arlo Parks/Black Dog/01 - Black Dog.mp3"),
                HeartsPlaylist.read(pl));
    }

    @Test
    public void recognisesAnAbsoluteLineAsTheSameSong() throws Exception {
        File music = tmp.newFolder("Music");
        File t = track(music, "a/b.mp3");
        File pl = HeartsPlaylist.file(music);
        pl.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(pl);
        out.write((t.getAbsolutePath() + "\r\n").getBytes("UTF-8"));
        out.close();
        assertFalse(HeartsPlaylist.add(music, t));
    }

    @Test
    public void removeTakesOutOnlyThatSong() throws Exception {
        File music = tmp.newFolder("Music");
        File a = track(music, "x/a.mp3");
        File b = track(music, "x/b.mp3");
        HeartsPlaylist.add(music, a);
        HeartsPlaylist.add(music, b);
        assertTrue(HeartsPlaylist.remove(music, a));
        assertFalse(HeartsPlaylist.remove(music, a));
        assertEquals(Arrays.asList("x/b.mp3"), HeartsPlaylist.read(HeartsPlaylist.file(music)));
    }

    @Test
    public void aSongOutsideMusicIsListedByAbsolutePath() throws Exception {
        File music = tmp.newFolder("Music");
        File elsewhere = new File(tmp.newFolder("Download"), "x.mp3");
        elsewhere.createNewFile();
        assertEquals(elsewhere.getAbsolutePath(), HeartsPlaylist.lineFor(music, elsewhere));
    }
}
