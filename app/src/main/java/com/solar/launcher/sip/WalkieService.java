package com.solar.launcher.sip;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Vibrator;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 2026-11-04 — Walkie-Talkie paging (Tucker): the Y1 stays registered with nookLine
 * in the background, so when the other Y1 opens Walkie-Talkie to an empty channel,
 * Asterisk pages this one (an incoming call to its extension). The page vibrates
 * and chirps (ringer volume) and offers Join / Ignore; Join answers the call, which
 * is the walkie channel.
 *
 * One worker thread owns the {@link NookSip} (it is not thread-safe); the screen and
 * the page prompt talk to it through a command queue. Registration is refreshed every
 * 5 minutes and retried a minute after a failure. No wake lock: a Y1 that is fully
 * asleep hears a page when it next wakes, which is the battery trade.
 */
public final class WalkieService {
    private static final String TAG = "SolarWalkie";
    private static final int EXPIRES = 600;
    private static final long REREGISTER_MS = 300 * 1000L;
    private static final long RETRY_MS = 60 * 1000L;
    private static final long PAGE_TIMEOUT_MS = 30 * 1000L;

    /** What the UI hears. Called on the worker thread; post to the UI yourself. */
    public interface Ui {
        void onStatus(String status);
        void onPage(String from);
        void onPageGone();
        void onCallChanged(boolean inCall);
    }

    private static WalkieService instance;

    public static synchronized WalkieService get(Context ctx) {
        if (instance == null) instance = new WalkieService(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final LinkedBlockingQueue<Runnable> cmds = new LinkedBlockingQueue<Runnable>();
    private volatile Ui ui;
    private volatile Thread worker;
    private volatile NookSip sip;
    private volatile boolean registered;
    private volatile boolean inCall;
    private volatile boolean ringing;
    private volatile boolean answering;     // accept() queued; the screen must not dial
    private volatile String status = "";
    private long ringingSince;
    private String acct = "";

    private WalkieService(Context ctx) {
        this.ctx = ctx;
    }

    public void setUi(Ui ui) { this.ui = ui; }
    public boolean registered() { return registered; }
    public boolean inCall() { return inCall; }
    public boolean ringing() { return ringing; }
    public boolean answering() { return answering; }
    public String status() { return status; }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
    }

    /** Start (or restart, after the account changed) the background registration. */
    public synchronized void start() {
        SharedPreferences p = prefs();
        if (!WalkieScreen.isConfigured(p)) return;
        String now = p.getString(WalkieScreen.PREF_SERVER, "") + "|" + p.getString(WalkieScreen.PREF_USER, "")
                + "|" + p.getString(WalkieScreen.PREF_PASSWORD, "");
        if (worker != null && worker.isAlive() && now.equals(acct)) return;
        stop();
        acct = now;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "SolarWalkieSip");
        worker.start();
    }

    public synchronized void stop() {
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
    }

    // ---- commands (any thread) ---------------------------------------------------

    /** Join the walkie room (dial it). */
    public void join() {
        cmds.offer(new Runnable() {
            @Override public void run() {
                NookSip s = sip;
                if (s == null || s.inCall()) return;
                try {
                    if (!registered) registered = s.register(EXPIRES);
                    setStatus("joining");
                    if (s.call(prefs().getString(WalkieScreen.PREF_ROOM, "7000"))) {
                        setInCall(true);
                    } else {
                        setStatus("no room");
                    }
                } catch (Exception e) {
                    setStatus("error: " + e.getMessage());
                }
            }
        });
    }

    /** Answer the ringing page: that call is the walkie room. */
    public void accept() {
        answering = true;
        ringing = false;
        cmds.offer(new Runnable() {
            @Override public void run() {
                NookSip s = sip;
                stopAlert();
                answering = false;
                try {
                    if (s != null && s.answer()) setInCall(true);
                    else setStatus("page gone");
                } catch (Exception e) {
                    setStatus("error: " + e.getMessage());
                }
            }
        });
    }

    public void declinePage() {
        cmds.offer(new Runnable() {
            @Override public void run() {
                stopAlert();
                ringing = false;
                try { if (sip != null) sip.decline(); } catch (Exception ignored) {}
            }
        });
    }

    public void leave() {
        cmds.offer(new Runnable() {
            @Override public void run() {
                if (sip != null && sip.inCall()) sip.hangup();
                setInCall(false);
            }
        });
    }

    /** Push-to-talk. AudioGroup modes are safe to set from any thread. */
    public void talk(boolean on) {
        NookSip s = sip;
        if (s != null && s.inCall()) s.talk(on);
    }

    // ---- the worker ----------------------------------------------------------------

    private void loop() {
        SharedPreferences p = prefs();
        NookSip s = new NookSip(p.getString(WalkieScreen.PREF_SERVER, ""), 5060,
                p.getString(WalkieScreen.PREF_USER, ""), p.getString(WalkieScreen.PREF_PASSWORD, ""));
        s.setListener(new NookSip.Listener() {
            @Override public void onState(String st) {
                if (st.startsWith("ended")) setInCall(false);
            }
            @Override public void onIncoming(String from) {
                ringing = true;
                ringingSince = System.currentTimeMillis();
                alert();
                Ui u = ui;
                if (u != null) u.onPage(from);
            }
            @Override public void onIncomingGone() {
                ringing = false;
                stopAlert();
                Ui u = ui;
                if (u != null) u.onPageGone();
            }
        });
        sip = s;
        long nextRegister = 0;
        try {
            while (Thread.currentThread() == worker) {
                long now = System.currentTimeMillis();
                if (now >= nextRegister) {
                    try {
                        registered = s.register(EXPIRES);
                    } catch (Exception e) {
                        registered = false;
                        Log.w(TAG, "register: " + e.getMessage());
                    }
                    setStatus(registered ? "registered" : "can't reach nookLine");
                    nextRegister = now + (registered ? REREGISTER_MS : RETRY_MS);
                }
                if (ringing && now - ringingSince > PAGE_TIMEOUT_MS) {
                    stopAlert();
                    ringing = false;
                    try { s.decline(); } catch (Exception ignored) {}
                    Ui u = ui;
                    if (u != null) u.onPageGone();
                }
                Runnable c = cmds.poll(10, TimeUnit.MILLISECONDS);
                if (c != null) { c.run(); continue; }
                try {
                    s.service(400);
                } catch (Exception e) {
                    Log.w(TAG, "socket: " + e.getMessage());
                    Thread.sleep(2000);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            stopAlert();
            try { s.register(0); } catch (Exception ignored) {}
            s.close();
            if (sip == s) sip = null;
            registered = false;
            setInCall(false);
        }
    }

    private void setStatus(String s) {
        status = s;
        Log.i(TAG, s);
        Ui u = ui;
        if (u != null) u.onStatus(s);
    }

    private void setInCall(boolean on) {
        if (inCall == on) return;
        inCall = on;
        Ui u = ui;
        if (u != null) u.onCallChanged(on);
    }

    // ---- the page alert: vibration (always) and a chirp (ringer volume) ------------

    private ToneGenerator tone;

    private void alert() {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(new long[] { 0, 400, 250, 400, 1200 }, 0);
        } catch (Exception e) {
            Log.w(TAG, "vibrate", e);
        }
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_RING, 80);
            tone.startTone(ToneGenerator.TONE_SUP_RINGTONE);
        } catch (Exception e) {
            Log.w(TAG, "tone", e);
        }
    }

    private void stopAlert() {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Exception ignored) {}
        try {
            if (tone != null) { tone.stopTone(); tone.release(); tone = null; }
        } catch (Exception ignored) {}
    }
}
