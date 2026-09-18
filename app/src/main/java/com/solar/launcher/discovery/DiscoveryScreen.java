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

    private final DeezerScreen.Host host;
    private final DeezerScreen deezer;

    private int uiMode = UI_PLAYLISTS;
    private final List<DiscoveryClient.Playlist> playlists =
            new ArrayList<DiscoveryClient.Playlist>();
    private final List<DiscoveryClient.Track> tracks = new ArrayList<DiscoveryClient.Track>();
    private DiscoveryClient.Playlist openPlaylist;
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

    public DiscoveryScreen(DeezerScreen.Host host, DeezerScreen deezer) {
        this.host = host;
        this.deezer = deezer;
    }

    public int uiMode() { return uiMode; }

    public String baseUrl() {
        return host.prefs() != null ? host.prefs().getString("navidrome_url", "") : "";
    }

    public boolean isConfigured() {
        return !baseUrl().trim().isEmpty();
    }

    /** Entry point: show the playlist tier, fetching it if we have nothing. */
    public void open() {
        uiMode = UI_PLAYLISTS;
        openPlaylist = null;
        if (playlists.isEmpty() && !loading) {
            fetchPlaylists();
        } else {
            render();
        }
    }

    /** @return true when back was consumed here rather than leaving the screen. */
    public boolean handleBackPress() {
        if (uiMode == UI_TRACKS) {
            uiMode = UI_PLAYLISTS;
            openPlaylist = null;
            render();
            return true;
        }
        return false;
    }

    private void fetchPlaylists() {
        loading = true;
        error = null;
        render();
        host.runOnBg(new Runnable() {
            @Override public void run() {
                final List<DiscoveryClient.Playlist> got =
                        new ArrayList<DiscoveryClient.Playlist>();
                String err = null;
                try {
                    // Everything nookTunes offers. It decides which seed-artist
                    // rows are worth showing and in what order -- it has the
                    // play history to rank them and this does not -- so the
                    // list is taken as given rather than filtered again here.
                    got.addAll(DiscoveryClient.playlists(baseUrl()));
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final String fErr = err;
                host.runOnUi(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        error = fErr;
                        playlists.clear();
                        playlists.addAll(got);
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

    private void play(final DiscoveryClient.Track t) {
        if (!host.requireInternet(R.string.deezer_wifi_required)) return;
        if (t.deezerId > 0) {
            start(t, new DeezerResult(t.deezerId, t.title, t.artist, t.album,
                    0L, t.durationSec, "", t.cover));
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
        runPending.clear();
        boolean after = false;
        for (DiscoveryClient.Track t : tracks) {
            if (after) runPending.add(t);
            if (t == from) after = true;
        }
        runQueued.clear();
        runTracks.clear();
        runQueued.add(Long.valueOf(fromId));
        runTracks.put(Long.valueOf(fromId), from);
        runReplace = queuedIds;
        queuedIds = new java.util.HashSet<Long>();
        runResolving = false;
        playingDeezerId = fromId;
        topUp(generation);
    }

    /**
     * MainActivity says this Deezer id started playing. When it is one of the
     * run's, the heart follows it -- it used to know only the song first
     * picked, so a hold on the next one fell through to the Navidrome heart --
     * and the queue is topped up behind it.
     */
    public void onDeezerTrackPlaying(long deezerTrackId) {
        playingDeezerId = deezerTrackId;
        DiscoveryClient.Track t = runTracks.get(Long.valueOf(deezerTrackId));
        if (t == null) return;
        lastPlayed = t;
        lastPlayedDeezerId = deezerTrackId;
        topUp(queueGeneration);
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
                        }
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
        if (!replace.isEmpty()) host.queueDeezerTracks(new ArrayList<DeezerResult>(), replace);
    }

    /** A row as something Deezer can play: its own id, or a search it can vouch for. */
    private DeezerResult resolve(DiscoveryClient.Track t) {
        if (t.deezerId > 0) {
            return new DeezerResult(t.deezerId, t.title, t.artist, t.album,
                    0L, t.durationSec, "", t.cover);
        }
        try {
            return DiscoveryMatch.best(search().searchTracks(t.searchQuery()), t.artist, t.title);
        } catch (Exception ignored) {
            // One unreachable search should not cost the rest.
            return null;
        }
    }

    /**
     * Keep the playing song, but only if it is one Discovery queued: the song
     * picked or any the run has played since.
     *
     * The id comes from the queue item itself, so this cannot heart the wrong
     * song once the listener has moved on to something Discovery did not pick.
     */
    public boolean heartIfDeezerTrack(long deezerTrackId) {
        if (lastPlayed == null || deezerTrackId <= 0
                || lastPlayedDeezerId != deezerTrackId) {
            return false;
        }
        return heartLastPlayed();
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
        final DiscoveryClient.Track t = lastPlayed;
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
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        String title = uiMode == UI_TRACKS && openPlaylist != null
                ? openPlaylist.name : host.string(R.string.home_menu_discovery);
        host.updateStatusBarTitle(title);
        if (host.tvBrowserPath() != null) host.tvBrowserPath().setText(title);

        LinearLayout container = host.containerBrowserItems();
        container.removeAllViews();

        if (loading) {
            container.addView(host.createListButton(host.string(R.string.discovery_loading)));
            return;
        }
        if (error != null) {
            container.addView(host.createListButton(
                    host.string(R.string.discovery_error, error)));
            return;
        }
        if (uiMode == UI_TRACKS) {
            renderTracks(container);
        } else {
            renderPlaylists(container);
        }
        focusFirstRow(container);
    }

    /**
     * Give the first row focus after rebuilding the list.
     *
     * render() replaces every child, which destroys whatever had focus, and a
     * list with nothing focused swallows the wheel: the centre key reaches
     * MainActivity's global handler and there is no row for it to activate.
     * Posting it runs after layout, when the view can actually take focus.
     */
    private void focusFirstRow(final LinearLayout container) {
        if (container.getChildCount() == 0) return;
        final View first = container.getChildAt(0);
        first.post(new Runnable() {
            @Override public void run() {
                first.setFocusable(true);
                first.setFocusableInTouchMode(true);
                first.requestFocus();
            }
        });
    }

    private void renderPlaylists(LinearLayout container) {
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
