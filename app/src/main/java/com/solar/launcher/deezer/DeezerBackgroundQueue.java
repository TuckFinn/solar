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
    /** The job the worker is downloading now (null between jobs). */
    private volatile Job current;
    /** The file extension the worker settled on (mp3 until it asked Deezer). */
    private volatile String ext = "mp3";

    public DeezerBackgroundQueue(SharedPreferences prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
    }

    public void start(List<Job> queueJobs) {
        enqueue(queueJobs);
    }

    /**
     * Append jobs and start the worker if idle — does not cancel in-flight downloads.
     *
     * 2026-09-18 — A job leaves the list once it is taken, and the lock is held
     * only to add or take one. runQueue used to be synchronized itself, so it
     * held the lock for the whole download loop: an enqueue from the UI thread
     * while a song was downloading blocked the UI until every download had
     * finished, which Discovery topping up a song at a time does routinely. And
     * nothing was ever removed, so each new worker walked every job ever added
     * from the start again, re-fetching any whose file had since been emptied.
     */
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
        jobs.clear();
        if (worker != null && worker.isAlive()) worker.interrupt();
        worker = null;
    }

    /**
     * The next job, or null when there is none -- in which case the worker is
     * retired under the same lock, so an enqueue racing it starts a fresh one
     * rather than handing work to a thread that is on its way out.
     */
    /**
     * 2026-10-11 — Move the waiting job for {@code dest} to the front. The song
     * on screen is waiting for it; skipped ahead onto a song the queue had not
     * reached, it waited behind every song queued before it (1Y1, Classical).
     *
     * @return true when the job was waiting here
     */
    public synchronized boolean prioritize(File dest) {
        if (dest == null) return false;
        for (int i = 0; i < jobs.size(); i++) {
            if (dest.equals(jobs.get(i).dest)) {
                if (i > 0) jobs.add(0, jobs.remove(i));
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-11-05 (#39) — The song on screen is waiting for {@code dest} while the worker
     * is busy downloading a different song: fetch it now on its own thread instead of
     * after that one. prioritize() alone only moved it to the front of the line, so a
     * stalled download ahead of it held "Loading" for as long as the stall lasted.
     *
     * @return true when a dedicated download was started
     */
    public synchronized boolean fetchNow(File dest) {
        if (dest == null || cancel.get()) return false;
        Job busy = current;
        if (busy == null || dest.equals(busy.dest)) return false;   // idle, or already on it
        Job mine = null;
        for (int i = 0; i < jobs.size(); i++) {
            if (dest.equals(jobs.get(i).dest)) { mine = jobs.remove(i); break; }
        }
        if (mine == null) return false;
        final Job j = mine;
        new Thread(new Runnable() {
            @Override public void run() { runJob(j, ext); }
        }, "DeezerFetchNow").start();
        return true;
    }

    private synchronized Job takeNext() {
        if (jobs.isEmpty()) {
            if (worker == Thread.currentThread()) worker = null;
            return null;
        }
        return jobs.remove(0);
    }

    private void runQueue() {
        try {
            DeezerClient probe = new DeezerClient(prefs);
            if (!probe.isSessionValid()) probe.initSession();
            ext = probe.fileExtension();
        } catch (Exception ignored) {}
        while (!cancel.get()) {
            final Job j = takeNext();
            if (j == null) {
                if (listener != null) listener.onAlbumComplete();
                return;
            }
            current = j;
            try {
                runJob(j, ext);
            } finally {
                current = null;
            }
        }
    }

    /** Download one job (worker or fetchNow), reporting through the listener. */
    private void runJob(final Job j, String ext) {
        {
            // Placeholders are created empty, and a failed download deletes its file,
            // so bytes here mean a finished download -- possibly the one playing right
            // now. Fetching it again would truncate it under the player.
            if (j.dest != null && j.dest.length() > 0) {
                if (listener != null) {
                    listener.onTrackProgress(j, 100);
                    listener.onTrackComplete(j);
                }
                return;
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
