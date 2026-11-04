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
 * 2026-11-03 — Walkie-Talkie on the home menu (Tucker): while this screen is open the
 * Y1 is in nookLine's walkie room (7000, a ConfBridge on familynook). Hold Play/Pause
 * to talk, let go to listen; Back leaves the room.
 *
 * 2026-11-04 — paging: the SIP side moved to {@link WalkieService}, which stays
 * registered in the background. Opening this screen dials the room; arriving here
 * from a page's Join answers the page instead (the page is the room). Opening it to
 * an empty room makes Asterisk page the other Y1.
 *
 * The account comes from SOLAR_SETTINGS (sip_server, sip_user, sip_password, sip_room),
 * set by nookTunes over adb with the SIP_CONFIG broadcast ({@link SipConfigReceiver}).
 */
public final class WalkieScreen {
    private static final String TAG = "SolarWalkie";
    public static final String PREF_SERVER = "sip_server";
    public static final String PREF_USER = "sip_user";
    public static final String PREF_PASSWORD = "sip_password";
    public static final String PREF_ROOM = "sip_room";

    /** What MainActivity lends this screen beyond Discovery's host. */
    public interface Shell {
        boolean isWalkieScreen();
        void pauseMusicForWalkie();
        void leaveWalkieScreen();
    }

    private final DeezerScreen.Host host;
    private final Shell shell;
    private volatile boolean wanted;
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

    private WalkieService svc() {
        return WalkieService.get(host.context());
    }

    /** Entering the screen: join the room (or take the page that brought us here). */
    public void open() {
        talking = false;
        if (!isConfigured(host.prefs())) {
            render();
            return;
        }
        WalkieService s = svc();
        s.start();
        if (!wanted) {
            wanted = true;
            shell.pauseMusicForWalkie();
            audioForCall(true);
        }
        if (!s.inCall() && !s.answering()) {
            if (s.ringing()) s.accept();     // opened from the menu while paged: same thing
            else s.join();
        }
        render();
    }

    /** Leaving the screen (Back, Home, anything): leave the room. */
    public void leave() {
        wanted = false;
        talking = false;
        svc().leave();
        audioForCall(false);
    }

    public boolean active() {
        return wanted;
    }

    /** Play/Pause pressed (true) or released (false). */
    public void pushToTalk(boolean down) {
        WalkieService s = svc();
        if (!s.inCall()) {
            if (down) host.clickFeedback();
            return;
        }
        if (down == talking) return;
        talking = down;
        s.talk(down);
        render();
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

    private String statusLine() {
        if (!isConfigured(host.prefs())) return host.string(R.string.walkie_not_set_up);
        WalkieService s = svc();
        if (s.inCall()) return host.string(R.string.walkie_in_room, host.prefs().getString(PREF_USER, ""));
        if (s.answering()) return host.string(R.string.walkie_joining);
        String st = s.status();
        if (st.startsWith("can't reach")) return host.string(R.string.walkie_no_server);
        if (st.startsWith("no room")) return host.string(R.string.walkie_no_room);
        if (st.startsWith("error")) return host.string(R.string.walkie_error, st);
        return host.string(wanted ? R.string.walkie_joining : R.string.walkie_connecting);
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
        c.addView(host.createListButton(statusLine()));
        boolean live = svc().inCall();
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
