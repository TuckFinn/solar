package com.solar.launcher.theme;

import com.solar.launcher.PrefsWriteAudit;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-07-05 — Maps theme solarConfig keys ({enable|disable|set}Label_With_Underscores) to prefs.
 * Unknown labels silently skip — add put("human label", "pref_key") when introducing new theme setting.
 * Called by ThemeManager on theme apply; updates SharedPreferences for the rest of the app.
 * When changing: document new key in theme config.json example; respect skipLcd3dFromTheme guard.
 * Reversal: remove mapping row; theme override for that label stops applying.
 */
public class SettingLookup {
    /** Maps normalised English label → SharedPreferences key string. */
    private static final Map<String, String> LABEL_TO_PREF_KEY = new HashMap<>();

    static {
        // 2026-07-05 — Map theme config labels to actual pref keys used in MainActivity.
        // Labels are normalised (lowercase, underscores→spaces, parentheses stripped).
        put("full width menus", "full_width_menus");
        put("infinite scroll", "infinite_scroll");
        put("menu transitions", "menu_transitions");
        put("auto shut down", "inactivity_shutdown_minutes");
        put("turn off wi-fi when idle", "wifi_sleep_power_off");
        put("lcd album art", "now_playing_lcd_art");
        put("3d album art", "now_playing_3d_album_art");
        put("artwork perspective", "artwork_perspective");
        put("match now playing to theme", "now_playing_match_font");
        put("now playing backdrop", "now_playing_backdrop");
        put("status bar match font", "status_bar_match_font");
        put("usb auto connect", "usb_auto_connect");
        put("skip plug-in prompt", "usb_suppress_connect_prompt");
        put("auto-connect", "usb_auto_connect");
        // 2026-07-11 — Label form for enable/disable/setMenu_Item_Padding.
        // 2026-07-18 — Pref id is sentinel only; ThemeManager reads solarConfig at runtime (no sticky write).
        put("menu item padding", "menu_item_padding");
        // 2026-07-11 — Home right-pane NP title/artist; also solarConfig.settingsShow_Now_Playing_Info.
        put("show now playing info", "show_now_playing_info");
        // 2026-07-11 — A5 portrait theme flag (runtime also forces full-width + no masks).
        put("a5 portrait mode", "a5_portrait_mode");
        put("a5 portrait", "a5_portrait_mode");
    }

    private static void put(String label, String prefKey) {
        LABEL_TO_PREF_KEY.put(normalize(label), prefKey);
    }

    /**
     * Normalises a label by trimming, converting to lower case and discarding any text
     * after the first opening parenthesis "(".
     */
    static String normalize(String label) {
        if (label == null) return "";
        int parenIdx = label.indexOf('(');
        if (parenIdx != -1) {
            label = label.substring(0, parenIdx);
        }
        return label.trim().toLowerCase(Locale.ROOT);
    }

    /** Returns the SharedPreferences key for a given human-readable label, or null if unknown. */
    public static String prefKeyForLabel(String label) {
        return LABEL_TO_PREF_KEY.get(normalize(label));
    }

    /** Returns the original label for a given prefKey, or null if not found. */
    public static String labelForPrefKey(String prefKey) {
        if (prefKey == null) return null;
        for (Map.Entry<String, String> e : LABEL_TO_PREF_KEY.entrySet()) {
            if (e.getValue().equals(prefKey)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Returns a correctly formatted solarConfig key (e.g. enableFull_Width_Menus) for a given preference. */
    public static String getSolarConfigKeyForPref(String prefKey, Object value) {
        String label = labelForPrefKey(prefKey);
        if (label == null) return null;
        
        String formattedLabel = formatLabelForConfig(label);
        
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "enable" + formattedLabel : "disable" + formattedLabel;
        } else {
            return "set" + formattedLabel;
        }
    }
    
    /** Returns a list of all possible prefixes for a given prefKey to allow removal of contradictory keys. */
    public static java.util.List<String> getAllSolarConfigKeysForPref(String prefKey) {
        String label = labelForPrefKey(prefKey);
        if (label == null) return java.util.Collections.emptyList();
        
        String formattedLabel = formatLabelForConfig(label);
        
        java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add("enable" + formattedLabel);
        keys.add("disable" + formattedLabel);
        keys.add("set" + formattedLabel);
        return keys;
    }
    
    private static String formatLabelForConfig(String label) {
        String[] words = label.split(" ");
        StringBuilder formattedLabel = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            // Handle special case for 3D to match typical casing, though parser is case-insensitive
            if (word.equals("3d")) {
                formattedLabel.append("3D");
            } else {
                formattedLabel.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    formattedLabel.append(word.substring(1));
                }
            }
            formattedLabel.append("_");
        }
        if (formattedLabel.length() > 0) {
            formattedLabel.setLength(formattedLabel.length() - 1);
        }
        return formattedLabel.toString();
    }

    /**
     * Apply overrides defined in a theme's "solarConfig" JSON object.
     *
     * @param ctx       application context
     * @param overrides map of raw solarConfig keys (e.g. "enableFull_Width_Menus") to values
     */
    public static void applySolarConfigOverrides(Context ctx, Map<String, Object> overrides) {
        if (ctx == null || overrides == null || overrides.isEmpty()) return;
        // 2026-07-11 — Must match MainActivity / ActiveThemeEngine (was package_preferences → no-op).
        SharedPreferences prefs = PrefsWriteAudit.get(ctx, "SOLAR_SETTINGS");
        SharedPreferences.Editor editor = prefs.edit();
        boolean anyChange = false;
        // ponytail: if theme enables both LCD and 3D album art, skip both — user pref wins.
        boolean themeEnablesLcd = false;
        boolean themeEnables3d = false;
        for (String rawKey : overrides.keySet()) {
            String remainder = rawKey;
            if (rawKey.length() > 6 && rawKey.regionMatches(true, 0, "enable", 0, 6)) {
                remainder = rawKey.substring(6);
            } else if (rawKey.length() > 7 && rawKey.regionMatches(true, 0, "disable", 0, 7)) {
                remainder = rawKey.substring(7);
            } else if (rawKey.length() > 3 && rawKey.regionMatches(true, 0, "set", 0, 3)) {
                remainder = rawKey.substring(3);
            }
            String label = remainder.replace('_', ' ');
            String prefKey = prefKeyForLabel(label);
            if ("now_playing_lcd_art".equals(prefKey)) themeEnablesLcd = true;
            if ("now_playing_3d_album_art".equals(prefKey)) themeEnables3d = true;
            if ("artwork_perspective".equals(prefKey)) themeEnables3d = true;
        }
        final boolean skipLcd3dFromTheme = themeEnablesLcd && themeEnables3d;
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            String rawKey = e.getKey();
            // Determine operation prefix: enable / disable / set
            String operation = "set";
            String remainder = rawKey;
            if (rawKey.length() > 6 && rawKey.regionMatches(true, 0, "enable", 0, 6)) {
                operation = "enable";
                remainder = rawKey.substring(6);
            } else if (rawKey.length() > 7 && rawKey.regionMatches(true, 0, "disable", 0, 7)) {
                operation = "disable";
                remainder = rawKey.substring(7);
            } else if (rawKey.length() > 3 && rawKey.regionMatches(true, 0, "set", 0, 3)) {
                operation = "set";
                remainder = rawKey.substring(3);
            }
            // Convert underscores to spaces for lookup
            String label = remainder.replace('_', ' ');
            String prefKey = prefKeyForLabel(label);
            if (prefKey == null) continue; // Unknown setting — skip
            // 2026-07-18 — Menu Item Padding: never sticky-write. ThemeManager.themeDefaultMenuItemPadding
            // reads enable/disable/set + settingsMenu_Item_Padding at runtime so theme changes revert.
            // Layman: padding follows the theme file, not an old on/off left in settings storage.
            // Reversal: remove this continue; write menu_item_padding global again (broken across themes).
            if ("menu_item_padding".equals(prefKey)) {
                continue;
            }
            if (skipLcd3dFromTheme
                    && ("now_playing_lcd_art".equals(prefKey)
                    || "now_playing_3d_album_art".equals(prefKey)
                    || "artwork_perspective".equals(prefKey))) {
                continue;
            }

            if ("artwork_perspective".equals(prefKey)) {
                Object v = e.getValue();
                if ("enable".equals(operation)) {
                    editor.putBoolean("now_playing_3d_album_art", true);
                    editor.putString(prefKey, "3D");
                    anyChange = true;
                } else if ("disable".equals(operation)) {
                    editor.putBoolean("now_playing_3d_album_art", false);
                    editor.putString(prefKey, "Flat");
                    anyChange = true;
                } else if (v instanceof String) {
                    String s = ((String) v).trim();
                    boolean is3d = "3D".equalsIgnoreCase(s);
                    editor.putBoolean("now_playing_3d_album_art", is3d);
                    editor.putString(prefKey, is3d ? "3D" : "Flat");
                    anyChange = true;
                } else if (v instanceof Boolean) {
                    boolean is3d = (Boolean) v;
                    editor.putBoolean("now_playing_3d_album_art", is3d);
                    editor.putString(prefKey, is3d ? "3D" : "Flat");
                    anyChange = true;
                }
                continue;
            }

            boolean value;
            if ("enable".equals(operation)) {
                value = true;
            } else if ("disable".equals(operation)) {
                value = false;
            } else {
                // "set" — boolean or on/off/true/false strings
                Object v = e.getValue();
                if (v instanceof Boolean) {
                    value = (Boolean) v;
                } else if (v instanceof String) {
                    String s = ((String) v).trim();
                    if ("true".equalsIgnoreCase(s) || "1".equals(s)
                            || "on".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)) {
                        value = true;
                    } else if ("false".equalsIgnoreCase(s) || "0".equals(s)
                            || "off".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s)) {
                        value = false;
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }
            editor.putBoolean(prefKey, value);
            anyChange = true;
        }
        if (anyChange) editor.apply();
    }
}
