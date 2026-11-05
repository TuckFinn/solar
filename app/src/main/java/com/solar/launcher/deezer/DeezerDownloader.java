package com.solar.launcher.deezer;

import com.solar.launcher.net.TlsHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Download and decrypt a Deezer track to a local file. */
public final class DeezerDownloader {
    public interface Listener {
        void onProgress(long done, long total);
        void onPartialReady(File dest, long bytesRead);
        void onComplete(File dest, DeezerTrackData track);
        void onError(String message);
    }

    /** Match Reach early-play policy (Soulseek uses 20%; Deezer uses 10%). */
    public static final int EARLY_PLAY_PERCENT = 10;
    /** Fallback when Content-Length is unknown — still start before full file. */
    private static final long PARTIAL_READY_MIN_BYTES = 128 * 1024;

    private final DeezerClient client;
    private final DeezerTrackResolver resolver;
    private final DeezerMedia media;
    private Thread downloadThread;
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    public DeezerDownloader(DeezerClient client) {
        this.client = client;
        this.resolver = new DeezerTrackResolver(client);
        this.media = new DeezerMedia(client);
    }

    public static boolean shouldFirePartialReady(long done, long total, boolean alreadyFired) {
        if (alreadyFired || done <= 0) return false;
        if (total > 0 && done * 100 / total >= EARLY_PLAY_PERCENT) return true;
        return total <= 0 && done >= PARTIAL_READY_MIN_BYTES;
    }

    public void cancel() {
        cancel.set(true);
    }

    public void download(final DeezerResult result, final File destDir, final String ext,
            final Listener listener) {
        cancel.set(false);
        if (downloadThread != null && downloadThread.isAlive()) {
            downloadThread.interrupt();
        }
        downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String direct = DirectStreams.get(result.id);
                    if (direct != null) {
                        File dest = new File(destDir, result.filenameBase() + "." + ext);
                        for (int n = 1; dest.exists(); n++) {
                            dest = new File(destDir, result.filenameBase() + " (" + n + ")." + ext);
                        }
                        directDownload(direct, dest, listener);
                        return;
                    }
                    if (!client.isSessionValid()) client.initSession();
                    DeezerTrackData track = resolver.resolveTrack(result.id);
                    String cdnUrl = null;
                    try {
                        cdnUrl = media.resolveUrl(track.trackToken);
                    } catch (IOException e) {
                        if (track.fallback != null) {
                            track = track.fallback;
                            cdnUrl = media.resolveUrl(track.trackToken);
                        } else {
                            throw e;
                        }
                    }
                    String safeName = result.filenameBase() + "." + ext;
                    File dest = new File(destDir, safeName);
                    int n = 1;
                    while (dest.exists()) {
                        dest = new File(destDir, result.filenameBase() + " (" + n + ")." + ext);
                        n++;
                    }
                    streamDecrypt(cdnUrl, dest, String.valueOf(track.sngId), listener, track);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Download failed";
                    try {
                        com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo info =
                                com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo
                                        .of("deezer")
                                        .id(result != null ? String.valueOf(result.id) : "")
                                        .title(result != null ? result.title : "")
                                        .artist(result != null ? result.artist : "")
                                        .album(result != null ? result.album : "")
                                        .reason(msg);
                        com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaFailed(
                                com.solar.launcher.SolarApplication.getAppContext(), info);
                    } catch (Throwable ignored) {}
                    if (listener != null) {
                        listener.onError(msg);
                    }
                }
            }
        }, "DeezerDownload");
        downloadThread.start();
    }

    /** Download into an exact file path (background album queue placeholders). */
    public void downloadToFile(final DeezerResult result, final File dest, final Listener listener) {
        cancel.set(false);
        if (downloadThread != null && downloadThread.isAlive()) {
            downloadThread.interrupt();
        }
        downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String direct = DirectStreams.get(result.id);
                    if (direct != null) {
                        directDownload(direct, dest, listener);
                        return;
                    }
                    if (!client.isSessionValid()) client.initSession();
                    DeezerTrackData track = resolver.resolveTrack(result.id);
                    String cdnUrl = null;
                    try {
                        cdnUrl = media.resolveUrl(track.trackToken);
                    } catch (IOException e) {
                        if (track.fallback != null) {
                            track = track.fallback;
                            cdnUrl = media.resolveUrl(track.trackToken);
                        } else {
                            throw e;
                        }
                    }
                    if (dest.getParentFile() != null && !dest.getParentFile().exists()) {
                        dest.getParentFile().mkdirs();
                    }
                    String ext = dest.getName();
                    int dot = ext.lastIndexOf('.');
                    ext = dot > 0 ? ext.substring(dot + 1) : "mp3";
                    streamDecrypt(cdnUrl, dest, String.valueOf(track.sngId), listener, track);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Download failed";
                    try {
                        com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo info =
                                com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo
                                        .of("deezer")
                                        .id(result != null ? String.valueOf(result.id) : "")
                                        .title(result != null ? result.title : "")
                                        .artist(result != null ? result.artist : "")
                                        .album(result != null ? result.album : "")
                                        .file(dest != null ? dest.getName() : "")
                                        .reason(msg);
                        com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaFailed(
                                com.solar.launcher.SolarApplication.getAppContext(), info);
                    } catch (Throwable ignored) {}
                    if (listener != null) {
                        listener.onError(msg);
                    }
                }
            }
        }, "DeezerDownload");
        downloadThread.start();
    }

    /**
     * 2026-10-25 — A nookTunes-served song (see {@link DirectStreams}): already a
     * plain mp3, so no session, token or decryption. Same listener contract,
     * with no DeezerTrackData (completion handlers fall back to the result).
     */
    private void directDownload(String url, final File dest, final Listener listener)
            throws IOException {
        if (dest.getParentFile() != null && !dest.getParentFile().exists()) {
            dest.getParentFile().mkdirs();
        }
        if (dest.exists()) dest.delete();
        final boolean[] partialFired = {false};
        try {
            com.solar.launcher.net.SolarHttp.downloadToFile(url, dest,
                    new com.solar.launcher.net.SolarHttp.DownloadProgress() {
                        @Override public void onProgress(long read, long total) {
                            if (listener == null) return;
                            if (shouldFirePartialReady(read, total, partialFired[0])) {
                                partialFired[0] = true;
                                listener.onPartialReady(dest, read);
                            }
                            listener.onProgress(read, total > 0 ? total : read);
                        }
                    }, 0L, null, cancel);
        } catch (IOException e) {
            if (dest.exists()) dest.delete();
            throw e;
        }
        if (listener != null) listener.onComplete(dest, null);
    }

    private void streamDecrypt(String url, File dest, String sngId, Listener listener,
            DeezerTrackData track) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", DeezerClient.USER_AGENT)
                .build();
        // 2026-11-05 (#39) — was 5 minutes. A read timeout is the longest gap between
        // bytes, not the whole download, so a slow transfer is unaffected; a dead one
        // held the Discovery queue for up to 5 min per account tier and attempt (2Y1
        // sat on "Loading" 07:32-07:44 on 10-29 with nothing arriving).
        OkHttpClient http = TlsHelper.client().newBuilder()
                .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        Response resp = http.newCall(req).execute();
        InputStream in = null;
        FileOutputStream rawOut = null;
        File tempRaw = new File(dest.getParentFile(), dest.getName() + ".enc");
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            long total = resp.body().contentLength();
            in = resp.body().byteStream();
            rawOut = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            long read = 0;
            long lastNotifyDone = 0;
            int lastNotifyPct = -1;
            boolean partialFired = false;

            java.io.ByteArrayOutputStream blockBuf = new java.io.ByteArrayOutputStream(2048);
            int blockIndex = 0;
            String keyStr = DeezerDecrypt.calcBfKey(sngId);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("Blowfish/CBC/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(keyStr.getBytes("UTF-8"), "Blowfish"),
                    new javax.crypto.spec.IvParameterSpec(hexToIv()));
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancel.get()) throw new IOException("Cancelled");
                blockBuf.write(buf, 0, n);
                while (blockBuf.size() >= 2048) {
                    byte[] block = blockBuf.toByteArray();
                    byte[] chunk = new byte[2048];
                    System.arraycopy(block, 0, chunk, 0, 2048);
                    byte[] remainder = new byte[block.length - 2048];
                    if (remainder.length > 0) {
                        System.arraycopy(block, 2048, remainder, 0, remainder.length);
                    }
                    blockBuf.reset();
                    if (remainder.length > 0) blockBuf.write(remainder, 0, remainder.length);
                    if ((blockIndex % 3) == 0) {
                        chunk = cipher.doFinal(chunk);
                    }
                    rawOut.write(chunk);
                    read += chunk.length;
                    blockIndex++;
                    if (listener != null) {
                        final long totalBytes = total > 0 ? total : read;
                        final int pct = total > 0 ? (int) (read * 100 / totalBytes) : -1;
                        if (shouldFirePartialReady(read, totalBytes, partialFired)) {
                            partialFired = true;
                            rawOut.flush();
                            listener.onPartialReady(dest, read);
                        }
                        if (read >= totalBytes || pct != lastNotifyPct
                                && (pct < 0 || pct % 5 == 0 || read - lastNotifyDone >= 65536)) {
                            listener.onProgress(read, totalBytes);
                            lastNotifyDone = read;
                            lastNotifyPct = pct;
                        }
                    }
                }
            }
            if (blockBuf.size() > 0) {
                byte[] tail = blockBuf.toByteArray();
                if ((blockIndex % 3) == 0 && tail.length == 2048) {
                    tail = cipher.doFinal(tail);
                }
                rawOut.write(tail);
                read += tail.length;
            }
            rawOut.flush();
            rawOut.close();
            rawOut = null;
            if (listener != null) {
                listener.onProgress(read, total > 0 ? total : read);
                listener.onComplete(dest, track);
            }
            try {
                com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo info =
                        com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo
                                .of("deezer")
                                .id(sngId != null ? sngId : "")
                                .title(track != null ? track.title : "")
                                .artist(track != null ? track.artist : "")
                                .album(track != null ? track.album : "")
                                .file(dest != null ? dest.getName() : "")
                                .reason("download complete")
                                .ok(true);
                com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaOk(
                        com.solar.launcher.SolarApplication.getAppContext(), info);
            } catch (Throwable ignored) {}
        } catch (Exception e) {
            if (dest.exists()) dest.delete();
            throw new IOException(e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (rawOut != null) try { rawOut.close(); } catch (IOException ignored) {}
            if (resp.body() != null) resp.body().close();
            if (tempRaw.exists()) tempRaw.delete();
        }
    }

    private static byte[] hexToIv() {
        byte[] iv = new byte[8];
        for (int i = 0; i < 8; i++) {
            iv[i] = (byte) Integer.parseInt("0001020304050607".substring(i * 2, i * 2 + 2), 16);
        }
        return iv;
    }
}
