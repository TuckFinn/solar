package com.solar.launcher.navidrome;

import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import com.solar.launcher.R;
import com.solar.launcher.debug.AgentDebugLog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-06: Navidrome browse — library-parity hub (Artists/Albums/Playlists/Search) + fast ListView drill-down.
 */
public final class NavidromeScreenHost {

    public interface Actions {
        android.app.Activity activity();
        void clickFeedback();
        Button createListButton(String label);
        void configureListButton(Button btn);
        void showScrollBrowse();
        void showFastListBrowse();
        void addScrollRow(View row);
        void setFastListAdapter(BaseAdapter adapter);
        void focusBrowse();
        void setScrollIndexNames(List<String> names);
        void setStatusTitle(String title);
        void updateStatusBar();
        void setBreadcrumb(String path);
        void playSongs(List<NavidromeSong> songs, int startIndex, String label);
        void downloadAlbum(NavidromeArtist artist, NavidromeAlbum album, List<NavidromeSong> songs);
        void downloadSong(NavidromeSong song);
        boolean requireInternet(int messageRes);
        void openSearchKeyboard(String prefill);
        int getListSelectedPosition();
        void setListSelectedPosition(int position);
        void applyListRowParams(View row, int heightPx);
        int rowHeightPx();
        void onRowFocused(NavidromeBrowseRow row);
    }

    public static final int KEYBOARD_SEARCH = 20;

    private static final int UI_ROOT = 0;
    private static final int UI_ARTISTS = 1;
    private static final int UI_ALBUMS = 2;
    private static final int UI_PLAYLISTS = 3;
    private static final int UI_SONGS = 4;
    private static final int UI_SEARCH = 5;
    private static final int UI_TRACKS = 6;

    private final Actions actions;
    private final NavidromeBrowseAdapter adapter;
    private int uiMode = UI_ROOT;
    private List<NavidromeArtist> artists = new ArrayList<NavidromeArtist>();
    private List<NavidromeAlbum> albums = new ArrayList<NavidromeAlbum>();
    private List<NavidromeSong> songs = new ArrayList<NavidromeSong>();
    private List<NavidromePlaylist> playlists = new ArrayList<NavidromePlaylist>();
    private NavidromeArtist selectedArtist;
    private NavidromeAlbum selectedAlbum;
    private NavidromePlaylist selectedPlaylist;
    private String searchQuery = "";

    public NavidromeScreenHost(Actions actions) {
        this.actions = actions;
        this.adapter = new NavidromeBrowseAdapter(new NavidromeBrowseAdapter.RowUi() {
            @Override public Button createListButton(String label) {
                return NavidromeScreenHost.this.actions.createListButton(label);
            }
            @Override public void bindListButton(Button btn, boolean focused, String label) {
                NavidromeScreenHost.this.actions.configureListButton(btn);
            }
            @Override public void applyListRowParams(View row, int heightPx) {
                NavidromeScreenHost.this.actions.applyListRowParams(row, heightPx);
            }
            @Override public int rowHeightPx() { return NavidromeScreenHost.this.actions.rowHeightPx(); }
            @Override public void onRowClick(NavidromeBrowseRow row) {
                NavidromeScreenHost.this.onRowClick(row);
            }
            @Override public void onRowFocused(NavidromeBrowseRow row, boolean hasFocus) {
                if (hasFocus) NavidromeScreenHost.this.actions.onRowFocused(row);
            }
        });
    }

    public void open() {
        if (!NavidromeClient.getInstance().isConfigured()) {
            return;
        }
        showRoot();
    }

    /** 2026-07-06: Back stack — true when consumed, false to exit Navidrome screen. */
    public boolean handleBack() {
        switch (uiMode) {
            case UI_ROOT:
                return false;
            case UI_ARTISTS:
            case UI_ALBUMS:
            case UI_PLAYLISTS:
            case UI_TRACKS:
            case UI_SEARCH:
                showRoot();
                return true;
            case UI_SONGS:
                if (selectedPlaylist != null) {
                    loadPlaylists();
                } else if (selectedArtist != null) {
                    openAlbums(selectedArtist);
                } else {
                    showRoot();
                }
                return true;
            default:
                showRoot();
                return true;
        }
    }

    /**
     * 2026-09-14: Re-show the level the user left — the owning playlist/album/list —
     * instead of resetting to the root. Back from Now Playing used to land on the
     * Navidrome root and force Playlists → selection → … all over again. Cached rows
     * are re-bound without a server round trip; the playing song's row is focused when
     * it is in the list. Levels with nothing cached reload as their entry points do.
     * Was: changeScreen(STATE_NAVIDROME) always called open() → showRoot().
     */
    public void reopen(String focusSongId) {
        if (!NavidromeClient.getInstance().isConfigured()) {
            return;
        }
        switch (uiMode) {
            case UI_SONGS:
                if (songs.isEmpty()) {
                    if (selectedPlaylist != null) {
                        openPlaylistSongs(selectedPlaylist);
                    } else if (selectedAlbum != null) {
                        openSongs(selectedAlbum);
                    } else {
                        showRoot();
                    }
                    return;
                }
                if (selectedPlaylist != null) {
                    showSongRows(selectedPlaylist.name);
                } else if (selectedAlbum != null) {
                    showSongRows(selectedAlbum.name);
                } else {
                    showRoot();
                    return;
                }
                focusSong(focusSongId);
                return;
            case UI_TRACKS:
                if (songs.isEmpty()) {
                    loadAllTracks();
                    return;
                }
                showSongRows(null);
                focusSong(focusSongId);
                return;
            case UI_PLAYLISTS:
                if (playlists.isEmpty()) {
                    loadPlaylists();
                } else {
                    showPlaylistRows();
                }
                return;
            case UI_ARTISTS:
                if (artists.isEmpty()) {
                    loadArtists();
                } else {
                    showArtistRows();
                }
                return;
            case UI_ALBUMS:
                if (albums.isEmpty()) {
                    if (selectedArtist != null) {
                        openAlbums(selectedArtist);
                    } else {
                        loadAlbums();
                    }
                } else {
                    showAlbumRows(selectedArtist != null ? selectedArtist.name : null);
                }
                return;
            case UI_SEARCH:
                finishSearchKeyboard(searchQuery);
                return;
            default:
                showRoot();
        }
    }
    /** Song rows are bound 1:1 from {@link #songs}, so the list index is the song index. */
    private void focusSong(String songId) {
        if (songId == null || songId.isEmpty()) return;
        for (int i = 0; i < songs.size(); i++) {
            NavidromeSong s = songs.get(i);
            if (s != null && songId.equals(s.id)) {
                actions.setListSelectedPosition(i);
                return;
            }
        }
    }
    public NavidromeSong getFocusedSong() {
        if (uiMode != UI_SONGS && uiMode != UI_TRACKS) return null;
        int pos = actions.getListSelectedPosition();
        NavidromeBrowseRow row = adapter.rowAt(pos);
        return row != null ? row.song : null;
    }

    public NavidromeAlbum getFocusedAlbumContext() {
        return selectedAlbum;
    }

    /** 2026-07-15 — True while Albums list (all albums or under an artist) is on screen. */
    public boolean isAlbumListVisible() {
        return uiMode == UI_ALBUMS;
    }

    /** 2026-07-15 — Focused album row for Save-album context menu. */
    public NavidromeAlbum getFocusedAlbum() {
        if (uiMode != UI_ALBUMS) return null;
        int pos = actions.getListSelectedPosition();
        NavidromeBrowseRow row = adapter.rowAt(pos);
        return row != null ? row.album : null;
    }

    /** 2026-07-06: Preview pane row lookup by ListView selection index. */
    public NavidromeBrowseRow getBrowseRowAt(int position) {
        return adapter.rowAt(position);
    }

    public List<NavidromeSong> getCurrentSongs() {
        return songs;
    }

    public boolean isSongListVisible() {
        return uiMode == UI_SONGS || uiMode == UI_TRACKS;
    }

    public void finishSearchKeyboard(String query) {
        searchQuery = query != null ? query.trim() : "";
        if (searchQuery.isEmpty()) {
            showRoot();
            return;
        }
        uiMode = UI_SEARCH;
        actions.setStatusTitle(actions.activity().getString(R.string.navidrome_search_title, searchQuery));
        showLoadingList();
        NavidromeClient.getInstance().search(searchQuery, new NavidromeClient.Callback<NavidromeSearchResult>() {
            @Override public void onSuccess(NavidromeSearchResult result) {
                showSearchResults(result);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showRoot() {
        uiMode = UI_ROOT;
        selectedArtist = null;
        selectedAlbum = null;
        selectedPlaylist = null;
        actions.showScrollBrowse();
        actions.setStatusTitle(actions.activity().getString(R.string.navidrome_menu));
        actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_root));
        addRootRow(R.string.browser_artists, new Runnable() {
            @Override public void run() { loadArtists(); }
        });
        addRootRow(R.string.browser_albums, new Runnable() {
            @Override public void run() { loadAlbums(); }
        });
        addRootRow(R.string.browser_playlists, new Runnable() {
            @Override public void run() { loadPlaylists(); }
        });
        addRootRow(R.string.browser_all_songs, new Runnable() {
            @Override public void run() { loadAllTracks(); }
        });
        addRootRow(R.string.browser_search, new Runnable() {
            @Override public void run() {
                actions.openSearchKeyboard(searchQuery);
            }
        });
        actions.focusBrowse();
        actions.updateStatusBar();
    }

    private void addRootRow(int labelRes, final Runnable action) {
        Button row = actions.createListButton(actions.activity().getString(labelRes));
        actions.configureListButton(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                action.run();
            }
        });
        actions.addScrollRow(row);
    }

    private void loadArtists() {
        uiMode = UI_ARTISTS;
        actions.setStatusTitle(actions.activity().getString(R.string.navidrome_artists));
        showLoadingList();
        NavidromeClient.getInstance().getArtists(actions.activity(),
                new NavidromeClient.Callback<List<NavidromeArtist>>() {
            @Override public void onSuccess(List<NavidromeArtist> result) {
                artists = result != null ? result : new ArrayList<NavidromeArtist>();
                showArtistRows();
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void loadAlbums() {
        uiMode = UI_ALBUMS;
        selectedArtist = null;
        actions.setStatusTitle(actions.activity().getString(R.string.status_library_albums));
        showLoadingList();
        NavidromeClient.getInstance().getAlbumList(new NavidromeClient.Callback<List<NavidromeAlbum>>() {
            @Override public void onSuccess(List<NavidromeAlbum> result) {
                albums = result != null ? result : new ArrayList<NavidromeAlbum>();
                showAlbumRows(null);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void loadAllTracks() {
        uiMode = UI_TRACKS;
        selectedArtist = null;
        selectedAlbum = null;
        selectedPlaylist = null;
        actions.setStatusTitle(actions.activity().getString(R.string.status_library_all_songs));
        showLoadingList();
        NavidromeClient.getInstance().getAllTracks(new NavidromeClient.Callback<List<NavidromeSong>>() {
            @Override public void onSuccess(List<NavidromeSong> result) {
                songs = result != null ? result : new ArrayList<NavidromeSong>();
                showSongRows(actions.activity().getString(R.string.browser_all_songs));
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void loadPlaylists() {
        uiMode = UI_PLAYLISTS;
        actions.setStatusTitle(actions.activity().getString(R.string.status_library_playlists));
        showLoadingList();
        NavidromeClient.getInstance().getPlaylists(new NavidromeClient.Callback<List<NavidromePlaylist>>() {
            @Override public void onSuccess(List<NavidromePlaylist> result) {
                playlists = result != null ? result : new ArrayList<NavidromePlaylist>();
                showPlaylistRows();
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openAlbums(final NavidromeArtist artist) {
        selectedArtist = artist;
        uiMode = UI_ALBUMS;
        actions.setStatusTitle(artist.name);
        showLoadingList();
        NavidromeClient.getInstance().getArtistAlbums(artist.id,
                new NavidromeClient.Callback<List<NavidromeAlbum>>() {
            @Override public void onSuccess(List<NavidromeAlbum> result) {
                albums = result != null ? result : new ArrayList<NavidromeAlbum>();
                showAlbumRows(artist.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openSongs(final NavidromeAlbum album) {
        selectedAlbum = album;
        selectedPlaylist = null;
        uiMode = UI_SONGS;
        showLoadingList();
        NavidromeClient.getInstance().getAlbumSongs(album.id,
                new NavidromeClient.Callback<List<NavidromeSong>>() {
            @Override public void onSuccess(List<NavidromeSong> result) {
                songs = result != null ? result : new ArrayList<NavidromeSong>();
                showSongRows(album.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openPlaylistSongs(final NavidromePlaylist playlist) {
        selectedPlaylist = playlist;
        selectedAlbum = null;
        uiMode = UI_SONGS;
        showLoadingList();
        NavidromeClient.getInstance().getPlaylistSongs(playlist.id,
                new NavidromeClient.Callback<List<NavidromeSong>>() {
            @Override public void onSuccess(List<NavidromeSong> result) {
                songs = result != null ? result : new ArrayList<NavidromeSong>();
                showSongRows(playlist.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showArtistRows() {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_artists));
        List<NavidromeBrowseRow> rows = new ArrayList<NavidromeBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (NavidromeArtist a : artists) {
            NavidromeBrowseRow row = new NavidromeBrowseRow();
            row.kind = NavidromeBrowseRow.Kind.ARTIST;
            row.label = a.name;
            row.subtitle = a.albumCount > 0 ? String.valueOf(a.albumCount) : "";
            row.coverArtId = NavidromeCoverArt.artIdForArtist(a);
            row.artist = a;
            rows.add(row);
            index.add(a.name);
        }
        bindFastList(rows, index);
    }

    private void showAlbumRows(String artistName) {
        if (artistName != null) {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_albums, artistName));
        } else {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_all_albums));
        }
        List<NavidromeBrowseRow> rows = new ArrayList<NavidromeBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (NavidromeAlbum al : albums) {
            NavidromeBrowseRow row = new NavidromeBrowseRow();
            row.kind = NavidromeBrowseRow.Kind.ALBUM;
            row.label = al.name;
            row.subtitle = al.artist;
            if (al.year > 0) row.subtitle += (row.subtitle.isEmpty() ? "" : " · ") + al.year;
            row.coverArtId = NavidromeCoverArt.artIdForAlbum(al);
            row.album = al;
            rows.add(row);
            index.add(al.name);
        }
        bindFastList(rows, index);
    }

    private void showPlaylistRows() {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_playlists));
        List<NavidromeBrowseRow> rows = new ArrayList<NavidromeBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (NavidromePlaylist p : playlists) {
            NavidromeBrowseRow row = new NavidromeBrowseRow();
            row.kind = NavidromeBrowseRow.Kind.PLAYLIST;
            row.label = p.name;
            row.subtitle = p.songCount > 0 ? String.valueOf(p.songCount) : "";
            row.playlist = p;
            rows.add(row);
            index.add(p.name);
        }
        bindFastList(rows, index);
    }

    private void showSongRows(String title) {
        if (uiMode == UI_TRACKS) {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_all_songs));
        } else {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_songs, title));
        }
        List<NavidromeBrowseRow> rows = new ArrayList<NavidromeBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (NavidromeSong s : songs) {
            NavidromeBrowseRow row = new NavidromeBrowseRow();
            row.kind = NavidromeBrowseRow.Kind.SONG;
            row.label = s.title;
            row.subtitle = s.artist;
            row.coverArtId = NavidromeCoverArt.artIdForSong(s, selectedAlbum);
            row.song = s;
            rows.add(row);
            index.add(s.title);
        }
        bindFastList(rows, index);
    }

    private void showSearchResults(NavidromeSearchResult result) {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_navidrome_search, searchQuery));
        songs = new ArrayList<NavidromeSong>();
        List<NavidromeBrowseRow> rows = new ArrayList<NavidromeBrowseRow>();
        List<String> index = new ArrayList<String>();
        if (result != null) {
            for (NavidromeArtist a : result.artists) {
                NavidromeBrowseRow row = new NavidromeBrowseRow();
                row.kind = NavidromeBrowseRow.Kind.ARTIST;
                row.label = a.name;
                row.coverArtId = NavidromeCoverArt.artIdForArtist(a);
                row.artist = a;
                rows.add(row);
                index.add(a.name);
            }
            for (NavidromeAlbum al : result.albums) {
                NavidromeBrowseRow row = new NavidromeBrowseRow();
                row.kind = NavidromeBrowseRow.Kind.ALBUM;
                row.label = al.name;
                row.subtitle = al.artist;
                row.coverArtId = NavidromeCoverArt.artIdForAlbum(al);
                row.album = al;
                rows.add(row);
                index.add(al.name);
            }
            for (NavidromeSong s : result.songs) {
                songs.add(s);
                NavidromeBrowseRow row = new NavidromeBrowseRow();
                row.kind = NavidromeBrowseRow.Kind.SONG;
                row.label = s.title;
                row.subtitle = s.artist;
                row.coverArtId = NavidromeCoverArt.artIdForSong(s, null);
                row.song = s;
                rows.add(row);
                index.add(s.title);
            }
        }
        if (rows.isEmpty()) {
            showScrollBrowseEmpty(actions.activity().getString(R.string.navidrome_search_empty));
            return;
        }
        bindFastList(rows, index);
    }

    private void bindFastList(List<NavidromeBrowseRow> rows, List<String> indexNames) {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("uiMode", uiMode);
            d.put("rowCount", rows.size());
            AgentDebugLog.log("NavidromeScreenHost.bindFastList", "B", "list bind", d);
        } catch (Exception ignored) {}
        // #endregion
        actions.showFastListBrowse();
        actions.setScrollIndexNames(indexNames);
        adapter.setRows(rows);
        actions.setFastListAdapter(adapter);
        actions.focusBrowse();
        actions.updateStatusBar();
    }

    private void showLoadingList() {
        actions.showScrollBrowse();
        Button loading = actions.createListButton(actions.activity().getString(R.string.navidrome_loading));
        loading.setEnabled(false);
        actions.addScrollRow(loading);
        actions.focusBrowse();
    }

    private void showError(String message) {
        showScrollBrowseEmpty(message != null ? message : "Error");
    }

    private void showScrollBrowseEmpty(String message) {
        actions.showScrollBrowse();
        Button err = actions.createListButton(message);
        err.setEnabled(false);
        actions.addScrollRow(err);
        actions.focusBrowse();
        actions.updateStatusBar();
    }

    private void onRowClick(NavidromeBrowseRow row) {
        if (row == null) return;
        actions.clickFeedback();
        if (row.kind == NavidromeBrowseRow.Kind.ARTIST && row.artist != null) {
            openAlbums(row.artist);
        } else if (row.kind == NavidromeBrowseRow.Kind.ALBUM && row.album != null) {
            openSongs(row.album);
        } else if (row.kind == NavidromeBrowseRow.Kind.PLAYLIST && row.playlist != null) {
            openPlaylistSongs(row.playlist);
        } else if (row.kind == NavidromeBrowseRow.Kind.SONG && row.song != null) {
            if (!actions.requireInternet(R.string.navidrome_wifi_required)) return;
            int idx = indexOfSongRow(row.song);
            if (idx < 0) idx = 0;
            String label = selectedPlaylist != null ? selectedPlaylist.name
                    : (selectedAlbum != null ? selectedAlbum.name
                    : (uiMode == UI_TRACKS
                            ? actions.activity().getString(R.string.browser_all_songs)
                            : row.song.album));
            actions.playSongs(songs, idx, label);
        }
    }

    /** 2026-07-06: Match selected row to loaded song list for queue start index. */
    private int indexOfSongRow(NavidromeSong song) {
        if (song == null || songs == null) return actions.getListSelectedPosition();
        for (int i = 0; i < songs.size(); i++) {
            NavidromeSong s = songs.get(i);
            if (s.id != null && s.id.equals(song.id)) return i;
        }
        int pos = actions.getListSelectedPosition();
        return pos >= 0 && pos < songs.size() ? pos : 0;
    }
}
