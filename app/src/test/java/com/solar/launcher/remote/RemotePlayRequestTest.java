package com.solar.launcher.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.solar.launcher.deezer.DeezerResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 2026-10-21 — "Play on Y1": a PLAY_REMOTE broadcast's extras as the songs to
 * play. The named song first, then queue_json's in order; ids as strings or
 * numbers; songs with no id kept for a search, songs with nothing dropped.
 */
public class RemotePlayRequestTest {

    private static List<Long> ids(List<RemotePlayRequest.Track> ts) {
        List<Long> out = new ArrayList<Long>();
        for (RemotePlayRequest.Track t : ts) out.add(t.deezerId);
        return out;
    }

    @Test
    public void theNamedSongAloneWithoutAQueue() {
        List<RemotePlayRequest.Track> ts = RemotePlayRequest.fromExtras(
                "Dreams", "Fleetwood Mac", "Rumours", "3135556", null);
        assertEquals(1, ts.size());
        RemotePlayRequest.Track t = ts.get(0);
        assertEquals("Dreams", t.title);
        assertEquals("Fleetwood Mac", t.artist);
        assertEquals("Rumours", t.album);
        assertEquals(3135556L, t.deezerId);
        assertTrue(t.hasDeezerId());
    }

    @Test
    public void theQueueFollowsInItsOrder() {
        String q = "[{\"title\":\"The Chain\",\"artist\":\"Fleetwood Mac\",\"album\":\"Rumours\","
                + "\"deezer_id\":\"3135557\"},"
                + "{\"title\":\"Songbird\",\"artist\":\"Fleetwood Mac\",\"album\":\"Rumours\","
                + "\"deezer_id\":3135558}]";
        List<RemotePlayRequest.Track> ts = RemotePlayRequest.fromExtras(
                "Dreams", "Fleetwood Mac", "Rumours", "3135556", q);
        assertEquals(Arrays.asList(3135556L, 3135557L, 3135558L), ids(ts));
        assertEquals("Songbird", ts.get(2).title);
    }

    @Test
    public void idsComeAsStringsOrNumbers() {
        assertEquals(42L, RemotePlayRequest.parseId("42"));
        assertEquals(42L, RemotePlayRequest.parseId(" 42 "));
        assertEquals(42L, RemotePlayRequest.parseId(Long.valueOf(42)));
        assertEquals(42L, RemotePlayRequest.parseId(Integer.valueOf(42)));
        assertEquals(0L, RemotePlayRequest.parseId(null));
        assertEquals(0L, RemotePlayRequest.parseId(""));
        assertEquals(0L, RemotePlayRequest.parseId("abc"));
        assertEquals(0L, RemotePlayRequest.parseId("-5"));
        assertEquals(0L, RemotePlayRequest.parseId(org.json.JSONObject.NULL));
    }

    @Test
    public void aSongWithoutAnIdIsKeptForASearch() {
        String q = "[{\"title\":\"Landslide\",\"artist\":\"Fleetwood Mac\"},"
                + "{\"title\":\"Gypsy\",\"artist\":\"Fleetwood Mac\",\"deezer_id\":null}]";
        List<RemotePlayRequest.Track> ts = RemotePlayRequest.fromExtras(
                "Dreams", "Fleetwood Mac", "", null, q);
        assertEquals(Arrays.asList(0L, 0L, 0L), ids(ts));
        assertFalse(ts.get(0).hasDeezerId());
        assertEquals("Fleetwood Mac Dreams", ts.get(0).searchQuery());
        assertEquals("", ts.get(1).album);
    }

    @Test
    public void aSongWithNeitherIdNorTitleIsDropped() {
        String q = "[{\"artist\":\"Nobody\"},{\"title\":\"  \"},{\"deezer_id\":\"77\"},"
                + "\"not an object\",{\"title\":\"Real\"}]";
        List<RemotePlayRequest.Track> ts = RemotePlayRequest.fromExtras(
                "", "Someone", "", "", q);
        assertEquals(2, ts.size());
        assertEquals(77L, ts.get(0).deezerId);
        assertEquals("Real", ts.get(1).title);
    }

    @Test
    public void aBrokenQueueLeavesTheFirstSongAlone() {
        List<RemotePlayRequest.Track> ts = RemotePlayRequest.fromExtras(
                "Dreams", "Fleetwood Mac", "Rumours", "3135556", "{not json");
        assertEquals(Arrays.asList(3135556L), ids(ts));
        ts = RemotePlayRequest.fromExtras("Dreams", "", "", "1", "{\"title\":\"x\"}");
        assertEquals(Arrays.asList(1L), ids(ts));
        ts = RemotePlayRequest.fromExtras("Dreams", "", "", "1", "   ");
        assertEquals(1, ts.size());
    }

    @Test
    public void theRequestIsCapped() {
        StringBuilder q = new StringBuilder("[");
        for (int i = 0; i < 200; i++) {
            if (i > 0) q.append(',');
            q.append("{\"title\":\"t").append(i).append("\",\"deezer_id\":").append(i + 1).append('}');
        }
        q.append(']');
        List<RemotePlayRequest.Track> ts = RemotePlayRequest.fromExtras(
                "first", "", "", "999", q.toString());
        assertEquals(RemotePlayRequest.MAX_TRACKS, ts.size());
        assertEquals(999L, ts.get(0).deezerId);
        assertEquals(1L, ts.get(1).deezerId);
    }

    @Test
    public void aTrackBecomesTheResultDiscoveryWouldBuild() {
        RemotePlayRequest.Track t = new RemotePlayRequest.Track(" Dreams ", "Fleetwood Mac",
                null, 3135556L);
        DeezerResult r = t.toDeezerResult();
        assertEquals(3135556L, r.id);
        assertEquals("Dreams", r.title);
        assertEquals("Fleetwood Mac", r.artist);
        assertEquals("", r.album);
        assertEquals("Fleetwood Mac - Dreams", r.displayTitle());
    }
}
