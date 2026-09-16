package com.solar.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.solar.launcher.deezer.DeezerResult;
import com.solar.launcher.discovery.DiscoveryMatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Country and seed rows have only an artist and a title, so the match has to earn it. */
public class DiscoveryMatchTest {

    private static DeezerResult r(long id, String artist, String title) {
        return new DeezerResult(id, title, artist, "", 0L, 0, "", "");
    }

    @Test public void exactMatchWins() {
        List<DeezerResult> in = Arrays.asList(
                r(1, "Someone Else", "People"),
                r(2, "Libianca", "People"));
        assertEquals(2, DiscoveryMatch.best(in, "Libianca", "People").id);
    }

    @Test public void prefersTheExactTitleOverAQualifiedOne() {
        List<DeezerResult> in = Arrays.asList(
                r(1, "Libianca", "People (Remix)"),
                r(2, "Libianca", "People"));
        assertEquals(2, DiscoveryMatch.best(in, "Libianca", "People").id);
    }

    @Test public void acceptsAQualifierWhenThatIsAllThereIs() {
        List<DeezerResult> in = Arrays.asList(r(1, "Libianca", "People (Remix)"));
        assertEquals(1, DiscoveryMatch.best(in, "Libianca", "People").id);
    }

    @Test public void rejectsADifferentSongThatStartsTheSame() {
        // "People Watching" is not "People" with a qualifier on it.
        List<DeezerResult> in = Arrays.asList(r(1, "Libianca", "People Watching"));
        assertNull(DiscoveryMatch.best(in, "Libianca", "People"));
    }

    @Test public void rejectsTheRightTitleByTheWrongArtist() {
        List<DeezerResult> in = Arrays.asList(r(1, "Karaoke Allstars", "People"));
        assertNull(DiscoveryMatch.best(in, "Libianca", "People"));
    }

    @Test public void matchesWhenOneSideCreditsGuests() {
        List<DeezerResult> in = Arrays.asList(r(1, "Tems", "Me & U"));
        assertEquals(1, DiscoveryMatch.best(in, "Tems, Future", "Me & U").id);
    }

    @Test public void foldsAccentsSoCountryRowsResolve() {
        List<DeezerResult> in = Arrays.asList(r(1, "Kapo", "Donde"));
        assertEquals(1, DiscoveryMatch.best(in, "Kapo", "DÓNDE").id);
    }

    @Test public void ignoresPunctuationAndCase() {
        List<DeezerResult> in = Arrays.asList(r(1, "D.A.D SECTE", "Victory!"));
        assertEquals(1, DiscoveryMatch.best(in, "DAD Secte", "victory").id);
    }

    @Test public void noResultsIsNoMatch() {
        assertNull(DiscoveryMatch.best(new ArrayList<DeezerResult>(), "Tems", "Me & U"));
        assertNull(DiscoveryMatch.best(null, "Tems", "Me & U"));
    }

    @Test public void refusesWhenTheRowHasNoArtist() {
        List<DeezerResult> in = Arrays.asList(r(1, "Anyone", "People"));
        assertNull(DiscoveryMatch.best(in, "", "People"));
    }
}
