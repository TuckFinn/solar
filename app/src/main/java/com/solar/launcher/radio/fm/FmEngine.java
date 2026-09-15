package com.solar.launcher.radio.fm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 2026-07-06 — FM hardware facade: native JNI (JJ path) with optional MTK service fallback.
 * Layman: powers the radio chip, plays sound, tunes stations.
 * Technical: FMRadioNative + FmAudioRouter; AIDL bind only when native class missing.
 */
public final class FmEngine {
  private static final String FM_PACKAGE = "com.mediatek.FMRadio";
  private static final String FM_SERVICE = "com.mediatek.FMRadio.FMRadioService";
  private static final String FM_SERVICE_ACTION = "com.mediatek.FMRadio.IFMRadioService";
  private static final String PREF_ENGINE_MODE = "solar_fm_engine_mode";
  private static final String MODE_SERVICE = "service";
  private static final long BIND_TIMEOUT_MS = 5000L;

  public interface ScanCallback {
    void onStationFound(int freqKhz, int signal, boolean stereo);

    void onScanComplete();

    void onError(String reason);
  }

  private final Context appCtx;
  private final FmNativeLoader nativeLoader;
  private final FmAudioRouter audioRouter;
  private final boolean useServiceFallback;

  private boolean deviceOpen;
  private boolean powerUp;
  private float currentFreqMhz = 87.5f;
  private String lastError = "";

  private Object fmService;
  private ServiceConnection connection;
  private boolean bound;
  private volatile boolean scanCancelled;

  public FmEngine(Context ctx) {
    appCtx = ctx.getApplicationContext();
    nativeLoader = new FmNativeLoader(appCtx);
    audioRouter = new FmAudioRouter(appCtx);
    SharedPreferences prefs = appCtx.getSharedPreferences("radio_settings", Context.MODE_PRIVATE);
    useServiceFallback =
        MODE_SERVICE.equals(prefs.getString(PREF_ENGINE_MODE, ""))
            || !nativeLoader.isReady();
    if (!nativeLoader.isReady() && lastError.isEmpty()) {
      lastError = nativeLoader.loadError();
    }
  }

  /** True when MTK FM package is installed (native or service path). */
  public boolean isAvailable() {
    return nativeLoader.isReady() || isFmPackageInstalled();
  }

  public boolean isPowerUp() {
    return powerUp;
  }

  public float currentFreqMhz() {
    return currentFreqMhz;
  }

  public int currentFreqKhz() {
    return Math.round(currentFreqMhz * 1000f);
  }

  public String lastError() {
    return lastError != null ? lastError : "";
  }

  public boolean isSpeakerOn() {
    return audioRouter.isSpeakerOn();
  }

  /** 2026-07-15 — Wired / Bluetooth / Speaker selection for FM audio. */
  public FmAudioRouter.Output audioOutput() {
    return audioRouter.getOutput();
  }

  public void setAudioOutput(FmAudioRouter.Output out) {
    audioRouter.setOutput(out);
  }

  /** Cycle Wired → Bluetooth → Speaker; returns the new mode. */
  public FmAudioRouter.Output cycleAudioOutput() {
    return audioRouter.cycleOutput();
  }

  public void setSpeaker(boolean useSpeaker) {
    audioRouter.setSpeaker(useSpeaker);
  }

  /** 2026-07-15 — Headset plug event → re-route (headphones unless Speaker chosen). */
  public void onHeadsetPlug(boolean pluggedIn) {
    audioRouter.onHeadsetPlug(pluggedIn);
  }

  public boolean isWiredHeadsetOn() {
    return audioRouter.isWiredHeadsetOn();
  }

  /** Active FM MediaPlayer stream (STREAM_MUSIC or STREAM_FM) for volume HUD. */
  public int audioStreamType() {
    return audioRouter.activeStreamType();
  }

  /** True when the audio pump is live. */
  public boolean isAudioPlaying() {
    return audioRouter.isPlaying();
  }

  /**
   * Open device, power up, route audio, and tune — full FM playback start.
   * @return false when prep, native, or audio routing fails
   */
  public synchronized boolean playStation(int freqKhz) {
    lastError = "";
    // 2026-07-06 — FM chip blocked while airplane mode is on (MTK /dev/fm).
    FmAirplaneModeHelper.beginSolarSession(appCtx);
    float mhz = khzToMhz(freqKhz);
    android.util.Log.i("FmEngine", "playStation khz=" + freqKhz + " mhz=" + mhz
            + " serviceFallback=" + useServiceFallback
            + " nativeReady=" + nativeLoader.isReady()
            + " speaker=" + audioRouter.isSpeakerOn());
    boolean ok;
    if (useServiceFallback) {
      ok = playStationViaService(freqKhz, mhz);
    } else {
      try {
        ok = powerUpInternal(mhz) && tuneInternal(mhz);
      } catch (Throwable t) {
        lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
        ok = false;
      }
      // 2026-07-15 — Native busy: hard free + full claim + one more native try.
      if (!ok && lastError != null
              && (lastError.toLowerCase(java.util.Locale.US).contains("busy")
                      || lastError.toLowerCase(java.util.Locale.US).contains("blocked")
                      || lastError.toLowerCase(java.util.Locale.US).contains("opendev"))) {
        android.util.Log.w("FmEngine", "playStation busy — hardFree + retry native: " + lastError);
        FmHardwarePrep.hardFreeBlocking();
        FmHardwarePrep.prepareBlocking();
        powerUp = false;
        deviceOpen = false;
        lastError = "";
        try {
          ok = powerUpInternal(mhz) && tuneInternal(mhz);
        } catch (Throwable t) {
          lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
          ok = false;
        }
      }
      // Service path if native still fails but FMRadio.apk is present.
      if (!ok && isFmPackageInstalled()) {
        android.util.Log.w("FmEngine", "playStation native fail — try service: " + lastError);
        String nativeErr = lastError;
        lastError = "";
        powerUp = false;
        deviceOpen = false;
        ok = playStationViaService(freqKhz, mhz);
        if (!ok && (lastError == null || lastError.isEmpty())) {
          lastError = nativeErr;
        }
      }
    }
    // 2026-07-15 — No-jack robustness: allow FM open without earphones (may be weak RF).
    // Layman: no cable plugged in? Still try power-up via speaker path — better than hard fail.
    // Never auto-force speaker when a wired jack is present (user picks Speaker in UI).
    // Was: only retried when lastError mentioned earphone/antenna/power-up.
    if (!ok && !audioRouter.isSpeakerOn() && !audioRouter.isWiredHeadsetOn()) {
      android.util.Log.w("FmEngine", "playStation retry with speaker (no jack): " + lastError);
      audioRouter.setSpeaker(true);
      lastError = "";
      powerUp = false;
      deviceOpen = false;
      FmHardwarePrep.prepareBlocking();
      if (useServiceFallback) {
        ok = playStationViaService(freqKhz, mhz);
      } else {
        try {
          ok = powerUpInternal(mhz) && tuneInternal(mhz);
        } catch (Throwable t) {
          lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
          ok = false;
        }
      }
    }
    // After any successful start, re-assert headphone routing if jack is in.
    if (ok) {
      audioRouter.applyOutputRoute();
    }
    android.util.Log.i("FmEngine", "playStation result ok=" + ok + " power=" + powerUp
            + " audio=" + audioRouter.isPlaying() + " err=" + lastError);
    if (!ok) {
      FmAirplaneModeHelper.endSolarSession(appCtx);
    }
    return ok;
  }

  public synchronized boolean openDevice() {
    if (useServiceFallback) {
      return ensureServiceBound() && invokeServiceBool("openDevice");
    }
    return openDeviceNative();
  }

  public synchronized boolean powerUp(int freqKhz) {
    if (useServiceFallback) {
      float mhz = khzToMhz(freqKhz);
      if (!ensureServiceBound() || !invokeServiceBool("openDevice")) return false;
      return invokeServiceBool("powerUp", float.class, mhz);
    }
    return powerUpInternal(khzToMhz(freqKhz));
  }

  public synchronized boolean powerDown() {
    scanCancelled = true;
    boolean ok = true;
    if (useServiceFallback) {
      ok = invokeServiceBool("powerDown");
      unbindServiceQuiet();
      powerUp = false;
      deviceOpen = false;
    } else if (nativeLoader.isReady() && powerUp) {
      try {
        audioRouter.stop();
        setMuteNative(true);
        nativeLoader.invokeStatic("powerdown", new Class<?>[] {int.class}, new Object[] {0});
        nativeLoader.invokeStatic("closedev", new Class<?>[] {}, new Object[] {});
      } catch (Throwable ignored) {}
      powerUp = false;
      deviceOpen = false;
    }
    // 2026-07-06 — Restore airplane/Wi‑Fi/BT snapshot when FM powers down.
    FmAirplaneModeHelper.endSolarSession(appCtx);
    return ok;
  }

  public synchronized boolean tune(int freqKhz) {
    float mhz = khzToMhz(freqKhz);
    if (useServiceFallback) {
      return invokeServiceBool("tune", float.class, mhz);
    }
    return tuneInternal(mhz);
  }

  public synchronized boolean mute(boolean muted) {
    if (useServiceFallback) {
      Object result = invokeService("setMute", boolean.class, muted);
      if (result instanceof Integer) return ((Integer) result) == 0;
      if (result instanceof Boolean) return (Boolean) result;
      return result != null;
    }
    setMuteNative(muted);
    return true;
  }

  public synchronized boolean startScan(final ScanCallback callback) {
    scanCancelled = false;
    if (useServiceFallback) {
      return startScanViaService(callback);
    }
    if (!powerUp) {
      if (callback != null) callback.onError("Turn on the radio first.");
      return false;
    }
    new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  short[] raw =
                      (short[]) nativeLoader.invokeStatic("autoscan", new Class<?>[] {}, new Object[] {});
                  if (scanCancelled) return;
                  if (raw == null || raw.length == 0) {
                    if (callback != null) {
                      callback.onError(lastError.isEmpty() ? "No stations found" : lastError);
                      callback.onScanComplete();
                    }
                    return;
                  }
                  for (short s : raw) {
                    if (scanCancelled) return;
                    int khz = vendorShortToKhz(s);
                    if (callback != null) callback.onStationFound(khz, 0, false);
                  }
                  if (callback != null) callback.onScanComplete();
                } catch (Throwable t) {
                  lastError = "AutoScan failed: " + t.getMessage();
                  if (callback != null) callback.onError(lastError);
                }
              }
            },
            "FmAutoScan")
        .start();
    return true;
  }

  public synchronized void stopScan() {
    scanCancelled = true;
    if (useServiceFallback) {
      invokeService("stopScan");
      invokeService("cancelScan");
      return;
    }
    try {
      nativeLoader.invokeStatic("stopscan", new Class<?>[] {}, new Object[] {});
    } catch (Throwable ignored) {}
  }

  /** Program service name from RDS, or null when unavailable. */
  public synchronized String getRdsPs() {
    if (useServiceFallback) return getRdsPsFromService();
    return bytesToString(invokeNativeBytes("getPS"));
  }

  /** Radio text from RDS. */
  public synchronized String getRdsRt() {
    if (useServiceFallback) return getRdsRtFromService();
    String rt = bytesToString(invokeNativeBytes("getLRText"));
    if (rt != null) return rt;
    return bytesToString(invokeNativeBytes("getRT"));
  }

  /**
   * 2026-07-06 — FM signal strength from chip; 0 when unavailable.
   * Layman: how loud the station is on the dial.
   * Technical: native getRssi / service getRssi reflection.
   */
  public synchronized int getRssi() {
    if (!powerUp) return 0;
    if (useServiceFallback) {
      Object r = invokeService("getRssi");
      if (r == null) r = invokeService("getRSSI");
      if (r instanceof Integer) return (Integer) r;
    }
    Integer r = invokeNativeInt("getRssi");
    if (r != null) return r;
    r = invokeNativeInt("getrssi");
    return r != null ? r : 0;
  }

  /**
   * 2026-07-15 — Stereo/mono from MTK chip when powered; false if unknown.
   * Layman: true when the station is coming in as stereo.
   * Technical: FMRadioNative.stereoMono / service isStereo reflection; never throws.
   */
  public synchronized boolean isStereo() {
    if (!powerUp) return false;
    if (useServiceFallback) {
      Object r = invokeService("isStereo");
      if (r == null) r = invokeService("getStereoMono");
      if (r instanceof Boolean) return (Boolean) r;
      if (r instanceof Integer) return ((Integer) r) != 0;
    }
    try {
      Object r = nativeLoader.invokeStatic("stereoMono", new Class<?>[] {}, new Object[] {});
      if (r instanceof Boolean) return (Boolean) r;
    } catch (Throwable ignored) {}
    Boolean b = invokeNativeBool("stereoMono");
    if (b != null) return b;
    b = invokeNativeBool("isStereo");
    return b != null && b;
  }

  /** Optional native boolean helper — null when method missing. */
  private Boolean invokeNativeBool(String method) {
    try {
      Object r = nativeLoader.invokeStatic(method, new Class<?>[] {}, new Object[] {});
      if (r instanceof Boolean) return (Boolean) r;
    } catch (Throwable ignored) {}
    return null;
  }

  /**
   * 2026-07-06 — Car-stereo seek: hardware step first, else band walk with RSSI/RDS.
   * Layman: jump to the next station that actually comes in.
   * Technical: JNI seek(float,bool) then stepped tune until signal or band wrap.
   *
   * @return tuned kHz, or -1 on failure
   */
  public synchronized int seekStationKhz(int fromKhz, boolean forward, com.solar.launcher.radio.FmBandPlan plan) {
    if (!powerUp || plan == null) return -1;
    Integer hw = tryHardwareSeek(forward);
    if (hw != null && hw > 0) {
      int k = plan.clampKhz(hw);
      currentFreqMhz = k / 1000f;
      return k;
    }
    return seekByBandWalk(fromKhz, forward, plan, false);
  }

  /**
   * 2026-07-15 — Power-on auto-seek: find the first station that actually receives (car radio).
   * Layman: turn radio on → skip dead air until something comes in.
   * Technical: settle RSSI; if weak, hardware seek then band walk; stay put if nothing found.
   *
   * @return final kHz after seek (may equal fromKhz)
   */
  public synchronized int seekFirstStationIfWeak(int fromKhz, com.solar.launcher.radio.FmBandPlan plan) {
    if (!powerUp || plan == null) return fromKhz;
    int start = plan.clampKhz(fromKhz);
    try {
      Thread.sleep(150L); // chip settle after powerup
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    if (!looksLikeDeadAir()) {
      return start;
    }
    android.util.Log.i("FmEngine", "auto-seek first station from " + start);
    Integer hw = tryHardwareSeek(true);
    if (hw != null && hw > 0) {
      int k = plan.clampKhz(hw);
      currentFreqMhz = k / 1000f;
      if (!looksLikeDeadAir()) return k;
    }
    int walked = seekByBandWalk(start, true, plan, true);
    return walked > 0 ? walked : start;
  }

  /**
   * True when current tune has no usable signal (RSSI/RDS/stereo).
   * Layman: “nothing on this dial mark.”
   */
  public synchronized boolean looksLikeDeadAir() {
    if (!powerUp) return true;
    int rssi = getRssi();
    if (rssi >= 8) return false;
    if (rssi >= 3 && isStereo()) return false;
    String ps = getRdsPs();
    if (ps != null && ps.trim().length() > 0) return false;
    String rt = getRdsRt();
    if (rt != null && rt.trim().length() > 0) return false;
    // rssi==0 often means “API missing” not silence — only treat as dead if truly zero after power
    // and no stereo; still seek so car-radio behaviour runs on first start.
    return rssi < 3;
  }

  /** Next/prev grid MHz on the band plan — wraps at ends (unit-testable). 2026-07-06 */
  static int nextBandStepKhz(int khz, boolean forward, com.solar.launcher.radio.FmBandPlan plan) {
    int step = plan.stepKhz();
    int next = forward ? khz + step : khz - step;
    if (next > plan.maxKhz()) next = plan.minKhz();
    if (next < plan.minKhz()) next = plan.maxKhz();
    return plan.clampKhz(next);
  }

  public synchronized void release() {
    stopScan();
    powerDown();
    unbindServiceQuiet();
  }

  // --- Native path ---

  /**
   * 2026-07-15 — switchAntenna(mode) then powerup. mode 1 = short (earphone), 0 = long (internal).
   * Layman: try each antenna path; empty jack is OK.
   * @return true when native powerup reports success
   */
  private boolean tryPowerUpWithAntenna(float mhz, int antennaMode) {
    try {
      nativeLoader.invokeStatic(
          "switchAntenna", new Class<?>[] {int.class}, new Object[] {antennaMode});
    } catch (Throwable t) {
      android.util.Log.w(
          "FmEngine", "switchAntenna(" + antennaMode + ") " + t.getMessage());
      // Continue — some builds ignore switchAntenna when jack empty.
    }
    try {
      Boolean powered =
          (Boolean)
              nativeLoader.invokeStatic(
                  "powerup", new Class<?>[] {float.class}, new Object[] {mhz});
      boolean ok = powered != null && powered;
      android.util.Log.i(
          "FmEngine", "powerup after antenna=" + antennaMode + " ok=" + ok);
      return ok;
    } catch (Throwable t) {
      android.util.Log.w(
          "FmEngine", "powerup after antenna=" + antennaMode + " " + t.getMessage());
      return false;
    }
  }

  private boolean powerUpInternal(float mhz) {
    if (!nativeLoader.isReady()) {
      lastError = "FMRadioNative driver missing";
      return false;
    }
    if (powerUp && Math.abs(currentFreqMhz - mhz) < 0.001f) {
      // 2026-07-15 — Already on station: restart audio pump if MediaPlayer died.
      if (!audioRouter.isPlaying()) {
        if (!audioRouter.start()) {
          lastError = audioRouter.lastError();
          return false;
        }
        setMuteNative(false);
      }
      return true;
    }
    if (powerUp) {
      if (!tuneInternal(mhz)) return false;
      if (!audioRouter.isPlaying()) {
        if (!audioRouter.start()) {
          lastError = audioRouter.lastError();
          return false;
        }
      }
      setMuteNative(false);
      return true;
    }
    // 2026-07-15 — Root claim of /dev/fm (kill holders, chmod 666, airplane off).
    // Layman: with root we own the conditions for FM — free the chip before every power-up.
    boolean claimed = FmHardwarePrep.prepareBlocking();
    android.util.Log.i("FmEngine", "hardware claim ok=" + claimed + " diag="
            + FmHardwarePrep.lastDiag());
    try {
      deviceOpen = false;
      // Up to 5 open attempts; hard-free after 2nd fail; soft unlock before every opendev.
      for (int attempt = 1; attempt <= 5 && !deviceOpen; attempt++) {
        try {
          nativeLoader.invokeStatic("closedev", new Class<?>[] {}, new Object[] {});
        } catch (Throwable ignored) {}
        // Re-assert node mode + airplane so udev/settings races cannot undo the claim.
        FmHardwarePrep.softUnlockBlocking();
        try {
          Thread.sleep(attempt == 1 ? 80L : 200L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        try {
          Boolean opened =
              (Boolean) nativeLoader.invokeStatic("opendev", new Class<?>[] {}, new Object[] {});
          deviceOpen = opened != null && opened;
          android.util.Log.i("FmEngine", "opendev attempt=" + attempt + " open=" + deviceOpen
                  + " claimDiag=" + FmHardwarePrep.lastDiag());
        } catch (Throwable t) {
          android.util.Log.w("FmEngine", "opendev attempt " + attempt + ": " + t.getMessage());
          deviceOpen = false;
        }
        if (!deviceOpen) {
          if (attempt == 2 || attempt == 4) {
            android.util.Log.w("FmEngine", "opendev still busy — hardFreeBlocking");
            FmHardwarePrep.hardFreeBlocking();
          } else if (attempt >= 3) {
            FmHardwarePrep.prepareBlocking();
          }
        }
      }
      if (!deviceOpen) {
        lastError = "Failed to open /dev/fm (hardware busy or blocked). diag="
            + FmHardwarePrep.lastDiag();
        android.util.Log.e("FmEngine", lastError);
        return false;
      }

      // Stock: powerUp native then mute immediately (chip silent until enableFmAudio).
      // 2026-07-15 — Do not hard-require earphones. Try short antenna (1) then long (0).
      // Layman: open the radio even with no headphones; signal may be weak without a cable.
      // Technical: MTK switchAntenna(1)=short/earphone, (0)=long/internal; never abort solely
      // because the jack is empty. Exclusivity (stop other media) is owned by startFmStation.
      Boolean powered =
          (Boolean) nativeLoader.invokeStatic("powerup", new Class<?>[] {float.class}, new Object[] {mhz});
      powerUp = powered != null && powered;
      if (!powerUp) {
        powerUp = tryPowerUpWithAntenna(mhz, 1 /* short / earphone */);
      }
      if (!powerUp) {
        powerUp = tryPowerUpWithAntenna(mhz, 0 /* long / internal */);
      }
      if (powerUp) {
        setMuteNative(true);
      }
      if (!powerUp) {
        // One more prep + open + power after failed powerup (service held chip half-open).
        FmHardwarePrep.prepareBlocking();
        try {
          nativeLoader.invokeStatic("closedev", new Class<?>[] {}, new Object[] {});
        } catch (Throwable ignored) {}
        try {
          Boolean opened =
              (Boolean) nativeLoader.invokeStatic("opendev", new Class<?>[] {}, new Object[] {});
          deviceOpen = opened != null && opened;
          if (deviceOpen) {
            powerUp = tryPowerUpWithAntenna(mhz, 1);
            if (!powerUp) {
              powerUp = tryPowerUpWithAntenna(mhz, 0);
            }
            if (!powerUp) {
              powered =
                  (Boolean)
                      nativeLoader.invokeStatic(
                          "powerup", new Class<?>[] {float.class}, new Object[] {mhz});
              powerUp = powered != null && powered;
            }
          }
        } catch (Throwable ignored) {}
      }
      if (!powerUp) {
        // Soft error — not "earphones required"; chip may still open later with speaker retry.
        lastError =
            (lastError != null && lastError.length() > 0)
                ? lastError
                : "Power up rejected by hardware (try Speaker in Audio if silent).";
        try {
          nativeLoader.invokeStatic("closedev", new Class<?>[] {}, new Object[] {});
        } catch (Throwable ignored) {}
        deviceOpen = false;
        return false;
      }
      currentFreqMhz = mhz;
      // 2026-07-15 — Stock FmRadioService.startPlayFm sequence (reference MTK + AOSP FMRadio):
      // powerUp already muted chip → setSpeaker → enableFmAudio (prepare+start) → setRds → unmute.
      // Layman: open the sound pipe while silent, then open the chip’s mute last.
      setMuteNative(true);
      try {
        nativeLoader.invokeStatic("rdsset", new Class<?>[] {boolean.class}, new Object[] {true});
      } catch (Throwable ignored) {}
      if (!audioRouter.start()) {
        lastError = audioRouter.lastError();
        if (lastError == null || lastError.isEmpty()) {
          lastError = "FM audio path failed";
        }
        android.util.Log.e("FmEngine", "audio fail " + lastError + " " + audioRouter.lastRouteDiag());
        try {
          setMuteNative(true);
          nativeLoader.invokeStatic("powerdown", new Class<?>[] {int.class}, new Object[] {0});
          nativeLoader.invokeStatic("closedev", new Class<?>[] {}, new Object[] {});
        } catch (Throwable ignored) {}
        powerUp = false;
        deviceOpen = false;
        return false;
      }
      setMuteNative(false);
      audioRouter.applyOutputRoute();
      android.util.Log.i("FmEngine", "powered+audio " + audioRouter.lastRouteDiag());
      return true;
    } catch (Throwable t) {
      lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
      powerUp = false;
      deviceOpen = false;
      return false;
    }
  }

  private boolean tuneInternal(float mhz) {
    if (!powerUp) {
      return powerUpInternal(mhz);
    }
    try {
      Boolean ok =
          (Boolean) nativeLoader.invokeStatic("tune", new Class<?>[] {float.class}, new Object[] {mhz});
      if (ok != null && ok) {
        currentFreqMhz = mhz;
        return true;
      }
      lastError = "Tune failed";
      return false;
    } catch (Throwable t) {
      lastError = t.getMessage();
      return false;
    }
  }

  private boolean openDeviceNative() {
    try {
      Boolean ok = (Boolean) nativeLoader.invokeStatic("opendev", new Class<?>[] {}, new Object[] {});
      deviceOpen = ok != null && ok;
      return deviceOpen;
    } catch (Throwable t) {
      lastError = t.getMessage();
      return false;
    }
  }

  private void setMuteNative(boolean mute) {
    try {
      nativeLoader.invokeStatic("setmute", new Class<?>[] {boolean.class}, new Object[] {mute});
    } catch (Throwable ignored) {}
  }

  private byte[] invokeNativeBytes(String name) {
    if (!powerUp || !nativeLoader.isReady()) return null;
    try {
      return (byte[]) nativeLoader.invokeStatic(name, new Class<?>[] {}, new Object[] {});
    } catch (Throwable ignored) {
      return null;
    }
  }

  private Integer invokeNativeInt(String name) {
    if (!powerUp || !nativeLoader.isReady()) return null;
    try {
      Object r = nativeLoader.invokeStatic(name, new Class<?>[] {}, new Object[] {});
      if (r instanceof Integer) return (Integer) r;
      if (r instanceof Short) return (int) (Short) r;
      if (r instanceof Byte) return (int) (Byte) r;
    } catch (Throwable ignored) {}
    return null;
  }

  /** MTK/JJ hardware seek — null when method missing or seek failed. 2026-07-06 */
  private Integer tryHardwareSeek(boolean forward) {
    if (useServiceFallback) {
      Object r =
          invokeService(
              "seek",
              new Class<?>[] {float.class, boolean.class},
              new Object[] {currentFreqMhz, forward});
      if (r instanceof Float) {
        float mhz = (Float) r;
        if (mhz > 0f) return Math.round(mhz * 1000f);
      }
      if (r instanceof Integer) {
        int k = (Integer) r;
        if (k > 10000) return k;
        if (k > 0) return k * 100;
      }
      return null;
    }
    if (!nativeLoader.isReady()) return null;
    try {
      Object r =
          nativeLoader.invokeStatic(
              "seek",
              new Class<?>[] {float.class, boolean.class},
              new Object[] {currentFreqMhz, forward});
      if (r instanceof Float) {
        float mhz = (Float) r;
        if (mhz > 0f) return Math.round(mhz * 1000f);
      }
    } catch (Throwable ignored) {}
    try {
      String method = forward ? "seekforward" : "seekbackward";
      Object r = nativeLoader.invokeStatic(method, new Class<?>[] {}, new Object[] {});
      if (r instanceof Float) {
        float mhz = (Float) r;
        if (mhz > 0f) return Math.round(mhz * 1000f);
      }
      if (r instanceof Boolean && (Boolean) r) {
        return currentFreqKhz();
      }
    } catch (Throwable ignored) {}
    return null;
  }

  /**
   * Step the band until RSSI/RDS says a station is present — bounded by timeout. 2026-07-06
   * @param stayIfNone true for power-on seek (keep start if no hit); false = prev/next nudge
   */
  private int seekByBandWalk(
      int startKhz, boolean forward, com.solar.launcher.radio.FmBandPlan plan, boolean stayIfNone) {
    final long deadline = android.os.SystemClock.elapsedRealtime() + 8000L;
    int khz = plan.clampKhz(startKhz);
    int steps = ((plan.maxKhz() - plan.minKhz()) / plan.stepKhz()) + 2;
    int baseline = getRssi();
    for (int i = 0; i < steps && android.os.SystemClock.elapsedRealtime() < deadline; i++) {
      khz = nextBandStepKhz(khz, forward, plan);
      if (!tune(khz)) continue;
      try {
        Thread.sleep(55L);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        break;
      }
      int rssi = getRssi();
      if (rssi >= 2 && rssi > baseline) return khz;
      if (rssi >= 8) return khz;
      if (rssi >= 3 && isStereo()) return khz;
      String ps = getRdsPs();
      if (ps != null && !ps.isEmpty()) return khz;
      if (khz == startKhz && i > 0) break;
    }
    if (stayIfNone) {
      tune(startKhz);
      return startKhz;
    }
    int fallback = nextBandStepKhz(startKhz, forward, plan);
    tune(fallback);
    return fallback;
  }

  // --- Service fallback (debug / missing native) ---

  private boolean playStationViaService(int freqKhz, float mhz) {
    // Free /dev/fm then bind a fresh FMRadioService (force-stop may have killed it).
    FmHardwarePrep.prepareBlocking();
    unbindServiceQuiet();
    if (!ensureServiceBound()) {
      lastError = "FM service not bound";
      return false;
    }
    if (!invokeServiceBool("openDevice")) {
      FmHardwarePrep.prepareBlocking();
      unbindServiceQuiet();
      if (!ensureServiceBound() || !invokeServiceBool("openDevice")) {
        lastError = "openDevice failed (service path)";
        return false;
      }
    }
    boolean powered = invokeServiceBool("isPowerUp");
    boolean ok;
    if (powered) {
      ok = invokeServiceBool("tune", float.class, mhz);
    } else {
      ok = invokeServiceBool("powerUp", float.class, mhz);
    }
    if (ok) {
      currentFreqMhz = mhz;
      powerUp = true;
      // 2026-07-15 — Service path starts audio pump; boolean start (no throw).
      if (!audioRouter.start()) {
        lastError = audioRouter.lastError();
        if (lastError == null || lastError.isEmpty()) {
          lastError = "FM audio start failed";
        }
        powerUp = false;
        return false;
      }
      mute(false);
      // #region agent log
      if (com.solar.launcher.debug.DebugGate.ON) {
      try {
        org.json.JSONObject d = new org.json.JSONObject();
        d.put("mhz", mhz);
        d.put("useService", true);
        d.put("stream", audioRouter.activeStreamType());
        com.solar.launcher.debug.SessionDebugLog.log(appCtx, "FmEngine.playStationViaService",
            "service tune + audio start", "F1", d);
      } catch (Exception ignored) {}
      }
      // #endregion
    }
    return ok;
  }

  private boolean startScanViaService(final ScanCallback callback) {
    if (!ensureServiceBound()) {
      if (callback != null) callback.onError("FM service not bound");
      return false;
    }
    Object result = invokeService("startScan");
    if (result instanceof int[]) {
      int[] freqs = (int[]) result;
      if (callback != null) {
        for (int f : freqs) {
          callback.onStationFound(freqFromVendor(f), 0, false);
        }
        callback.onScanComplete();
      }
      return true;
    }
    if (invokeServiceBool("autoScan")) return true;
    if (callback != null) callback.onError("scan not supported");
    return false;
  }

  private String getRdsPsFromService() {
    Object ps = invokeService("getPS");
    if (ps instanceof String) {
      String s = ((String) ps).trim();
      return s.isEmpty() ? null : s;
    }
    ps = invokeService("getPs");
    if (ps instanceof String) {
      String s = ((String) ps).trim();
      return s.isEmpty() ? null : s;
    }
    return null;
  }

  private String getRdsRtFromService() {
    Object rt = invokeService("getRadioText");
    if (rt instanceof String) {
      String s = ((String) rt).trim();
      return s.isEmpty() ? null : s;
    }
    rt = invokeService("getRT");
    if (rt instanceof String) {
      String s = ((String) rt).trim();
      return s.isEmpty() ? null : s;
    }
    return null;
  }

  private boolean isFmPackageInstalled() {
    try {
      appCtx.getPackageManager().getPackageInfo(FM_PACKAGE, 0);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static float khzToMhz(int freqKhz) {
    return freqKhz / 1000f;
  }

  /** MTK autoscan short (875) → kHz (87500); JJ divides by 10 for MHz. */
  static int vendorShortToKhz(short vendor) {
    if (vendor > 10000) return vendor;
    return vendor * 100;
  }

  static int freqFromVendor(int vendorFreq) {
    return vendorShortToKhz((short) vendorFreq);
  }

  private static String bytesToString(byte[] raw) {
    if (raw == null || raw.length == 0) return null;
    try {
      String s = new String(raw, "UTF-8").trim();
      return s.isEmpty() ? null : s;
    } catch (Exception e) {
      return null;
    }
  }

  private boolean ensureServiceBound() {
    if (fmService != null) return true;
    if (Looper.myLooper() == Looper.getMainLooper()) {
      final boolean[] ok = new boolean[] {false};
      final CountDownLatch done = new CountDownLatch(1);
      new Thread(
              new Runnable() {
                @Override
                public void run() {
                  ok[0] = ensureServiceBoundBlocking();
                  done.countDown();
                }
              },
              "FmEngineBind")
          .start();
      try {
        done.await(BIND_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ignored) {}
      return ok[0];
    }
    return ensureServiceBoundBlocking();
  }

  private boolean ensureServiceBoundBlocking() {
    if (fmService != null) return true;
    synchronized (this) {
      if (fmService != null) return true;
      final CountDownLatch latch = new CountDownLatch(1);
      connection =
          new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
              try {
                Context fmCtx =
                    appCtx.createPackageContext(
                        FM_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                Class<?> stub =
                    Class.forName(
                        "com.mediatek.FMRadio.IFMRadioService$Stub", true, fmCtx.getClassLoader());
                Method asInterface = stub.getMethod("asInterface", IBinder.class);
                fmService = asInterface.invoke(null, binder);
                bound = true;
              } catch (Exception e) {
                fmService = null;
              } finally {
                latch.countDown();
              }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
              fmService = null;
              bound = false;
            }
          };
      try {
        Intent intent = new Intent(FM_SERVICE_ACTION);
        intent.setComponent(new ComponentName(FM_PACKAGE, FM_SERVICE));
        boolean requested = appCtx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!requested) {
          connection = null;
          return false;
        }
        latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        return false;
      }
      return fmService != null;
    }
  }

  private void unbindServiceQuiet() {
    if (bound && connection != null) {
      try {
        appCtx.unbindService(connection);
      } catch (Exception ignored) {}
      bound = false;
      connection = null;
      fmService = null;
    }
  }

  private Object invokeService(String method, Class<?> type, Object arg) {
    return invokeService(method, new Class<?>[] {type}, new Object[] {arg});
  }

  private Object invokeService(String method) {
    return invokeService(method, new Class<?>[] {}, new Object[] {});
  }

  private Object invokeService(String method, Class<?>[] types, Object[] args) {
    if (!ensureServiceBound() || fmService == null) return null;
    try {
      Method m = fmService.getClass().getMethod(method, types);
      return m.invoke(fmService, args);
    } catch (Exception e) {
      return null;
    }
  }

  private boolean invokeServiceBool(String method) {
    return invokeServiceBool(method, new Class<?>[] {}, new Object[] {});
  }

  private boolean invokeServiceBool(String method, Class<?> type, Object arg) {
    return invokeServiceBool(method, new Class<?>[] {type}, new Object[] {arg});
  }

  private boolean invokeServiceBool(String method, Class<?>[] types, Object[] args) {
    Object result = invokeService(method, types, args);
    if (result instanceof Boolean) return (Boolean) result;
    if (result instanceof Integer) return ((Integer) result) >= 0;
    return result != null;
  }
}
