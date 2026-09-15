package com.solar.launcher;

import android.content.Context;
import android.media.AudioManager;

/**
 * Media playback volume only — wheel / context sliders adjust {@link AudioManager#STREAM_MUSIC}
 * (or FM when active). Ring and notification streams stay silent.
 * Display levels use 0–100 via {@link HearingSafetyVolume} (100 = top of allowed range).
 */
public final class MediaVolumeControl {

    /** Do not trigger the stock volume panel when changing level from Solar. */
    public static final int FLAGS_NO_UI = 0;
    /** VolumePanelHooks skips overlay while Solar in-app adjusts level (avoids double UI). */
    public static final String PROP_INTERNAL_ADJUST = "persist.solar.volume.internal";

    private static final int STREAM_FM = 10;

    private MediaVolumeControl() {}

    /** Keep ringer / notification / system UI sounds muted on Y1/Y2. */
    public static void ensureAlertStreamsSilent(Context ctx) {
        if (ctx == null) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.setStreamVolume(AudioManager.STREAM_RING, 0, FLAGS_NO_UI);
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, FLAGS_NO_UI);
            am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, FLAGS_NO_UI);
            am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        } catch (Exception ignored) {}
    }

    /** True when this stream should use Solar's media volume UI (not ring/notification). */
    public static boolean isMediaVolumeStream(int streamType) {
        return streamType == AudioManager.STREAM_MUSIC || streamType == STREAM_FM;
    }

    public static int getVolume(Context ctx, int streamType) {
        AudioManager am = audioManager(ctx);
        if (am == null || !isMediaVolumeStream(streamType)) return 0;
        return am.getStreamVolume(streamType);
    }

    public static int getMaxVolume(Context ctx, int streamType) {
        return HearingSafetyVolume.getEffectiveMaxIndex(ctx, streamType);
    }

    /** UI slider max — always 100 (100% of allowed range). */
    public static int getDisplayMaxVolume(Context ctx) {
        return HearingSafetyVolume.DISPLAY_MAX;
    }

    public static int getDisplayVolume(Context ctx, int streamType) {
        return HearingSafetyVolume.getDisplayVolume(ctx, streamType);
    }

    public static int getMediaVolume(Context ctx) {
        return getVolume(ctx, AudioManager.STREAM_MUSIC);
    }

    public static int getMediaMaxVolume(Context ctx) {
        return getMaxVolume(ctx, AudioManager.STREAM_MUSIC);
    }

    public static int getMediaDisplayVolume(Context ctx) {
        return getDisplayVolume(ctx, AudioManager.STREAM_MUSIC);
    }

    /** Step media (or FM) stream up/down; returns new level index. */
    public static int adjustStream(Context ctx, int streamType, boolean up) {
        return HearingSafetyVolume.adjustStreamIndex(ctx, streamType, up);
    }

    /**
     * Brief gate so Xposed volume hooks ignore Solar in-app slider adjustments.
     * 2026-07-16 — Never call {@link RootShell#canRun()} here (that was a sync {@code su -c id}
     * on every wheel tick → multi-second NP volume lag). Prefer reflection SystemProperties;
     * root setprop only as async fallback.
     */
    static void markInternalVolumeAdjust() {
        // Layman: tell the system “Solar is moving the volume knob itself” without waking root.
        if (setSystemPropertyFast(PROP_INTERNAL_ADJUST, "1")) {
            scheduleInternalAdjustClear();
            return;
        }
        // Async only — never block the volume wheel on su.
        RootShell.runAsync("setprop " + PROP_INTERNAL_ADJUST + " 1");
        scheduleInternalAdjustClear();
    }

    private static final android.os.Handler INTERNAL_ADJUST_HANDLER;
    private static volatile long internalAdjustClearAtMs;
    private static java.lang.reflect.Method sSystemPropertiesSet;

    static {
        android.os.HandlerThread ht = new android.os.HandlerThread("SolarVolInternal");
        ht.start();
        INTERNAL_ADJUST_HANDLER = new android.os.Handler(ht.getLooper());
    }

    /** 2026-07-16 — In-process setprop when allowed (no su spawn on volume wheel). */
    private static boolean setSystemPropertyFast(String key, String value) {
        try {
            java.lang.reflect.Method set = sSystemPropertiesSet;
            if (set == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                set = sp.getMethod("set", String.class, String.class);
                sSystemPropertiesSet = set;
            }
            set.invoke(null, key, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Extend clear deadline on rapid wheel steps — one setprop 0 after burst ends. */
    private static void scheduleInternalAdjustClear() {
        internalAdjustClearAtMs = android.os.SystemClock.uptimeMillis() + 250L;
        INTERNAL_ADJUST_HANDLER.removeCallbacks(clearInternalAdjustRunnable);
        INTERNAL_ADJUST_HANDLER.postDelayed(clearInternalAdjustRunnable, 250L);
    }

    private static final Runnable clearInternalAdjustRunnable = new Runnable() {
        @Override
        public void run() {
            if (android.os.SystemClock.uptimeMillis() < internalAdjustClearAtMs) {
                INTERNAL_ADJUST_HANDLER.postDelayed(this,
                        internalAdjustClearAtMs - android.os.SystemClock.uptimeMillis());
                return;
            }
            if (setSystemPropertyFast(PROP_INTERNAL_ADJUST, "0")) return;
            RootShell.runAsync("setprop " + PROP_INTERNAL_ADJUST + " 0");
        }
    };

    /** 2026-07-06 — Which stream global overlay should adjust (FM app or Solar FM vs music). */
    public static int resolveActiveStream(Context ctx) {
        String fg = ExternalInputHandoff.getForegroundPackageName(ctx);
        if (ExternalInputHandoff.FM_RADIO_PACKAGE.equals(fg)) return STREAM_FM;
        return AudioManager.STREAM_MUSIC;
    }

    public static int adjustMedia(Context ctx, boolean up) {
        // #region agent log
        AudioManager am = audioManager(ctx);
        int musicBefore = am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : -1;
        int fmBefore = am != null ? am.getStreamVolume(STREAM_FM) : -1;
        String fg = ExternalInputHandoff.getForegroundPackageName(ctx);
        int resolved = resolveActiveStream(ctx);
        // #endregion
        int result = adjustStream(ctx, AudioManager.STREAM_MUSIC, up);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("up", up);
            d.put("streamUsed", AudioManager.STREAM_MUSIC);
            d.put("resolvedStream", resolved);
            d.put("streamMismatch", resolved != AudioManager.STREAM_MUSIC);
            d.put("fg", fg);
            d.put("musicBefore", musicBefore);
            d.put("musicAfter", am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : -1);
            d.put("fmBefore", fmBefore);
            d.put("fmAfter", am != null ? am.getStreamVolume(STREAM_FM) : -1);
            d.put("result", result);
            d.put("fmForeground", ExternalInputHandoff.FM_RADIO_PACKAGE.equals(fg));
            Debug6d1aeeLog.log("MediaVolumeControl.adjustMedia", "volume step", "H-A", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return result;
    }

    /** Push 0–100 slider level to system stream index (hearing cap + warning apply). */
    public static void setDisplayVolume(Context ctx, int streamType, int displayLevel) {
        int effMax = getMaxVolume(ctx, streamType);
        int index = HearingSafetyVolume.displayToIndex(displayLevel, effMax);
        HearingSafetyVolume.setStreamIndex(ctx, streamType, index);
    }

    /** Refresh a context-menu / overlay slider — always 0–100 display units. */
    public static void syncVolumeSliderUi(Context ctx, ThemedContextMenu menu) {
        if (ctx == null || menu == null) return;
        int musicDisplay = getDisplayVolume(ctx, AudioManager.STREAM_MUSIC);
        int fmDisplay = getDisplayVolume(ctx, STREAM_FM);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("musicDisplay", musicDisplay);
            d.put("fmDisplay", fmDisplay);
            d.put("fg", ExternalInputHandoff.getForegroundPackageName(ctx));
            d.put("sliderShowsMusic", true);
            Debug6d1aeeLog.log("MediaVolumeControl.syncVolumeSliderUi", "slider sync", "H-B", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        menu.updateVolumeSlider(musicDisplay, getDisplayMaxVolume(ctx));
    }

    private static AudioManager audioManager(Context ctx) {
        if (ctx == null) return null;
        return (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }
}
