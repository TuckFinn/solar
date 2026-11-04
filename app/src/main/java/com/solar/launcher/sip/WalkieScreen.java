package com.solar.launcher.sip;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import com.solar.launcher.R;
import com.solar.launcher.deezer.DeezerScreen;

/**
 * 2026-11-03 — Walkie-Talkie on the home menu (Tucker): the Y1 joins nookLine's
 * walkie room (7000, a ConfBridge on familynook) while this screen is open.
 * Hold Play/Pause to talk, let go to listen; Back leaves the room.
 *
 * The account comes from SOLAR_SETTINGS (sip_server, sip_user, sip_password,
 * sip_room), set by nookTunes over adb with the SIP_CONFIG broadcast
 * ({@link SipConfigReceiver}). It is drawn in the shared browser list, like
 * Discovery. Registration and the call live on a worker thread for as long as
 * the screen is open; nothing listens in the background, so the Y1's Wi-Fi can
 * sleep the rest of the time.
 */
public final class WalkieScreen {
    private static final String TAG = "SolarWalkie";
    public static final String PREF_SERVER = "sip_server";
    public static final String PREF_USER = "sip_user";
    public static final String PREF_PASSWORD = "sip_password";
    public static final String PREF_ROOM = "sip_room";
    private static final int REREGISTER_MS = 300 * 1000;

    /** What MainActivity lends this screen beyond Discovery's host. */
    public interface Shell {
        boolean isWalkieScreen();
        void pauseMusicForWalkie();
        void leaveWalkieScreen();
    }

    private final DeezerScreen.Host host;
    private final Shell shell;
    private volatile NookSip sip;
    private volatile Thread worker;
    private volatile boolean wanted;
    private volatile String status = "";
    private volatile boolean talking;
    private int savedAudioMode = AudioManager.MODE_NORMAL;
    private boolean savedSpeaker;

    public WalkieScreen(DeezerScreen.Host host, Shell shell) {
        this.host = host;
        this.shell = shell;
    }

    public static boolean isConfigured(SharedPreferences p) {
        return p != null && !p.getString(PREF_USER, "").isEmpty()
                && !p.getString(PREF_SERVER, "").isEmpty() && !p.getString(PREF_PASSWORD, "").isEmpty();
    }

    /** Entering the screen: join the room. */
    public void open() {
        talking = false;
        if (!isConfigured(host.prefs())) {
            status = host.string(R.string.walkie_not_set_up);
            render();
            return;
        }
        if (worker != null && worker.isAlive()) {
            render();
            return;
        }
        wanted = true;
        status = host.string(R.string.walkie_connecting);
        shell.pauseMusicForWalkie();
        audioForCall(true);
        render();
        final SharedPreferences p = host.prefs();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                runCall(p.getString(PREF_SERVER, ""), p.getString(PREF_USER, ""),
                        p.getString(PREF_PASSWORD, ""), p.getString(PREF_ROOM, "7000"));
            }
        }, "SolarWalkie");
        worker.start();
    }

    /** Leaving the screen (Back, Home, anything): leave the room. */
    public void leave() {
        wanted = false;
        talking = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        audioForCall(false);
    }

    public boolean active() {
        return wanted;
    }

    /** Play/Pause pressed (true) or released (false). */
    public void pushToTalk(boolean down) {
        NookSip s = sip;
        if (s == null || !s.inCall()) {
            if (down) host.clickFeedback();
            return;
        }
        if (down == talking) return;
        talking = down;
        s.talk(down);
        render();
    }

    private void runCall(String server, String user, String password, String room) {
        NookSip s = new NookSip(server, 5060, user, password);
        sip = s;
        try {
            if (!s.register(600)) {
                setStatus(host.string(R.string.walkie_no_server));
                return;
            }
            setStatus(host.string(R.string.walkie_joining));
            if (!s.call(room)) {
                setStatus(host.string(R.string.walkie_no_room));
                return;
            }
            setStatus(host.string(R.string.walkie_in_room, user));
            long nextRegister = System.currentTimeMillis() + REREGISTER_MS;
            while (wanted && s.inCall()) {
                s.service(500);
                if (System.currentTimeMillis() > nextRegister) {
                    s.register(600);
                    nextRegister = System.currentTimeMillis() + REREGISTER_MS;
                }
            }
            if (wanted && !s.inCall()) setStatus(host.string(R.string.walkie_room_closed));
        } catch (Exception e) {
            Log.w(TAG, "walkie stopped", e);
            setStatus(host.string(R.string.walkie_error, String.valueOf(e.getMessage())));
        } finally {
            try { s.hangup(); } catch (Exception ignored) {}
            try { s.register(0); } catch (Exception ignored) {}
            s.close();
            sip = null;
            talking = false;
            host.runOnUi(new Runnable() {
                @Override public void run() {
                    if (!wanted) audioForCall(false);
                    render();
                }
            });
        }
    }

    private void setStatus(String s) {
        status = s;
        Log.i(TAG, s);
        host.runOnUi(new Runnable() {
            @Override public void run() { render(); }
        });
    }

    /**
     * A call wants MODE_IN_COMMUNICATION (the voice path, echo suppression) and,
     * with no headphones in, the speaker. Put back what was there on the way out.
     */
    private void audioForCall(boolean on) {
        Context c = host.context();
        AudioManager am = c == null ? null : (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (on) {
                savedAudioMode = am.getMode();
                savedSpeaker = am.isSpeakerphoneOn();
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                @SuppressWarnings("deprecation")
                boolean headset = am.isWiredHeadsetOn() || am.isBluetoothA2dpOn();
                am.setSpeakerphoneOn(!headset);
            } else if (am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                am.setSpeakerphoneOn(savedSpeaker);
                am.setMode(savedAudioMode == AudioManager.MODE_IN_COMMUNICATION
                        ? AudioManager.MODE_NORMAL : savedAudioMode);
            }
        } catch (Exception e) {
            Log.w(TAG, "audio mode", e);
        }
    }

    public void render() {
        if (!shell.isWalkieScreen() || host.isNowPlayingScreen()) return;
        host.prepareBrowserChrome();
        host.applyReachBrowseLayoutMode();
        String title = host.string(R.string.home_menu_walkie);
        host.updateStatusBarTitle(title);
        if (host.tvBrowserPath() != null) host.tvBrowserPath().setText(title);
        LinearLayout c = host.containerBrowserItems();
        c.removeAllViews();
        c.addView(host.createListButton(status));
        NookSip s = sip;
        boolean live = s != null && s.inCall();
        c.addView(host.createListButton(host.string(
                !live ? R.string.walkie_hint_wait
                        : talking ? R.string.walkie_talking : R.string.walkie_hint_talk)));
        Button leave = host.createListButton(host.string(R.string.walkie_leave));
        leave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.clickFeedback();
                shell.leaveWalkieScreen();
            }
        });
        c.addView(leave);
        final View focus = leave;
        focus.post(new Runnable() {
            @Override public void run() {
                focus.setFocusable(true);
                focus.requestFocus();
            }
        });
    }
}
