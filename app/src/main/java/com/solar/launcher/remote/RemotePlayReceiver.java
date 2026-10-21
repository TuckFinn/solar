package com.solar.launcher.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.solar.launcher.MainActivity;
import com.solar.launcher.R;

import java.util.List;

/**
 * 2026-10-21 — "Play on Y1": nookTunes on nookman sends
 * {@link RemotePlayRequest#ACTION} over adb and the Y1 plays that song (and
 * queue_json's after it) through Discovery's Deezer path.
 *
 * Who may send it. The manifest guards this receiver with
 * android.permission.DUMP. The shell user (uid 2000, what `adb shell` runs
 * as) holds DUMP -- it is how `dumpsys` works -- and root (uid 0) passes every
 * permission check, so adb gets through either way. A third-party app cannot
 * hold DUMP on API 17: it is signature|system|development, and a development
 * permission is only ever granted by `pm grant`, i.e. from adb itself. The
 * check has to be the manifest's: in onReceive Binder.getCallingUid() is this
 * process's own uid (the broadcast is not delivered inside the sender's binder
 * call), and the sender's uid is not exposed until API 34.
 *
 * The songs are handed to MainActivity in memory, not as Intent extras.
 * MainActivity is exported, so anything on the device can start it with any
 * extras; carrying the request here keeps the permission the only way in. The
 * Intent only asks MainActivity to look.
 */
public final class RemotePlayReceiver extends BroadcastReceiver {

    private static final String TAG = "SolarRemotePlay";
    /** Set on the Intent that brings MainActivity forward; it carries no songs. */
    public static final String EXTRA_LOOK = "solar_remote_play";
    /**
     * A request left waiting longer than this is dropped rather than played:
     * Solar was killed or busy, and a song starting a minute after it was
     * asked for would be a surprise.
     */
    private static final long MAX_AGE_MS = 60_000L;

    private static List<RemotePlayRequest.Track> pending;
    private static long pendingAt;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !RemotePlayRequest.ACTION.equals(intent.getAction())) return;
        Bundle x = intent.getExtras();
        if (x == null) {
            Log.w(TAG, "no extras; nothing to play");
            return;
        }
        List<RemotePlayRequest.Track> tracks = RemotePlayRequest.fromExtras(
                x.getString(RemotePlayRequest.EXTRA_TITLE),
                x.getString(RemotePlayRequest.EXTRA_ARTIST),
                x.getString(RemotePlayRequest.EXTRA_ALBUM),
                x.get(RemotePlayRequest.EXTRA_DEEZER_ID),
                x.getString(RemotePlayRequest.EXTRA_QUEUE_JSON));
        if (tracks.isEmpty()) {
            Log.w(TAG, "no playable song in the request");
            return;
        }
        Log.i(TAG, "request: " + tracks.size() + " song(s), first id=" + tracks.get(0).deezerId);
        synchronized (RemotePlayReceiver.class) {
            pending = tracks;
            pendingAt = SystemClock.elapsedRealtime();
        }
        Toast.makeText(context, R.string.remote_play_toast, Toast.LENGTH_SHORT).show();
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch.putExtra(EXTRA_LOOK, true);
        try {
            context.startActivity(launch);
        } catch (Exception e) {
            // MainActivity also looks on its next resume.
            Log.w(TAG, "could not bring Solar forward", e);
        }
    }

    /** The waiting request, once; null when there is none or it went stale. */
    public static List<RemotePlayRequest.Track> takePending() {
        synchronized (RemotePlayReceiver.class) {
            List<RemotePlayRequest.Track> t = pending;
            long at = pendingAt;
            pending = null;
            if (t == null) return null;
            if (SystemClock.elapsedRealtime() - at > MAX_AGE_MS) {
                Log.w(TAG, "dropped a stale request");
                return null;
            }
            return t;
        }
    }

    public static boolean hasPending() {
        synchronized (RemotePlayReceiver.class) {
            return pending != null;
        }
    }
}
