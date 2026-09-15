package com.solar.launcher.net;

import android.os.Build;

import org.conscrypt.Conscrypt;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;

/**
 * Conscrypt + OkHttp 3.12 — TLS 1.2 on API 17 Y1 (verified); 1.3 if Conscrypt negotiates it.
 * Bundled + system CA merge fixes Let's Encrypt trust for podcasts/themes.
 */
public final class TlsHelper {
    private static final String TAG = "SolarTls";
    private static volatile boolean bootstrapped;
    private static volatile SSLSocketFactory cached;
    private static volatile X509TrustManager cachedTrustManager;
    private static volatile OkHttpClient cachedClient;
    private static volatile android.content.Context appContext;

    private static final String[] BUNDLED_ROOTS = {
            "certs/isrg-root-x1.pem",
            "certs/isrg-root-x2.pem",
            "certs/digicert-global-root-g2.pem",
            "certs/amazon-root-ca-1.pem",
            "certs/gts-root-r1.pem",
            "certs/gts-root-r4.pem",
            "certs/usertrust-rsa-ca.pem",
    };

    private TlsHelper() {}

    public static void init(android.content.Context context) {
        if (bootstrapped) return;
        synchronized (TlsHelper.class) {
            if (bootstrapped) return;
            if (context != null) appContext = context.getApplicationContext();
            setApplicationContext(context);
            Provider provider = conscryptProvider();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && provider != null) {
                Security.insertProviderAt(provider, 1);
            }
            try {
                SSLSocketFactory factory = buildSocketFactory();
                cached = factory;
                HttpsURLConnection.setDefaultSSLSocketFactory(factory);
                cachedClient = buildOkHttpClient();
                logI("Modern TLS enabled (API " + Build.VERSION.SDK_INT + ", conscrypt="
                        + (provider != null ? provider.getName() : "none") + ", bundledRoots="
                        + bundledRootCount() + ")");
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("api", Build.VERSION.SDK_INT);
                    d.put("conscrypt", provider != null ? provider.getName() : "none");
                    d.put("bundledRoots", bundledRootCount());
                    d.put("tmfProvider", lastTmfProvider);
                    d.put("amazonRootLoaded", amazonRootLoaded);
                    d.put("deviceTimeMs", System.currentTimeMillis());
                    com.solar.launcher.Debug543e15Log.log(
                            "TlsHelper.init", "tls bootstrap", "H-CERT-B", d);
                } catch (Throwable ignored) {}
                }
                // #endregion
                scheduleTlsProbe();
            } catch (Exception e) {
                logE("Modern TLS setup failed", e);
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("err", e.getClass().getName());
                    d.put("msg", e.getMessage() != null ? e.getMessage() : "");
                    com.solar.launcher.Debug543e15Log.log(
                            "TlsHelper.init", "tls bootstrap failed", "H-CERT-C", d);
                } catch (Throwable ignored) {}
                }
                // #endregion
                try {
                    cachedClient = buildOkHttpClient();
                } catch (Exception e2) {
                    cachedClient = bareOkHttpClient();
                }
            }
            bootstrapped = true;
        }
    }

    /** Shared OkHttp — podcasts, themes, OTA; RESTRICTED_TLS = TLS 1.2 + 1.3. */
    public static OkHttpClient client() {
        if (cachedClient != null) return cachedClient;
        synchronized (TlsHelper.class) {
            if (cachedClient != null) return cachedClient;
            try {
                if (appContext != null) init(appContext);
                cachedClient = bootstrapped ? buildOkHttpClient() : bareOkHttpClient();
            } catch (Exception e) {
                cachedClient = bareOkHttpClient();
            }
            return cachedClient;
        }
    }

    public static void ensureSecurityProvider() {
        if (!bootstrapped && appContext != null) init(appContext);
    }

    public static SSLSocketFactory socketFactory() throws Exception {
        if (cached != null) return cached;
        synchronized (TlsHelper.class) {
            if (cached != null) return cached;
            cached = buildSocketFactory();
            return cached;
        }
    }

    public static void applyTo(HttpsURLConnection conn) {
        if (conn == null) return;
        try {
            conn.setSSLSocketFactory(socketFactory());
        } catch (Exception e) {
            logW("applyTo failed", e);
        }
    }

    /** HEAD/GET via OkHttp — matches Stem/Lalal path (not HttpsURLConnection). 2026-07-19 */
    public static String probeProtocol(String urlStr) {
        try {
            if (!bootstrapped) init(null);
            ensureSecurityProvider();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(urlStr)
                    .head()
                    .build();
            okhttp3.Response resp = client().newCall(req).execute();
            try {
                int code = resp.code();
                String protocol = null;
                if (resp.handshake() != null && resp.handshake().tlsVersion() != null) {
                    protocol = resp.handshake().tlsVersion().javaName();
                }
                if (code < 200 || code >= 500) {
                    // HEAD may 405 — still proves TLS worked.
                    if (code == 405 && protocol != null) return protocol;
                    return null;
                }
                return protocol != null ? protocol : "TLS";
            } finally {
                resp.close();
            }
        } catch (Exception e) {
            logW("probeProtocol failed " + urlStr, e);
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("url", urlStr);
                d.put("err", e.getClass().getName());
                d.put("msg", e.getMessage() != null ? e.getMessage() : "");
                Throwable c = e.getCause();
                if (c != null) {
                    d.put("cause", c.getClass().getSimpleName()
                            + ": " + (c.getMessage() != null ? c.getMessage() : ""));
                }
                d.put("deviceTimeMs", System.currentTimeMillis());
                com.solar.launcher.Debug543e15Log.log(
                        "TlsHelper.probeProtocol", "probe failed", "H-CERT-A", d);
            } catch (Throwable ignored) {}
            }
            // #endregion
            return null;
        }
    }

    /** ponytail: reflection — compile SDK stub omits getSSLSession on API 17 classpath */
    private static String readNegotiatedProtocol(HttpsURLConnection conn) {
        try {
            java.lang.reflect.Method m = conn.getClass().getMethod("getSSLSession");
            Object session = m.invoke(conn);
            if (session != null) {
                java.lang.reflect.Method gp = session.getClass().getMethod("getProtocol");
                Object p = gp.invoke(session);
                if (p instanceof String) return (String) p;
            }
        } catch (Exception ignored) {}
        try {
            String suite = conn.getCipherSuite();
            return (suite != null && !suite.isEmpty()) ? "TLS" : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void scheduleTlsProbe() {
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String le = probeProtocol("https://valid-isrgrootx1.letsencrypt.org/");
                    String t13 = probeProtocol("https://tls13.1d.pw/");
                    String lalal = probeProtocol("https://www.lalal.ai/");
                    logI("TLS probe LE=" + le + " tls13-only-host=" + t13 + " lalal=" + lalal);
                    // #region agent log
                    if (com.solar.launcher.debug.DebugGate.ON) {
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("le", le != null ? le : org.json.JSONObject.NULL);
                        d.put("tls13", t13 != null ? t13 : org.json.JSONObject.NULL);
                        d.put("lalal", lalal != null ? lalal : org.json.JSONObject.NULL);
                        d.put("amazonRootLoaded", amazonRootLoaded);
                        d.put("tmfProvider", lastTmfProvider);
                        d.put("deviceTimeMs", System.currentTimeMillis());
                        com.solar.launcher.Debug543e15Log.log(
                                "TlsHelper.scheduleTlsProbe", "probe results", "H-CERT-A", d);
                    } catch (Throwable ignored) {}
                    }
                    // #endregion
                }
            }, "SolarTlsProbe").start();
        } catch (Throwable ignored) {}
    }

    private static OkHttpClient bareOkHttpClient() {
        return new OkHttpClient.Builder()
                // 2026-07-06: RESTRICTED_TLS alone blocks http:// LAN Navidrome; cleartext for legacy/http feeds.
                .connectionSpecs(Arrays.asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    private static OkHttpClient buildOkHttpClient() throws Exception {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                // 2026-07-06: HTTPS via RESTRICTED_TLS; allow http:// for Navidrome LAN + podcast http fallback.
                .connectionSpecs(Arrays.asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return b.build();
        }
        Provider p = conscryptProvider();
        if (p == null) return b.build();
        X509TrustManager tm = trustManager();
        SSLContext ctx = SSLContext.getInstance("TLS", p);
        ctx.init(null, new TrustManager[] {tm}, null);
        b.sslSocketFactory(new ModernTlsSocketFactory(ctx.getSocketFactory()), tm);
        return b.build();
    }

    private static SSLSocketFactory buildSocketFactory() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SSLContext ctx = SSLContext.getDefault();
            return new ModernTlsSocketFactory(ctx.getSocketFactory());
        }
        Provider tlsProvider = conscryptProvider();
        if (tlsProvider == null) tlsProvider = Security.getProvider("Conscrypt");
        X509TrustManager tm = trustManager();
        SSLContext ctx = tlsProvider != null
                ? SSLContext.getInstance("TLS", tlsProvider)
                : SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, null);
        return new ModernTlsSocketFactory(ctx.getSocketFactory());
    }

    private static volatile Provider conscrypt;
    /** Last TrustManagerFactory provider name — debug 543e15. */
    private static volatile String lastTmfProvider = "";
    /** True when assets amazon-root-ca-1.pem entered the merged KeyStore. */
    private static volatile boolean amazonRootLoaded;

    private static Provider conscryptProvider() {
        if (conscrypt != null) return conscrypt;
        synchronized (TlsHelper.class) {
            if (conscrypt != null) return conscrypt;
            try {
                conscrypt = Conscrypt.newProvider();
            } catch (Throwable t) {
                // JVM unit tests
            }
            return conscrypt;
        }
    }

    private static X509TrustManager trustManager() throws Exception {
        if (cachedTrustManager != null) return cachedTrustManager;
        synchronized (TlsHelper.class) {
            if (cachedTrustManager != null) return cachedTrustManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    cachedTrustManager = Conscrypt.getDefaultX509TrustManager();
                    return cachedTrustManager;
                } catch (Throwable ignored) {}
            }
            cachedTrustManager = mergedTrustManager();
            return cachedTrustManager;
        }
    }

    private static X509TrustManager mergedTrustManager() throws Exception {
        KeyStore ks;
        try {
            KeyStore system = KeyStore.getInstance("AndroidCAStore");
            system.load(null, null);
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            Enumeration<String> aliases = system.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                ks.setCertificateEntry("sys:" + alias, system.getCertificate(alias));
            }
        } catch (Exception e) {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            return firstX509(tmf);
        }
        if (appContext != null) {
            // Prefer Android/Conscrypt factory — BC X.509 parser + PKIX is flaky on API 17. 2026-07-19
            CertificateFactory cf = certificateFactoryX509();
            for (String path : BUNDLED_ROOTS) {
                InputStream in = null;
                try {
                    in = appContext.getAssets().open(path);
                    ks.setCertificateEntry("le:" + path, cf.generateCertificate(in));
                    if (path.indexOf("amazon-root-ca-1") >= 0) amazonRootLoaded = true;
                } catch (Exception e) {
                    logW("Could not load root " + path, e);
                } finally {
                    if (in != null) try { in.close(); } catch (Exception ignored) {}
                }
            }
        }
        TrustManagerFactory tmf = androidPkixFactory();
        tmf.init(ks);
        return firstX509(tmf);
    }

    /**
     * X.509 CertificateFactory that prefers Conscrypt/AndroidOpenSSL over BouncyCastle.
     * Layman: parse PEM roots with the same crypto stack we use for HTTPS.
     * 2026-07-19
     */
    private static CertificateFactory certificateFactoryX509() throws Exception {
        Provider c = conscryptProvider();
        if (c != null) {
            try {
                return CertificateFactory.getInstance("X.509", c);
            } catch (Exception ignored) {}
        }
        for (String name : new String[] {"AndroidOpenSSL", "AndroidDefault", "HarmonyJSSE"}) {
            try {
                Provider p = Security.getProvider(name);
                if (p == null) continue;
                return CertificateFactory.getInstance("X.509", p);
            } catch (Exception ignored) {}
        }
        return CertificateFactory.getInstance("X.509");
    }

    private static TrustManagerFactory androidPkixFactory() throws Exception {
        // Prefer Conscrypt PKIX on Y1 — stock path often lands on BC → "Could not validate certificate".
        Provider c = conscryptProvider();
        if (c != null) {
            try {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm(), c);
                lastTmfProvider = c.getName();
                return tmf;
            } catch (Exception ignored) {}
        }
        for (String name : new String[] {"AndroidOpenSSL", "AndroidDefault", "HarmonyJSSE"}) {
            try {
                Provider p = Security.getProvider(name);
                if (p == null) continue;
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX", p);
                lastTmfProvider = name;
                return tmf;
            } catch (Exception ignored) {}
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        lastTmfProvider = "default:" + tmf.getProvider().getName();
        return tmf;
    }

    private static X509TrustManager firstX509(TrustManagerFactory tmf) {
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("No X509TrustManager");
    }

    private static int bundledRootCount() {
        if (appContext == null) return 0;
        int n = 0;
        for (String path : BUNDLED_ROOTS) {
            InputStream in = null;
            try {
                in = appContext.getAssets().open(path);
                n++;
            } catch (Exception ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
            }
        }
        return n;
    }

    private static void setApplicationContext(android.content.Context context) {
        if (context == null) return;
        try {
            java.lang.reflect.Method m = Conscrypt.class.getMethod(
                    "setApplicationContext", android.content.Context.class);
            m.invoke(null, context.getApplicationContext());
        } catch (Throwable ignored) {}
    }

    private static void logI(String msg) {
        try { android.util.Log.i(TAG, msg); } catch (Throwable ignored) { }
    }

    private static void logW(String msg, Throwable t) {
        try { android.util.Log.w(TAG, msg, t); } catch (Throwable ignored) { }
    }

    private static void logE(String msg, Throwable t) {
        try { android.util.Log.e(TAG, msg, t); } catch (Throwable ignored) { }
    }
}
