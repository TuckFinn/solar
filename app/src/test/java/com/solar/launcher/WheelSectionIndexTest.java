package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

public class WheelSectionIndexTest {
    @Test public void normalizesPrefixesAndKorean() {
        assertEquals("A", WheelSectionIndex.normalize("🎵 alpha"));
        assertEquals("ㄱ", WheelSectionIndex.normalize("가나다"));
        assertEquals("#", WheelSectionIndex.normalize("  "));
    }

    @Test public void jumpsBetweenSectionStarts() {
        WheelSectionIndex idx = WheelSectionIndex.build(new String[] {"Able", "Atom", "Beta", "Bravo", "Charlie"});
        assertEquals(2, idx.jumpTarget(0, 1));
        assertEquals(4, idx.jumpTarget(3, 1));
        assertEquals(0, idx.jumpTarget(3, -1));
        assertEquals(-1, idx.jumpTarget(4, 1));
    }

    @Test public void tellsSortedListsFromPlaylistOrder() {
        assertTrue(WheelSectionIndex.build(new String[] {
                "Adele", "Aya Nakamura", "Beyoncé", "Björk", "Cher", "Dido", "Doja Cat", "Enya"}).isAlphabetical());
        assertFalse(WheelSectionIndex.build(new String[] {
                "Tems", "Adele", "Rosalía", "Björk", "Zuchu", "Cher", "Mitski", "Adele", "Dido", "Tems",
                "Shakira", "Beyoncé"}).isAlphabetical());
        assertFalse(WheelSectionIndex.EMPTY.isAlphabetical());
    }

    @Test public void handlesLargeDataset() {
        String[] labels = new String[5000];
        for (int i = 0; i < labels.length; i++) labels[i] = (char) ('A' + i / 200) + " item";
        WheelSectionIndex idx = WheelSectionIndex.build(labels);
        assertEquals(2600, idx.jumpTarget(2599, 1));
    }
}
