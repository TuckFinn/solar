package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import com.solar.launcher.diag.SolarDiagFeatureLog;
import com.solar.launcher.net.SolarHttp;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2026-07-16 — Clock + timezone for rooted Solar (Y1/Y2/A5), hybrid offline/online.
 *
 * <ul>
 *   <li>Internet time default <b>on</b> but only runs when already online — never wakes Wi‑Fi on boot.</li>
 *   <li>IANA zones with optional <b>Observe DST</b> (off → fixed standard-time offset, fully offline).</li>
 *   <li>Silent Wi‑Fi only on explicit “Sync now” (user intent), never as a background habit.</li>
 *   <li>Root via {@link RootShell} (Y2 cannot use bare {@code Runtime.exec("su")}).</li>
 * </ul>
 */
public final class SolarAutoTime {
    public static final String PREF_AUTO_INTERNET_TIME = "solar_auto_internet_time";
    public static final String PREF_TIMEZONE_ID = "solar_timezone_id";
    /** When true (default), IANA zone uses tzdata DST rules. When false, standard-time offset year-round. */
    public static final String PREF_OBSERVE_DST = "solar_observe_dst";
    public static final String PREF_LAST_SYNC_MS = "solar_auto_time_last_sync_ms";
    public static final String PREF_LAST_SERVER = "solar_auto_time_last_server";

    /** Min gap between successful/attempted full syncs on reconnect (battery). */
    private static final long MIN_SYNC_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long BOOT_WIFI_WAIT_MS = 25_000L;
    private static final long ONLINE_POLL_MS = 800L;
    private static final long MAX_SKEW_APPLY_MS = 24L * 60L * 60L * 1000L * 365L * 20L;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile long lastAttemptMs;

    private SolarAutoTime() {}

    public static boolean isAutoEnabled(SharedPreferences prefs) {
        return prefs == null || prefs.getBoolean(PREF_AUTO_INTERNET_TIME, true);
    }

    public static void setAutoEnabled(SharedPreferences prefs, boolean on) {
        if (prefs == null) return;
        prefs.edit().putBoolean(PREF_AUTO_INTERNET_TIME, on).apply();
    }

    /** Default on — Android tzdata handles spring-forward / fall-back for the chosen zone. */
    public static boolean isObserveDst(SharedPreferences prefs) {
        return prefs == null || prefs.getBoolean(PREF_OBSERVE_DST, true);
    }

    public static void setObserveDst(SharedPreferences prefs, boolean observe) {
        if (prefs == null) return;
        prefs.edit().putBoolean(PREF_OBSERVE_DST, observe).apply();
        // Re-apply effective zone so wall clock / system props match the new policy offline.
        applyEffectiveTimezoneRoot(prefs);
    }

    /** Stored preferred IANA timezone id, or system default when unset. */
    public static String timezoneId(SharedPreferences prefs) {
        if (prefs != null) {
            String id = prefs.getString(PREF_TIMEZONE_ID, "");
            if (id != null && !id.trim().isEmpty()) return id.trim();
        }
        return TimeZone.getDefault().getID();
    }

    public static void setTimezoneId(SharedPreferences prefs, String id) {
        if (prefs == null || id == null || id.trim().isEmpty()) return;
        prefs.edit().putString(PREF_TIMEZONE_ID, id.trim()).apply();
        applyEffectiveTimezoneRoot(prefs);
    }

    /**
     * Zone used for display + wall-clock conversion: IANA with DST, or fixed standard offset.
     * Fully offline — no network required.
     */
    public static TimeZone effectiveTimeZone(SharedPreferences prefs) {
        String id = timezoneId(prefs);
        TimeZone base = TimeZone.getTimeZone(id != null ? id : "UTC");
        if (isObserveDst(prefs)) return base;
        // Standard time year-round (raw offset, no DST transitions).
        return TimeZone.getTimeZone(fixedOffsetEtcGmtId(base.getRawOffset()));
    }

    /** Persist the effective zone (IANA or Etc/GMT±N when DST observation is off). */
    public static void applyEffectiveTimezoneRoot(SharedPreferences prefs) {
        if (isObserveDst(prefs)) {
            applyTimezoneRoot(timezoneId(prefs));
        } else {
            TimeZone base = TimeZone.getTimeZone(timezoneId(prefs));
            applyTimezoneRoot(fixedOffsetEtcGmtId(base.getRawOffset()));
        }
    }

    /**
     * Etc/GMT ids invert the sign of the offset (POSIX legacy).
     * UTC−5 (US Eastern standard) → {@code Etc/GMT+5}.
     */
    static String fixedOffsetEtcGmtId(int rawOffsetMs) {
        int hours = Math.round(rawOffsetMs / 3600000f);
        if (hours == 0) return "UTC";
        // Clamp to common Etc range.
        if (hours > 14) hours = 14;
        if (hours < -12) hours = -12;
        if (hours > 0) return "Etc/GMT-" + hours;
        return "Etc/GMT+" + (-hours);
    }

    /** Curated list for wheel picker (id only). */
    public static String[] commonTimezoneIds() {
        return new String[] {
                "UTC",
                "Pacific/Honolulu",
                "America/Anchorage",
                "America/Los_Angeles",
                "America/Denver",
                "America/Chicago",
                "America/New_York",
                "America/Sao_Paulo",
                "Europe/London",
                "Europe/Paris",
                "Europe/Berlin",
                "Europe/Moscow",
                "Africa/Cairo",
                "Asia/Dubai",
                "Asia/Kolkata",
                "Asia/Bangkok",
                "Asia/Shanghai",
                "Asia/Tokyo",
                "Asia/Seoul",
                "Australia/Sydney",
                "Pacific/Auckland"
        };
    }

    public static int indexOfTimezone(String id) {
        String[] ids = commonTimezoneIds();
        if (id == null) return 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equalsIgnoreCase(id)) return i;
        }
        return 0;
    }

    /**
     * Wheel label for the preferred city zone; offset respects Observe DST setting.
     * Layman: with DST on, New York shows UTC-4 in summer; with DST off, stays standard year-round.
     */
    public static String displayTimezoneLabel(String id) {
        return displayTimezoneLabel(id, true);
    }

    public static String displayTimezoneLabel(SharedPreferences prefs) {
        return displayTimezoneLabel(timezoneId(prefs), isObserveDst(prefs));
    }

    public static String displayTimezoneLabel(String id, boolean observeDst) {
        if (id == null || id.isEmpty()) id = TimeZone.getDefault().getID();
        TimeZone base = TimeZone.getTimeZone(id);
        long now = System.currentTimeMillis();
        int totalMin = (observeDst ? base.getOffset(now) : base.getRawOffset()) / 60000;
        int h = Math.abs(totalMin) / 60;
        int m = Math.abs(totalMin) % 60;
        String sign = totalMin >= 0 ? "+" : "-";
        String off = m == 0
                ? String.format(Locale.US, "UTC%s%d", sign, h)
                : String.format(Locale.US, "UTC%s%d:%02d", sign, h, m);
        String city = id;
        int slash = id.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < id.length()) city = id.substring(slash + 1).replace('_', ' ');
        if (observeDst && base.inDaylightTime(new Date(now))) {
            return city + " (" + off + " DST)";
        }
        if (!observeDst && base.useDaylightTime()) {
            return city + " (" + off + " no DST)";
        }
        return city + " (" + off + ")";
    }

    /**
     * Apply user wall-clock fields in the effective zone (DST policy applied) via RootShell.
     * Works fully offline.
     */
    public static boolean applyLocalWallTime(SharedPreferences prefs,
            int year, int month1to12, int day, int hour24, int minute) {
        TimeZone tz = effectiveTimeZone(prefs);
        long utcMs = localWallToUtcEpochMs(tz, year, month1to12, day, hour24, minute);
        if (utcMs <= 0L) return false;
        applyEffectiveTimezoneRoot(prefs);
        return applyUtcEpochRoot(utcMs);
    }

    /**
     * Convert local Y-M-D H:M in {@code tz} to UTC epoch ms.
     * Uses {@link Calendar} so spring-forward / fall-back match when DST is observed.
     */
    static long localWallToUtcEpochMs(String tzId, int year, int month1to12, int day,
            int hour24, int minute) {
        TimeZone tz = TimeZone.getTimeZone(
                tzId != null && !tzId.isEmpty() ? tzId : TimeZone.getDefault().getID());
        return localWallToUtcEpochMs(tz, year, month1to12, day, hour24, minute);
    }

    static long localWallToUtcEpochMs(TimeZone tz, int year, int month1to12, int day,
            int hour24, int minute) {
        if (month1to12 < 1 || month1to12 > 12 || day < 1 || day > 31) return -1L;
        if (hour24 < 0 || hour24 > 23 || minute < 0 || minute > 59) return -1L;
        if (tz == null) tz = TimeZone.getDefault();
        Calendar cal = Calendar.getInstance(tz);
        cal.clear();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month1to12 - 1);
        cal.set(Calendar.DAY_OF_MONTH, day);
        cal.set(Calendar.HOUR_OF_DAY, hour24);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * Process start — offline-first: apply timezone only; NTP only if already online.
     * Never silent-wakes Wi‑Fi (keeps the disconnected experience snappy).
     */
    public static void onProcessStart(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Cheap offline path — zone/DST policy only, no network.
                    applyEffectiveTimezoneRoot(prefs);
                } catch (Exception ignored) {}
                if (!isAutoEnabled(prefs)) return;
                // No Wi‑Fi wake: only sync if the device is already online.
                if (!ConnectivityHelper.isOnline(app)) return;
                // Epoch/1970 clocks break HTTPS — always attempt NTP (ignore min interval). 2026-07-19
                if (isWallClockImplausible()) {
                    syncNow(app, prefs, true);
                    return;
                }
                syncNow(app, prefs, false);
            }
        }, "SolarAutoTimeBoot").start();
    }

    /** Wi‑Fi / hotspot has routable connectivity — passive NTP (no radio wake). */
    public static void onInternetAvailable(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        if (!isAutoEnabled(prefs)) return;
        if (!ConnectivityHelper.isOnline(app)) return;
        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < MIN_SYNC_INTERVAL_MS) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                syncNow(app, prefs, false);
            }
        }, "SolarAutoTimeNet").start();
    }

    /**
     * User-initiated “Sync now” — may briefly silent-wake Wi‑Fi if offline.
     * Background paths never call this.
     */
    public static void requestSyncNow(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                syncWithOptionalWifiWake(app, prefs, true);
            }
        }, "SolarAutoTimeForce").start();
    }

    /**
     * Silent Wi‑Fi wake (no status icon / settings “On”) then NTP if online; restore radio
     * unless the user claimed Wi‑Fi mid-session.
     */
    private static void syncWithOptionalWifiWake(Context app, SharedPreferences prefs, boolean allowWake) {
        if (!running.compareAndSet(false, true)) return;
        lastAttemptMs = System.currentTimeMillis();
        boolean beganSilent = false;
        try {
            boolean online = false;
            try {
                online = ConnectivityHelper.isOnline(app);
            } catch (Exception ignored) {}
            if (!online && allowWake) {
                beganSilent = SolarSilentWifi.begin(app);
                if (beganSilent) waitUntilOnline(app, BOOT_WIFI_WAIT_MS);
            }
            if (ConnectivityHelper.isOnline(app)) {
                performSync(app, prefs);
            }
        } catch (Exception ignored) {
        } finally {
            if (beganSilent) {
                SolarSilentWifi.end(app);
            }
            running.set(false);
        }
    }

    private static void syncNow(Context app, SharedPreferences prefs, boolean force) {
        if (!force && !isAutoEnabled(prefs)) return;
        if (!running.compareAndSet(false, true)) return;
        lastAttemptMs = System.currentTimeMillis();
        try {
            if (!ConnectivityHelper.isOnline(app)) return;
            performSync(app, prefs);
        } catch (Exception ignored) {
        } finally {
            running.set(false);
        }
    }

    private static void waitUntilOnline(Context app, long maxWaitMs) {
        long deadline = SystemClock.elapsedRealtime() + maxWaitMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ConnectivityHelper.isOnline(app)) return;
            try {
                Thread.sleep(ONLINE_POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void performSync(Context app, SharedPreferences prefs) {
        // Apply user timezone / DST policy first (works offline; no NTP required).
        String preferredId = timezoneId(prefs);
        applyEffectiveTimezoneRoot(prefs);

        String[] hosts = ntpHostsForContext(app, preferredId);
        long utcMs = SolarNtpClient.queryFirstUtcEpochMs(hosts);
        if (utcMs <= 0L) {
            // Last-resort pool
            utcMs = SolarNtpClient.queryUtcEpochMs("pool.ntp.org");
        }
        if (utcMs <= 0L) return;
        // Sanity: reject absurd NTP answers; when device clock is epoch, still accept 2020–2035. 2026-07-19
        long min = 1420070400000L; // 2015-01-01
        long maxCap = 2051222400000L; // 2035-01-01
        if (utcMs < min || utcMs > maxCap) return;
        if (applyUtcEpochRoot(utcMs)) {
            String server = hosts.length > 0 ? hosts[0] : "pool.ntp.org";
            prefs.edit()
                    .putLong(PREF_LAST_SYNC_MS, System.currentTimeMillis())
                    .putString(PREF_LAST_SERVER, server)
                    .apply();
            try {
                app.sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));
            } catch (Exception ignored) {}
            try {
                SolarDiagFeatureLog.event("time", "ntp_ok server=" + server
                        + " tz=" + preferredId
                        + " dst=" + isObserveDst(prefs)
                        + " utc=" + utcMs);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * NTP pool selection: prefer geo IP timezone region; else user timezone id; else global pool.
     * Timezone soft-default lives in {@link SolarGeoRegion}; this path only chooses NTP servers.
     */
    static String[] ntpHostsForContext(Context app, String userTzId) {
        String region = regionFromTimezone(userTzId);
        String geoTz = null;
        try {
            geoTz = SolarGeoRegion.geoTimezone(
                    app.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE));
        } catch (Exception ignored) {}
        if (geoTz == null || geoTz.isEmpty()) {
            geoTz = fetchGeoTimezoneHint(app);
        }
        if (geoTz != null && !geoTz.isEmpty()) {
            region = regionFromTimezone(geoTz);
        }
        return poolsForRegion(region);
    }

    static String regionFromTimezone(String tzId) {
        if (tzId == null) return "global";
        String t = tzId.trim();
        if (t.startsWith("America/") || t.startsWith("US/") || t.startsWith("Canada/")
                || t.startsWith("Pacific/Honolulu") || t.startsWith("America/Anchorage")) {
            return "north-america";
        }
        if (t.startsWith("Europe/") || t.startsWith("Atlantic/")) return "europe";
        if (t.startsWith("Asia/") || t.startsWith("Indian/")) return "asia";
        if (t.startsWith("Australia/") || t.startsWith("Pacific/Auckland")
                || t.startsWith("Pacific/Fiji")) {
            return "oceania";
        }
        if (t.startsWith("Africa/")) return "africa";
        if (t.startsWith("Pacific/")) return "oceania";
        return "global";
    }

    static String[] poolsForRegion(String region) {
        if ("north-america".equals(region)) {
            return new String[] {
                    "0.north-america.pool.ntp.org",
                    "1.north-america.pool.ntp.org",
                    "pool.ntp.org"
            };
        }
        if ("europe".equals(region)) {
            return new String[] {
                    "0.europe.pool.ntp.org",
                    "1.europe.pool.ntp.org",
                    "pool.ntp.org"
            };
        }
        if ("asia".equals(region)) {
            return new String[] {
                    "0.asia.pool.ntp.org",
                    "1.asia.pool.ntp.org",
                    "pool.ntp.org"
            };
        }
        if ("oceania".equals(region)) {
            return new String[] {
                    "0.oceania.pool.ntp.org",
                    "1.oceania.pool.ntp.org",
                    "pool.ntp.org"
            };
        }
        if ("africa".equals(region)) {
            return new String[] {
                    "0.africa.pool.ntp.org",
                    "pool.ntp.org"
            };
        }
        return new String[] { "0.pool.ntp.org", "1.pool.ntp.org", "pool.ntp.org" };
    }

    /** Lightweight geo hint for NTP pool only (HTTP JSON). */
    private static String fetchGeoTimezoneHint(Context app) {
        try {
            // Cleartext fields-only endpoint — small body; TLS optional via SolarHttp if upgraded.
            String json = SolarHttp.getText(
                    "http://ip-api.com/json/?fields=status,timezone");
            if (json == null || json.isEmpty()) return null;
            JSONObject o = new JSONObject(json);
            if (!"success".equalsIgnoreCase(o.optString("status", ""))) return null;
            String tz = o.optString("timezone", "");
            return tz != null && !tz.isEmpty() ? tz : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when wall clock is before 2020 or absurd — TLS certs (Lalal etc.) will fail.
     * Layman: phone thinks it is 1970 → HTTPS says certificates are “not yet valid”.
     * 2026-07-19
     */
    public static boolean isWallClockImplausible() {
        long now = System.currentTimeMillis();
        // 2020-01-01 UTC
        if (now < 1577836800000L) return true;
        // More than ~2 years past build-ish ceiling (keeps runaway RTC detectable).
        if (now > 1893456000000L) return true; // 2030-01-01
        return false;
    }

    /**
     * Root: set wall clock to absolute UTC epoch. Display uses the IANA zone (DST-aware).
     * Public for Date &amp; Time Apply (must use RootShell paths, not bare {@code su}).
     * 2026-07-19 — Prefer busybox {@code yyyy.MM.dd-HH:mm:ss}; toolbox date rejects year 2026
     * on some Y1 kernels ({@code settimeofday Invalid argument}) and leaves the clock at epoch.
     */
    public static boolean applyUtcEpochRoot(long utcEpochMs) {
        if (utcEpochMs <= 0L) return false;
        // Try framework first (SET_TIME when granted as system).
        try {
            SystemClock.setCurrentTimeMillis(utcEpochMs);
        } catch (Exception ignored) {}
        // Always reinforce via root date formats (same self-verify script as Date & Time UI).
        Date d = new Date(utcEpochMs);
        // Use UTC for the string so date(1) stores the correct absolute instant regardless of TZ.
        SimpleDateFormat toolbox = new SimpleDateFormat("yyyyMMdd.HHmmss", Locale.US);
        toolbox.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat posix = new SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US);
        posix.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        // Busybox — works on Y1 when toolbox settimeofday rejects 2026. 2026-07-19
        SimpleDateFormat busy = new SimpleDateFormat("yyyy.MM.dd-HH:mm:ss", Locale.US);
        busy.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat ymdFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
        ymdFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String ymd = ymdFmt.format(d);
        String f1 = toolbox.format(d);
        String f2 = posix.format(d);
        String f3 = iso.format(d);
        String fBusy = busy.format(d);
        // Disable Android auto_time so our clock sticks; write RTC.
        // Try busybox first — toolbox date can fail Invalid argument for 2026 on MT6572.
        String cmd = "settings put global auto_time 0; settings put system auto_time 0; "
                + "busybox date -u -s " + fBusy + " 2>/dev/null || date -u -s " + fBusy + " 2>/dev/null; "
                + "if [ \"$(date -u +%Y%m%d)\" != \"" + ymd + "\" ]; then "
                + "  date -u -s " + f1 + "; "
                + "  if [ \"$(date -u +%Y%m%d)\" != \"" + ymd + "\" ]; then "
                + "    date -u " + f2 + "; "
                + "    if [ \"$(date -u +%Y%m%d)\" != \"" + ymd + "\" ]; then "
                + "      date -u -s \"" + f3 + "\"; "
                + "    fi; "
                + "  fi; "
                + "fi; "
                + "hwclock -w -u 2>/dev/null; hwclock -w 2>/dev/null; "
                + "busybox hwclock -w -u 2>/dev/null; sync; "
                + "test \"$(date -u +%Y%m%d)\" = \"" + ymd + "\"";
        // All Solar targets are rooted — allow A5 setuid su.
        boolean ok = RootShell.run(cmd, true);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject dlog = new org.json.JSONObject();
            dlog.put("utcEpochMs", utcEpochMs);
            dlog.put("ymd", ymd);
            dlog.put("ok", ok);
            dlog.put("nowAfter", System.currentTimeMillis());
            dlog.put("implausibleBefore", isWallClockImplausible());
            com.solar.launcher.Debug543e15Log.log(
                    "SolarAutoTime.applyUtcEpochRoot", "set wall clock", "H-CERT-A", dlog);
        } catch (Throwable ignored) {}
        }
        // #endregion
        return ok;
    }

    /** Root: persist IANA timezone (tzdata applies DST transitions automatically). */
    public static boolean applyTimezoneRoot(String tzId) {
        if (tzId == null || tzId.trim().isEmpty()) return false;
        final String id = tzId.trim();
        // Framework (may work when system-signed / SET_TIME_ZONE).
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id));
        } catch (Exception ignored) {}
        try {
            android.app.AlarmManager am = null;
            Context app = SolarApplication.getAppContext();
            if (app != null) {
                am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.setTimeZone(id);
            }
        } catch (Exception ignored) {}
        // Root persist + broadcast — reliable on Solar ROMs (all targets rooted).
        String cmd = "setprop persist.sys.timezone " + shellQuote(id)
                + "; am broadcast -a android.intent.action.TIMEZONE_CHANGED "
                + "--es time-zone " + shellQuote(id) + " 2>/dev/null; true";
        boolean ok = RootShell.run(cmd, true);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id));
        } catch (Exception ignored) {}
        return ok;
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
