package com.solar.launcher;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The Get Music history has to survive being written into SOLAR_SETTINGS.xml.
 *
 * It used to join entries with 0x1E, which XML 1.0 cannot represent in any
 * form. Android's parser read it back happily, so nothing on the device ever
 * complained, but the file was not valid XML and off-device tooling refused
 * it: provision-y1.py could not configure a player that had ever been
 * searched (1y1, 2026-09-16).
 */
public class GetMusicSearchHistoryTest {

    private static final String RECORD_SEPARATOR = "";

    private static List<String> queries() {
        return new ArrayList<String>(Arrays.asList(
                "Tems", "Cleo Sol", "João Gilberto", "TINH HÀ \"SAY HI\"",
                "Charli xcx"));
    }

    /** Every codepoint XML 1.0 forbids in character data. Tab/LF/CR are legal. */
    private static int firstIllegalChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            if (c < 0x20) return c;
        }
        return -1;
    }

    private static void parseOrThrow(String xml) throws Exception {
        DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        b.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private static String prefsFileHolding(String history) {
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map><string name=\"get_music_search_history\">"
                + history
                + "</string></map>";
    }

    @Test
    public void storedHistoryHoldsNothingXmlForbids() {
        int bad = firstIllegalChar(GetMusicSearchHistory.join(queries()));
        if (bad != -1) {
            throw new AssertionError(String.format(
                    "history contains 0x%02x, which cannot go in an XML document", bad));
        }
    }

    @Test
    public void aPrefsFileHoldingHistoryIsWellFormed() throws Exception {
        parseOrThrow(prefsFileHolding(GetMusicSearchHistory.join(queries())));
    }

    /** The bug itself, so the regression is visible rather than assumed. */
    @Test
    public void theOldSeparatorWouldHaveMadeAnUnparseableFile() {
        StringBuilder sb = new StringBuilder();
        for (String q : queries()) {
            if (sb.length() > 0) sb.append(RECORD_SEPARATOR);
            sb.append(q);
        }
        try {
            parseOrThrow(prefsFileHolding(sb.toString()));
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("0x1E was expected to be rejected by an XML parser");
    }

    @Test
    public void historyWrittenByTheOldBuildStillLoads() {
        List<String> got = GetMusicSearchHistory.split(
                "Tems" + RECORD_SEPARATOR + "Cleo Sol" + RECORD_SEPARATOR + "Charli xcx");
        if (!got.equals(Arrays.asList("Tems", "Cleo Sol", "Charli xcx"))) {
            throw new AssertionError("old 0x1E history did not load: " + got);
        }
    }

    /** A device upgraded mid-list ends up with one of each separator. */
    @Test
    public void aMixtureOfBothSeparatorsReadsAsOneList() {
        List<String> got = GetMusicSearchHistory.split(
                "Tems\nCleo Sol" + RECORD_SEPARATOR + "Charli xcx");
        if (!got.equals(Arrays.asList("Tems", "Cleo Sol", "Charli xcx"))) {
            throw new AssertionError("mixed separators did not load: " + got);
        }
    }

    @Test
    public void joinAndSplitAreInverses() {
        List<String> original = queries();
        List<String> got = GetMusicSearchHistory.split(
                GetMusicSearchHistory.join(original));
        if (!got.equals(original)) {
            throw new AssertionError("round trip changed the list: " + got);
        }
    }

    @Test
    public void emptyHistoryIsAnEmptyList() {
        if (!GetMusicSearchHistory.split("").isEmpty()) {
            throw new AssertionError("empty string should load as no entries");
        }
        if (!GetMusicSearchHistory.split(null).isEmpty()) {
            throw new AssertionError("null should load as no entries");
        }
    }
}
