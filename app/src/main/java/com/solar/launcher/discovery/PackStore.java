package com.solar.launcher.discovery;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-03 — Q22 Pack for the road (piPod docs/pack-for-the-road-qa.md):
 * Discovery lists kept on the Y1 so they play without Wi-Fi.
 *
 * A pack is {@code /sdcard/Packed/<list id>/}: one {@code <deezer id>.mp3}
 * (or .flac) per song, named like Solar's Deezer cache so a song is found by
 * its id, and {@code pack.json} -- nookTunes' pack document (list, name,
 * tracks...) plus {@code packed_at}, on one line, which nookTunes' listing of
 * the Y1 reads. Packed/ sits beside Music/, so packed songs stay out of the
 * library, Artists, Albums and shuffle, the way Audiobooks/ and Podcasts/ do.
 *
 * Packed files are never played in place: Discovery empties the audio of songs
 * it has played (KEEP_BEHIND), so a copy goes into the stream cache instead
 * ({@link #copyTo}), which costs a local copy and no network.
 */
public final class PackStore {

    public static final String DIR = "Packed";
    public static final String META = "pack.json";
    private static final String[] EXTS = { "mp3", "flac" };

    /** id -> file, rebuilt when a pack changes. */
    private static Map<Long, File> index;

    private PackStore() {}

    /** /sdcard/Packed: beside the Music/ folder nookTunes syncs into. */
    public static File root() {
        return new File(Environment.getExternalStorageDirectory(), DIR);
    }

    /** A list id as a folder name. */
    public static File dirFor(String listId) {
        String safe = listId == null ? "list" : listId.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(root(), safe);
    }

    public static synchronized void invalidate() {
        index = null;
    }

    private static synchronized Map<Long, File> index() {
        if (index != null) return index;
        Map<Long, File> m = new HashMap<Long, File>();
        File[] dirs = root().listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                File[] files = d.isDirectory() ? d.listFiles() : null;
                if (files == null) continue;
                for (File f : files) {
                    long id = idOf(f);
                    if (id != 0 && f.length() > 0) m.put(Long.valueOf(id), f);
                }
            }
        }
        index = m;
        return m;
    }

    /** "123.mp3" -> 123; 0 for anything else (pack.json, .part files). */
    static long idOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot <= 0) return 0;
        String ext = n.substring(dot + 1);
        boolean audio = false;
        for (String e : EXTS) audio |= e.equals(ext);
        if (!audio) return 0;
        try {
            return Long.parseLong(n.substring(0, dot));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The packed copy of a Deezer song, or null. */
    public static File find(long deezerId) {
        if (deezerId == 0) return null;
        File f = index().get(Long.valueOf(deezerId));
        return f != null && f.isFile() && f.length() > 0 ? f : null;
    }

    public static String extOf(File f) {
        String n = f.getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    /** Copy a packed song to {@code dest} (the stream cache). */
    public static boolean copyTo(File packed, File dest) {
        File tmp = new File(dest.getPath() + ".pk");
        InputStream in = null;
        OutputStream out = null;
        try {
            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
            in = new FileInputStream(packed);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            out = null;
            if (dest.exists()) dest.delete();
            return tmp.renameTo(dest);
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    /** One pack as pack.json describes it. */
    public static final class Pack {
        public final String listId;
        public final String name;
        public final int total;
        public final long packedAt;
        public final JSONObject doc;
        public final File dir;

        Pack(File dir, JSONObject doc) {
            this.dir = dir;
            this.doc = doc;
            this.listId = doc.optString("list", doc.optString("id", ""));
            this.name = doc.optString("name", listId);
            this.total = doc.optInt("total", 0);
            this.packedAt = doc.optLong("packed_at", 0L);
        }

        /** Songs downloaded so far. */
        public int have() {
            int n = 0;
            File[] files = dir.listFiles();
            if (files == null) return 0;
            for (File f : files) if (idOf(f) != 0 && f.length() > 0) n++;
            return n;
        }

        public boolean complete() {
            return total > 0 && have() >= total;
        }

        /** The packed songs, in pack order: what plays offline. */
        public List<DiscoveryClient.Track> tracks() {
            List<DiscoveryClient.Track> out = new ArrayList<DiscoveryClient.Track>();
            for (DiscoveryClient.Track t : DiscoveryClient.parseTracks(doc.optJSONArray("tracks"), "")) {
                if (t.deezerId > 0 && find(t.deezerId) != null) out.add(t);
            }
            return out;
        }
    }

    public static Pack read(File dir) {
        File meta = new File(dir, META);
        if (!meta.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(meta);
            byte[] buf = new byte[(int) Math.min(meta.length(), 8L * 1024 * 1024)];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new Pack(dir, new JSONObject(new String(buf, 0, off, "UTF-8")));
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    public static Pack forList(String listId) {
        return read(dirFor(listId));
    }

    /** Every pack on the Y1, newest first. */
    public static List<Pack> all() {
        List<Pack> out = new ArrayList<Pack>();
        File[] dirs = root().listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                Pack p = d.isDirectory() ? read(d) : null;
                if (p != null) out.add(p);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<Pack>() {
            @Override public int compare(Pack a, Pack b) {
                return Long.compare(b.packedAt, a.packedAt);
            }
        });
        return out;
    }

    /** pack.json, written on one line (nookTunes reads it line by line). */
    static boolean writeMeta(File dir, JSONObject doc) {
        boolean made = dir.mkdirs();
        if (!dir.isDirectory()) {
            android.util.Log.w("SolarPack", "mkdirs " + dir + " failed (made=" + made
                    + ", parent exists=" + (dir.getParentFile() != null && dir.getParentFile().exists())
                    + ", parent writable=" + (dir.getParentFile() != null && dir.getParentFile().canWrite())
                    + ", root writable=" + root().getParentFile().canWrite() + ")");
        }
        File tmp = new File(dir, META + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(doc.toString().replace("\n", " ").getBytes("UTF-8"));
            out.close();
            out = null;
            boolean ok = tmp.renameTo(new File(dir, META));
            if (!ok) android.util.Log.w("SolarPack", "rename " + tmp + " failed");
            return ok;
        } catch (Exception e) {
            android.util.Log.w("SolarPack", "write " + dir + "/" + META + " failed", e);
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    /** Ids a pack document lists, for keeping what a re-pack still wants. */
    static java.util.Set<Long> ids(JSONObject doc) {
        java.util.Set<Long> out = new java.util.HashSet<Long>();
        JSONArray arr = doc.optJSONArray("tracks");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.isNull("deezerId")) continue;
            try {
                out.add(Long.valueOf(Long.parseLong(o.optString("deezerId", "0").trim())));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}
