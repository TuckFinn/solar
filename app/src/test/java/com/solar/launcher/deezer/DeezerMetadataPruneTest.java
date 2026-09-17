package com.solar.launcher.deezer;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which cached-metadata keys may be dropped.
 *
 * The cache had no eviction: four keys per track, named after the file's
 * absolute path, and purgeUnreferenced deleted the audio without them. On 2Y1
 * that came to 480 of 533 keys, 82 of 120 paths naming files that no longer
 * existed — mostly stranded when the cache moved to Deezer-id filenames.
 */
public class DeezerMetadataPruneTest {

    private static final String CACHE = "/data/data/com.solar.launcher/cache/deezer";

    private static Set<String> set(String... s) {
        return new HashSet<String>(Arrays.asList(s));
    }

    private static Set<String> keysFor(String path) {
        return set("meta_title_" + path, "meta_artist_" + path,
                   "meta_album_" + path, "meta_cover_url_" + path);
    }

    @Test
    public void aFileThatIsStillThereKeepsItsMetadata() {
        String path = CACHE + "/1080487192.mp3";
        List<String> drop = DeezerMetadata.staleKeys(keysFor(path), CACHE, set(path));
        if (!drop.isEmpty()) {
            throw new AssertionError("nothing should be dropped, got " + drop);
        }
    }

    @Test
    public void allFourKeysGoWhenTheFileIsGone() {
        String path = CACHE + "/1080487192.mp3";
        List<String> drop = DeezerMetadata.staleKeys(
                keysFor(path), CACHE, Collections.<String>emptySet());
        if (drop.size() != 4) {
            throw new AssertionError("expected all 4 keys, got " + drop);
        }
    }

    /** The 82 entries stranded on 2Y1 when the cache moved to id filenames. */
    @Test
    public void theOrphansOfTheIdRenameAreDropped() {
        String renamed = CACHE + "/1080487192.mp3";
        String orphan = CACHE + "/Rita Lee - Mania De Voce.mp3";
        Set<String> all = new HashSet<String>(keysFor(renamed));
        all.addAll(keysFor(orphan));
        List<String> drop = DeezerMetadata.staleKeys(all, CACHE, set(renamed));
        if (drop.size() != 4) {
            throw new AssertionError("only the orphan's 4 keys should go, got " + drop);
        }
        for (String k : drop) {
            if (!k.endsWith(orphan)) {
                throw new AssertionError("dropped a key for the live file: " + k);
            }
        }
    }

    /**
     * The guard that matters most. Saved music lives on the sdcard, which can
     * be unmounted — "the file is not there" must never mean "forget it".
     */
    @Test
    public void savedMusicIsNeverPrunedForBeingAbsent() {
        String saved = "/mnt/sdcard/Music/Candy Dulfer/Saxuality/01 - Lily Was Here.flac";
        List<String> drop = DeezerMetadata.staleKeys(
                keysFor(saved), CACHE, Collections.<String>emptySet());
        if (!drop.isEmpty()) {
            throw new AssertionError("saved music must be left alone, got " + drop);
        }
    }

    /** A path below the cache directory is not what purgeUnreferenced manages. */
    @Test
    public void aNestedPathIsLeftAlone() {
        String nested = CACHE + "/stems/1080487192.mp3";
        List<String> drop = DeezerMetadata.staleKeys(
                keysFor(nested), CACHE, Collections.<String>emptySet());
        if (!drop.isEmpty()) {
            throw new AssertionError("nested paths are not ours to prune, got " + drop);
        }
    }

    @Test
    public void unrelatedSettingsAreNotTouched() {
        Set<String> all = set("navidrome_url", "navidrome_pass", "home_menu_order",
                              "deezer_arl", "metadata_is_not_a_meta_key");
        List<String> drop = DeezerMetadata.staleKeys(
                all, CACHE, Collections.<String>emptySet());
        if (!drop.isEmpty()) {
            throw new AssertionError("only meta_* keys are ours, got " + drop);
        }
    }

    @Test
    public void nullsDoNotThrow() {
        if (!DeezerMetadata.staleKeys(null, CACHE, set()).isEmpty()) {
            throw new AssertionError("null key set should drop nothing");
        }
        if (!DeezerMetadata.staleKeys(set("meta_title_x"), null, set()).isEmpty()) {
            throw new AssertionError("null cache dir should drop nothing");
        }
        if (!DeezerMetadata.staleKeys(set("meta_title_x"), CACHE, null).isEmpty()) {
            throw new AssertionError("null present set should drop nothing");
        }
    }

    @Test
    public void aTrailingSlashOnTheCacheDirIsHandled() {
        String path = CACHE + "/gone.mp3";
        List<String> drop = DeezerMetadata.staleKeys(
                keysFor(path), CACHE + "/", Collections.<String>emptySet());
        if (drop.size() != 4) {
            throw new AssertionError("trailing slash changed the answer: " + drop);
        }
    }
}
