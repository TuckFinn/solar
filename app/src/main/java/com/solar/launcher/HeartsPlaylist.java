package com.solar.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-09-18 — The Hearts playlist, Music/Playlists/Hearts.m3u.
 *
 * nookTunes' y1sync writes it from every heart it knows of, and keeps any line
 * it did not write. A song on the device's own storage is added here directly
 * when it becomes a favourite -- the Play/Pause hold, or Add to Favorites --
 * and removed when it stops being one, so the playlist changes at once and
 * without a network. Lines use the same form y1sync writes -- the path under
 * the Music folder, CRLF-terminated -- so the two agree on what is already
 * there.
 */
public final class HeartsPlaylist {

    public static final String NAME = "Hearts.m3u";

    private HeartsPlaylist() {}

    public static File file(File musicRoot) {
        return new File(PlaylistManager.playlistsDir(musicRoot), NAME);
    }

    /** The playlist line for a track: its path under the Music folder, else absolute. */
    public static String lineFor(File musicRoot, File track) {
        String abs = track.getAbsolutePath();
        String root = musicRoot.getAbsolutePath();
        if (!root.endsWith("/")) root = root + "/";
        return abs.startsWith(root) ? abs.substring(root.length()) : abs;
    }

    /**
     * Add a track unless it is already there, in either form.
     *
     * @return true when it was added, false when it was already in the playlist
     */
    public static synchronized boolean add(File musicRoot, File track) throws IOException {
        File f = file(musicRoot);
        List<String> lines = read(f);
        String line = lineFor(musicRoot, track);
        String abs = track.getAbsolutePath();
        for (String l : lines) {
            if (l.equals(line) || l.equals(abs)) return false;
        }
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
        try {
            // Start on a fresh line if the file does not already end in one.
            if (f.length() > 0 && !endsWithNewline(f)) w.write("\r\n");
            w.write(line);
            w.write("\r\n");
        } finally {
            w.close();
        }
        return true;
    }

    /**
     * Take a track out, in either form. The file is rewritten without it.
     *
     * @return true when it was there
     */
    public static synchronized boolean remove(File musicRoot, File track) throws IOException {
        File f = file(musicRoot);
        List<String> lines = read(f);
        String line = lineFor(musicRoot, track);
        String abs = track.getAbsolutePath();
        List<String> keep = new ArrayList<String>();
        for (String l : lines) {
            if (!l.equals(line) && !l.equals(abs)) keep.add(l);
        }
        if (keep.size() == lines.size()) return false;
        Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
        try {
            for (String l : keep) {
                w.write(l);
                w.write("\r\n");
            }
        } finally {
            w.close();
        }
        return true;
    }

    static List<String> read(File f) throws IOException {
        List<String> out = new ArrayList<String>();
        if (!f.isFile()) return out;
        StringBuilder sb = new StringBuilder();
        Reader r = new InputStreamReader(new FileInputStream(f), "UTF-8");
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            r.close();
        }
        for (String l : sb.toString().replace("\r", "").split("\n")) {
            String t = l.trim();
            if (t.length() > 0 && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    private static boolean endsWithNewline(File f) throws IOException {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            if (raf.length() == 0) return true;
            raf.seek(raf.length() - 1);
            return raf.read() == '\n';
        } finally {
            raf.close();
        }
    }
}
