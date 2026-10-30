package com.solar.launcher.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.solar.launcher.MainActivity;

/**
 * 2026-10-27 — Remote control from nookTunes (the Flipper app): next, prev,
 * playpause, heart, notforme, volup, voldown, volume (+ "level").
 * 2026-10-30 — Queue: cmd "queue" reports it; cmd "qedit" with "op"
 * (remove|move|play|clear|heart|notforme), "key", "index", "to" edits it; both
 * carry "req", which the report sent back to nookTunes repeats.
 *
 *   adb shell am broadcast -a com.solar.launcher.action.REMOTE --es cmd next
 *
 * Guarded by android.permission.DUMP like {@link RemotePlayReceiver}, so only
 * adb (shell or root) gets through. Unlike Play on Y1 it does not bring Solar
 * forward: a skip from across the room should not change the screen. With no
 * live MainActivity there is nothing to control, and the command is dropped.
 */
public final class RemoteControlReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.solar.launcher.action.REMOTE";
    private static final String TAG = "SolarRemote";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        final String cmd = intent.getStringExtra("cmd");
        final int level = intent.getIntExtra("level", -1);
        final String req = intent.getStringExtra("req");
        final String op = intent.getStringExtra("op");
        final String key = intent.getStringExtra("key");
        final int index = intent.getIntExtra("index", -1);
        final int to = intent.getIntExtra("to", -1);
        final MainActivity a = MainActivity.instance;
        if (cmd == null || a == null) {
            Log.w(TAG, "dropped " + cmd + (a == null ? " (Solar not running)" : ""));
            return;
        }
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                if ("queue".equals(cmd) || "qedit".equals(cmd)) {
                    a.handleRemoteQueue(cmd, req, op, key, index, to);
                } else {
                    a.handleRemoteCommand(cmd, level);
                }
            }
        });
    }
}
