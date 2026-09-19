package com.solar.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Back lands on the row the list was left from. */
public class BackFocusMemoryTest {

    private static List<String> list(String... s) {
        return Arrays.asList(s);
    }

    @Test
    public void findsTheRowByLabel() {
        BackFocusMemory m = new BackFocusMemory();
        m.remember("home", "Discovery", 3);
        assertEquals(3, BackFocusMemory.resolve(
                list("Now Playing", "Music", "Navidrome", "Discovery", "Settings"), m.recall("home")));
    }

    @Test
    public void followsTheLabelWhenARowAppearsAbove() {
        // Left Home at Discovery (row 2); playback then added Now Playing on top.
        BackFocusMemory.Row r = new BackFocusMemory.Row("Discovery", 2);
        assertEquals(3, BackFocusMemory.resolve(
                list("Now Playing", "Music", "Navidrome", "Discovery"), r));
    }

    @Test
    public void fallsBackToThePositionClamped() {
        BackFocusMemory.Row r = new BackFocusMemory.Row("Gone", 7);
        assertEquals(2, BackFocusMemory.resolve(list("a", "b", "c"), r));
        assertEquals(1, BackFocusMemory.resolve(list("a", "b", "c"), new BackFocusMemory.Row(null, 1)));
    }

    @Test
    public void aRepeatedLabelPicksTheCopyNearestTheOldPosition() {
        BackFocusMemory.Row r = new BackFocusMemory.Row("Intro", 5);
        assertEquals(4, BackFocusMemory.resolve(list("Intro", "x", "y", "z", "Intro", "w"), r));
    }

    @Test
    public void indexOfLabelIsMinusOneWhenAbsent() {
        assertEquals(-1, BackFocusMemory.indexOfLabel(list("a", "b"), "c", 0));
        assertEquals(-1, BackFocusMemory.indexOfLabel(list("a", "b"), "", 0));
        assertEquals(-1, BackFocusMemory.indexOfLabel(list("a", "b"), null, 0));
        assertEquals(1, BackFocusMemory.indexOfLabel(list("NSYNC", "1010benja"), "1010benja", 0));
    }

    @Test
    public void nothingToFocus() {
        assertEquals(-1, BackFocusMemory.resolve(Collections.<String>emptyList(),
                new BackFocusMemory.Row("a", 0)));
        assertEquals(-1, BackFocusMemory.resolve(list("a"), null));
    }

    @Test
    public void levelsKeepTheirOwnRow() {
        BackFocusMemory m = new BackFocusMemory();
        m.remember("home", "Music", 1);
        m.remember("library", "Playlists", 9);
        m.remember("home", "Discovery", 3);
        assertEquals("Discovery", m.recall("home").label);
        assertEquals("Playlists", m.recall("library").label);
        assertNull(m.recall("settings"));
        m.remember("x", "ignored", -1);
        assertNull(m.recall("x"));
    }
}
