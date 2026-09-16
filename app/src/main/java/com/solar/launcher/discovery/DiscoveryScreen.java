package com.solar.launcher.discovery;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.solar.launcher.R;
import com.solar.launcher.deezer.DeezerResult;
import com.solar.launcher.deezer.DeezerScreen;

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
 * Genre rows only, for now. A Deezer genre chart is Deezer end to end, so every
 * song carries an exact id and needs no searching; the country and seed rows
 * come from Apple and Spotify and have to be matched by artist and title first.
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
                    for (DiscoveryClient.Playlist p : DiscoveryClient.playlists(baseUrl())) {
                        if (p.resolvesExactly()) got.add(p);
                    }
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

    private void play(DiscoveryClient.Track t) {
        if (t.deezerId <= 0) {
            // Only reachable if a genre chart ever serves a row without an id.
            Toast.makeText(host.context(), host.string(R.string.discovery_no_source),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!host.requireInternet(R.string.deezer_wifi_required)) return;
        lastPlayed = t;
        deezer.startTransfer(new DeezerResult(t.deezerId, t.title, t.artist, t.album,
                0L, t.durationSec, "", t.cover), DeezerScreen.ACTION_PLAY);
    }

    /**
     * Keep the playing song, but only if it is the one Discovery started.
     *
     * The id comes from the queue item itself, so this cannot heart the wrong
     * song once the listener has moved on to something Discovery did not pick.
     */
    public boolean heartIfDeezerTrack(long deezerTrackId) {
        DiscoveryClient.Track t = lastPlayed;
        if (t == null || deezerTrackId <= 0 || t.deezerId != deezerTrackId) return false;
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
        // Fire and forget: nookTunes may have to fetch the lossless original,
        // which takes far longer than a button press should block for.
        host.runOnBg(new Runnable() {
            @Override public void run() {
                DiscoveryClient.heart(baseUrl(), t.key);
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
