package com.solar.launcher;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * 2026-07-06 — Tier-2 overlay key pipe: one root {@code sh} streams {@code am startservice} lines.
 * Layman: helper keeps a single shell open so wheel/back reach the modal without spawning storms.
 * Technical: {@link GlobalOverlayTriggerMain} uses this when {@code sys.solar.overlay.active=1} and
 * Xposed PWM miss (Solar foreground / hung main). Reversal: revert to per-key {@code Runtime.exec}.
 */
final class OverlayRootKeyShell {

    private static final String TAG = "OverlayRootKeyShell";
    private static Process shell;
    private static OutputStream stdin;
    private static volatile long lastForwardAt;

    private OverlayRootKeyShell() {}

    /** Forward one navigation key to {@link SolarOverlayService} — no fork per key. */
    static void forward(int keyCode, int action) {
        if (keyCode == 0) return;
        if (action != android.view.KeyEvent.ACTION_DOWN
                && action != android.view.KeyEvent.ACTION_UP) {
            return;
        }
        synchronized (OverlayRootKeyShell.class) {
            try {
                ensureShellLocked();
                if (stdin == null) return;
                String cmd = "am startservice -a " + OverlayTriggers.ACTION_OVERLAY_KEY
                        + " -n com.solar.launcher/.SolarOverlayService"
                        + " --ei " + OverlayTriggers.EXTRA_KEY_CODE + " " + keyCode
                        + " --ei " + OverlayTriggers.EXTRA_KEY_ACTION + " " + action
                        + "\n";
                stdin.write(cmd.getBytes("UTF-8"));
                stdin.flush();
                lastForwardAt = System.currentTimeMillis();
                // #region agent log
                if (com.solar.launcher.debug.DebugGate.ON) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("keyCode", keyCode);
                    d.put("action", action);
                    DebugAf054eLog.logStandalone("OverlayRootKeyShell.forward",
                            "tier-2 root forward", "H6,H7", d);
                } catch (Exception ignored) {}
                }
                // #endregion
            } catch (Exception e) {
                resetShellLocked();
            }
        }
    }

    /** Test hook — last forward timestamp. */
    static long lastForwardAtForTest() {
        return lastForwardAt;
    }

    private static void ensureShellLocked() throws Exception {
        if (shell != null && stdin != null) return;
        resetShellLocked();
        shell = Runtime.getRuntime().exec(new String[]{"sh"});
        stdin = shell.getOutputStream();
        Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (shell != null && shell.getInputStream() != null) {
                        byte[] buf = new byte[256];
                        while (shell.getInputStream().read(buf) >= 0) {}
                    }
                } catch (Exception ignored) {}
            }
        }, "OverlayRootKeyShellDrain");
        drain.setDaemon(true);
        drain.start();
    }

    private static void resetShellLocked() {
        try {
            if (stdin != null) stdin.close();
        } catch (Exception ignored) {}
        try {
            if (shell != null) shell.destroy();
        } catch (Exception ignored) {}
        stdin = null;
        shell = null;
    }
}
