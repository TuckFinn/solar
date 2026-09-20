package com.solar.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable section boundaries for allocation-free wheel jumps. */
public final class WheelSectionIndex {
    public static final WheelSectionIndex EMPTY = new WheelSectionIndex(new int[0], new String[0], 0, false);

    private final int[] starts;
    private final String[] letters;
    private final int itemCount;
    private final boolean alphabetical;

    private WheelSectionIndex(int[] starts, String[] letters, int itemCount, boolean alphabetical) {
        this.starts = starts;
        this.letters = letters;
        this.itemCount = itemCount;
        this.alphabetical = alphabetical;
    }

    /**
     * 2026-09-19 — Whether the list is in letter order, so a letter jump means
     * something. A sorted list has about one run per letter; a playlist in its
     * own order changes letter nearly every row, and jumping "to the next
     * letter" there moves one row.
     */
    public boolean isAlphabetical() {
        return alphabetical;
    }

    public static WheelSectionIndex build(String[] labels) {
        if (labels == null || labels.length == 0) return EMPTY;
        List<Integer> positions = new ArrayList<Integer>();
        List<String> sections = new ArrayList<String>();
        String previous = null;
        for (int i = 0; i < labels.length; i++) {
            String letter = normalize(labels[i]);
            if (!letter.equals(previous)) {
                positions.add(i);
                sections.add(letter);
                previous = letter;
            }
        }
        int[] starts = new int[positions.size()];
        String[] letters = new String[sections.size()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = positions.get(i);
            letters[i] = sections.get(i);
        }
        // Letters step backwards at a section boundary in a shuffled list about
        // half the time; in a sorted one almost never ("#" and stray titles aside).
        int backwards = 0;
        for (int i = 1; i < letters.length; i++) {
            if (letters[i].compareTo(letters[i - 1]) < 0) backwards++;
        }
        boolean alphabetical = letters.length > 1 && backwards <= Math.max(1, (letters.length - 1) / 10);
        return new WheelSectionIndex(starts, letters, labels.length, alphabetical);
    }

    public int jumpTarget(int position, int direction) {
        return jumpTarget(position, direction, 1);
    }

    /**
     * 2026-09-19 — {@code sections} letters at once, so a hard spin travels
     * further than a slow one. One letter per flush meant spinning a long list
     * crawled: the wheel's notches were coalesced into a flush and the flush
     * moved a single letter, slower than clicking row by row (Tucker, 1Y1).
     * Past the last letter it stops on the last one rather than giving up, so
     * the spin still lands somewhere.
     */
    public int jumpTarget(int position, int direction, int sections) {
        if (starts.length == 0 || position < 0 || position >= itemCount || direction == 0) return -1;
        int step = Math.max(1, sections);
        int section = sectionForPosition(position);
        int target = section + (direction < 0 ? -step : step);
        if (target >= starts.length) {
            // Already in the last letter: nothing to jump to, so the caller
            // hops rows instead and the spin can still reach the last item.
            if (section >= starts.length - 1) return -1;
            target = starts.length - 1;
        }
        if (target < 0) {
            if (section <= 0 && position <= starts[0]) return -1;
            target = 0;
        }
        return starts[target] != position ? starts[target] : -1;
    }

    public String letterAtPosition(int position) {
        if (starts.length == 0 || position < 0 || position >= itemCount) return "#";
        return letters[sectionForPosition(position)];
    }

    public int itemCount() {
        return itemCount;
    }

    private int sectionForPosition(int position) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= position) lo = mid + 1;
            else hi = mid - 1;
        }
        return Math.max(0, hi);
    }

    public static String normalize(String text) {
        if (text == null) return "#";
        String clean = text.replace("📁 ", "").replace("👤 ", "")
                .replace("💿 ", "").replace("🎵 ", "")
                .replace("📦 [INSTALL] ", "").trim();
        if (clean.isEmpty()) return "#";
        char c = clean.charAt(0);
        if (c >= 0xAC00 && c <= 0xD7A3) {
            char[] chosungs = {'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'};
            return String.valueOf(chosungs[(c - 0xAC00) / (21 * 28)]);
        }
        return clean.substring(0, 1).toUpperCase(Locale.getDefault());
    }
}
