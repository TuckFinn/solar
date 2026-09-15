#!/system/bin/sh
# 2026-07-06 — One-shot Solar rescue: fullscreen "Restarting", HOME→Solar, disable rivals, OS reboot.
# Layman: hold Back/Power 10s — Linux takes over even if Android UI is stuck; screen shows Restarting through reboot.
# Technical: root shell; solar-launcher-exec switch solar; pm-disable HOME competitors; sync reboot.
# Reversal: restore cold-start MainActivity only (no reboot) if full restart is too disruptive.

FG="${1:-}"

SOLAR_PKG="com.solar.launcher"
HELPER_PKG="com.solar.launcher.homehelper"
ROCKBOX_PKG="org.rockbox"
JJ_PKG="com.themoon.y1"
# 2026-09-14 — Solar-only ROMs ship neither Rockbox nor JJ. `pm disable` on a package
# that is not registered crashes the pm helper VM ("FATAL EXCEPTION IN SYSTEM PROCESS:
# Unknown package: com.themoon.y1"); MTK's exception handler then spawns an
# EXCEPTION_HAPPEND broadcast helper for each one — seen stacked 3 deep on 2Y1. Every
# pm enable/disable below is now gated on `pm path` (registered) first.
LAUNCHER_EXEC="/system/etc/solar/solar-launcher-exec.sh"
SET_HOME_ACTION="com.solar.launcher.action.SET_PREFERRED_HOME"
HOME_RECEIVER="com.solar.launcher/.LauncherHomeReceiver"
APPLY_HOME_ACTION="com.solar.launcher.homehelper.action.APPLY_HOME_TARGET"
HELPER_RECEIVER="com.solar.launcher.homehelper/.LauncherSwitchReceiver"
HUD_MAIN="com.solar.launcher.SolarRescueHudMain"
SOLAR_APK="/system/app/com.solar.launcher.apk"
PROP_FULLSCREEN="sys.solar.rescue.fullscreen"
REBOOT_HOLD_SEC=2

setprop sys.solar.ime.active 0 2>/dev/null
setprop sys.solar.ime.ui 0 2>/dev/null
setprop sys.solar.overlay.active 0 2>/dev/null
# 2026-07-08 — Ghost WM menu flag; clear so post-rescue BACK is normal.
setprop sys.solar.overlay.shell_visible 0 2>/dev/null
setprop sys.solar.handoff.active 0 2>/dev/null
setprop sys.solar.rescue.hold_deadline 0 2>/dev/null
setprop sys.solar.rescue.hold_kind "" 2>/dev/null

# 2026-07-06 — Paint fullscreen Restarting first — display keeps last frame through forced reboot.
paint_restarting_fullscreen() {
    setprop sys.solar.rescue.hud_second -1 2>/dev/null
    setprop "$PROP_FULLSCREEN" 1 2>/dev/null
    _apk="$(pm path "$SOLAR_PKG" 2>/dev/null | head -1 | sed 's/package://' | tr -d '\r')"
    [ -z "$_apk" ] && _apk="$SOLAR_APK"
    if [ -f "$_apk" ]; then
        export CLASSPATH="$_apk"
        app_process /system/bin "$HUD_MAIN" >/dev/null 2>&1 &
        sleep 0.35
    fi
}

is_allowed() {
    case "$1" in
        ""|android|"$SOLAR_PKG") return 0 ;;
        com.android.systemui*|com.android.phone*|com.android.bluetooth*) return 0 ;;
        com.solar.launcher.xposed.*|com.mediatek.FMRadio|com.innioasis.*) return 0 ;;
        com.android.keyguard*|com.android.inputmethod*) return 0 ;;
    esac
    return 1
}

# 2026-07-06 — disable_extra_home_launchers scans pm; solar-launcher-exec switch solar is canonical.
disable_extra_home_launchers() {
    for _pkg in $(pm list packages 2>/dev/null | sed 's/package://'); do
        case "$_pkg" in
            "$SOLAR_PKG"|"$HELPER_PKG"|com.android.launcher|com.android.launcher2|com.android.launcher3)
                continue ;;
        esac
        if dumpsys package "$_pkg" 2>/dev/null | grep -q "android.intent.category.HOME"; then
            pm path "$_pkg" >/dev/null 2>&1 && pm disable "$_pkg" 2>/dev/null
            am force-stop "$_pkg" 2>/dev/null
        fi
    done
}

log -p i -t SolarRescue "exec start fg=${FG:-none}"

paint_restarting_fullscreen

am startservice -a com.solar.launcher.action.DISMISS_OVERLAY \
    -n com.solar.launcher/.SolarOverlayService 2>/dev/null
am startservice -a com.solar.launcher.action.IME_DISMISS \
    -n com.solar.launcher/.SolarInputMethodService 2>/dev/null

if [ -n "$FG" ] && ! is_allowed "$FG" \
        && [ "$FG" != "$ROCKBOX_PKG" ] && [ "$FG" != "$JJ_PKG" ]; then
    am force-stop "$FG" 2>/dev/null
fi

if [ -f "$LAUNCHER_EXEC" ]; then
    sh "$LAUNCHER_EXEC" switch solar 2>/dev/null
else
    am force-stop "$ROCKBOX_PKG" 2>/dev/null
    pm path "$ROCKBOX_PKG" >/dev/null 2>&1 && pm disable "$ROCKBOX_PKG" 2>/dev/null
    am force-stop "$JJ_PKG" 2>/dev/null
    pm path "$JJ_PKG" >/dev/null 2>&1 && pm disable "$JJ_PKG" 2>/dev/null
    pm enable "$SOLAR_PKG" 2>/dev/null
    pm enable "$HELPER_PKG" 2>/dev/null
    setprop persist.solar.home.target solar 2>/dev/null
    am broadcast -a "$SET_HOME_ACTION" -n "$HOME_RECEIVER" --es target solar 2>/dev/null
fi

am broadcast -a "$APPLY_HOME_ACTION" -n "$HELPER_RECEIVER" \
    --es target solar --es source rescue_exec 2>/dev/null

disable_extra_home_launchers

sleep "$REBOOT_HOLD_SEC"
sync
log -p i -t SolarRescue "reboot after rescue fg=${FG:-none}"
reboot
