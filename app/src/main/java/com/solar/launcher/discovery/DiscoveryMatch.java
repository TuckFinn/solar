package com.solar.launcher.discovery;

import com.solar.launcher.deezer.DeezerResult;

import java.util.List;
import java.util.Locale;

/**
 * Pick the Deezer track that is the song a Discovery row names.
 *
 * Genre rows carry an exact Deezer id, but country rows come from Apple and
 * seed rows from Spotify, so those have only an artist and a title to go on.
 * Searching for them returns covers, live versions, remixes and karaoke before
 * the record itself, and playing the wrong song is worse than playing none:
 * the point of the screen is that you can trust what it found. So a candidate
 * has to match on both fields, and no match returns null rather than the first
 * result.
 */
public final class DiscoveryMatch {

    private DiscoveryMatch() {}

    public static DeezerResult best(List<DeezerResult> results, String artist, String title) {
        if (results == null || results.isEmpty()) return null;
        String wantArtist = norm(artist);
        String wantTitle = norm(title);
        if (wantTitle.isEmpty()) return null;
        DeezerResult loose = null;
        for (DeezerResult r : results) {
            if (r == null) continue;
            String haveArtist = norm(r.artist);
            String haveTitle = norm(r.title);
            if (!artistMatches(wantArtist, haveArtist)) continue;
            if (haveTitle.equals(wantTitle)) return r;      // both exact: done
            if (loose == null && titleMatches(wantTitle, haveTitle)) loose = r;
        }
        return loose;
    }

    /**
     * An empty wanted artist matches nothing: a title alone is not enough to
     * be sure, and Discovery rows always carry an artist.
     */
    private static boolean artistMatches(String want, String have) {
        if (want.isEmpty() || have.isEmpty()) return false;
        if (want.equals(have)) return true;
        // "Tems" against "Tems, Future" and the reverse -- chart rows credit
        // every guest, Deezer's own record often does not, and vice versa.
        return have.contains(want) || want.contains(have);
    }

    /**
     * A prefix relationship only, and only in the direction that adds a
     * qualifier: "People" matches "People (Remix)" but "People" must not match
     * "People Watching", which is a different song.
     */
    private static boolean titleMatches(String want, String have) {
        if (want.equals(have)) return true;
        String longer = have.length() >= want.length() ? have : want;
        String shorter = have.length() >= want.length() ? want : have;
        if (!longer.startsWith(shorter)) return false;
        // What follows has to look like a qualifier, not another word.
        String rest = longer.substring(shorter.length()).trim();
        return rest.isEmpty() || rest.startsWith("(") || rest.startsWith("-")
                || rest.startsWith("feat") || rest.startsWith("ft")
                || rest.startsWith("with") || rest.startsWith("remix");
    }

    /** Lowercase, drop accents and punctuation, collapse whitespace. */
    static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(fold(c));
            } else if (c == '(' || c == '-') {
                sb.append(c);
            } else if (Character.isWhitespace(c)) {
                sb.append(' ');
            }
            // everything else (quotes, dots, commas, brackets) is dropped
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Fold the accented latin letters the charts actually contain. API 17 has
     * no java.text.Normalizer worth relying on here, and "Türkiye" must match
     * "Turkiye" or half the country rows never resolve.
     */
    private static char fold(char c) {
        if (c >= 'a' && c <= 'z') return c;
        if ("àáâãäåā".indexOf(c) >= 0) return 'a';
        if ("èéêëē".indexOf(c) >= 0) return 'e';
        if ("ìíîïī".indexOf(c) >= 0) return 'i';
        if ("òóôõöøō".indexOf(c) >= 0) return 'o';
        if ("ùúûüū".indexOf(c) >= 0) return 'u';
        if (c == 'ñ') return 'n';
        if (c == 'ç') return 'c';
        if (c == 'ý' || c == 'ÿ') return 'y';
        return c;
    }
}
