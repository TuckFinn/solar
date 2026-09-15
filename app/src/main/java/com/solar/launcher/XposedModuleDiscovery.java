package com.solar.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-07-05 — Find hook APKs on device via PackageManager xposedmodule meta; track prompt state.
 * Layman: spots every Xposed add-on installed or registered with the framework.
 * Technical: PM GET_META_DATA scan + merge enabled_modules.xml / modules.list via root.
 * Reversal: delete class; MainActivity stops auto-prompting for new modules.
 */
public final class XposedModuleDiscovery {

    /** One package per line — user saw the new-module prompt (Go or Later). */
    public static final String PROMPT_SEEN_FILE = "/data/local/solar/xposed-prompt-seen";

    private static final String META_XPOSED = "xposedmodule";
    private static final String META_DESCRIPTION = "xposeddescription";
    private static final String INSTALLER_PKG = "de.robv.android.xposed.installer";
    private static final Pattern ENABLED_NAME = Pattern.compile("<int\\s+name=\"([^\"]+)\"\\s+value=\"[01]\"\\s*/>");

    private XposedModuleDiscovery() {}

    /** Installed hook packages — PM meta scan merged with Xposed Installer config. */
    public static List<String> listInstalledHookPackages(Context ctx) {
        // #region agent log
        long t0 = System.currentTimeMillis();
        // #endregion
        Set<String> merged = new LinkedHashSet<String>();
        merged.addAll(listPackagesFromModulesListFile());
        if (ctx != null) {
            merged.addAll(listFromPackageManager(ctx));
        }
        merged.addAll(listFromInstallerConfig());
        merged.addAll(listFromRegistryWhenPresent());
        merged.remove(INSTALLER_PKG);
        List<String> out = new ArrayList<String>(merged);
        Collections.sort(out);
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject d = new JSONObject();
            d.put("count", out.size());
            d.put("ms", System.currentTimeMillis() - t0);
            DebugXposedMenuLog.log("XposedModuleDiscovery.listInstalledHookPackages",
                    "discovery done", "H3-H4", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return out;
    }

    /** APK paths listed in modules.list — Xposed Installer module directory (2026-07-05). */
    static Set<String> listPackagesFromModulesListFile() {
        Set<String> out = new LinkedHashSet<String>();
        String raw = RootShell.runCapture("cat " + XposedModuleStore.MODULES_LIST + " 2>/dev/null");
        if (raw == null || raw.isEmpty()) return out;
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String apk = lines[i].trim();
            if (apk.isEmpty() || apk.startsWith("#")) continue;
            String pkg = packageForApkPath(apk);
            if (pkg != null && pkg.length() > 0) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** Required Solar hooks when PM or /system/app bake proves they exist (2026-07-06). */
    static Set<String> listFromRegistryWhenPresent() {
        Set<String> out = new LinkedHashSet<String>();
        List<String> required = XposedModuleRegistry.requiredModulePackages();
        for (int i = 0; i < required.size(); i++) {
            String pkg = required.get(i);
            if (isPackageOrSystemApkPresent(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** Fast presence — pm path first, then baked /system/app APK file. */
    static boolean isPackageOrSystemApkPresent(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String pmPath = RootShell.runCapture("pm path " + pkg + " 2>/dev/null");
        if (pmPath != null && pmPath.contains("package:")) return true;
        String sysApk = XposedModuleRegistry.systemApkPathForPackage(pkg);
        if (sysApk == null) return false;
        String safe = sysApk.replace("'", "'\\''");
        String hit = RootShell.runCapture("test -f '" + safe + "' && echo yes");
        return hit != null && "yes".equals(hit.trim());
    }

    /** PM scan — APKs declaring {@code xposedmodule} meta. */
    static List<String> listFromPackageManager(Context ctx) {
        if (ctx == null) return Collections.emptyList();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo ai = apps.get(i);
            if (ai == null || ai.packageName == null) continue;
            if (INSTALLER_PKG.equals(ai.packageName)) continue;
            if (hasXposedMeta(ai)) {
                out.add(ai.packageName);
            }
        }
        return out;
    }

    /** Packages registered in Xposed Installer prefs / modules.list (root read). */
    static Set<String> listFromInstallerConfig() {
        Set<String> out = new LinkedHashSet<String>();
        String xml = RootShell.runCapture("cat " + XposedModuleStore.ENABLED_PREFS + " 2>/dev/null");
        if (xml != null) {
            Matcher m = ENABLED_NAME.matcher(xml);
            while (m.find()) {
                String pkg = m.group(1);
                if (pkg != null && !pkg.isEmpty() && !INSTALLER_PKG.equals(pkg)) {
                    out.add(pkg);
                }
            }
        }
        String listRaw = RootShell.runCapture("cat " + XposedModuleStore.MODULES_LIST + " 2>/dev/null");
        if (listRaw != null) {
            String[] lines = listRaw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String apk = lines[i].trim();
                if (apk.isEmpty()) continue;
                String pkg = packageForApkPath(apk);
                if (pkg != null && !INSTALLER_PKG.equals(pkg)) {
                    out.add(pkg);
                }
            }
        }
        return out;
    }

    /** Resolve package name from an APK path — registry map first; pm grep is slow on JB. */
    static String packageForApkPath(String apkPath) {
        if (apkPath == null || apkPath.isEmpty()) return null;
        String known = XposedModuleRegistry.packageForSystemApkPath(apkPath);
        if (known != null) return known;
        // #region agent log
        long t0 = System.currentTimeMillis();
        // #endregion
        String safe = apkPath.replace("'", "'\\''");
        String line = RootShell.runCapture(
                "pm list packages -f 2>/dev/null | grep -F '" + safe + "'");
        if (line == null || line.isEmpty()) return null;
        line = line.trim();
        // format: package:/path/to/base.apk
        int colon = line.indexOf(':');
        if (colon <= 0) return null;
        String pkg = line.substring(0, colon);
        if (pkg.startsWith("package=")) {
            pkg = pkg.substring("package=".length());
        }
        // #region agent log
        if (com.solar.launcher.debug.DebugGate.ON) {
        try {
            JSONObject d = new JSONObject();
            d.put("apk", apkPath.length() > 80 ? apkPath.substring(apkPath.length() - 80) : apkPath);
            d.put("pkg", pkg);
            d.put("ms", System.currentTimeMillis() - t0);
            DebugXposedMenuLog.log("XposedModuleDiscovery.packageForApkPath",
                    "pm grep", "H3", d);
        } catch (Exception ignored) {}
        }
        // #endregion
        return pkg.length() > 0 ? pkg : null;
    }

    /** True when APK manifest includes xposedmodule meta (JB/KK PackageManager). */
    static boolean hasXposedMeta(ApplicationInfo ai) {
        return ai != null && ai.metaData != null && ai.metaData.containsKey(META_XPOSED);
    }

    /** Human label for Debug rows — falls back to package name. */
    public static CharSequence labelForPackage(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return pkg != null ? pkg : "";
        try {
            PackageManager pm = ctx.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (label != null && label.length() > 0) return label;
        } catch (Throwable ignored) {}
        return pkg;
    }

    /** Module description from xposeddescription manifest meta — empty when absent. */
    public static CharSequence descriptionForPackage(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return "";
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            if (ai.metaData != null && ai.metaData.containsKey(META_DESCRIPTION)) {
                Object v = ai.metaData.get(META_DESCRIPTION);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (s.length() > 0) return s;
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** Hook APK present but prefs value=0 — candidate for enable prompt. */
    public static List<String> listDisabledInstalled(Context ctx) {
        List<String> installed = listInstalledHookPackages(ctx);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < installed.size(); i++) {
            String pkg = installed.get(i);
            if (!XposedModuleStore.isModuleEnabled(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** Disabled hooks we have not shown the new-module prompt for yet. */
    public static List<String> findUnpromptedDisabled(Context ctx) {
        Set<String> seen = readPromptSeenPackages();
        List<String> disabled = listDisabledInstalled(ctx);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < disabled.size(); i++) {
            String pkg = disabled.get(i);
            if (XposedModuleRegistry.isForceDisabledLabPackage(pkg)) continue;
            if (!seen.contains(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** Required Solar hooks the user turned off (in override file or prefs off). */
    public static List<String> findDisabledRequiredSolar(Context ctx) {
        List<String> out = new ArrayList<String>();
        List<String> installed = listInstalledHookPackages(ctx);
        for (int i = 0; i < installed.size(); i++) {
            String pkg = installed.get(i);
            if (!XposedModuleCatalog.isRequiredProductionPackage(pkg)) continue;
            if (!XposedModuleStore.isModuleEnabled(pkg)
                    || XposedModuleStore.isUserDisabled(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** Parse prompt-seen file — same rules as user-disabled parser. */
    public static Set<String> readPromptSeenPackages() {
        String raw = RootShell.runCapture("cat " + PROMPT_SEEN_FILE + " 2>/dev/null");
        return new LinkedHashSet<String>(XposedModuleStore.parseUserDisabledLines(raw));
    }

    /** Mark packages as prompted so we do not nag until a new APK appears. */
    public static boolean markPromptSeen(Iterable<String> packages) {
        if (packages == null || !RootShell.canRun()) return false;
        Set<String> merged = readPromptSeenPackages();
        for (String pkg : packages) {
            if (pkg != null && pkg.length() > 0) merged.add(pkg);
        }
        return writePromptSeenPackages(merged);
    }

    /** Persist seen list under /data/local/solar (shell-readable, matches Xposed override pattern). */
    static boolean writePromptSeenPackages(Set<String> packages) {
        if (!RootShell.canRun()) return false;
        StringBuilder sb = new StringBuilder();
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty()) continue;
                sb.append(pkg).append('\n');
            }
        }
        String body = sb.toString();
        String sh = ""
                + "mkdir -p /data/local/solar; "
                + "printf '%s' '" + body.replace("'", "'\\''") + "' > " + PROMPT_SEEN_FILE + "; "
                + "chmod 644 " + PROMPT_SEEN_FILE;
        return RootShell.run(sh);
    }
}
