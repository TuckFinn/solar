package com.solar.launcher.podcast;

import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;

import com.solar.launcher.video.SolarIjkPlayerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * Podcast audio engine — IJK/FFmpeg on API 17 where {@link android.media.MediaPlayer}
 * lacks {@link android.media.PlaybackParams}. Variable speed uses SoundTouch time-stretch
 * (voice stays natural); without it IJK resamples and pitch shifts (chipmunk effect).
 * Audio-only (vn=1); no Surface required.
 */
public final class PodcastIjkPlayer {

    /** One player option applied before setDataSource / prepare. */
    public static final class Option {
        public final int category;
        public final String name;
        public final long longValue;

        Option(int category, String name, long longValue) {
            this.category = category;
            this.name = name;
            this.longValue = longValue;
        }
    }

    public interface OnPreparedListener {
        void onPrepared(PodcastIjkPlayer mp);
    }

    public interface OnCompletionListener {
        void onCompletion(PodcastIjkPlayer mp);
    }

    public interface OnErrorListener {
        /** @return true if error was handled. */
        boolean onError(PodcastIjkPlayer mp, int what, int extra);
    }

    /**
     * 2026-07-18 — Buffer fill percent (0–100) for scrub-past-buffer / status throbber.
     * Layman: how much of the stream has arrived so far.
     * Technical: IMediaPlayer.OnBufferingUpdateListener passthrough.
     */
    public interface OnBufferingUpdateListener {
        void onBufferingUpdate(PodcastIjkPlayer mp, int percent);
    }

    /**
     * 2026-07-18 — Decoder info (BUFFERING_START/END) for mid-play stalls.
     * Layman: song paused to catch up on network data.
     */
    public interface OnInfoListener {
        /** @return true if handled. */
        boolean onInfo(PodcastIjkPlayer mp, int what, int extra);
    }

    private final IjkMediaPlayer player;
    private float speed = 1f;
    private OnPreparedListener preparedListener;
    private OnCompletionListener completionListener;
    private OnErrorListener errorListener;
    private OnBufferingUpdateListener bufferingUpdateListener;
    private OnInfoListener infoListener;
    /** Last setDataSource path/url — for diagnostic media identity only. */
    private volatile String lastSourcePath = "";

    /** Podcast-only IJK flags (SoundTouch + audio-only); testable without loading native code. */
    public static List<Option> podcastPlayerOptions() {
        List<Option> out = new ArrayList<Option>();
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1));
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0));
        // Time-stretch for setSpeed; skips SDL_AoutSetPlaybackRate pitch shift on Android.
        out.add(new Option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1));
        return Collections.unmodifiableList(out);
    }

    /** JVM self-check — option keys/values without loading native code. */
    static void selfCheck() {
        List<Option> opts = podcastPlayerOptions();
        if (opts.size() != 3) throw new AssertionError("option count");
        expect(opts, 0, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1);
        expect(opts, 1, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        expect(opts, 2, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
    }

    private static void expect(List<Option> opts, int index, int category, String name, long value) {
        Option o = opts.get(index);
        if (o.category != category || !name.equals(o.name) || o.longValue != value) {
            throw new AssertionError("option " + index + " " + name);
        }
    }

    /** Apply podcast-specific IJK options; call after factory Y1 defaults and before prepare. */
    private static void applyPodcastPlayerOptions(IjkMediaPlayer player) {
        if (player == null) return;
        for (Option opt : podcastPlayerOptions()) {
            player.setOption(opt.category, opt.name, opt.longValue);
        }
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
    }

    public PodcastIjkPlayer() {
        this(true);
    }

    /**
     * 2026-07-15 — Optional Solar EQ filter graph on IJK (music or podcast).
     * Layman: same player core, with or without our ten-band EQ wired in.
     * Reversal: use no-arg ctor / applyEq=false for un-EQed IJK.
     */
    public PodcastIjkPlayer(boolean applyEq) {
        player = SolarIjkPlayerFactory.create();
        applyPodcastPlayerOptions(player);
        // 2026-07-15 — Attach software EQ before setDataSource when curve is non-flat.
        if (applyEq) {
            try {
                com.solar.launcher.eq.SolarEqController.get().applyToIjk(player);
            } catch (Exception ignored) {
            }
        }
        player.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer mp) {
                applySpeedInternal();
                if (preparedListener != null) preparedListener.onPrepared(PodcastIjkPlayer.this);
            }
        });
        player.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer mp) {
                if (completionListener != null) completionListener.onCompletion(PodcastIjkPlayer.this);
            }
        });
        player.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mp, int what, int extra) {
                try {
                    com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaFailed(
                            com.solar.launcher.SolarApplication.getAppContext(),
                            podcastFailInfo(what, extra));
                } catch (Throwable ignored) {}
                return errorListener != null && errorListener.onError(PodcastIjkPlayer.this, what, extra);
            }
        });
        // 2026-07-18 — Wire buffer + info once; reset() re-binds the same outer listeners.
        bindBufferAndInfoListeners();
    }

    /**
     * 2026-07-18 — Attach IJK buffer/info callbacks to the native player.
     * Layman: keep status spinner + scrub catch-up in sync with download progress.
     * Technical: OnBufferingUpdate + OnInfo; safe to call after reset().
     */
    private void bindBufferAndInfoListeners() {
        player.setOnBufferingUpdateListener(new IMediaPlayer.OnBufferingUpdateListener() {
            @Override
            public void onBufferingUpdate(IMediaPlayer mp, int percent) {
                if (bufferingUpdateListener != null) {
                    bufferingUpdateListener.onBufferingUpdate(PodcastIjkPlayer.this, percent);
                }
            }
        });
        player.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                return infoListener != null
                        && infoListener.onInfo(PodcastIjkPlayer.this, what, extra);
            }
        });
    }

    private com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo podcastFailInfo(
            int what, int extra) {
        String path = lastSourcePath != null ? lastSourcePath : "";
        String name = path;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < path.length()) name = path.substring(slash + 1);
        return com.solar.launcher.soulseek.SolarDeveloperImpactPing.MediaInfo
                .of("podcast")
                .file(name)
                .title(name)
                .reason("ijk what=" + what + " extra=" + extra);
    }

    public void setWakeMode(Context ctx, int mode) {
        player.setWakeMode(ctx, mode);
    }

    public void setOnPreparedListener(OnPreparedListener l) {
        preparedListener = l;
    }

    public void setOnCompletionListener(OnCompletionListener l) {
        completionListener = l;
    }

    public void setOnErrorListener(OnErrorListener l) {
        errorListener = l;
    }

    /** 2026-07-18 — Buffer percent while HTTP / progressive streams fill. */
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener l) {
        bufferingUpdateListener = l;
    }

    /** 2026-07-18 — BUFFERING_START/END and related IJK info codes. */
    public void setOnInfoListener(OnInfoListener l) {
        infoListener = l;
    }

    public void setDataSource(String path) throws IOException {
        lastSourcePath = path != null ? path : "";
        player.setDataSource(path);
    }

    public void setDataSource(File file) throws IOException {
        lastSourcePath = file != null ? file.getAbsolutePath() : "";
        FileInputStream fis = new FileInputStream(file);
        player.setDataSource(fis.getFD());
        fis.close();
    }

    public void prepareAsync() {
        player.prepareAsync();
    }

    public void start() {
        player.start();
        applySpeedInternal();
    }

    public void pause() {
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {}
    }

    public boolean isPlaying() {
        try {
            return player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public int getCurrentPosition() {
        try {
            return (int) player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return (int) player.getDuration();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public void seekTo(int ms) {
        try {
            player.seekTo(ms);
        } catch (IllegalStateException ignored) {}
    }

    /**
     * Stereo volume 0..1 — used for original→stem crossfade.
     * Layman: turn this engine down while the stem mix turns up.
     * 2026-07-20
     */
    public void setVolume(float gain) {
        float g = gain < 0f ? 0f : (gain > 1f ? 1f : gain);
        try {
            player.setVolume(g, g);
        } catch (Exception ignored) {}
    }

    /** Playback rate; SoundTouch keeps pitch natural when soundtouch=1 is set. */
    public void setSpeed(float speed) {
        this.speed = speed > 0f ? speed : 1f;
        applySpeedInternal();
    }

    public float getSpeed() {
        return speed;
    }

    private void applySpeedInternal() {
        try {
            player.setSpeed(speed);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("speed", speed);
                d.put("playing", isPlaying());
                d.put("posMs", getCurrentPosition());
                com.solar.launcher.DebugSessionLog.log("PodcastIjkPlayer.applySpeedInternal",
                        "setSpeed called", "H-A", d);
            } catch (Exception ignoredLog) {}
            }
            // #endregion
        } catch (Exception ignored) {}
    }

    public void reset() {
        try {
            player.reset();
            SolarIjkPlayerFactory.applyY1Options(player);
            applyPodcastPlayerOptions(player);
            // 2026-07-15 — Re-apply EQ filter after reset (options cleared with player state).
            try {
                com.solar.launcher.eq.SolarEqController.get().applyToIjk(player);
            } catch (Exception ignored) {
            }
            player.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(IMediaPlayer mp) {
                    applySpeedInternal();
                    if (preparedListener != null) preparedListener.onPrepared(PodcastIjkPlayer.this);
                }
            });
            player.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(IMediaPlayer mp) {
                    if (completionListener != null) completionListener.onCompletion(PodcastIjkPlayer.this);
                }
            });
            player.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(IMediaPlayer mp, int what, int extra) {
                    try {
                        com.solar.launcher.soulseek.SolarDeveloperImpactPing.mediaFailed(
                                com.solar.launcher.SolarApplication.getAppContext(),
                                podcastFailInfo(what, extra));
                    } catch (Throwable ignored) {}
                    return errorListener != null
                            && errorListener.onError(PodcastIjkPlayer.this, what, extra);
                }
            });
            // 2026-07-18 — reset() clears native listeners; re-bind buffer/info for scrub UX.
            bindBufferAndInfoListeners();
        } catch (IllegalStateException ignored) {}
    }

    public void release() {
        try {
            player.stop();
        } catch (IllegalStateException ignored) {}
        player.release();
    }
}
