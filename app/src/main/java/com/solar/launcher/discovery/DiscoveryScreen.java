package com.solar.launcher.discovery;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.solar.launcher.DebugAgentLog;
import com.solar.launcher.R;
import com.solar.launcher.deezer.DeezerClient;
import com.solar.launcher.deezer.DeezerResult;
import com.solar.launcher.deezer.DeezerScreen;
import com.solar.launcher.deezer.DeezerSearch;

import java.util.ArrayList;
import java.util.List;

/**
 * nookTunes Discovery as a home screen of its own: genres and countries, then
 * the songs in one, played through the path that already starts in seconds.
 *
 * Nothing here downloads or decodes anything. A row becomes a
 * {@link DeezerResult} and goes to {@link DeezerScreen#startTransfer}, which is
 * what plays a file while it is still arriving -- five seconds to audio, where
 * fetching the same song through nookTunes' Subsonic endpoint took 28 to 77
 * because it cannot send a byte until the whole track has landed.
 *
 * Genres, countries and seed artists. A Deezer genre chart is Deezer end to
 * end, so every song carries an exact id; country and seed rows come from Apple
 * and Spotify, so those are matched by artist and title through
 * {@link DiscoveryMatch}, which refuses a doubtful match rather than playing
 * the wrong song.
 */
public final class DiscoveryScreen {

    public static final int UI_PLAYLISTS = 0;
    public static final int UI_TRACKS = 1;
    /**
     * 2026-10-21 — Q18: the folders (Fresh, For You, Genres, Countries, Seeds)
     * above the lists. Only when nookTunes sends them; an older one keeps the
     * flat playlist tier as the top.
     */
    public static final int UI_FOLDERS = 2;

    private final DeezerScreen.Host host;
    private final DeezerScreen deezer;

    private int uiMode = UI_PLAYLISTS;
    private final List<DiscoveryClient.Playlist> playlists =
            new ArrayList<DiscoveryClient.Playlist>();
    private final List<DiscoveryClient.Track> tracks = new ArrayList<DiscoveryClient.Track>();
    private DiscoveryClient.Playlist openPlaylist;
    /** 2026-10-21 — Q18: the lists and their folders, as last fetched. */
    private DiscoveryFolders catalog = DiscoveryFolders.EMPTY;
    /** The folder open, or whose only list is open; null on the top tier. */
    private String openFolder;
    /**
     * True when the open folder holds one list (Fresh) and its songs were
     * opened directly, so Back from them skips the one-row list tier.
     */
    private boolean folderSkipped;
    /**
     * 2026-09-19 — The row the next render focuses, or -1 for the first. Back
     * sets it so the list comes back on the row that was left: the playlist
     * just closed, or the song that was started from it.
     */
    private int focusIndex = -1;
    /**
     * 2026-09-19 — The first row render() put on screen. When the list shows another
     * first row, Deezer's download panel (progress, retrying, failed) has replaced it
     * while a picked song is fetched, and Back shows the songs again rather than
     * leaving the playlist.
     */
    private View renderedFirstRow;
    /**
     * 2026-09-19 — The song picked from the list, which Back from Now Playing lands on.
     * Not lastPlayed: that follows the run as it plays on, for the heart.
     */
    private DiscoveryClient.Track pickedTrack;
    private boolean loading;
    private String error;
    /** The last song started from here, so a heart knows what to keep. */
    private DiscoveryClient.Track lastPlayed;
    /**
     * The Deezer id it actually played under. A country row has none of its
     * own -- it was resolved by search -- so the heart has to compare against
     * what was played, not against what the row carried.
     */
    private long lastPlayedDeezerId;
    private DeezerSearch search;

    /**
     * 2026-09-18 — Songs kept queued behind the one playing.
     *
     * The whole rest of a playlist used to go in at once: every country and
     * seed row searched one after another before anything was queued, then
     * every song downloaded. Fine at 20 songs; at 100 it is minutes before the
     * second song is queued and ~900 MB written into the stream cache, which
     * has no cap and lives on the Y1's 1 GB /data. Three covers a slow search or
     * a slow download and keeps the cache to a few songs.
     */
    static final int QUEUE_AHEAD = 3;

    /**
     * 2026-09-18 — Played songs kept whole behind the one playing. Older ones
     * keep their place in the queue but give up their audio (~9 MB each), so
     * an afternoon of a 100-song playlist does not fill /data; Previous past
     * them waits for a download instead.
     */
    static final int KEEP_BEHIND = 3;
    /** Every Deezer id Discovery has queued this session -- all it may empty. */
    private final java.util.Set<Long> discoveryIds = new java.util.HashSet<Long>();
    /** The playlist the run draws from, as it was when the song was picked. */
    private final List<DiscoveryClient.Track> runPlaylist = new ArrayList<DiscoveryClient.Track>();
    private final java.util.Random random = new java.util.Random();

    /** Bumped on every play, so a superseded run stops resolving. */
    private volatile int queueGeneration;
    /**
     * Every id the current run queued behind its first song. Handed to the next
     * run so those are dropped rather than left stacked below it.
     */
    private java.util.Set<Long> queuedIds = new java.util.HashSet<Long>();
    // The run: the songs after the one picked, fed into the queue a few at a
    // time as it plays. UI thread only.
    private final List<DiscoveryClient.Track> runPending = new ArrayList<DiscoveryClient.Track>();
    /** Deezer ids the run has queued, in order, starting with the picked song. */
    private final List<Long> runQueued = new ArrayList<Long>();
    private final java.util.Map<Long, DiscoveryClient.Track> runTracks =
            new java.util.HashMap<Long, DiscoveryClient.Track>();
    /** The previous run's ids, dropped with this run's first delivery. */
    private java.util.Set<Long> runReplace;
    private boolean runResolving;
    /** What MainActivity last said is playing. */
    private long playingDeezerId;

    /**
     * 2026-10-20 — A run song that gives way to the next after less than this,
     * or less than {@link #SKIP_UNDER_FRACTION} of its length, was skipped.
     */
    static final long SKIP_UNDER_MS = 30000L;
    static final double SKIP_UNDER_FRACTION = 0.35;
    /** The run song playing now, and when it started, to judge once the next starts. */
    private DiscoveryClient.Track outcomeTrack;
    private long outcomeTrackId;
    private long outcomeStartedAt;

    public DiscoveryScreen(DeezerScreen.Host host, DeezerScreen deezer) {
        this.host = host;
        this.deezer = deezer;
        // Hearts and played pings made with Wi-Fi off wait in the files dir.
        if (host.context() != null) DiscoveryClient.init(host.context().getFilesDir());
        // 2026-10-26 — the run's queued songs survive a restart, so the next pick
        // still drops them (2Y1: Saturday's Acoustic tail sat behind Monday's Rock
        // after an install restarted Solar and these ids were forgotten).
        if (host.prefs() != null) {
            queuedIds.addAll(decodeIds(host.prefs().getString(PREF_RUN_QUEUED, "")));
            discoveryIds.addAll(queuedIds);
        }
    }

    static final String PREF_RUN_QUEUED = "discovery_run_queued";

    /** Save what the next pick must drop: this run's songs, plus the last run's until delivered. */
    private void saveRunQueued() {
        if (host.prefs() == null) return;
        java.util.Set<Long> all = new java.util.HashSet<Long>(queuedIds);
        if (runReplace != null) all.addAll(runReplace);
        host.prefs().edit().putString(PREF_RUN_QUEUED, encodeIds(all)).apply();
    }

    static String encodeIds(java.util.Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null || id.longValue() == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id.longValue());
        }
        return sb.toString();
    }

    static java.util.Set<Long> decodeIds(String s) {
        java.util.Set<Long> out = new java.util.HashSet<Long>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            try {
                long v = Long.parseLong(part.trim());
                if (v != 0) out.add(Long.valueOf(v));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public int uiMode() { return uiMode; }

    public String baseUrl() {
        return host.prefs() != null ? host.prefs().getString("navidrome_url", "") : "";
    }

    public boolean isConfigured() {
        return !baseUrl().trim().isEmpty();
    }

    /** Entry point: show the top tier, fetching it if we have nothing. */
    public void open() {
        uiMode = topTier();
        openPlaylist = null;
        openFolder = null;
        folderSkipped = false;
        focusIndex = -1;
        if (playlists.isEmpty() && !loading) {
            fetchPlaylists();
        } else {
            render();
        }
    }

    /**
     * 2026-09-19 — Back into Discovery, from Now Playing: the list that was
     * left, on the song started from it. This used to be open(), which starts
     * over at the playlists, so Back from a song lost the playlist it came from.
     */
    public void reopen() {
        if (uiMode != UI_TRACKS || openPlaylist == null) {
            if (playlists.isEmpty() && !loading) {
                open();
                return;
            }
            render();
            return;
        }
        int i = indexOfTrack(pickedTrack);
        focusIndex = i >= 0 ? i + 1 : -1; // row 0 is "Back to playlists"
        render();
    }

    /**
     * @return true when back was consumed here rather than leaving the screen.
     * 2026-09-19 — Lands on the playlist that was open. Was: the first row.
     */
    public boolean handleBackPress() {
        if (uiMode == UI_TRACKS && !listShown()) {
            // Off Deezer's download panel, back to the songs, on the one picked. The
            // download carries on; it opens Now Playing when the song is ready.
            reopen();
            return true;
        }
        if (uiMode == UI_TRACKS) {
            if (folderSkipped) {
                // 2026-10-21 — Q18: a one-list folder (Fresh) opened its songs
                // directly, so Back goes to the folders, on that folder.
                focusIndex = indexOfFolder(openFolder);
                uiMode = UI_FOLDERS;
                openFolder = null;
                folderSkipped = false;
            } else {
                focusIndex = indexOfPlaylist(openPlaylist);
                uiMode = UI_PLAYLISTS;
            }
            openPlaylist = null;
            // A track fetch still running belongs to the list being left; its
            // result is dropped, so it must not keep the screen on "Loading".
            loading = false;
            render();
            return true;
        }
        if (uiMode == UI_PLAYLISTS && openFolder != null) {
            // 2026-10-21 — Q18: out of a folder's lists, back on that folder.
            focusIndex = indexOfFolder(openFolder);
            uiMode = UI_FOLDERS;
            openFolder = null;
            render();
            return true;
        }
        return false;
    }

    /** 2026-10-21 — Q18: folders first when nookTunes sends them, else the flat lists. */
    private int topTier() {
        return catalog.hasFolders() ? UI_FOLDERS : UI_PLAYLISTS;
    }

    /** The lists the playlist tier shows: the open folder's, or all of them. */
    private List<DiscoveryClient.Playlist> shownPlaylists() {
        return openFolder != null ? catalog.inFolder(openFolder) : playlists;
    }

    private int indexOfFolder(String folder) {
        return folder == null ? -1 : catalog.folders().indexOf(folder);
    }

    /**
     * 2026-10-21 — Q18: a folder chosen. One list (Fresh) opens its songs
     * directly; more show the folder's lists.
     */
    private void openFolder(String folder) {
        List<DiscoveryClient.Playlist> in = catalog.inFolder(folder);
        openFolder = folder;
        if (in.size() == 1) {
            folderSkipped = true;
            fetchTracks(in.get(0));
            return;
        }
        folderSkipped = false;
        uiMode = UI_PLAYLISTS;
        render();
    }

    private int indexOfPlaylist(DiscoveryClient.Playlist p) {
        if (p == null) return -1;
        List<DiscoveryClient.Playlist> playlists = shownPlaylists();
        for (int i = 0; i < playlists.size(); i++) {
            DiscoveryClient.Playlist q = playlists.get(i);
            if (q == p || (q.id != null && q.id.equals(p.id))) return i;
        }
        return -1;
    }

    private int indexOfTrack(DiscoveryClient.Track t) {
        if (t == null) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            DiscoveryClient.Track u = tracks.get(i);
            if (u == t) return i;
        }
        for (int i = 0; i < tracks.size(); i++) {
            DiscoveryClient.Track u = tracks.get(i);
            if (t.deezerId > 0 ? u.deezerId == t.deezerId
                    : u.displayTitle().equals(t.displayTitle())) return i;
        }
        return -1;
    }

    private void fetchPlaylists() {
        loading = true;
        error = null;
        render();
        host.runOnBg(new Runnable() {
            @Override public void run() {
                DiscoveryFolders got = DiscoveryFolders.EMPTY;
                String err = null;
                try {
                    // Everything nookTunes offers. It decides which seed-artist
                    // rows are worth showing and in what order -- it has the
                    // play history to rank them and this does not -- so the
                    // list is taken as given rather than filtered again here.
                    got = DiscoveryClient.catalog(baseUrl());
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final String fErr = err;
                final DiscoveryFolders fGot = got;
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        error = fErr;
                        catalog = fGot;
                        playlists.clear();
                        playlists.addAll(fGot.all());
                        // 2026-10-21 — Q18: only open() fetches, so this is the
                        // top tier: folders when they came, else the flat lists.
                        uiMode = topTier();
                        openFolder = null;
                        folderSkipped = false;
                        render();
                    }
                });
            }
        });
    }

    private void fetchTracks(final DiscoveryClient.Playlist p) {
        loading = true;
        error = null;
        openPlaylist = p;
        uiMode = UI_TRACKS;
        render();
        host.runOnBg(new Runnable() {
            @Override public void run() {
                List<DiscoveryClient.Track> got = null;
                String err = null;
                try {
                    got = DiscoveryClient.tracks(baseUrl(), p.id);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final List<DiscoveryClient.Track> fGot = got;
                final String fErr = err;
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        // Back (or another playlist) came first: not ours to show.
                        if (openPlaylist != p || uiMode != UI_TRACKS) return;
                        loading = false;
                        error = fErr;
                        tracks.clear();
                        if (fGot != null) tracks.addAll(fGot);
                        render();
                    }
                });
            }
        });
    }

    private DeezerSearch search() {
        if (search == null) search = new DeezerSearch(new DeezerClient(host.prefs()));
        return search;
    }

    /**
     * 2026-09-19 — Which pick the listener is waiting on. A country row has to
     * be searched for first, and that search outlived the tap: three picks in a
     * row and each late result called start(), whose startTransfer cancels the
     * download already running, so the song sat at 0% (Tucker, 1Y1). A newer
     * pick now makes the older searches drop what they found.
     */
    private int pickGeneration;

    private void play(final DiscoveryClient.Track t) {
        if (!host.requireInternet(R.string.deezer_wifi_required)) return;
        final int pick = ++pickGeneration;
        if (t.deezerId > 0) {
            start(t, new DeezerResult(t.deezerId, t.title, t.artist, t.album,
                    0L, t.durationSec, "", t.cover));
            return;
        }
        DeezerResult direct = directResult(t);
        if (direct != null) {
            start(t, direct);
            return;
        }
        // A country row knows only who and what, so find it first. Searching
        // blocks, and a wrong hit is worse than none, so this runs off the UI
        // thread and DiscoveryMatch refuses anything it cannot vouch for.
        Toast.makeText(host.context(), host.string(R.string.discovery_finding, t.title),
                Toast.LENGTH_SHORT).show();
        host.runOnBg(new Runnable() {
            @Override public void run() {
                DeezerResult hit = null;
                try {
                    hit = DiscoveryMatch.best(search().searchTracks(t.searchQuery()),
                            t.artist, t.title);
                } catch (Exception e) {
                    DebugAgentLog.log(host.context(), "DiscoveryScreen.play",
                            "search failed", "H-DISC", null);
                }
                final DeezerResult found = hit;
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        if (pick != pickGeneration) return;   // a newer pick is playing
                        if (found == null) {
                            Toast.makeText(host.context(),
                                    host.string(R.string.discovery_not_found, t.displayTitle()),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        start(t, found);
                    }
                });
            }
        });
    }

    private void start(DiscoveryClient.Track t, DeezerResult r) {
        lastPlayed = t;
        lastPlayedDeezerId = r.id;
        pickedTrack = t;
        deezer.startTransfer(r, DeezerScreen.ACTION_PLAY);
        beginRun(t, r.id);
    }

    /**
     * Start a run from {@code from}: the rest of the playlist follows it,
     * a few songs at a time.
     *
     * A play on its own is one song: when it ended the player fell back to
     * whatever queue was already loaded, which meant picking a Classical track
     * dropped you back into the Navidrome stream from before. So what follows
     * goes in behind it as placeholders and downloads in sequence -- the same
     * path a Deezer album takes -- but only {@link #QUEUE_AHEAD} at a time.
     */
    private void beginRun(DiscoveryClient.Track from, long fromId) {
        final int generation = ++queueGeneration;
        runPlaylist.clear();
        runPlaylist.addAll(tracks);
        runPending.clear();
        runPending.addAll(pendingOrder(runPlaylist, from,
                java.util.Collections.singleton(from), shuffleOn(), random));
        runQueued.clear();
        runTracks.clear();
        runQueued.add(Long.valueOf(fromId));
        runTracks.put(Long.valueOf(fromId), from);
        discoveryIds.add(Long.valueOf(fromId));
        runReplace = queuedIds;
        queuedIds = new java.util.HashSet<Long>();
        saveRunQueued();
        runResolving = false;
        playingDeezerId = fromId;
        topUp(generation);
    }

    private boolean shuffleOn() {
        return host.prefs() != null && host.prefs().getBoolean("shuffle", false);
    }

    /**
     * 2026-09-18 — What a run plays after {@code current}, following Solar's
     * shuffle: in order, the playlist's songs after it; shuffled, every song
     * of the playlist, before it as well as after, in random order. Songs in
     * {@code queued} are left out either way -- they are playing or played.
     */
    static <T> List<T> pendingOrder(List<T> playlist, T current, java.util.Set<T> queued,
            boolean shuffle, java.util.Random random) {
        List<T> out = new ArrayList<T>();
        boolean after = false;
        for (T t : playlist) {
            if (t == current) {
                after = true;
                continue;
            }
            if ((shuffle || after) && !queued.contains(t)) out.add(t);
        }
        if (shuffle) java.util.Collections.shuffle(out, random);
        return out;
    }

    /**
     * Solar's shuffle was switched. While a run song is playing the run takes
     * it: what it has not played is reordered, and the few songs queued ahead
     * are replaced from the new order.
     *
     * Solar's own reshuffle permutes every song in the queue, which suits an
     * album and not a run -- played songs (their audio possibly emptied) land
     * ahead of the head, and the run's last song, the one it queues behind,
     * can land behind it, which ends the run.
     *
     * @return true when the run handled it and Solar should not reshuffle
     */
    public boolean handleShuffleChanged(boolean on) {
        int at = runQueued.indexOf(Long.valueOf(playingDeezerId));
        DiscoveryClient.Track current = runTracks.get(Long.valueOf(playingDeezerId));
        if (at < 0 || current == null || runPlaylist.isEmpty()) return false;
        final int generation = ++queueGeneration;   // abandon any search in flight
        runResolving = false;
        java.util.Set<Long> ahead = new java.util.HashSet<Long>();
        while (runQueued.size() > at + 1) {
            Long id = runQueued.remove(runQueued.size() - 1);
            runTracks.remove(id);
            ahead.add(id);
        }
        if (!ahead.isEmpty()) host.queueDeezerTracks(new ArrayList<DeezerResult>(), ahead);
        runPending.clear();
        runPending.addAll(pendingOrder(runPlaylist, current,
                new java.util.HashSet<DiscoveryClient.Track>(runTracks.values()), on, random));
        topUp(generation);
        return true;
    }

    /**
     * MainActivity says this Deezer id started playing. When it is one of the
     * run's, the heart follows it -- it used to know only the song first
     * picked, so a hold on the next one fell through to the Navidrome heart --
     * and the queue is topped up behind it.
     */
    /** The listener moved on to other music: stop filling this run's queue. */
    public void abandonRun() {
        queueGeneration++;
        runPending.clear();
    }

    public void onDeezerTrackPlaying(long deezerTrackId) {
        playingDeezerId = deezerTrackId;
        if (!discoveryIds.isEmpty()) host.releaseDeezerAudioBehind(KEEP_BEHIND, discoveryIds);
        DiscoveryClient.Track t = runTracks.get(Long.valueOf(deezerTrackId));
        judgePrevious(deezerTrackId, t);
        if (t == null) return;
        lastPlayed = t;
        lastPlayedDeezerId = deezerTrackId;
        reportPlayed(t);
        topUp(queueGeneration);
    }

    /** 2026-09-19 — Tell nookTunes this song was heard, for its daily rotation. */
    private void reportPlayed(final DiscoveryClient.Track t) {
        if (t.key == null || t.key.length() == 0 || !isConfigured()) return;
        final String base = baseUrl();
        host.runOnBg(new Runnable() {
            @Override public void run() {
                if (!DiscoveryClient.played(base, t.key)) {
                    android.util.Log.w("SolarDiscovery", "could not report played " + t.key);
                }
            }
        });
    }

    /**
     * 2026-10-20 — Another song started: say whether the run song before it was
     * skipped or heard out, then start timing {@code now} (null when what plays
     * now is not the run's). Timed by the wall clock from when it became the
     * playing song, so a pause in the middle counts as listening.
     */
    private void judgePrevious(long nowId, DiscoveryClient.Track now) {
        long at = android.os.SystemClock.elapsedRealtime();
        if (outcomeTrack != null && nowId != outcomeTrackId) {
            reportOutcome(outcomeTrack, outcome(at - outcomeStartedAt, outcomeTrack.durationSec));
        }
        outcomeTrack = now;
        outcomeTrackId = nowId;
        outcomeStartedAt = at;
    }

    /** Skip when under {@link #SKIP_UNDER_MS}, or under {@link #SKIP_UNDER_FRACTION} of a known length. */
    static String outcome(long listenedMs, int durationSec) {
        if (listenedMs < SKIP_UNDER_MS) return DiscoveryClient.OUTCOME_SKIP;
        if (durationSec > 0 && listenedMs < durationSec * 1000L * SKIP_UNDER_FRACTION) {
            return DiscoveryClient.OUTCOME_SKIP;
        }
        return DiscoveryClient.OUTCOME_FULL;
    }

    private void reportOutcome(final DiscoveryClient.Track t, final String outcome) {
        if (t.key == null || t.key.length() == 0 || !isConfigured()) return;
        final String base = baseUrl();
        host.runOnBg(new Runnable() {
            @Override public void run() {
                if (!DiscoveryClient.playedOutcome(base, t.key, outcome)) {
                    android.util.Log.w("SolarDiscovery", "could not report " + outcome + " " + t.key);
                }
            }
        });
    }

    /**
     * How many more to queue so {@link #QUEUE_AHEAD} sit behind the song
     * playing. {@code playingAt} is its place in the run, -1 when what is
     * playing is not the run's: then nothing, and the queue is left alone.
     */
    static int songsWanted(int queued, int playingAt) {
        if (playingAt < 0) return 0;
        return Math.max(0, QUEUE_AHEAD - (queued - 1 - playingAt));
    }

    private void topUp(final int generation) {
        if (runResolving || generation != queueGeneration) return;
        int want = songsWanted(runQueued.size(), runQueued.indexOf(Long.valueOf(playingDeezerId)));
        if (runPending.isEmpty() || want == 0) {
            dropPreviousRun();
            return;
        }
        final List<DiscoveryClient.Track> batch = new ArrayList<DiscoveryClient.Track>();
        while (batch.size() < want && !runPending.isEmpty()) batch.add(runPending.remove(0));
        final java.util.Set<Long> have = new java.util.HashSet<Long>(runQueued);
        runResolving = true;
        // Resolving a country or seed row costs a search each, so this runs off
        // the UI thread and skips anything it cannot vouch for rather than
        // queueing the wrong song.
        host.runOnBg(new Runnable() {
            @Override public void run() {
                final List<DeezerResult> found = new ArrayList<DeezerResult>();
                final List<DiscoveryClient.Track> rows = new ArrayList<DiscoveryClient.Track>();
                for (DiscoveryClient.Track t : batch) {
                    // A newer pick supersedes this run; stop searching for it.
                    if (generation != queueGeneration) return;
                    DeezerResult r = resolve(t);
                    if (r == null || !have.add(Long.valueOf(r.id))) continue;
                    found.add(r);
                    rows.add(t);
                }
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        runResolving = false;
                        if (generation != queueGeneration) return;
                        if (!found.isEmpty() && !deliver(found)) {
                            // The run's last song has left the queue: the
                            // listener moved on, and this is not theirs to extend.
                            runPending.clear();
                            return;
                        }
                        for (int i = 0; i < found.size(); i++) {
                            Long id = Long.valueOf(found.get(i).id);
                            runQueued.add(id);
                            runTracks.put(id, rows.get(i));
                            queuedIds.add(id);
                            discoveryIds.add(id);
                        }
                        if (!found.isEmpty()) saveRunQueued();
                        // Still short when songs failed to resolve, or when
                        // playback moved on while this was searching.
                        topUp(generation);
                    }
                });
            }
        });
    }

    /**
     * Put resolved songs in the queue. The first delivery of a run goes in
     * behind the play head and drops the previous run's tail, as the whole
     * playlist used to; the rest go directly behind the run's last song, so
     * the order holds wherever the play head is.
     */
    private boolean deliver(List<DeezerResult> found) {
        if (runReplace != null) {
            java.util.Set<Long> replace = runReplace;
            runReplace = null;
            saveRunQueued();
            host.queueDeezerTracks(found, replace);
            return true;
        }
        long anchor = runQueued.get(runQueued.size() - 1).longValue();
        return host.queueDeezerTracksAfter(found, anchor);
    }

    /** A run with nothing to add still drops the previous run's tail. */
    private void dropPreviousRun() {
        if (runReplace == null) return;
        java.util.Set<Long> replace = runReplace;
        runReplace = null;
        saveRunQueued();
        if (!replace.isEmpty()) host.queueDeezerTracks(new ArrayList<DeezerResult>(), replace);
    }

    /** A row as something Deezer can play: its own id, or a search it can vouch for. */
    private DeezerResult resolve(DiscoveryClient.Track t) {
        if (t.deezerId > 0) {
            return new DeezerResult(t.deezerId, t.title, t.artist, t.album,
                    0L, t.durationSec, "", t.cover);
        }
        DeezerResult direct = directResult(t);
        if (direct != null) return direct;
        try {
            return DiscoveryMatch.best(search().searchTracks(t.searchQuery()), t.artist, t.title);
        } catch (Exception ignored) {
            // One unreachable search should not cost the rest.
            return null;
        }
    }

    /**
     * 2026-10-25 — A song Deezer lacks that nookTunes serves itself (Bandcamp
     * Daily picks): a stand-in negative id the downloader maps to nookTunes.
     */
    private static DeezerResult directResult(DiscoveryClient.Track t) {
        if (t.streamUrl.isEmpty()) return null;
        long id = com.solar.launcher.deezer.DirectStreams.idForKey(t.key);
        com.solar.launcher.deezer.DirectStreams.put(id, t.streamUrl);
        return new DeezerResult(id, t.title, t.artist, t.album, 0L, t.durationSec, "", t.cover);
    }

    /**
     * Keep the playing song, but only if it is one Discovery queued: the song
     * picked or any the run has played since.
     *
     * The id comes from the queue item itself, so this cannot heart the wrong
     * song once the listener has moved on to something Discovery did not pick.
     */
    public boolean heartIfDeezerTrack(long deezerTrackId) {
        DiscoveryClient.Track t = playingTrackFor(deezerTrackId);
        return t != null && heartTrack(t);
    }

    /**
     * 2026-10-21 — Q9: the Discovery row behind the playing Deezer id, by the
     * same match {@link #heartIfDeezerTrack} makes, or null when Discovery did
     * not queue it. The hold menu reads it once, when it opens, so the song it
     * acts on is the one that was playing then.
     */
    public DiscoveryClient.Track playingTrackFor(long deezerTrackId) {
        if (lastPlayed == null || deezerTrackId == 0
                || lastPlayedDeezerId != deezerTrackId) {
            return null;
        }
        return lastPlayed;
    }

    /**
     * Keep whatever was last started here. nookTunes fetches the lossless
     * original for the archive, so the compressed copy the device played is
     * never what gets kept.
     *
     * @return false when nothing has played yet, so the caller can fall back to
     *         its usual heart.
     */
    public boolean heartLastPlayed() {
        return heartTrack(lastPlayed);
    }

    /** Keep {@code t}, as {@link #heartLastPlayed} does; false when it is null. */
    public boolean heartTrack(final DiscoveryClient.Track t) {
        if (t == null) return false;
        Toast.makeText(host.context(), host.string(R.string.discovery_keeping, t.title),
                Toast.LENGTH_SHORT).show();
        // 2026-09-17 — Put the artist in this device's Get Music history now,
        // rather than waiting for nookman to hand it back.
        //
        // nookTunes queues the name and y1sync applies it on a timer, which
        // means up to fifteen minutes, and only to whichever player
        // Y1_ADB_HOSTS points at — on 2026-09-17 that was the other Y1, so a
        // heart made here would have shown up in the test device's history and
        // never in this one's. The artist is already in hand; writing it
        // locally is immediate, needs no network, and cannot land on the wrong
        // device. remember() de-duplicates and caps the list itself, so the
        // server's copy arriving later is harmless.
        com.solar.launcher.GetMusicSearchHistory.remember(host.prefs(), t.artist);
        // Still asynchronous — nookTunes may have to fetch the lossless
        // original, far longer than a button press should block for — but no
        // longer forgotten. The press is acknowledged by the caller; this
        // reports what the server actually said, whenever it says it.
        host.runOnBg(new Runnable() {
            @Override public void run() {
                final boolean ok = DiscoveryClient.heart(baseUrl(), t.key);
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        host.heartFeedback(ok, t.title);
                    }
                });
            }
        });
        return true;
    }

    private void render() {
        if (host.isNowPlayingScreen()) return;
        // 2026-09-19 — A late fetch must not draw over the screen the user went to;
        // open()/reopen() render again on the way back.
        if (!host.isDiscoveryScreen()) return;
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        String title = uiMode == UI_TRACKS && openPlaylist != null
                ? openPlaylist.name
                : uiMode == UI_PLAYLISTS && openFolder != null
                ? openFolder : host.string(R.string.home_menu_discovery);
        host.updateStatusBarTitle(title);
        if (host.tvBrowserPath() != null) host.tvBrowserPath().setText(title);

        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();

        if (loading) {
            container.addView(host.createListButton(host.string(R.string.discovery_loading)));
            renderedFirstRow = container.getChildAt(0);
            return;
        }
        if (error != null) {
            container.addView(host.createListButton(
                    host.string(R.string.discovery_error, error)));
            renderedFirstRow = container.getChildAt(0);
            return;
        }
        if (uiMode == UI_TRACKS) {
            renderTracks(container);
        } else if (uiMode == UI_FOLDERS) {
            renderFolders(container);
        } else {
            renderPlaylists(container);
        }
        renderedFirstRow = container.getChildCount() > 0 ? container.getChildAt(0) : null;
        int at = focusIndex;
        focusIndex = -1;
        focusRow(container, at >= 0 && at < container.getChildCount() ? at : 0);
    }

    /** True while the list on screen is the one render() drew. */
    private boolean listShown() {
        LinearLayout c = host.containerBrowserItems();
        return c != null && c.getChildCount() > 0 && c.getChildAt(0) == renderedFirstRow;
    }

    /**
     * Give a row focus after rebuilding the list: the first, or the one Back
     * left (the scroll view brings it into sight).
     *
     * render() replaces every child, which destroys whatever had focus, and a
     * list with nothing focused swallows the wheel: the centre key reaches
     * MainActivity's global handler and there is no row for it to activate.
     * Posting it runs after layout, when the view can actually take focus.
     */
    private void focusRow(final LinearLayout container, int index) {
        if (container.getChildCount() == 0) return;
        final View row = container.getChildAt(index);
        row.post(new Runnable() {
            @Override public void run() {
                row.setFocusable(true);
                row.setFocusableInTouchMode(true);
                row.requestFocus();
            }
        });
    }

    /**
     * 2026-10-21 — Q18: one row per folder, its name and how many lists it
     * holds, drawn and chosen like the playlist rows.
     */
    private void renderFolders(LinearLayout container) {
        for (final String f : catalog.folders()) {
            Button b = host.createListButton(
                    DiscoveryFolders.folderLabel(f, catalog.inFolder(f).size()));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    host.clickFeedback();
                    openFolder(f);
                }
            });
            container.addView(b);
        }
    }

    private void renderPlaylists(LinearLayout container) {
        List<DiscoveryClient.Playlist> playlists = shownPlaylists();
        if (playlists.isEmpty()) {
            container.addView(host.createListButton(host.string(R.string.discovery_empty)));
            return;
        }
        for (final DiscoveryClient.Playlist p : playlists) {
            Button b = host.createListButton(p.name);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    host.clickFeedback();
                    fetchTracks(p);
                }
            });
            container.addView(b);
        }
    }

    private void renderTracks(LinearLayout container) {
        Button back = host.createListButton(host.string(R.string.discovery_back_playlists));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                handleBackPress();
            }
        });
        container.addView(back);

        if (tracks.isEmpty()) {
            container.addView(host.createListButton(host.string(R.string.discovery_empty)));
            return;
        }
        for (final DiscoveryClient.Track t : tracks) {
            Button b = host.createListButton(t.displayTitle());
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    host.clickFeedback();
                    play(t);
                }
            });
            container.addView(b);
        }
    }
}
