package com.solar.launcher.diag;

import com.solar.launcher.PrefsWriteAudit;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.solar.launcher.AppVersion;
import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.WifiScanFilter;
import com.solar.launcher.deezer.DeezerAccount;
import com.solar.launcher.soulseek.SoulseekAccount;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 2026-07-16 — Environment + account snapshot for diagnostic reports
 * (network, Wi‑Fi, BT, location, sensors, Deezer ARL class for shared-ARL triage).
 */
public final class SolarDiagContextCollector {
    private SolarDiagContextCollector() {}

    /** Build device JSON for the worker metadata block. */
    public static org.json.JSONObject deviceJson(Context context) {
        org.json.JSONObject d = new org.json.JSONObject();
        try {
            // Prefer Solar family label (A5/Y1/Y2) so GitHub issue titles are not stock "Y1"
            // when an A5 ROM still reports ro.product.model=Y1 before identity pins.
            String familyLabel = DeviceFeatures.deviceModelLabel();
            d.put("model", familyLabel);
            d.put("buildModel", Build.MODEL != null ? Build.MODEL : "");
            d.put("device", Build.DEVICE);
            d.put("brand", Build.BRAND);
            d.put("product", Build.PRODUCT);
            d.put("manufacturer", Build.MANUFACTURER);
            d.put("sdk", Build.VERSION.SDK_INT);
            d.put("release", Build.VERSION.RELEASE);
            d.put("fingerprint", Build.FINGERPRINT);
            d.put("hardware", Build.HARDWARE);
            d.put("board", Build.BOARD);
            d.put("versionName", AppVersion.installedVersionName(context));
            d.put("versionCode", AppVersion.installedVersionCode(context));
            d.put("family", DeviceFeatures.deviceFamily());
            d.put("familyLabel", familyLabel);
        } catch (Exception ignored) {}
        return d;
    }

    /**
     * Rich environment text (network, geo, sensors, BT, Wi‑Fi).
     * Prefer {@link #collectEnvironmentLight} for routine/background ships.
     */
    public static String collectEnvironment(Context context) {
        return collectEnvironment(context, true);
    }

    /**
     * Light snapshot for routine/background uploads — no sensor wait, no Wi‑Fi scan dump.
     * Full detail reserved for developer remote pull / crash.
     */
    public static String collectEnvironmentLight(Context context) {
        return collectEnvironment(context, false);
    }

    private static String collectEnvironment(Context context, boolean full) {
        appContextForMem = context != null ? context.getApplicationContext() : null;
        StringBuilder sb = new StringBuilder(full ? 8192 : 2048);
        sb.append("=== Solar diagnostic environment ===\n");
        sb.append("detail: ").append(full ? "full" : "light").append('\n');
        sb.append("time_ms: ").append(System.currentTimeMillis()).append('\n');
        sb.append("time_iso: ").append(new java.util.Date().toString()).append('\n');
        // 2026-07-20 — Each block is fail-open: one bad probe must not kill the whole dump.
        // Layman: if Bluetooth (or anything) hiccups, skip that bit and keep gathering the rest.
        // Was: bare append* calls; NoSuchMethodError from BT getType aborted USER_REPORT ships.
        // Reversal: call append* directly without safeAppend.
        safeAppend(sb, "build", new Runnable() { public void run() { appendBuild(sb); } });
        safeAppend(sb, "app", new Runnable() { public void run() { appendApp(sb, context); } });
        safeAppend(sb, "network", new Runnable() { public void run() { appendNetwork(sb, context); } });
        if (full) {
            safeAppend(sb, "wifi", new Runnable() { public void run() { appendWifi(sb, context); } });
            safeAppend(sb, "bluetooth", new Runnable() { public void run() { appendBluetooth(sb); } });
            safeAppend(sb, "location", new Runnable() { public void run() { appendLocation(sb, context); } });
            safeAppend(sb, "sensors", new Runnable() { public void run() { appendSensors(sb, context); } });
            safeAppend(sb, "proc", new Runnable() { public void run() { appendProc(sb); } });
            safeAppend(sb, "prefs", new Runnable() { public void run() { appendPrefsKeys(sb, context); } });
            safeAppend(sb, "storage", new Runnable() { public void run() { appendStorage(sb); } });
        } else {
            // Cheap and highly informative for triage — no sensor wait / Wi‑Fi scan.
            safeAppend(sb, "wifi_light", new Runnable() { public void run() { appendWifiLight(sb, context); } });
            safeAppend(sb, "proc", new Runnable() { public void run() { appendProc(sb); } });
            safeAppend(sb, "storage", new Runnable() { public void run() { appendStorage(sb); } });
        }
        return sb.toString();
    }

    /**
     * Run one env section; on any Throwable write a skip note and continue.
     * 2026-07-20 — Errors (NoSuchMethodError) are not Exceptions — catch Throwable.
     */
    private static void safeAppend(StringBuilder sb, String section, Runnable block) {
        if (block == null) return;
        try {
            block.run();
        } catch (Throwable t) {
            try {
                sb.append("\n--- ").append(section).append(" skipped ---\n");
                sb.append("skip_reason: ")
                        .append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(t.getMessage() != null ? t.getMessage() : "")
                        .append('\n');
            } catch (Throwable ignored) {}
        }
    }

    /**
     * True when {@link BluetoothDevice#getType()} exists (API 18+).
     * 2026-07-20 — Y1 is API 17; calling getType there throws NoSuchMethodError.
     */
    public static boolean supportsBluetoothDeviceType(int sdkInt) {
        return sdkInt >= 18;
    }

    /**
     * Format bonded-device type for env text, or null to omit the field.
     * 2026-07-20 — Null typeOrNull means the live getType call failed — skip, do not abort.
     */
    public static String formatBluetoothDeviceType(int sdkInt, Integer typeOrNull) {
        if (!supportsBluetoothDeviceType(sdkInt) || typeOrNull == null) return null;
        return String.valueOf(typeOrNull.intValue());
    }

    /**
     * Best-effort device class for one bonded peer; never throws.
     * 2026-07-20 — Reflect getType (API 18+) so Y1 DEX never hard-links a missing method.
     * Was: direct d.getType() → NoSuchMethodError aborted USER_REPORT on API 17.
     * Reversal: Integer.valueOf(d.getType()) after SDK gate.
     */
    private static String readBluetoothDeviceTypeSafe(BluetoothDevice d) {
        if (d == null || !supportsBluetoothDeviceType(Build.VERSION.SDK_INT)) return null;
        try {
            java.lang.reflect.Method m = BluetoothDevice.class.getMethod("getType");
            Object v = m.invoke(d);
            if (!(v instanceof Integer)) return null;
            return formatBluetoothDeviceType(Build.VERSION.SDK_INT, (Integer) v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Connected SSID/RSSI only — no scan results / configured network dump. */
    private static void appendWifiLight(StringBuilder sb, Context context) {
        sb.append("\n--- wifi (light) ---\n");
        if (context == null) {
            sb.append("context: null\n");
            return;
        }
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                sb.append("wifi_manager: null\n");
                return;
            }
            sb.append("wifi_enabled: ").append(wm.isWifiEnabled()).append('\n');
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                String ssid = com.solar.launcher.WifiScanFilter.displayableConnectedSsid(info.getSSID());
                sb.append("connected_ssid: ").append(ssid != null ? ssid : "").append('\n');
                sb.append("rssi: ").append(info.getRssi()).append('\n');
            }
        } catch (Exception e) {
            sb.append("wifi_error: ").append(e.getMessage()).append('\n');
        }
    }

    /** Account/ARL fields for shared-ARL triage (full ships only — user report / pull / crash). */
    public static String collectAccountContext(Context context, SharedPreferences prefs) {
        return collectAccountContext(context, prefs, true);
    }

    /** Routine ships: class + fingerprint only — no full ARL payload on the wire. */
    public static String collectAccountContextLight(Context context, SharedPreferences prefs) {
        return collectAccountContext(context, prefs, false);
    }

    private static String collectAccountContext(Context context, SharedPreferences prefs,
            boolean fullArl) {
        StringBuilder sb = new StringBuilder(fullArl ? 2048 : 512);
        sb.append("=== Account / ARL context (diagnostic) ===\n");
        sb.append("detail: ").append(fullArl ? "full" : "light").append('\n');
        sb.append("time_ms: ").append(System.currentTimeMillis()).append('\n');
        if (prefs == null && context != null) {
            prefs = PrefsWriteAudit.get(context, "SOLAR_SETTINGS");
        }
        if (prefs == null) {
            sb.append("prefs: unavailable\n");
            return sb.toString();
        }
        try {
            SoulseekAccount slsk = SoulseekAccount.load(prefs, context);
            sb.append("soulseek_username: ").append(nullToEmpty(slsk.username)).append('\n');
            sb.append("soulseek_custom: ").append(slsk.custom).append('\n');
            // Password never included — only username for correlation when trigger allows.
            sb.append("soulseek_password: [redacted]\n");
        } catch (Exception e) {
            sb.append("soulseek: error ").append(e.getMessage()).append('\n');
        }
        try {
            String storedArl = DeezerAccount.loadArl(prefs);
            String sessionArl = DeezerAccount.defaultSessionArl(prefs);
            boolean userConfigured = DeezerAccount.isUserArlConfigured(prefs);
            sb.append("deezer_enabled: ").append(prefs.getBoolean(DeezerAccount.PREF_ENABLED, true)).append('\n');
            sb.append("deezer_user_arl_configured: ").append(userConfigured).append('\n');
            sb.append("deezer_user_name: ").append(prefs.getString(DeezerAccount.PREF_USER_NAME, "")).append('\n');
            sb.append("deezer_user_id: ").append(prefs.getString(DeezerAccount.PREF_USER_ID, "")).append('\n');
            sb.append("deezer_premium: ").append(prefs.getBoolean(DeezerAccount.PREF_PREMIUM, false)).append('\n');
            sb.append("deezer_quality: ").append(prefs.getString(DeezerAccount.PREF_QUALITY, "")).append('\n');
            sb.append("deezer_stored_arl_len: ").append(storedArl.length()).append('\n');
            sb.append("deezer_session_arl_len: ").append(sessionArl.length()).append('\n');
            sb.append("deezer_session_is_bundled_demo: ")
                    .append(DeezerAccount.isBundledArl(sessionArl)
                            && sessionArl.equals(DeezerAccount.bundledDemoArl())).append('\n');
            sb.append("deezer_session_is_bundled_free: ")
                    .append(DeezerAccount.isBundledArl(sessionArl)
                            && sessionArl.equals(DeezerAccount.bundledFreeArl())).append('\n');
            sb.append("deezer_session_arl_class: ").append(arlClass(prefs, sessionArl)).append('\n');
            // Fingerprint (first/last 8) helps match without re-reading huge strings in titles.
            if (sessionArl.length() >= 16) {
                sb.append("deezer_session_arl_fp: ")
                        .append(sessionArl.substring(0, 8))
                        .append("…")
                        .append(sessionArl.substring(sessionArl.length() - 8))
                        .append('\n');
            }
            if (fullArl) {
                sb.append("deezer_stored_arl: ").append(storedArl).append('\n');
                sb.append("deezer_session_arl: ").append(sessionArl).append('\n');
            } else {
                sb.append("deezer_stored_arl: [redacted_light]\n");
                sb.append("deezer_session_arl: [redacted_light]\n");
            }
        } catch (Exception e) {
            sb.append("deezer: error ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static String arlClass(SharedPreferences prefs, String sessionArl) {
        if (sessionArl == null || sessionArl.isEmpty()) return "none";
        if (DeezerAccount.isUserArlConfigured(prefs)
                && sessionArl.equals(DeezerAccount.loadArl(prefs))) {
            return "user";
        }
        if (sessionArl.equals(DeezerAccount.bundledDemoArl())) return "bundled_demo_shared";
        if (sessionArl.equals(DeezerAccount.bundledFreeArl())) return "bundled_free_shared";
        if (DeezerAccount.isBundledArl(sessionArl)) return "bundled_other";
        return "unknown";
    }

    private static void appendBuild(StringBuilder sb) {
        sb.append("\n--- build ---\n");
        sb.append("solar_model: ").append(DeviceFeatures.deviceModelLabel()).append('\n');
        sb.append("family: ").append(DeviceFeatures.deviceFamily()).append('\n');
        sb.append("familyLabel: ").append(DeviceFeatures.deviceModelLabel()).append('\n');
        sb.append("build_model: ").append(Build.MODEL).append('\n');
        sb.append("device: ").append(Build.DEVICE).append('\n');
        sb.append("brand: ").append(Build.BRAND).append('\n');
        sb.append("manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("product: ").append(Build.PRODUCT).append('\n');
        sb.append("board: ").append(Build.BOARD).append('\n');
        sb.append("hardware: ").append(Build.HARDWARE).append('\n');
        sb.append("sdk: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("release: ").append(Build.VERSION.RELEASE).append('\n');
        sb.append("incremental: ").append(Build.VERSION.INCREMENTAL).append('\n');
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
    }

    private static void appendApp(StringBuilder sb, Context context) {
        sb.append("\n--- app ---\n");
        sb.append("versionName: ").append(AppVersion.installedVersionName(context)).append('\n');
        sb.append("versionCode: ").append(AppVersion.installedVersionCode(context)).append('\n');
        sb.append("package: com.solar.launcher\n");
        try {
            if (context != null) {
                sb.append("dataDir: ").append(context.getApplicationInfo().dataDir).append('\n');
            }
        } catch (Exception ignored) {}
    }

    private static void appendNetwork(StringBuilder sb, Context context) {
        sb.append("\n--- network / IP ---\n");
        try {
            if (context != null) {
                ConnectivityManager cm = (ConnectivityManager)
                        context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    NetworkInfo ni = cm.getActiveNetworkInfo();
                    if (ni != null) {
                        sb.append("active_type: ").append(ni.getTypeName()).append('\n');
                        sb.append("active_subtype: ").append(ni.getSubtypeName()).append('\n');
                        sb.append("active_connected: ").append(ni.isConnected()).append('\n');
                        sb.append("active_roaming: ").append(ni.isRoaming()).append('\n');
                        sb.append("active_extra: ").append(ni.getExtraInfo()).append('\n');
                    } else {
                        sb.append("active: null\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("connectivity_error: ").append(e.getMessage()).append('\n');
        }
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                for (NetworkInterface nif : Collections.list(ifaces)) {
                    if (nif == null || !nif.isUp()) continue;
                    sb.append("iface: ").append(nif.getName())
                            .append(" mtu=").append(nif.getMTU())
                            .append(" loopback=").append(nif.isLoopback())
                            .append('\n');
                    Enumeration<InetAddress> addrs = nif.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a == null) continue;
                        sb.append("  addr: ").append(a.getHostAddress())
                                .append(a instanceof Inet4Address ? " (v4)" : " (v6)")
                                .append(a.isLoopbackAddress() ? " loopback" : "")
                                .append('\n');
                    }
                }
            }
        } catch (Exception e) {
            sb.append("ifaces_error: ").append(e.getMessage()).append('\n');
        }
    }

    private static void appendWifi(StringBuilder sb, Context context) {
        sb.append("\n--- wifi ---\n");
        if (context == null) {
            sb.append("context: null\n");
            return;
        }
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                sb.append("wifi_manager: null\n");
                return;
            }
            sb.append("wifi_enabled: ").append(wm.isWifiEnabled()).append('\n');
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                String ssid = WifiScanFilter.displayableConnectedSsid(info.getSSID());
                sb.append("connected_ssid: ").append(ssid != null ? ssid : info.getSSID()).append('\n');
                sb.append("bssid: ").append(info.getBSSID()).append('\n');
                sb.append("rssi: ").append(info.getRssi()).append('\n');
                sb.append("link_speed_mbps: ").append(info.getLinkSpeed()).append('\n');
                sb.append("ip_int: ").append(info.getIpAddress()).append('\n');
                sb.append("ip_dotted: ").append(intToIp(info.getIpAddress())).append('\n');
                sb.append("mac: ").append(info.getMacAddress()).append('\n');
                sb.append("network_id: ").append(info.getNetworkId()).append('\n');
                sb.append("supplicant: ").append(info.getSupplicantState()).append('\n');
                try {
                    sb.append("frequency: ").append(info.getFrequency()).append('\n');
                } catch (Throwable ignored) {}
            }
            try {
                List<ScanResult> scan = wm.getScanResults();
                if (scan != null) {
                    sb.append("scan_results: ").append(scan.size()).append('\n');
                    int n = 0;
                    for (ScanResult r : scan) {
                        if (r == null || n >= 30) break;
                        sb.append("  ap: ssid=").append(r.SSID)
                                .append(" bssid=").append(r.BSSID)
                                .append(" level=").append(r.level)
                                .append(" freq=").append(r.frequency)
                                .append(" caps=").append(r.capabilities)
                                .append('\n');
                        n++;
                    }
                }
            } catch (Exception e) {
                sb.append("scan_error: ").append(e.getMessage()).append('\n');
            }
            try {
                List<android.net.wifi.WifiConfiguration> cfg = wm.getConfiguredNetworks();
                if (cfg != null) {
                    sb.append("configured_networks: ").append(cfg.size()).append('\n');
                    int n = 0;
                    for (android.net.wifi.WifiConfiguration c : cfg) {
                        if (c == null || n >= 40) break;
                        sb.append("  saved: id=").append(c.networkId)
                                .append(" ssid=").append(c.SSID)
                                .append(" status=").append(c.status)
                                .append('\n');
                        n++;
                    }
                }
            } catch (Exception e) {
                sb.append("configured_error: ").append(e.getMessage()).append('\n');
            }
        } catch (Exception e) {
            sb.append("wifi_error: ").append(e.getMessage()).append('\n');
        }
    }

    private static void appendBluetooth(StringBuilder sb) {
        sb.append("\n--- bluetooth ---\n");
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                sb.append("adapter: null\n");
                return;
            }
            try {
                sb.append("enabled: ").append(adapter.isEnabled()).append('\n');
            } catch (Throwable t) {
                sb.append("enabled: [skipped]\n");
            }
            try {
                sb.append("name: ").append(adapter.getName()).append('\n');
            } catch (Throwable t) {
                // 2026-07-20 — Was SecurityException-only; any failure → skip field.
                sb.append("name: [skipped]\n");
            }
            try {
                sb.append("address: ").append(adapter.getAddress()).append('\n');
            } catch (Throwable t) {
                sb.append("address: [skipped]\n");
            }
            try {
                sb.append("state: ").append(adapter.getState()).append('\n');
            } catch (Throwable t) {
                sb.append("state: [skipped]\n");
            }
            try {
                sb.append("scan_mode: ").append(adapter.getScanMode()).append('\n');
            } catch (Throwable ignored) {}
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    sb.append("bonded_count: ").append(bonded.size()).append('\n');
                    for (BluetoothDevice d : bonded) {
                        if (d == null) continue;
                        // 2026-07-20 — Per-device fail-open; never call getType on API 17.
                        // Was: d.getType() always → NoSuchMethodError on Y1 aborted ship.
                        // Reversal: append type=d.getType() inside SecurityException-only catch.
                        try {
                            sb.append("  bonded: name=");
                            try {
                                sb.append(d.getName());
                            } catch (Throwable t) {
                                sb.append("[skipped]");
                            }
                            sb.append(" addr=");
                            try {
                                sb.append(d.getAddress());
                            } catch (Throwable t) {
                                sb.append("[skipped]");
                            }
                            sb.append(" bond=");
                            try {
                                sb.append(d.getBondState());
                            } catch (Throwable t) {
                                sb.append("[skipped]");
                            }
                            String typeLabel = readBluetoothDeviceTypeSafe(d);
                            if (typeLabel != null) {
                                sb.append(" type=").append(typeLabel);
                            }
                            sb.append('\n');
                        } catch (Throwable t) {
                            sb.append("  bonded: [skipped]\n");
                        }
                    }
                }
            } catch (Throwable t) {
                sb.append("bonded: [skipped] ")
                        .append(t.getClass().getSimpleName())
                        .append('\n');
            }
        } catch (Throwable t) {
            sb.append("bt_error: ")
                    .append(t.getClass().getSimpleName())
                    .append(": ")
                    .append(t.getMessage() != null ? t.getMessage() : "")
                    .append('\n');
        }
    }

    private static void appendLocation(StringBuilder sb, Context context) {
        sb.append("\n--- location / geo ---\n");
        if (context == null) {
            sb.append("context: null\n");
            return;
        }
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                sb.append("location_manager: null\n");
                return;
            }
            List<String> providers = lm.getAllProviders();
            sb.append("providers: ").append(providers != null ? providers.toString() : "null").append('\n');
            if (providers != null) {
                for (String p : providers) {
                    try {
                        sb.append("provider_enabled[").append(p).append("]: ")
                                .append(lm.isProviderEnabled(p)).append('\n');
                    } catch (Exception ignored) {}
                    try {
                        Location loc = lm.getLastKnownLocation(p);
                        if (loc != null) {
                            sb.append("last_known[").append(p).append("]: lat=")
                                    .append(loc.getLatitude())
                                    .append(" lon=").append(loc.getLongitude())
                                    .append(" acc=").append(loc.getAccuracy())
                                    .append(" time=").append(loc.getTime())
                                    .append(" provider=").append(loc.getProvider())
                                    .append(" hasAlt=").append(loc.hasAltitude());
                            if (loc.hasAltitude()) sb.append(" alt=").append(loc.getAltitude());
                            if (loc.hasSpeed()) sb.append(" speed=").append(loc.getSpeed());
                            if (loc.hasBearing()) sb.append(" bearing=").append(loc.getBearing());
                            sb.append('\n');
                        } else {
                            sb.append("last_known[").append(p).append("]: null\n");
                        }
                    } catch (SecurityException se) {
                        sb.append("last_known[").append(p).append("]: [permission]\n");
                    } catch (Exception e) {
                        sb.append("last_known[").append(p).append("]: error ")
                                .append(e.getMessage()).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            sb.append("location_error: ").append(e.getMessage()).append('\n');
        }
    }

    private static void appendSensors(StringBuilder sb, Context context) {
        sb.append("\n--- sensors ---\n");
        if (context == null) {
            sb.append("context: null\n");
            return;
        }
        try {
            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                sb.append("sensor_manager: null\n");
                return;
            }
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            sb.append("sensor_count: ").append(all != null ? all.size() : 0).append('\n');
            if (all != null) {
                int n = 0;
                for (Sensor s : all) {
                    if (s == null || n >= 40) break;
                    sb.append("  sensor: name=").append(s.getName())
                            .append(" type=").append(s.getType())
                            .append(" vendor=").append(s.getVendor())
                            .append(" power=").append(s.getPower())
                            .append(" res=").append(s.getResolution())
                            .append(" max=").append(s.getMaximumRange())
                            .append('\n');
                    n++;
                }
            }
            Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) {
                sb.append("accelerometer: present name=").append(accel.getName()).append('\n');
                // 2026-07-20 — Sensor callbacks need a Looper; SolarDiagScan has none.
                // Layman: skip motion sample on the log-upload worker so we don't crash.
                // Was: always sampleAccelerometer. Reversal: always call sampleAccelerometer.
                float[] sample = null;
                if (android.os.Looper.myLooper() != null) {
                    sample = sampleAccelerometer(sm, accel, 400L);
                } else {
                    sb.append("accelerometer_sample: skipped_no_looper\n");
                }
                if (sample != null && sample.length >= 3) {
                    sb.append("accelerometer_sample: x=").append(sample[0])
                            .append(" y=").append(sample[1])
                            .append(" z=").append(sample[2])
                            .append('\n');
                    double mag = Math.sqrt(sample[0] * sample[0] + sample[1] * sample[1]
                            + sample[2] * sample[2]);
                    sb.append("accelerometer_magnitude: ").append(mag).append('\n');
                } else if (android.os.Looper.myLooper() != null) {
                    sb.append("accelerometer_sample: unavailable\n");
                }
            } else {
                sb.append("accelerometer: absent\n");
            }
            Sensor gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            sb.append("gyroscope: ").append(gyro != null ? "present" : "absent").append('\n');
            Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
            sb.append("light: ").append(light != null ? "present" : "absent").append('\n');
            Sensor prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            sb.append("proximity: ").append(prox != null ? "present" : "absent").append('\n');
            Sensor mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            sb.append("magnetic: ").append(mag != null ? "present" : "absent").append('\n');
        } catch (Exception e) {
            sb.append("sensors_error: ").append(e.getMessage()).append('\n');
        }
    }

    private static float[] sampleAccelerometer(SensorManager sm, Sensor accel, long timeoutMs) {
        final AtomicReference<float[]> ref = new AtomicReference<float[]>();
        final CountDownLatch latch = new CountDownLatch(1);
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event != null && event.values != null && event.values.length >= 3) {
                    ref.set(new float[] { event.values[0], event.values[1], event.values[2] });
                    latch.countDown();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        try {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_NORMAL);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                sm.unregisterListener(listener);
            } catch (Exception ignored) {}
        }
        return ref.get();
    }

    private static void appendStorage(StringBuilder sb) {
        sb.append("\n--- storage ---\n");
        sb.append("primary_path: ").append(com.solar.launcher.DeviceFeatures.primaryStoragePath())
                .append('\n');
        sb.append("secondary_path: ")
                .append(com.solar.launcher.DeviceFeatures.secondaryStoragePath()).append('\n');
        // MicroSD capacity (total space) — sub-512MB totals often mean card/partition failure on Y1.
        try {
            java.io.File micro = com.solar.launcher.SolarLogPaths.resolveMicroSdProbeRoot();
            if (micro != null) {
                long total = micro.getTotalSpace();
                long free = micro.getUsableSpace();
                sb.append("microsd_path: ").append(micro.getAbsolutePath()).append('\n');
                sb.append("microsd_total: ").append(total).append(" (")
                        .append(com.solar.launcher.SolarLogPaths.formatBytes(total)).append(")\n");
                sb.append("microsd_free: ").append(free).append(" (")
                        .append(com.solar.launcher.SolarLogPaths.formatBytes(free)).append(")\n");
                sb.append("microsd_capacity_suspicious: ")
                        .append(com.solar.launcher.SolarLogPaths.isSuspiciousMicroSdCapacity(total))
                        .append('\n');
                sb.append("microsd_warn_floor_bytes: ")
                        .append(com.solar.launcher.SolarLogPaths.MICROSD_CAPACITY_WARN_BYTES)
                        .append('\n');
            } else {
                sb.append("microsd_path: unavailable\n");
            }
        } catch (Exception e) {
            sb.append("microsd_probe_error: ").append(e.getMessage()).append('\n');
        }
        // Canonical log roots only (mirrored solar/logs + app-private).
        try {
            for (java.io.File logDir : com.solar.launcher.SolarLogPaths.logDirs(null)) {
                appendPathLine(sb, logDir.getAbsolutePath());
            }
        } catch (Exception ignored) {}
        for (java.io.File root : com.solar.launcher.DeviceFeatures.getStorageRoots()) {
            if (root != null) appendPathLine(sb, root.getAbsolutePath());
        }
        appendPathLine(sb, "/data/data/com.solar.launcher");
        try {
            java.io.File data = new java.io.File("/data");
            sb.append("data_usable: ").append(data.getUsableSpace()).append('\n');
            sb.append("data_total: ").append(data.getTotalSpace()).append('\n');
        } catch (Exception ignored) {}
    }

    private static void appendPathLine(StringBuilder sb, String p) {
        try {
            java.io.File f = new java.io.File(p);
            sb.append("path: ").append(p)
                    .append(" exists=").append(f.exists())
                    .append(" dir=").append(f.isDirectory())
                    .append(" canRead=").append(f.canRead())
                    .append(" canWrite=").append(f.canWrite());
            if (f.isDirectory()) {
                try {
                    sb.append(" total=").append(f.getTotalSpace())
                            .append(" free=").append(f.getUsableSpace());
                } catch (Exception ignored) {}
                String[] kids = f.list();
                sb.append(" children=").append(kids != null ? kids.length : -1);
            } else if (f.isFile()) {
                sb.append(" size=").append(f.length());
            }
            sb.append('\n');
        } catch (Exception e) {
            sb.append("path: ").append(p).append(" error=").append(e.getMessage()).append('\n');
        }
    }

    private static void appendProc(StringBuilder sb) {
        sb.append("\n--- proc ---\n");
        sb.append("proc_version: ").append(readOneLine("/proc/version")).append('\n');
        sb.append("proc_meminfo_head:\n");
        sb.append(readLines("/proc/meminfo", 8));
        sb.append("proc_loadavg: ").append(readOneLine("/proc/loadavg")).append('\n');
        sb.append("proc_uptime: ").append(readOneLine("/proc/uptime")).append('\n');
        // 2026-07-16 — ActivityManager + process status for Y1 RAM triage.
        // Reversal: delete am_memory / self_status blocks.
        try {
            if (appContextForMem != null) {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        appContextForMem.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi =
                            new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    sb.append("am_avail_mem: ").append(mi.availMem).append('\n');
                    sb.append("am_threshold: ").append(mi.threshold).append('\n');
                    sb.append("am_low_memory: ").append(mi.lowMemory).append('\n');
                    if (android.os.Build.VERSION.SDK_INT >= 16) {
                        sb.append("am_total_mem: ").append(mi.totalMem).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            sb.append("am_memory_error: ").append(e.getMessage()).append('\n');
        }
        try {
            sb.append("self_status:\n");
            sb.append(readSelfStatusLines());
        } catch (Exception ignored) {}
        try {
            sb.append("low_memory_gate: ")
                    .append(com.solar.launcher.LowMemoryGate.snapshotOneLine(appContextForMem))
                    .append('\n');
        } catch (Exception ignored) {}
    }

    /** Set during collect so appendProc can sample ActivityManager without extra params. */
    private static volatile Context appContextForMem;

    private static String readSelfStatusLines() {
        StringBuilder out = new StringBuilder();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/status")));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("VmRSS:") || line.startsWith("VmSize:")
                            || line.startsWith("VmPeak:") || line.startsWith("Threads:")
                            || line.startsWith("FDSize:") || line.startsWith("voluntary_ctxt")
                            || line.startsWith("nonvoluntary_ctxt")) {
                        out.append("  ").append(line).append('\n');
                    }
                }
            } finally {
                br.close();
            }
        } catch (Exception e) {
            out.append("  error: ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }

    /** Keys only — no secret values (ARLs live in account-context file). */
    private static void appendPrefsKeys(StringBuilder sb, Context context) {
        sb.append("\n--- prefs_keys (no values) ---\n");
        if (context == null) return;
        try {
            SharedPreferences prefs = PrefsWriteAudit.get(context, "SOLAR_SETTINGS");
            java.util.Map<String, ?> all = prefs.getAll();
            if (all == null) return;
            List<String> keys = new java.util.ArrayList<String>(all.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                Object v = all.get(k);
                String type = v == null ? "null" : v.getClass().getSimpleName();
                sb.append("key: ").append(k).append(" type=").append(type);
                if (v instanceof String) {
                    sb.append(" len=").append(((String) v).length());
                } else if (v instanceof Boolean || v instanceof Number) {
                    sb.append(" value=").append(v);
                }
                sb.append('\n');
            }
        } catch (Exception e) {
            sb.append("prefs_error: ").append(e.getMessage()).append('\n');
        }
    }

    private static String intToIp(int ip) {
        return String.format(Locale.US, "%d.%d.%d.%d",
                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }

    private static String readOneLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
            String line = br.readLine();
            br.close();
            return line != null ? line : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String readLines(String path, int max) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n < max) {
                sb.append("  ").append(line).append('\n');
                n++;
            }
            br.close();
        } catch (Exception e) {
            sb.append("  error: ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
