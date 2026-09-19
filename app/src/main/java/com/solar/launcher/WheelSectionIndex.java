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
        if (starts.length == 0 || position < 0 || position >= itemCount || direction == 0) return -1;
        int section = sectionForPosition(position);
        int target = direction < 0 ? section - 1 : section + 1;
        return target >= 0 && target < starts.length ? starts[target] : -1;
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
