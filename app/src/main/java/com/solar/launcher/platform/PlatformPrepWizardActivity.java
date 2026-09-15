package com.solar.launcher.platform;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import com.solar.launcher.LandscapeOrientationGuard;
import com.solar.launcher.MainActivity;
import com.solar.launcher.PowerActions;
import com.solar.launcher.R;
import com.solar.launcher.theme.ThemeManager;

/**
 * 2026-07-05 — Blocking prep wizard UI; runs SolarPlatformPrep ladder before MainActivity.
 * APK/ROM parity: user-facing face of APK self-heal; ROM flash already baked most steps at build time.
 * 2026-07-20 — Phone chrome: portrait via LandscapeOrientationGuard (was manifest landscape only).
 * Rooted phones: same SolarPlatformPrep ladder as Y1/Y2 — not skipped like A5.
 * When changing: SolarPlatformPrep ladder order; wheel keys via Y1InputKeys; markDismissed vs prepVersion bump.
 * Reversal: remove activity; boot goes straight to MainActivity again; hardcode landscape.
 */
public class PlatformPrepWizardActivity extends Activity {

    /** Settings → manual platform repair sets this — only path that shows blocking wizard. */
    public static final String EXTRA_MANUAL_REPAIR = "solar_platform_prep_manual";
    /** Xposed/module repair finished — show reboot confirm only (2026-07-06). */
    public static final String EXTRA_REBOOT_ONLY = "solar_platform_prep_reboot_only";
    /**
     * 2026-07-16 — First-boot / cold-start gate from {@link com.solar.launcher.SolarLaunchActivity}.
     * Layman: full-screen “Getting things ready…” instead of silent prep on a blank screen.
     */
    public static final String EXTRA_FIRST_BOOT = "solar_platform_prep_first_boot";

    private static final int MAX_LOG_LINES = 40;
    /** Auto-enter Solar after partial/limited notice — user may skip with Back or Center. */
    private static final long AUTO_CONTINUE_MS = 3500L;
    private boolean firstBoot;

    private TextView titleView;
    private TextView subtitleView;
    private TextView percentView;
    private TextView logView;
    private ProgressBar progressBar;
    private Button actionButton;
    private ScrollView logScroll;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean prepRunning = true;
    private volatile boolean actionReady;
    private SolarPlatformPrep.PrepResult prepResult;
    private Runnable autoContinueRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 2026-07-20 — Match MainActivity: chrome phones portrait; Y1/Y2 landscape.
        // Was: android:screenOrientation=landscape always (phones sideways during prep).
        try {
            LandscapeOrientationGuard.enforceForDevice(this);
        } catch (Throwable ignored) {}
        super.onCreate(savedInstanceState);
        final boolean manual = getIntent().getBooleanExtra(EXTRA_MANUAL_REPAIR, false);
        final boolean rebootOnly = getIntent().getBooleanExtra(EXTRA_REBOOT_ONLY, false);
        firstBoot = getIntent().getBooleanExtra(EXTRA_FIRST_BOOT, false);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("manual", manual);
            d.put("rebootOnly", rebootOnly);
            d.put("firstBoot", firstBoot);
            d.put("pid", android.os.Process.myPid());
            com.solar.launcher.DebugA1f293Log.log(this, "PlatformPrepWizardActivity.onCreate",
                    "wizard shown", "A", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        if (rebootOnly) {
            setContentView(R.layout.activity_platform_prep);
            bindWizardViews();
            prepRunning = false;
            prepResult = new SolarPlatformPrep.PrepResult();
            prepResult.outcome = SolarPlatformPrep.PrepOutcome.REBOOT_PENDING;
            prepResult.rebootRequired = true;
            progressBar.setProgress(100);
            percentView.setText("100%");
            appendLog(getString(R.string.platform_prep_modules_ready));
            showOutcome();
            return;
        }
        // 2026-07-05 — Auto-launched wizard after prep already applied (legacy boot gate) → skip to Solar.
        try {
            PlatformPrepManifest manifest = PlatformPrepManifest.load(this);
            boolean needsPrep = PlatformPrepState.needsSilentPrep(this, manifest)
                    || PlatformPrepState.isRebootPending(this);
            // #region agent log
            JSONObject gate = new JSONObject();
            try {
                gate.put("manual", manual);
                gate.put("firstBoot", firstBoot);
                gate.put("needsSilentPrep", needsPrep);
                gate.put("applied", PlatformPrepState.getAppliedVersion(this));
                gate.put("prepVersion", manifest.prepVersion);
                gate.put("outcome", PlatformPrepState.getOutcome(this).name());
            } catch (Exception ignored) {}
            PlatformPrepDebugLog.log(this, "PlatformPrepWizardActivity.onCreate", "gate", "H2", gate);
            // #endregion
            if (!manual && !firstBoot && !needsPrep) {
                finishIntoSolar(false);
                return;
            }
            if (!manual && !needsPrep) {
                // First-boot extra but ladder already applied — still show brief ready then Solar.
                finishIntoSolar(false);
                return;
            }
        } catch (Exception e) {
            if (!manual && !firstBoot) {
                finishIntoSolar(false);
                return;
            }
        }
        setContentView(R.layout.activity_platform_prep);
        bindWizardViews();
        startPrepThread();
    }

    /** Wire wizard layout views + theme — shared by full prep and reboot-only paths (2026-07-06). */
    private void bindWizardViews() {
        titleView = (TextView) findViewById(R.id.platform_prep_title);
        subtitleView = (TextView) findViewById(R.id.platform_prep_subtitle);
        percentView = (TextView) findViewById(R.id.platform_prep_percent);
        logView = (TextView) findViewById(R.id.platform_prep_log);
        progressBar = (ProgressBar) findViewById(R.id.platform_prep_progress);
        actionButton = (Button) findViewById(R.id.platform_prep_action);
        logScroll = (ScrollView) findViewById(R.id.platform_prep_log_scroll);

        // 2026-07-16 — First-boot: keep high-contrast white-on-black (layout already black).
        // Manual repair can still use theme text tokens for log/detail.
        if (firstBoot || getIntent().getBooleanExtra(EXTRA_REBOOT_ONLY, false)) {
            if (titleView != null) titleView.setTextColor(0xFFFFFFFF);
            if (subtitleView != null) subtitleView.setTextColor(0xFFCCCCCC);
            if (percentView != null) percentView.setTextColor(0xFFAAAAAA);
            if (logView != null) logView.setTextColor(0xFF888888);
        } else {
            ThemeManager.applyThemedTextStyle(titleView, ThemeManager.getTextColorPrimary());
            ThemeManager.applyThemedTextStyle(subtitleView, ThemeManager.getTextColorSecondary());
            ThemeManager.applyThemedTextStyle(logView, ThemeManager.getTextColorSecondary());
            ThemeManager.applyThemedTextStyle(actionButton, ThemeManager.getTextColorPrimary());
        }
        if (titleView != null) {
            titleView.setText(R.string.getting_things_ready);
        }
        if (subtitleView != null) {
            subtitleView.setText(R.string.getting_things_ready_detail);
        }

        actionButton.setFocusable(true);
        actionButton.setFocusableInTouchMode(true);
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onActionPressed();
            }
        });
    }

    private void startPrepThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                prepResult = SolarPlatformPrep.run(
                        PlatformPrepWizardActivity.this,
                        new SolarPlatformPrep.ProgressListener() {
                            @Override
                            public void onProgress(final int percent, final String message) {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateProgress(percent, message);
                                    }
                                });
                            }

                            @Override
                            public void onLogLine(final String line) {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        appendLog(line);
                                    }
                                });
                            }
                        });
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        prepRunning = false;
                        showOutcome();
                    }
                });
            }
        }, "PlatformPrepWizard").start();
    }

    /**
     * 2026-07-20 — Re-lock portrait on chrome phones if another app twisted the panel.
     * Layman: keep the prep screen upright on phones.
     */
    @Override
    protected void onResume() {
        super.onResume();
        try {
            LandscapeOrientationGuard.enforceForDevice(this);
        } catch (Throwable ignored) {}
    }

    private void updateProgress(int percent, String message) {
        if (progressBar != null) progressBar.setProgress(percent);
        if (percentView != null) percentView.setText(percent + "%");
        if (subtitleView != null && message != null) subtitleView.setText(message);
    }

    private void appendLog(String line) {
        if (logView == null || line == null) return;
        String existing = logView.getText() != null ? logView.getText().toString() : "";
        String combined = existing.isEmpty() ? line : existing + "\n" + line;
        String[] lines = combined.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                if (trimmed.length() > 0) trimmed.append('\n');
                trimmed.append(lines[i]);
            }
            combined = trimmed.toString();
        }
        logView.setText(combined);
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void showOutcome() {
        cancelAutoContinue();
        if (prepResult == null) {
            finishIntoSolar(false);
            return;
        }
        switch (prepResult.outcome) {
            case REBOOT_PENDING:
                // 2026-07-19 — Auto-restart; keep “Getting things ready…” (no Restart button).
                // Layman: player reboots itself; user just waits on the same ready screen.
                // Was: “Restart to finish setup” + Restart now. Reversal: restore button path.
                beginAutoRestart();
                break;
            case PARTIAL:
                actionReady = true;
                actionButton.setVisibility(View.VISIBLE);
                actionButton.requestFocus();
                titleView.setText(R.string.platform_prep_title);
                subtitleView.setText(getString(R.string.platform_prep_partial_message) + "\n\n"
                        + getString(R.string.platform_prep_continuing_soon));
                actionButton.setText(R.string.platform_prep_continue);
                scheduleAutoContinue();
                break;
            case LIMITED:
                actionReady = true;
                actionButton.setVisibility(View.VISIBLE);
                actionButton.requestFocus();
                titleView.setText(R.string.platform_prep_title);
                subtitleView.setText(getString(R.string.platform_prep_limited_message) + "\n\n"
                        + getString(R.string.platform_prep_continuing_soon));
                actionButton.setText(R.string.platform_prep_continue);
                scheduleAutoContinue();
                break;
            default:
                finishIntoSolar(false);
                return;
        }
    }

    /**
     * Keep the getting-ready face and reboot without asking.
     * Short delay so the UI paints before {@code reboot} kills the process.
     */
    private void beginAutoRestart() {
        actionReady = false;
        if (actionButton != null) {
            actionButton.setVisibility(View.GONE);
        }
        if (titleView != null) {
            titleView.setText(R.string.getting_things_ready);
        }
        if (subtitleView != null) {
            subtitleView.setText(R.string.getting_things_ready_detail);
        }
        if (progressBar != null) {
            progressBar.setProgress(100);
        }
        if (percentView != null) {
            percentView.setText("100%");
        }
        // #region agent log
        PlatformPrepDebugLog.log(this, "PlatformPrepWizardActivity.beginAutoRestart",
                "auto reboot scheduled", "H-REBOOT", null);
        // #endregion
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                // No diag prep — keep ready face until reboot; root shell restarts immediately.
                PowerActions.restart();
            }
        }, 900L);
    }

    private void scheduleAutoContinue() {
        cancelAutoContinue();
        autoContinueRunnable = new Runnable() {
            @Override
            public void run() {
                // #region agent log
                JSONObject d = new JSONObject();
                try {
                    d.put("actionReady", actionReady);
                    d.put("finishing", isFinishing());
                } catch (Exception ignored) {}
                PlatformPrepDebugLog.log(PlatformPrepWizardActivity.this,
                        "PlatformPrepWizardActivity.scheduleAutoContinue", "firing", "H3", d);
                // #endregion
                finishIntoSolar(false);
            }
        };
        // #region agent log
        PlatformPrepDebugLog.log(this, "PlatformPrepWizardActivity.scheduleAutoContinue",
                "scheduled", "H3", null);
        // #endregion
        mainHandler.postDelayed(autoContinueRunnable, AUTO_CONTINUE_MS);
    }

    private void cancelAutoContinue() {
        if (autoContinueRunnable != null) {
            mainHandler.removeCallbacks(autoContinueRunnable);
            autoContinueRunnable = null;
        }
    }

    private void onActionPressed() {
        if (!actionReady) return;
        cancelAutoContinue();
        if (prepResult != null && prepResult.outcome == SolarPlatformPrep.PrepOutcome.REBOOT_PENDING) {
            PowerActions.restart();
            return;
        }
        finishIntoSolar(false);
    }

    /** Enter MainActivity — dismissed=true when user skipped via Back. */
    private void finishIntoSolar(boolean dismissed) {
        cancelAutoContinue();
        // #region agent log
        JSONObject d = new JSONObject();
        try {
            d.put("dismissed", dismissed);
            d.put("outcome", prepResult != null ? prepResult.outcome.name() : "null");
        } catch (Exception ignored) {}
        PlatformPrepDebugLog.log(this, "PlatformPrepWizardActivity.finishIntoSolar", "exit wizard", "H3", d);
        // #endregion
        if (dismissed) {
            try {
                PlatformPrepManifest manifest = PlatformPrepManifest.load(this);
                PlatformPrepState.markDismissed(this, manifest.prepVersion);
            } catch (Exception ignored) {
                PlatformPrepState.markDismissed(this, PlatformPrepState.getAppliedVersion(this));
            }
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 2026-07-16 — Keep ready overlay until home menu is usable after prep.
        i.putExtra(com.solar.launcher.FirstSessionReadyGate.EXTRA_KEEP_READY_OVERLAY, true);
        startActivity(i);
        finish();
    }

    @Override
    public void onBackPressed() {
        dismissWizard();
    }

    private void dismissWizard() {
        // 2026-07-19 — Do not leave mid auto-restart; keep getting-ready until reboot.
        if (prepResult != null
                && prepResult.outcome == SolarPlatformPrep.PrepOutcome.REBOOT_PENDING) {
            return;
        }
        finishIntoSolar(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleWizardKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Y2 wheel often delivers keys via onKeyUp — mirror MainActivity hardware routing. */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (handleWizardKey(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /** Shared wheel/back/center handling for prep wizard exit. */
    private boolean handleWizardKey(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (PlatformPrepWizardKeys.shouldDismissWizard(keyCode, action)) {
            dismissWizard();
            return true;
        }
        if (PlatformPrepWizardKeys.shouldActivateAction(keyCode, action) && actionReady) {
            onActionPressed();
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && PlatformPrepWizardKeys.shouldConsumeKeyDown(keyCode)) {
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        cancelAutoContinue();
        super.onDestroy();
    }
}
