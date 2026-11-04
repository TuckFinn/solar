package com.solar.launcher.discovery;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;

import com.solar.launcher.deezer.DeezerClient;
import com.solar.launcher.deezer.DeezerDownloadRunner;
import com.solar.launcher.deezer.DeezerResult;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-03 — Q22 Pack for the road: download a Discovery list's next few
 * hours into {@link PackStore}, on the Y1 itself, through the same Deezer path
 * Discovery plays from. One pack at a time; a new one replaces the one running.
 *
 * Packing a list again replaces its pack (Tucker, Q4): songs the new pack still
 * wants are kept rather than downloaded twice, the rest are deleted. A hearted
 * song is not lost with them -- the heart already sent it to nookTunes, which
 * keeps the lossless original and syncs it back into Hearts.
 *
 * A pack survives a restart: pack.json is written first with every song it
 * wants, so {@link #resume} carries on from whatever has landed.
 */
public final class PackRunner {
    private static final String TAG = "SolarPack";
    static final String PREF_ACTIVE = "pack_active_list";

    /** What is packing now, for the status report and the list row; null when idle. */
    public static final class Progress {
        public final String listId;
        public final String name;
        public final int done;
        public final int total;

        Progress(String listId, String name, int done, int total) {
            this.listId = listId;
            this.name = name;
            this.done = done;
            this.total = total;
        }
    }

    private static volatile Progress progress;
    private static Thread worker;
    private static volatile int generation;

    private PackRunner() {}

    public static Progress progress() {
        return progress;
    }

    /** Pack {@code listId}: ask nookTunes for the songs, then download them. */
    public static synchronized void start(final Context ctx, final SharedPreferences prefs,
            final String base, final String listId, final double hours) {
        final int gen = ++generation;
        if (worker != null) worker.interrupt();
        prefs.edit().putString(PREF_ACTIVE, listId).apply();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                pack(ctx.getApplicationContext(), prefs, base, listId, hours, gen);
            }
        }, "SolarPack");
        worker.start();
    }

    /** After a restart: finish the pack that was running, without asking nookTunes again. */
    public static synchronized void resume(final Context ctx, final SharedPreferences prefs) {
        final String listId = prefs.getString(PREF_ACTIVE, "");
        if (listId.isEmpty() || (worker != null && worker.isAlive())) return;
        PackStore.Pack p = PackStore.forList(listId);
        if (p == null || p.complete()) {
            prefs.edit().remove(PREF_ACTIVE).apply();
            return;
        }
        final int gen = ++generation;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                pack(ctx.getApplicationContext(), prefs, null, listId, 0, gen);
            }
        }, "SolarPack");
        worker.start();
    }

    private static void pack(Context ctx, SharedPreferences prefs, String base, String listId,
            double hours, int gen) {
        PowerManager.WakeLock wake = null;
        WifiManager.WifiLock wifi = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolarPack");
                wake.acquire();
            }
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "SolarPack");
                wifi.acquire();
            }
            File dir = PackStore.dirFor(listId);
            JSONObject doc;
            if (base != null) {
                doc = DiscoveryClient.packJson(base, listId, hours);
                doc.put("list", listId);
                doc.put("packed_at", System.currentTimeMillis() / 1000L);
                List<DiscoveryClient.Track> want = DiscoveryClient.parseTracks(doc.optJSONArray("tracks"), base);
                int total = 0;
                for (DiscoveryClient.Track t : want) if (t.deezerId > 0) total++;
                doc.put("total", total);
                // Replace: keep what the new pack still lists, delete the rest.
                Set<Long> keep = PackStore.ids(doc);
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        long id = PackStore.idOf(f);
                        if (f.getName().equals(PackStore.META)) continue;
                        if (id == 0 || !keep.contains(Long.valueOf(id))) f.delete();
                    }
                }
                if (!PackStore.writeMeta(dir, doc)) throw new IllegalStateException("cannot write " + dir);
                PackStore.invalidate();
            } else {
                PackStore.Pack p = PackStore.forList(listId);
                if (p == null) return;
                doc = p.doc;
            }
            String name = doc.optString("name", listId);
            List<DiscoveryClient.Track> tracks = DiscoveryClient.parseTracks(doc.optJSONArray("tracks"), "");
            int total = doc.optInt("total", tracks.size());
            String ext = "mp3";
            try {
                DeezerClient probe = new DeezerClient(prefs);
                if (!probe.isSessionValid()) probe.initSession();
                ext = probe.fileExtension();
            } catch (Exception ignored) {}
            int done = 0, failed = 0;
            for (DiscoveryClient.Track t : tracks) {
                if (t.deezerId <= 0) continue;
                if (gen != generation || Thread.currentThread().isInterrupted()) return;
                progress = new Progress(listId, name, done, total);
                if (PackStore.find(t.deezerId) != null) {
                    done++;
                    continue;
                }
                File part = new File(dir, t.deezerId + ".part");
                DeezerResult r = new DeezerResult(t.deezerId, t.title, t.artist, t.album,
                        0L, t.durationSec, "", t.cover);
                String err = DeezerDownloadRunner.downloadWithFallback(prefs, r, part, ext, null);
                if (err == null && part.length() > 0 && part.renameTo(new File(dir, t.deezerId + "." + ext))) {
                    PackStore.invalidate();
                    done++;
                } else {
                    part.delete();
                    failed++;
                    Log.w(TAG, "could not pack " + t.displayTitle() + ": " + err);
                }
            }
            Log.i(TAG, "packed " + name + ": " + done + " of " + total + (failed > 0 ? ", " + failed + " failed" : ""));
            prefs.edit().remove(PREF_ACTIVE).apply();
        } catch (Exception e) {
            Log.w(TAG, "pack " + listId + " stopped: " + e.getMessage());
        } finally {
            if (gen == generation) progress = null;
            PackStore.invalidate();
            try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
            try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        }
    }
}
