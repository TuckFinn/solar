package com.solar.launcher.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 2026-10-21 — Q18: Discovery's lists in folders. The folders come in the
 * server's order, each holds its lists in the server's order, and a nookTunes
 * that sends no groups leaves the flat list as it was.
 */
public class DiscoveryFoldersTest {

    private static String row(String id, String name, String group) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"kind\":\"chart\",\"count\":20"
                + (group == null ? "" : ",\"group\":\"" + group + "\"") + "}";
    }

    private static List<String> ids(List<DiscoveryClient.Playlist> ps) {
        List<String> out = new ArrayList<String>();
        for (DiscoveryClient.Playlist p : ps) out.add(p.id);
        return out;
    }

    private static DiscoveryFolders parse(String json) throws Exception {
        return DiscoveryFolders.parse(new JSONObject(json));
    }

    private static final String GROUPED = "{\"playlists\":["
            + row("fresh", "Fresh this week", "Fresh") + ","
            + row("nd-chart-g132", "Pop", "Genres") + ","
            + row("nd-chart-fr", "France", "Countries") + ","
            + row("nd-chart-g116", "Rap", "Genres") + ","
            + row("seed-a", "Robyn", "For You") + ","
            + row("nd-chart-jp", "Japan", "Countries") + ","
            + row("seed-b", "Björk", "Seeds")
            + "],\"groups\":[\"Fresh\",\"For You\",\"Genres\",\"Countries\",\"Seeds\"]}";

    @Test
    public void foldersComeInTheServersOrder() throws Exception {
        DiscoveryFolders f = parse(GROUPED);
        assertTrue(f.hasFolders());
        assertEquals(Arrays.asList("Fresh", "For You", "Genres", "Countries", "Seeds"), f.folders());
    }

    @Test
    public void eachFolderHoldsItsListsInTheServersOrder() throws Exception {
        DiscoveryFolders f = parse(GROUPED);
        assertEquals(Arrays.asList("fresh"), ids(f.inFolder("Fresh")));
        assertEquals(Arrays.asList("seed-a"), ids(f.inFolder("For You")));
        assertEquals(Arrays.asList("nd-chart-g132", "nd-chart-g116"), ids(f.inFolder("Genres")));
        assertEquals(Arrays.asList("nd-chart-fr", "nd-chart-jp"), ids(f.inFolder("Countries")));
        assertEquals(Arrays.asList("seed-b"), ids(f.inFolder("Seeds")));
        assertEquals("Genres", f.inFolder("Genres").get(0).group);
        assertTrue(f.inFolder("Nope").isEmpty());
        assertEquals(7, f.all().size());
    }

    @Test
    public void anEmptyFolderIsLeftOut() throws Exception {
        DiscoveryFolders f = parse("{\"playlists\":[" + row("nd-chart-g132", "Pop", "Genres")
                + "],\"groups\":[\"Fresh\",\"Genres\"]}");
        assertEquals(Arrays.asList("Genres"), f.folders());
    }

    @Test
    public void aListInNoNamedFolderIsStillReachable() throws Exception {
        DiscoveryFolders f = parse("{\"playlists\":[" + row("a", "A", "Genres") + ","
                + row("b", "B", "Moods") + "," + row("c", "C", null)
                + "],\"groups\":[\"Genres\"]}");
        assertEquals(Arrays.asList("Genres", "Moods", "Other"), f.folders());
        assertEquals(Arrays.asList("c"), ids(f.inFolder("Other")));
    }

    @Test
    public void noGroupsKeepsTheFlatList() throws Exception {
        DiscoveryFolders f = parse("{\"playlists\":[" + row("nd-chart-g132", "Pop", null) + ","
                + row("nd-chart-fr", "France", null) + "," + row("", "dropped", null) + "]}");
        assertFalse(f.hasFolders());
        assertTrue(f.folders().isEmpty());
        assertEquals(Arrays.asList("nd-chart-g132", "nd-chart-fr"), ids(f.all()));
        assertEquals("", f.all().get(0).group);
        assertEquals(20, f.all().get(0).count);
    }

    @Test
    public void emptyGroupsIsTheFlatListToo() throws Exception {
        assertFalse(parse("{\"playlists\":[" + row("a", "A", "Genres") + "],\"groups\":[]}")
                .hasFolders());
        assertFalse(parse("{}").hasFolders());
    }

    @Test
    public void folderLabelShowsTheCount() {
        assertEquals("Genres (33)", DiscoveryFolders.folderLabel("Genres", 33));
    }
}
