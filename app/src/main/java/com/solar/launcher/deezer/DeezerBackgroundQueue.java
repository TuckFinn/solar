package com.solar.launcher.deezer;

import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sequential background Deezer album downloads into an already-queued playback list. */
public final class DeezerBackgroundQueue {
    public static final class Job {
        public final DeezerResult track;
        public final File dest;

        public Job(DeezerResult track, File dest) {
            this.track = track;
            this.dest = dest;
        }
    }

    public interface Listener {
        void onTrackProgress(Job job, int percent);
        void onTrackComplete(Job job);
        void onTrackFailed(Job job, String error);
        void onAlbumComplete();
    }

    private final SharedPreferences prefs;
    private final Listener listener;
    private final List<Job> jobs = new ArrayList<Job>();
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private Thread worker;

    public DeezerBackgroundQueue(SharedPreferences prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
    }

    public void start(List<Job> queueJobs) {
        enqueue(queueJobs);
    }

    /** Append jobs and start worker if idle — does not cancel in-flight downloads. */
    public synchronized void enqueue(List<Job> queueJobs) {
        if (queueJobs == null || queueJobs.isEmpty()) return;
        jobs.addAll(queueJobs);
        if (worker != null && worker.isAlive()) return;
        cancel.set(false);
        worker = new Thread(new Runnable() {
            @Override public void run() {
                runQueue();
            }
        }, "DeezerBgQueue");
        worker.start();
    }

    public synchronized void cancel() {
        cancel.set(true);
        if (worker != null && worker.isAlive()) worker.interrupt();
        worker = null;
    }

    private synchronized void runQueue() {
        String ext = "mp3";
        try {
            DeezerClient probe = new DeezerClient(prefs);
            if (!probe.isSessionValid()) probe.initSession();
            ext = probe.fileExtension();
        } catch (Exception ignored) {}
        for (int i = 0; i < jobs.size(); i++) {
            if (cancel.get()) return;
            final Job j = jobs.get(i);
            // Placeholders are created empty, and a failed download deletes its file,
            // so bytes here mean a finished download -- possibly the one playing right
            // now. Fetching it again would truncate it under the player.
            if (j.dest != null && j.dest.length() > 0) {
                if (listener != null) {
                    listener.onTrackProgress(j, 100);
                    listener.onTrackComplete(j);
                }
                continue;
            }
            DeezerDownloadRunner.Progress progress = new DeezerDownloadRunner.Progress() {
                @Override public void onProgress(int percent, long done, long total) {
                    if (listener == null) return;
                    int pct = percent;
                    if (pct < 0 && total > 0) pct = (int) (done * 100 / total);
                    if (pct < 0 && done > 0) pct = 1;
                    listener.onTrackProgress(j, Math.min(99, Math.max(0, pct)));
                }
            };
            String err = DeezerDownloadRunner.downloadWithFallback(
                    prefs, j.track, j.dest, ext, progress);
            if (cancel.get()) return;
            if (err != null) {
                if (listener != null) listener.onTrackFailed(j, err);
            } else {
                if (listener != null) {
                    listener.onTrackProgress(j, 100);
                    listener.onTrackComplete(j);
                }
            }
        }
        if (!cancel.get() && listener != null) listener.onAlbumComplete();
        synchronized (this) {
            if (!cancel.get()) worker = null;
        }
    }

    /**
     * 2026-09-16 — Cache path for a track: its Deezer id.
     *
     * Was: "Artist - Title.ext", with a {@code while (exists)} loop appending " (n)".
     * The downloader ran the same loop independently, so the two could land on
     * different files -- a queue row pointing at a 0-byte sibling of a complete
     * download, and playback sitting at "[0%] Loading..." for ever. An id cannot
     * be spelt two ways, and it is the recording, so an existing file under this
     * name is always the right audio -- unlike a title, which "Song" and
     * "Song (Edit)" both reduce to.
     *
     * Human-readable names still belong in the music library; this is the cache.
     * Reversal: restore the filenameBase + " (n)" walk here and in reservePlaceholder.
     */
    public static File cacheFileFor(File cacheDir, DeezerResult result, String ext) {
        String base = result.id > 0 ? String.valueOf(result.id) : result.filenameBase();
        return new File(cacheDir, base + "." + ext);
    }

    /** Reserve the track's cache path (empty file so queue rows can reference it). */
    public static File reservePlaceholder(File cacheDir, DeezerResult result, String ext)
            throws IOException {
        if (cacheDir != null && !cacheDir.isDirectory()) cacheDir.mkdirs();
        File dest = cacheFileFor(cacheDir, result, ext);
        // Already there: the same id, so the same recording. Queueing a track twice
        // now costs a repeat in the list, not a download that goes nowhere.
        if (dest.exists()) return dest;
        if (!dest.createNewFile()) throw new IOException("mkdir placeholder");
        return dest;
    }
}
