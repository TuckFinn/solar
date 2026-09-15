package com.solar.launcher.plex;

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
 * 2026-07-06: Plex browse — library-parity hub (Artists/Albums/Playlists/Search) + fast ListView drill-down.
 */
public final class PlexScreenHost {

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
        void playSongs(List<PlexSong> songs, int startIndex, String label);
        void downloadAlbum(PlexArtist artist, PlexAlbum album, List<PlexSong> songs);
        void downloadSong(PlexSong song);
        boolean requireInternet(int messageRes);
        void openSearchKeyboard(String prefill);
        int getListSelectedPosition();
        void applyListRowParams(View row, int heightPx);
        int rowHeightPx();
        void onRowFocused(PlexBrowseRow row);
    }

    public static final int KEYBOARD_SEARCH = 23;

    private static final int UI_ROOT = 0;
    private static final int UI_ARTISTS = 1;
    private static final int UI_ALBUMS = 2;
    private static final int UI_PLAYLISTS = 3;
    private static final int UI_SONGS = 4;
    private static final int UI_SEARCH = 5;
    private static final int UI_TRACKS = 6;

    private final Actions actions;
    private final PlexBrowseAdapter adapter;
    private int uiMode = UI_ROOT;
    private List<PlexArtist> artists = new ArrayList<PlexArtist>();
    private List<PlexAlbum> albums = new ArrayList<PlexAlbum>();
    private List<PlexSong> songs = new ArrayList<PlexSong>();
    private List<PlexPlaylist> playlists = new ArrayList<PlexPlaylist>();
    private PlexArtist selectedArtist;
    private PlexAlbum selectedAlbum;
    private PlexPlaylist selectedPlaylist;
    private String searchQuery = "";

    public PlexScreenHost(Actions actions) {
        this.actions = actions;
        this.adapter = new PlexBrowseAdapter(new PlexBrowseAdapter.RowUi() {
            @Override public Button createListButton(String label) {
                return PlexScreenHost.this.actions.createListButton(label);
            }
            @Override public void bindListButton(Button btn, boolean focused, String label) {
                PlexScreenHost.this.actions.configureListButton(btn);
            }
            @Override public void applyListRowParams(View row, int heightPx) {
                PlexScreenHost.this.actions.applyListRowParams(row, heightPx);
            }
            @Override public int rowHeightPx() { return PlexScreenHost.this.actions.rowHeightPx(); }
            @Override public void onRowClick(PlexBrowseRow row) {
                PlexScreenHost.this.onRowClick(row);
            }
            @Override public void onRowFocused(PlexBrowseRow row, boolean hasFocus) {
                if (hasFocus) PlexScreenHost.this.actions.onRowFocused(row);
            }
        });
    }

    public void open() {
        if (!PlexClient.getInstance().isConfigured()) {
            return;
        }
        showRoot();
    }

    /** 2026-07-06: Back stack — true when consumed, false to exit Plex screen. */
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

    public PlexSong getFocusedSong() {
        if (uiMode != UI_SONGS && uiMode != UI_TRACKS) return null;
        int pos = actions.getListSelectedPosition();
        PlexBrowseRow row = adapter.rowAt(pos);
        return row != null ? row.song : null;
    }

    public PlexAlbum getFocusedAlbumContext() {
        return selectedAlbum;
    }

    /** 2026-07-15 — True while Albums list (all albums or under an artist) is on screen. */
    public boolean isAlbumListVisible() {
        return uiMode == UI_ALBUMS;
    }

    /** 2026-07-15 — Focused album row for Save-album context menu. */
    public PlexAlbum getFocusedAlbum() {
        if (uiMode != UI_ALBUMS) return null;
        int pos = actions.getListSelectedPosition();
        PlexBrowseRow row = adapter.rowAt(pos);
        return row != null ? row.album : null;
    }

    /** 2026-07-06: Preview pane row lookup by ListView selection index. */
    public PlexBrowseRow getBrowseRowAt(int position) {
        return adapter.rowAt(position);
    }

    public List<PlexSong> getCurrentSongs() {
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
        actions.setStatusTitle(actions.activity().getString(R.string.plex_search_title, searchQuery));
        showLoadingList();
        PlexClient.getInstance().search(searchQuery, new PlexClient.Callback<PlexSearchResult>() {
            @Override public void onSuccess(PlexSearchResult result) {
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
        actions.setStatusTitle(actions.activity().getString(R.string.plex_menu));
        actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_root));
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
        actions.setStatusTitle(actions.activity().getString(R.string.plex_artists));
        showLoadingList();
        PlexClient.getInstance().getArtists(actions.activity(),
                new PlexClient.Callback<List<PlexArtist>>() {
            @Override public void onSuccess(List<PlexArtist> result) {
                artists = result != null ? result : new ArrayList<PlexArtist>();
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
        PlexClient.getInstance().getAlbumList(new PlexClient.Callback<List<PlexAlbum>>() {
            @Override public void onSuccess(List<PlexAlbum> result) {
                albums = result != null ? result : new ArrayList<PlexAlbum>();
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
        PlexClient.getInstance().getAllTracks(new PlexClient.Callback<List<PlexSong>>() {
            @Override public void onSuccess(List<PlexSong> result) {
                songs = result != null ? result : new ArrayList<PlexSong>();
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
        PlexClient.getInstance().getPlaylists(new PlexClient.Callback<List<PlexPlaylist>>() {
            @Override public void onSuccess(List<PlexPlaylist> result) {
                playlists = result != null ? result : new ArrayList<PlexPlaylist>();
                showPlaylistRows();
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openAlbums(final PlexArtist artist) {
        selectedArtist = artist;
        uiMode = UI_ALBUMS;
        actions.setStatusTitle(artist.name);
        showLoadingList();
        PlexClient.getInstance().getArtistAlbums(artist.id,
                new PlexClient.Callback<List<PlexAlbum>>() {
            @Override public void onSuccess(List<PlexAlbum> result) {
                albums = result != null ? result : new ArrayList<PlexAlbum>();
                showAlbumRows(artist.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openSongs(final PlexAlbum album) {
        selectedAlbum = album;
        selectedPlaylist = null;
        uiMode = UI_SONGS;
        showLoadingList();
        PlexClient.getInstance().getAlbumSongs(album.id,
                new PlexClient.Callback<List<PlexSong>>() {
            @Override public void onSuccess(List<PlexSong> result) {
                songs = result != null ? result : new ArrayList<PlexSong>();
                showSongRows(album.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openPlaylistSongs(final PlexPlaylist playlist) {
        selectedPlaylist = playlist;
        selectedAlbum = null;
        uiMode = UI_SONGS;
        showLoadingList();
        PlexClient.getInstance().getPlaylistSongs(playlist.id,
                new PlexClient.Callback<List<PlexSong>>() {
            @Override public void onSuccess(List<PlexSong> result) {
                songs = result != null ? result : new ArrayList<PlexSong>();
                showSongRows(playlist.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showArtistRows() {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_artists));
        List<PlexBrowseRow> rows = new ArrayList<PlexBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (PlexArtist a : artists) {
            PlexBrowseRow row = new PlexBrowseRow();
            row.kind = PlexBrowseRow.Kind.ARTIST;
            row.label = a.name;
            row.subtitle = a.albumCount > 0 ? String.valueOf(a.albumCount) : "";
            row.coverArtId = PlexCoverArt.artIdForArtist(a);
            row.artist = a;
            rows.add(row);
            index.add(a.name);
        }
        bindFastList(rows, index);
    }

    private void showAlbumRows(String artistName) {
        if (artistName != null) {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_albums, artistName));
        } else {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_all_albums));
        }
        List<PlexBrowseRow> rows = new ArrayList<PlexBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (PlexAlbum al : albums) {
            PlexBrowseRow row = new PlexBrowseRow();
            row.kind = PlexBrowseRow.Kind.ALBUM;
            row.label = al.name;
            row.subtitle = al.artist;
            if (al.year > 0) row.subtitle += (row.subtitle.isEmpty() ? "" : " · ") + al.year;
            row.coverArtId = PlexCoverArt.artIdForAlbum(al);
            row.album = al;
            rows.add(row);
            index.add(al.name);
        }
        bindFastList(rows, index);
    }

    private void showPlaylistRows() {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_playlists));
        List<PlexBrowseRow> rows = new ArrayList<PlexBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (PlexPlaylist p : playlists) {
            PlexBrowseRow row = new PlexBrowseRow();
            row.kind = PlexBrowseRow.Kind.PLAYLIST;
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
            actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_all_songs));
        } else {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_songs, title));
        }
        List<PlexBrowseRow> rows = new ArrayList<PlexBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (PlexSong s : songs) {
            PlexBrowseRow row = new PlexBrowseRow();
            row.kind = PlexBrowseRow.Kind.SONG;
            row.label = s.title;
            row.subtitle = s.artist;
            row.coverArtId = PlexCoverArt.artIdForSong(s, selectedAlbum);
            row.song = s;
            rows.add(row);
            index.add(s.title);
        }
        bindFastList(rows, index);
    }

    private void showSearchResults(PlexSearchResult result) {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_plex_search, searchQuery));
        songs = new ArrayList<PlexSong>();
        List<PlexBrowseRow> rows = new ArrayList<PlexBrowseRow>();
        List<String> index = new ArrayList<String>();
        if (result != null) {
            for (PlexArtist a : result.artists) {
                PlexBrowseRow row = new PlexBrowseRow();
                row.kind = PlexBrowseRow.Kind.ARTIST;
                row.label = a.name;
                row.coverArtId = PlexCoverArt.artIdForArtist(a);
                row.artist = a;
                rows.add(row);
                index.add(a.name);
            }
            for (PlexAlbum al : result.albums) {
                PlexBrowseRow row = new PlexBrowseRow();
                row.kind = PlexBrowseRow.Kind.ALBUM;
                row.label = al.name;
                row.subtitle = al.artist;
                row.coverArtId = PlexCoverArt.artIdForAlbum(al);
                row.album = al;
                rows.add(row);
                index.add(al.name);
            }
            for (PlexSong s : result.songs) {
                songs.add(s);
                PlexBrowseRow row = new PlexBrowseRow();
                row.kind = PlexBrowseRow.Kind.SONG;
                row.label = s.title;
                row.subtitle = s.artist;
                row.coverArtId = PlexCoverArt.artIdForSong(s, null);
                row.song = s;
                rows.add(row);
                index.add(s.title);
            }
        }
        if (rows.isEmpty()) {
            showScrollBrowseEmpty(actions.activity().getString(R.string.plex_search_empty));
            return;
        }
        bindFastList(rows, index);
    }

    private void bindFastList(List<PlexBrowseRow> rows, List<String> indexNames) {
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject d = new JSONObject();
            d.put("uiMode", uiMode);
            d.put("rowCount", rows.size());
            AgentDebugLog.log("PlexScreenHost.bindFastList", "B", "list bind", d);
        } catch (Exception ignored) {}
        }
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
        Button loading = actions.createListButton(actions.activity().getString(R.string.plex_loading));
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

    private void onRowClick(PlexBrowseRow row) {
        if (row == null) return;
        actions.clickFeedback();
        if (row.kind == PlexBrowseRow.Kind.ARTIST && row.artist != null) {
            openAlbums(row.artist);
        } else if (row.kind == PlexBrowseRow.Kind.ALBUM && row.album != null) {
            openSongs(row.album);
        } else if (row.kind == PlexBrowseRow.Kind.PLAYLIST && row.playlist != null) {
            openPlaylistSongs(row.playlist);
        } else if (row.kind == PlexBrowseRow.Kind.SONG && row.song != null) {
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("songId", row.song.id != null ? row.song.id : "");
                d.put("title", row.song.title != null ? row.song.title : "");
                d.put("partKeyLen", row.song.mediaPartKey != null ? row.song.mediaPartKey.length() : 0);
                d.put("container", row.song.container != null ? row.song.container : "");
                d.put("songsLen", songs != null ? songs.size() : -1);
                com.solar.launcher.Debug5c1a93Log.log(
                        "PlexScreenHost.onRowClick", "song selected", "A", d);
            } catch (Exception ignored) {}
            }
            // #endregion
            if (!actions.requireInternet(R.string.plex_wifi_required)) return;
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
    private int indexOfSongRow(PlexSong song) {
        if (song == null || songs == null) return actions.getListSelectedPosition();
        for (int i = 0; i < songs.size(); i++) {
            PlexSong s = songs.get(i);
            if (s.id != null && s.id.equals(song.id)) return i;
        }
        int pos = actions.getListSelectedPosition();
        return pos >= 0 && pos < songs.size() ? pos : 0;
    }
}
