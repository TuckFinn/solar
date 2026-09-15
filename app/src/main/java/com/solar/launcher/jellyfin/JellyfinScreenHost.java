package com.solar.launcher.jellyfin;

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
 * 2026-07-06: Jellyfin browse — library-parity hub (Artists/Albums/Playlists/Search) + fast ListView drill-down.
 */
public final class JellyfinScreenHost {

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
        void playSongs(List<JellyfinSong> songs, int startIndex, String label);
        void downloadAlbum(JellyfinArtist artist, JellyfinAlbum album, List<JellyfinSong> songs);
        void downloadSong(JellyfinSong song);
        boolean requireInternet(int messageRes);
        void openSearchKeyboard(String prefill);
        int getListSelectedPosition();
        void applyListRowParams(View row, int heightPx);
        int rowHeightPx();
        void onRowFocused(JellyfinBrowseRow row);
    }

    public static final int KEYBOARD_SEARCH = 33;

    private static final int UI_ROOT = 0;
    private static final int UI_ARTISTS = 1;
    private static final int UI_ALBUMS = 2;
    private static final int UI_PLAYLISTS = 3;
    private static final int UI_SONGS = 4;
    private static final int UI_SEARCH = 5;
    private static final int UI_TRACKS = 6;

    private final Actions actions;
    private final JellyfinBrowseAdapter adapter;
    private int uiMode = UI_ROOT;
    private List<JellyfinArtist> artists = new ArrayList<JellyfinArtist>();
    private List<JellyfinAlbum> albums = new ArrayList<JellyfinAlbum>();
    private List<JellyfinSong> songs = new ArrayList<JellyfinSong>();
    private List<JellyfinPlaylist> playlists = new ArrayList<JellyfinPlaylist>();
    private JellyfinArtist selectedArtist;
    private JellyfinAlbum selectedAlbum;
    private JellyfinPlaylist selectedPlaylist;
    private String searchQuery = "";

    public JellyfinScreenHost(Actions actions) {
        this.actions = actions;
        this.adapter = new JellyfinBrowseAdapter(new JellyfinBrowseAdapter.RowUi() {
            @Override public Button createListButton(String label) {
                return JellyfinScreenHost.this.actions.createListButton(label);
            }
            @Override public void bindListButton(Button btn, boolean focused, String label) {
                JellyfinScreenHost.this.actions.configureListButton(btn);
            }
            @Override public void applyListRowParams(View row, int heightPx) {
                JellyfinScreenHost.this.actions.applyListRowParams(row, heightPx);
            }
            @Override public int rowHeightPx() { return JellyfinScreenHost.this.actions.rowHeightPx(); }
            @Override public void onRowClick(JellyfinBrowseRow row) {
                JellyfinScreenHost.this.onRowClick(row);
            }
            @Override public void onRowFocused(JellyfinBrowseRow row, boolean hasFocus) {
                if (hasFocus) JellyfinScreenHost.this.actions.onRowFocused(row);
            }
        });
    }

    public void open() {
        if (!JellyfinClient.getInstance().isConfigured()) {
            return;
        }
        showRoot();
    }

    /** 2026-07-06: Back stack — true when consumed, false to exit Jellyfin screen. */
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

    public JellyfinSong getFocusedSong() {
        if (uiMode != UI_SONGS && uiMode != UI_TRACKS) return null;
        int pos = actions.getListSelectedPosition();
        JellyfinBrowseRow row = adapter.rowAt(pos);
        return row != null ? row.song : null;
    }

    public JellyfinAlbum getFocusedAlbumContext() {
        return selectedAlbum;
    }

    /** 2026-07-15 — True while Albums list (all albums or under an artist) is on screen. */
    public boolean isAlbumListVisible() {
        return uiMode == UI_ALBUMS;
    }

    /** 2026-07-15 — Focused album row for Save-album context menu. */
    public JellyfinAlbum getFocusedAlbum() {
        if (uiMode != UI_ALBUMS) return null;
        int pos = actions.getListSelectedPosition();
        JellyfinBrowseRow row = adapter.rowAt(pos);
        return row != null ? row.album : null;
    }

    /** 2026-07-06: Preview pane row lookup by ListView selection index. */
    public JellyfinBrowseRow getBrowseRowAt(int position) {
        return adapter.rowAt(position);
    }

    public List<JellyfinSong> getCurrentSongs() {
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
        actions.setStatusTitle(actions.activity().getString(R.string.jellyfin_search_title, searchQuery));
        showLoadingList();
        JellyfinClient.getInstance().search(searchQuery, new JellyfinClient.Callback<JellyfinSearchResult>() {
            @Override public void onSuccess(JellyfinSearchResult result) {
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
        actions.setStatusTitle(actions.activity().getString(R.string.jellyfin_menu));
        actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_root));
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
        actions.setStatusTitle(actions.activity().getString(R.string.jellyfin_artists));
        showLoadingList();
        JellyfinClient.getInstance().getArtists(actions.activity(),
                new JellyfinClient.Callback<List<JellyfinArtist>>() {
            @Override public void onSuccess(List<JellyfinArtist> result) {
                artists = result != null ? result : new ArrayList<JellyfinArtist>();
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
        JellyfinClient.getInstance().getAlbumList(new JellyfinClient.Callback<List<JellyfinAlbum>>() {
            @Override public void onSuccess(List<JellyfinAlbum> result) {
                albums = result != null ? result : new ArrayList<JellyfinAlbum>();
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
        JellyfinClient.getInstance().getAllTracks(new JellyfinClient.Callback<List<JellyfinSong>>() {
            @Override public void onSuccess(List<JellyfinSong> result) {
                songs = result != null ? result : new ArrayList<JellyfinSong>();
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
        JellyfinClient.getInstance().getPlaylists(new JellyfinClient.Callback<List<JellyfinPlaylist>>() {
            @Override public void onSuccess(List<JellyfinPlaylist> result) {
                playlists = result != null ? result : new ArrayList<JellyfinPlaylist>();
                showPlaylistRows();
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openAlbums(final JellyfinArtist artist) {
        selectedArtist = artist;
        uiMode = UI_ALBUMS;
        actions.setStatusTitle(artist.name);
        showLoadingList();
        JellyfinClient.getInstance().getArtistAlbums(artist.id,
                new JellyfinClient.Callback<List<JellyfinAlbum>>() {
            @Override public void onSuccess(List<JellyfinAlbum> result) {
                albums = result != null ? result : new ArrayList<JellyfinAlbum>();
                showAlbumRows(artist.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openSongs(final JellyfinAlbum album) {
        selectedAlbum = album;
        selectedPlaylist = null;
        uiMode = UI_SONGS;
        showLoadingList();
        JellyfinClient.getInstance().getAlbumSongs(album.id,
                new JellyfinClient.Callback<List<JellyfinSong>>() {
            @Override public void onSuccess(List<JellyfinSong> result) {
                songs = result != null ? result : new ArrayList<JellyfinSong>();
                showSongRows(album.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void openPlaylistSongs(final JellyfinPlaylist playlist) {
        selectedPlaylist = playlist;
        selectedAlbum = null;
        uiMode = UI_SONGS;
        showLoadingList();
        JellyfinClient.getInstance().getPlaylistSongs(playlist.id,
                new JellyfinClient.Callback<List<JellyfinSong>>() {
            @Override public void onSuccess(List<JellyfinSong> result) {
                songs = result != null ? result : new ArrayList<JellyfinSong>();
                showSongRows(playlist.name);
            }
            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showArtistRows() {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_artists));
        List<JellyfinBrowseRow> rows = new ArrayList<JellyfinBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (JellyfinArtist a : artists) {
            JellyfinBrowseRow row = new JellyfinBrowseRow();
            row.kind = JellyfinBrowseRow.Kind.ARTIST;
            row.label = a.name;
            row.subtitle = a.albumCount > 0 ? String.valueOf(a.albumCount) : "";
            row.coverArtId = JellyfinCoverArt.artIdForArtist(a);
            row.artist = a;
            rows.add(row);
            index.add(a.name);
        }
        bindFastList(rows, index);
    }

    private void showAlbumRows(String artistName) {
        if (artistName != null) {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_albums, artistName));
        } else {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_all_albums));
        }
        List<JellyfinBrowseRow> rows = new ArrayList<JellyfinBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (JellyfinAlbum al : albums) {
            JellyfinBrowseRow row = new JellyfinBrowseRow();
            row.kind = JellyfinBrowseRow.Kind.ALBUM;
            row.label = al.name;
            row.subtitle = al.artist;
            if (al.year > 0) row.subtitle += (row.subtitle.isEmpty() ? "" : " · ") + al.year;
            row.coverArtId = JellyfinCoverArt.artIdForAlbum(al);
            row.album = al;
            rows.add(row);
            index.add(al.name);
        }
        bindFastList(rows, index);
    }

    private void showPlaylistRows() {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_playlists));
        List<JellyfinBrowseRow> rows = new ArrayList<JellyfinBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (JellyfinPlaylist p : playlists) {
            JellyfinBrowseRow row = new JellyfinBrowseRow();
            row.kind = JellyfinBrowseRow.Kind.PLAYLIST;
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
            actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_all_songs));
        } else {
            actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_songs, title));
        }
        List<JellyfinBrowseRow> rows = new ArrayList<JellyfinBrowseRow>();
        List<String> index = new ArrayList<String>();
        for (JellyfinSong s : songs) {
            JellyfinBrowseRow row = new JellyfinBrowseRow();
            row.kind = JellyfinBrowseRow.Kind.SONG;
            row.label = s.title;
            row.subtitle = s.artist;
            row.coverArtId = JellyfinCoverArt.artIdForSong(s, selectedAlbum);
            row.song = s;
            rows.add(row);
            index.add(s.title);
        }
        bindFastList(rows, index);
    }

    private void showSearchResults(JellyfinSearchResult result) {
        actions.setBreadcrumb(actions.activity().getString(R.string.path_jellyfin_search, searchQuery));
        songs = new ArrayList<JellyfinSong>();
        List<JellyfinBrowseRow> rows = new ArrayList<JellyfinBrowseRow>();
        List<String> index = new ArrayList<String>();
        if (result != null) {
            for (JellyfinArtist a : result.artists) {
                JellyfinBrowseRow row = new JellyfinBrowseRow();
                row.kind = JellyfinBrowseRow.Kind.ARTIST;
                row.label = a.name;
                row.coverArtId = JellyfinCoverArt.artIdForArtist(a);
                row.artist = a;
                rows.add(row);
                index.add(a.name);
            }
            for (JellyfinAlbum al : result.albums) {
                JellyfinBrowseRow row = new JellyfinBrowseRow();
                row.kind = JellyfinBrowseRow.Kind.ALBUM;
                row.label = al.name;
                row.subtitle = al.artist;
                row.coverArtId = JellyfinCoverArt.artIdForAlbum(al);
                row.album = al;
                rows.add(row);
                index.add(al.name);
            }
            for (JellyfinSong s : result.songs) {
                songs.add(s);
                JellyfinBrowseRow row = new JellyfinBrowseRow();
                row.kind = JellyfinBrowseRow.Kind.SONG;
                row.label = s.title;
                row.subtitle = s.artist;
                row.coverArtId = JellyfinCoverArt.artIdForSong(s, null);
                row.song = s;
                rows.add(row);
                index.add(s.title);
            }
        }
        if (rows.isEmpty()) {
            showScrollBrowseEmpty(actions.activity().getString(R.string.jellyfin_search_empty));
            return;
        }
        bindFastList(rows, index);
    }

    private void bindFastList(List<JellyfinBrowseRow> rows, List<String> indexNames) {
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject d = new JSONObject();
            d.put("uiMode", uiMode);
            d.put("rowCount", rows.size());
            AgentDebugLog.log("JellyfinScreenHost.bindFastList", "B", "list bind", d);
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
        Button loading = actions.createListButton(actions.activity().getString(R.string.jellyfin_loading));
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

    private void onRowClick(JellyfinBrowseRow row) {
        if (row == null) return;
        actions.clickFeedback();
        if (row.kind == JellyfinBrowseRow.Kind.ARTIST && row.artist != null) {
            openAlbums(row.artist);
        } else if (row.kind == JellyfinBrowseRow.Kind.ALBUM && row.album != null) {
            openSongs(row.album);
        } else if (row.kind == JellyfinBrowseRow.Kind.PLAYLIST && row.playlist != null) {
            openPlaylistSongs(row.playlist);
        } else if (row.kind == JellyfinBrowseRow.Kind.SONG && row.song != null) {
            if (!actions.requireInternet(R.string.jellyfin_wifi_required)) return;
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
    private int indexOfSongRow(JellyfinSong song) {
        if (song == null || songs == null) return actions.getListSelectedPosition();
        for (int i = 0; i < songs.size(); i++) {
            JellyfinSong s = songs.get(i);
            if (s.id != null && s.id.equals(song.id)) return i;
        }
        int pos = actions.getListSelectedPosition();
        return pos >= 0 && pos < songs.size() ? pos : 0;
    }
}
