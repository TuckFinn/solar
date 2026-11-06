package com.solar.launcher.theme;

import android.content.Context;
import android.graphics.Typeface;

import com.solar.launcher.DeviceFeatures;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

/**
 * Publishes the active Solar theme font to primary storage for the Xposed system-font module.
 * Other apps cannot read Solar prefs or internal MMC cache — they read /.solar/system-font.ttf.
 */
public final class SystemFontBridge {

    /** Hidden dir on primary storage (MicroSD on Y1/Y2 default). */
    public static final String SIDECAR_DIR = ".solar";
    /** Fixed sidecar filename — always .ttf extension even when source is .otf. */
    public static final String SIDECAR_FILE = "system-font.ttf";

    private SystemFontBridge() {}

    /** Sidecar path for the device primary storage root. */
    public static File sidecarFileForRoot(File primaryRoot) {
        if (primaryRoot == null) return new File("/dev/null");
        return new File(new File(primaryRoot, SIDECAR_DIR), SIDECAR_FILE);
    }

    /** Sidecar on this device's primary user volume. */
    public static File sidecarFile(Context ctx) {
        File root = DeviceFeatures.getPrimaryStorageRoot();
        if (root == null && ctx != null) {
            root = DeviceFeatures.getNewMediaRoot(ctx);
        }
        return sidecarFileForRoot(root);
    }

    /** True when Android Skia can load the file (TTF/OTF only — not WOFF). */
    public static boolean isCompatibleFontExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".ttf") || lower.endsWith(".otf");
    }

    public static boolean isCompatibleFontFile(File file) {
        return file != null && file.isFile() && isCompatibleFontExtension(file.getName());
    }

    /** 2026-07-05 — Copy source font to every sidecar root when Skia-compatible. */
    public static void publish(Context ctx) {
        if (ctx == null) return;
        File source = resolveSourceFontFile(ctx);
        if (source == null || !isCompatibleFontFile(source) || !canLoadFont(source)) {
            clearSidecar(ctx);
            return;
        }
        try {
            SidecarPublishHelper.publishFile(ctx, SIDECAR_FILE, source);
        } catch (Exception ignored) {
            clearSidecar(ctx);
        }
    }

    /** Remove sidecar from all roots so the Xposed module falls back to stock fonts. */
    public static void clearSidecar(Context ctx) {
        SidecarPublishHelper.deleteFromAllRoots(ctx, SIDECAR_FILE);
    }

    /** Resolve active theme font file (Y1 config.json or JJ "font" key). */
    public static File resolveSourceFontFile(Context ctx) {
        if (ctx != null) {
            ActiveThemeEngine.init(ctx);
        }
        if (ActiveThemeEngine.isJjMode()) {
            return resolveJjFontFile();
        }
        return resolveY1FontFile(ctx);
    }

    private static File resolveY1FontFile(Context ctx) {
        ThemeManager.ThemeEntry theme = ThemeManager.getCurrentTheme();
        if (theme == null || theme.root == null) return null;
        String fontFile = theme.root.optString("fontFamily", "");
        if (fontFile.isEmpty()) return null;

        File candidate = new File(theme.folderPath, fontFile);
        if (!candidate.isFile() && ctx != null && theme.folderName != null) {
            File mmc = new File(ThemeManager.internalThemesDir(ctx),
                    theme.folderName + "/" + fontFile);
            if (mmc.isFile()) candidate = mmc;
        }
        if (!candidate.isFile() && ctx != null) {
            File onSd = new File(ThemeManager.themesRoot(),
                    ThemeManager.BUILTIN_DEFAULT_FOLDER + "/" + fontFile);
            if (onSd.isFile()) candidate = onSd;
        }
        return candidate.isFile() ? candidate : null;
    }

    private static File resolveJjFontFile() {
        JjThemeManager.ThemeData theme = JjThemeManager.getCurrentTheme();
        if (theme == null || theme.folderPath == null || "default".equals(theme.folderPath)) {
            return null;
        }
        File config = new File(theme.folderPath, "config.json");
        if (!config.isFile()) return null;
        try {
            FileInputStream fis = new FileInputStream(config);
            byte[] data = new byte[(int) config.length()];
            fis.read(data);
            fis.close();
            String jsonStr = new String(data, "UTF-8").replace("\uFEFF", "");
            JSONObject json = new JSONObject(jsonStr);
            if (!json.has("font")) return null;
            File font = new File(theme.folderPath, json.getString("font"));
            return font.isFile() ? font : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Probe Skia load — same fail-open pattern as ThemeManager.getThemeFont. */
    static boolean canLoadFont(File file) {
        if (file == null || !file.isFile()) return false;
        try {
            Typeface tf = Typeface.createFromFile(ThemeManager.stableFontCopy(file));
            return tf != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void copyFile(File source, File dest) throws java.io.IOException {
        SidecarPublishHelper.copyFile(source, dest);
    }
}
