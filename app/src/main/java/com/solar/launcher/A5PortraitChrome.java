package com.solar.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 2026-07-11 — A5 portrait chrome: full-width list + bottom info strip (iPod nano style).
 * Layman: tall screen — menus fill the width; hints sit in a short strip at the bottom.
 * Tech: repositions preview views to bottom gravity; list hosts leave margin for strip.
 * Reversal: hide strip; restore dual-pane via applyFullWidthMenusLayout only.
 */
public final class A5PortraitChrome {

    /** Bottom strip ≈ ¼–⅓ of 320px tall (~90dp at mdpi). */
    public static final int STRIP_HEIGHT_DP = 90;

    private A5PortraitChrome() {}

    /**
     * 2026-07-14 — True when tall/full-width chrome should paint (A5 portrait or Y1/Y2 experiment).
     * Layman: upright A5, or Y1/Y2 Debug Portrait On with a tall buffer.
     * Tech: Y1PortraitExperiment → physical portrait only (wide buffer keeps landscape chrome);
     * A5: pref first; landscape lock fails open when heightPixels > widthPixels.
     * Reversal: drop Y1 branch; ORIENT_LANDSCAPE → always false (ignore physical mismatch).
     */
    /** Last chrome-gate sample ms — avoid flooding every layout pass. */
    private static volatile long lastChromeGateLogMs;

    public static boolean usePortraitChrome(Context ctx) {
        if (ctx == null) return false;
        // Y1/Y2 portrait experiment — wait for tall buffer before nano chrome.
        if (Y1PortraitExperiment.isEnabled(ctx)) {
            return isPhysicalPortrait(ctx);
        }
        if (!DeviceFeatures.isA5()) return false;
        String mode = A5NavigationMode.orientation(ctx);
        boolean phys = isPhysicalPortrait(ctx);
        boolean result;
        if (A5NavigationMode.ORIENT_LANDSCAPE.equals(mode)) {
            // Landscape locked but framebuffer still tall (Y1-as-A5 lab / slow rotate).
            result = phys;
        } else if (A5NavigationMode.ORIENT_PORTRAIT.equals(mode)) {
            result = true;
        } else {
            // auto — follow current configuration
            Configuration c = ctx.getResources().getConfiguration();
            result = c == null || c.orientation != Configuration.ORIENTATION_LANDSCAPE;
        }
        // #region agent log
        long now = System.currentTimeMillis();
        if (now - lastChromeGateLogMs > 1500L) {
            lastChromeGateLogMs = now;
            try {
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("mode", mode);
                d.put("physPortrait", phys);
                d.put("result", result);
                if (dm != null) {
                    d.put("physW", dm.widthPixels);
                    d.put("physH", dm.heightPixels);
                }
                Debug1fc727Log.log(ctx, "A5PortraitChrome.usePortraitChrome",
                        "chrome gate", "L1", d);
            } catch (Exception ignored) {}
        }
        // #endregion
        return result;
    }

    /**
     * 2026-07-14 — Display taller than wide (240×320 buffer).
     * Layman: phone is standing upright. Tech: heightPixels > widthPixels.
     */
    public static boolean isPhysicalPortrait(Context ctx) {
        if (ctx == null) return false;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm != null && dm.heightPixels > dm.widthPixels;
    }

    /** Strip height in px for current density. */
    public static int stripHeightPx(Context ctx) {
        if (ctx == null) return STRIP_HEIGHT_DP;
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.max(72, (int) (STRIP_HEIGHT_DP * d + 0.5f));
    }

    /**
     * 2026-07-14 — Bottom strip only mirrors Y1 dual-pane preview (home + settings).
     * Layman: music library and other screens stay full height — no floating hint strip.
     * Reversal: return usePortraitChrome(ctx) always.
     */
    public static boolean showBottomStrip(Context ctx, boolean homeOrSettings,
            boolean reachBrowseFullWidth) {
        return usePortraitChrome(ctx) && homeOrSettings && !reachBrowseFullWidth;
    }

    /**
     * Apply or clear portrait chrome on home/settings hosts.
     * @param bottomStrip the A5 bottom info container (may be null)
     * @param showStrip true only on home/settings when portrait chrome is active
     */
    public static void apply(Activity activity, View menuListHost, View settingsMenuHost,
            View settingsPreviewPane, ImageView homePreview, TextView homeTitle, TextView homeArtist,
            View bottomStrip, ImageView stripIcon, TextView stripTitle, TextView stripHint,
            boolean reachBrowseFullWidth, boolean showStrip) {
        if (activity == null) return;
        boolean portrait = usePortraitChrome(activity);
        int stripH = stripHeightPx(activity);
        int statusH = 0;
        try {
            statusH = (int) activity.getResources().getDimension(
                    com.solar.launcher.R.dimen.y1_status_bar_height);
        } catch (Throwable ignored) {}

        boolean stripOn = portrait && showStrip && !reachBrowseFullWidth;
        if (bottomStrip != null) {
            bottomStrip.setVisibility(stripOn ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams lp = bottomStrip.getLayoutParams();
            if (lp != null) {
                lp.height = stripH;
                bottomStrip.setLayoutParams(lp);
            }
            // #region agent log
            if (com.solar.launcher.debug.DebugGate.ON) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("sessionId", "b4208e");
                d.put("runId", "power-strip-1");
                d.put("stripOn", stripOn);
                d.put("portrait", portrait);
                d.put("showStrip", showStrip);
                d.put("reachBrowse", reachBrowseFullWidth);
                d.put("stripVis", bottomStrip.getVisibility());
                d.put("previewVis", homePreview != null ? homePreview.getVisibility() : -1);
                DebugB4208eLog.log("A5PortraitChrome.apply", "strip visibility set", "S-A,S-B", d);
            } catch (Exception ignored) {}
            }
            // #endregion
        }

        if (portrait) {
            // Full-width list; leave strip margin only when strip is shown.
            int hostStrip = stripOn ? stripH : 0;
            layoutListHost(menuListHost, true, statusH, hostStrip);
            layoutListHost(settingsMenuHost, true, statusH, hostStrip);
            // Hide landscape right-pane; content mirrored into strip by callers.
            if (settingsPreviewPane != null && !reachBrowseFullWidth) {
                settingsPreviewPane.setVisibility(View.GONE);
            }
            if (homePreview != null) homePreview.setVisibility(View.GONE);
            if (homeTitle != null) homeTitle.setVisibility(View.GONE);
            if (homeArtist != null) homeArtist.setVisibility(View.GONE);
            if (stripOn) {
                if (stripIcon != null) stripIcon.setVisibility(View.VISIBLE);
                if (stripTitle != null) {
                    stripTitle.setVisibility(View.VISIBLE);
                    stripTitle.setSelected(true);
                }
                if (stripHint != null) {
                    stripHint.setVisibility(View.VISIBLE);
                    stripHint.setSelected(true);
                }
            } else {
                if (stripIcon != null) stripIcon.setVisibility(View.GONE);
                if (stripTitle != null) stripTitle.setVisibility(View.GONE);
                if (stripHint != null) stripHint.setVisibility(View.GONE);
            }
        } else {
            // 2026-07-14 — Leaving portrait chrome: strip must die; list hosts revert via
            // applyFullWidthMenusLayout (do not keep MATCH_PARENT + strip bottomMargin).
            // Was: only GONE the strip — leftover bottomMargin ate Songs list height.
            // Reversal: else-if bottomStrip GONE only.
            if (bottomStrip != null) bottomStrip.setVisibility(View.GONE);
            clearStripMargin(menuListHost);
            clearStripMargin(settingsMenuHost);
        }
    }

    /** Zero portrait bottom inset so dual-pane / list height is not short by stripH. */
    private static void clearStripMargin(View host) {
        if (host == null) return;
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if (lp.bottomMargin != 0) {
            lp.bottomMargin = 0;
            host.setLayoutParams(lp);
        }
    }

    /** Compat overload — assume strip allowed (home/settings callers). */
    public static void apply(Activity activity, View menuListHost, View settingsMenuHost,
            View settingsPreviewPane, ImageView homePreview, TextView homeTitle, TextView homeArtist,
            View bottomStrip, ImageView stripIcon, TextView stripTitle, TextView stripHint,
            boolean reachBrowseFullWidth) {
        apply(activity, menuListHost, settingsMenuHost, settingsPreviewPane,
                homePreview, homeTitle, homeArtist, bottomStrip, stripIcon, stripTitle, stripHint,
                reachBrowseFullWidth, true);
    }

    /** Bind home preview art + marquee lines into the bottom strip. */
    public static void bindHomeStrip(ImageView stripIcon, TextView stripTitle, TextView stripHint,
            ImageView sourcePreview, CharSequence title, CharSequence hint) {
        if (stripIcon != null && sourcePreview != null && sourcePreview.getDrawable() != null) {
            stripIcon.setImageDrawable(sourcePreview.getDrawable());
            stripIcon.setVisibility(View.VISIBLE);
        }
        if (stripTitle != null) {
            stripTitle.setText(title != null ? title : "");
            stripTitle.setSelected(true);
        }
        if (stripHint != null) {
            stripHint.setText(hint != null ? hint : "");
            stripHint.setSelected(true);
        }
    }

    /** Bind settings preview icon + explainer into the bottom strip. */
    public static void bindSettingsStrip(ImageView stripIcon, TextView stripTitle, TextView stripHint,
            ImageView sourceIcon, CharSequence title, CharSequence hint) {
        if (stripIcon != null) {
            if (sourceIcon != null && sourceIcon.getDrawable() != null
                    && sourceIcon.getVisibility() == View.VISIBLE) {
                stripIcon.setImageDrawable(sourceIcon.getDrawable());
                stripIcon.setVisibility(View.VISIBLE);
            } else {
                stripIcon.setVisibility(View.GONE);
            }
        }
        if (stripTitle != null) {
            stripTitle.setText(title != null ? title : "");
            stripTitle.setSelected(true);
        }
        if (stripHint != null) {
            stripHint.setText(hint != null ? hint : "");
            stripHint.setSelected(true);
        }
    }

    /**
     * 2026-07-11 — Stack NP content vertically for A5 portrait — art fills, info under, transport bottom.
     * 2026-07-14 — Landscape leave only flips axis; MainActivity.applyA5ScaledNowPlayingLayout restores
     * scaled Y1 art/info/overshoot (was: topMargin left at 0 after portrait zeroed −14 overshoot).
     * Layman: tall = cover on top like a nano; sideways = miniature Y1 side-by-side.
     * Reversal: landscape branch re-apply XML margins here instead of scaled helper.
     */
    public static void applyNowPlayingPortrait(View playerContentRow, View albumContainer,
            View infoColumn, boolean portrait) {
        if (!(playerContentRow instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) playerContentRow;
        if (portrait) {
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            if (albumContainer != null) {
                LinearLayout.LayoutParams alp = albumLp(albumContainer);
                if (alp != null) {
                    alp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                    alp.height = 0;
                    alp.weight = 1f;
                    alp.leftMargin = 0;
                    // Portrait stack: no negative overshoot into status pad.
                    alp.topMargin = 0;
                    albumContainer.setLayoutParams(alp);
                }
            }
            if (infoColumn != null) {
                LinearLayout.LayoutParams ilp = albumLp(infoColumn);
                if (ilp != null) {
                    ilp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                    ilp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                    ilp.weight = 0f;
                    ilp.leftMargin = 0;
                    infoColumn.setLayoutParams(ilp);
                }
            }
        } else {
            // Horizontal Y1 replica — sizes/margins applied by applyA5ScaledNowPlayingLayout.
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            Context ctx = playerContentRow.getContext();
            DisplayMetrics dm = ctx != null ? ctx.getResources().getDisplayMetrics() : null;
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("runId", "pre-fix");
            d.put("portrait", portrait);
            d.put("family", DeviceFeatures.deviceFamily());
            d.put("y1Portrait", ctx != null && Y1PortraitExperiment.isEnabled(ctx));
            d.put("isA5", DeviceFeatures.isA5());
            if (dm != null) {
                d.put("physW", dm.widthPixels);
                d.put("physH", dm.heightPixels);
                d.put("density", dm.density);
            }
            if (albumContainer != null) {
                LinearLayout.LayoutParams alp = albumLp(albumContainer);
                d.put("artW", alp != null ? alp.width : -1);
                d.put("artH", alp != null ? alp.height : -1);
                d.put("artWeight", alp != null ? alp.weight : -1f);
                d.put("artMeasuredW", albumContainer.getWidth());
                d.put("artMeasuredH", albumContainer.getHeight());
            }
            if (infoColumn != null) {
                LinearLayout.LayoutParams ilp = albumLp(infoColumn);
                d.put("infoW", ilp != null ? ilp.width : -1);
                d.put("infoH", ilp != null ? ilp.height : -1);
                d.put("infoWeight", ilp != null ? ilp.weight : -1f);
            }
            d.put("rowOrient", row.getOrientation());
            Debug210a10Log.log(ctx, "A5PortraitChrome.applyNowPlayingPortrait",
                    "NP portrait LPs", "H-A", d);
            // Measured after layout — apply-time W/H often still 0.
            if (portrait && albumContainer != null) {
                final View art = albumContainer;
                final View info = infoColumn;
                final Context c = ctx;
                art.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            org.json.JSONObject m = new org.json.JSONObject();
                            m.put("runId", "pre-fix");
                            m.put("family", DeviceFeatures.deviceFamily());
                            m.put("artMeasuredW", art.getWidth());
                            m.put("artMeasuredH", art.getHeight());
                            if (info != null) {
                                m.put("infoMeasuredW", info.getWidth());
                                m.put("infoMeasuredH", info.getHeight());
                            }
                            DisplayMetrics dm2 = c != null
                                    ? c.getResources().getDisplayMetrics() : null;
                            if (dm2 != null) {
                                m.put("physW", dm2.widthPixels);
                                m.put("physH", dm2.heightPixels);
                                m.put("artFracW", dm2.widthPixels > 0
                                        ? (float) art.getWidth() / dm2.widthPixels : -1f);
                                m.put("artFracH", dm2.heightPixels > 0
                                        ? (float) art.getHeight() / dm2.heightPixels : -1f);
                            }
                            Debug210a10Log.log(c, "A5PortraitChrome.applyNowPlayingPortrait",
                                    "NP art measured", "H-A,H-E", m);
                        } catch (Exception ignored) {}
                    }
                });
            }
        } catch (Exception ignored) {}
        }
        // #endregion
    }

    private static LinearLayout.LayoutParams albumLp(View v) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) return (LinearLayout.LayoutParams) raw;
        return null;
    }

    private static void layoutListHost(View host, boolean portrait, int statusH, int stripH) {
        if (host == null) return;
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if (portrait) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.leftMargin = 0;
            lp.topMargin = statusH;
            lp.bottomMargin = stripH;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
        }
        host.setLayoutParams(lp);
    }

    /** Screen shorter side for narrow-overlay detection. */
    public static boolean isNarrowViewport(Context ctx) {
        if (ctx == null) return false;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return Math.min(dm.widthPixels, dm.heightPixels) <= 280;
    }

    /**
     * 2026-07-14 — Two chip rows only when the screen is portrait-tall.
     * Layman: standing up — stack quick icons in two rows; sideways — one Y1-style strip.
     * Tech: physical height > width AND (A5 / shortSide≤280 / Y1 portrait experiment).
     * Was: isA5 || narrow only — Y1 tall (~360 short) stayed one-row. Reversal: drop y1PortraitEligible.
     */
    public static boolean useTwoRowQuickBar(Context ctx) {
        if (ctx == null) return false;
        boolean eligible = DeviceFeatures.isA5() || isNarrowViewport(ctx)
                || Y1PortraitExperiment.isEnabled(ctx);
        return useTwoRowQuickBar(isPhysicalPortrait(ctx), eligible);
    }

    /**
     * 2026-07-14 — Pure gate for unit tests (no DisplayMetrics).
     * Layman: two rows only if upright and narrow/A5/Y1-portrait. Tech: portrait ∧ eligible.
     */
    public static boolean useTwoRowQuickBar(boolean physicalPortrait, boolean a5OrNarrowOrY1Portrait) {
        return physicalPortrait && a5OrNarrowOrY1Portrait;
    }

    /**
     * 2026-07-14 — Narrow context modal like A5 when tall chrome is active.
     * Layman: skinny overlay on upright screens (A5 always; Y1/Y2 only in portrait experiment).
     * Tech: isA5 || shortSide≤280 || usePortraitChrome. Reversal: drop usePortraitChrome branch.
     */
    public static boolean useNarrowContextMenu(Context ctx) {
        if (ctx == null) return false;
        return DeviceFeatures.isA5() || isNarrowViewport(ctx) || usePortraitChrome(ctx);
    }
}
