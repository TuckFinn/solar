package com.solar.launcher.discovery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-21 — Q18: Discovery's ~158 lists in folders (Fresh, For You, Genres,
 * Countries, Seeds), so the wheel is not scrolled through all of them.
 *
 * nookTunes sends the folders in display order as {@code groups} and names
 * one on every list as {@code group}. Plain Java with no Android in it, so the
 * grouping is unit-tested. An older nookTunes sends no {@code groups}: then
 * {@link #hasFolders()} is false and the screen keeps its flat list.
 */
public final class DiscoveryFolders {

    public static final DiscoveryFolders EMPTY = new DiscoveryFolders(
            new ArrayList<DiscoveryClient.Playlist>(), new ArrayList<String>(),
            new LinkedHashMap<String, List<DiscoveryClient.Playlist>>());

    private final List<DiscoveryClient.Playlist> all;
    private final List<String> folders;
    private final Map<String, List<DiscoveryClient.Playlist>> byFolder;

    private DiscoveryFolders(List<DiscoveryClient.Playlist> all, List<String> folders,
            Map<String, List<DiscoveryClient.Playlist>> byFolder) {
        this.all = Collections.unmodifiableList(all);
        this.folders = Collections.unmodifiableList(folders);
        this.byFolder = byFolder;
    }

    /** The response of {@code /api/discovery/native/playlists}. */
    static DiscoveryFolders parse(JSONObject doc) {
        List<DiscoveryClient.Playlist> all = new ArrayList<DiscoveryClient.Playlist>();
        JSONArray arr = doc == null ? null : doc.optJSONArray("playlists");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.length() == 0) continue;
                String group = o.isNull("group") ? "" : o.optString("group", "").trim();
                all.add(new DiscoveryClient.Playlist(id, o.optString("name", id),
                        o.optString("kind", ""), o.optInt("count", 0), group));
            }
        }
        Map<String, List<DiscoveryClient.Playlist>> byFolder =
                new LinkedHashMap<String, List<DiscoveryClient.Playlist>>();
        JSONArray groups = doc == null ? null : doc.optJSONArray("groups");
        if (groups == null || groups.length() == 0) return new DiscoveryFolders(all, new ArrayList<String>(), byFolder);
        for (int i = 0; i < groups.length(); i++) {
            String g = groups.optString(i, "").trim();
            if (g.length() > 0 && !byFolder.containsKey(g)) {
                byFolder.put(g, new ArrayList<DiscoveryClient.Playlist>());
            }
        }
        for (DiscoveryClient.Playlist p : all) {
            // A list naming no folder, or one the server left out of groups,
            // still has to be reachable: it goes in a folder of its own name
            // after the server's ("Other" when it names none).
            String g = p.group.length() > 0 ? p.group : "Other";
            List<DiscoveryClient.Playlist> in = byFolder.get(g);
            if (in == null) {
                in = new ArrayList<DiscoveryClient.Playlist>();
                byFolder.put(g, in);
            }
            in.add(p);
        }
        List<String> folders = new ArrayList<String>();
        for (Map.Entry<String, List<DiscoveryClient.Playlist>> e : byFolder.entrySet()) {
            if (!e.getValue().isEmpty()) folders.add(e.getKey());   // empty ones left out
        }
        return new DiscoveryFolders(all, folders, byFolder);
    }

    /** Every list, in the server's order: the flat tier. */
    public List<DiscoveryClient.Playlist> all() { return all; }

    /** True when the screen should show folders first. */
    public boolean hasFolders() { return !folders.isEmpty(); }

    /** The folders, in the server's order, none empty. */
    public List<String> folders() { return folders; }

    /** The lists in {@code folder}, in the server's order; empty when unknown. */
    public List<DiscoveryClient.Playlist> inFolder(String folder) {
        List<DiscoveryClient.Playlist> in = folder == null ? null : byFolder.get(folder);
        return in == null ? Collections.<DiscoveryClient.Playlist>emptyList()
                : Collections.unmodifiableList(in);
    }

    /** The folder row's label: its name and how many lists it holds. */
    public static String folderLabel(String folder, int lists) {
        return folder + " (" + lists + ")";
    }
}
