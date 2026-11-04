package com.solar.launcher.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;

/** 2026-11-03 — Q22: pack files are found by their Deezer id, as the stream cache's are. */
public class PackStoreTest {
    @Test public void idOfAudio() {
        assertEquals(3818963601L, PackStore.idOf(new File("/x/3818963601.mp3")));
        assertEquals(42L, PackStore.idOf(new File("/x/42.flac")));
    }

    @Test public void idOfOtherFilesIsZero() {
        assertEquals(0L, PackStore.idOf(new File("/x/42.part")));
        assertEquals(0L, PackStore.idOf(new File("/x/pack.json")));
        assertEquals(0L, PackStore.idOf(new File("/x/Artist - Song.mp3")));
        assertEquals(0L, PackStore.idOf(new File("/x/.mp3")));
    }
}
