#!/system/bin/sh
# 2026-07-05 — Root HUD tick for rescue hold (last 3s: "Restarting in: n…", then "Restarting").
# Layman: Linux babysitter reads the hold timer and wakes the paint layer near restart.
# Technical: polls sys.solar.rescue.hold_deadline vs /proc/uptime; hud_second 1-3 countdown, -1 firing.
# 2026-07-06 — Singleton + idle backoff — was N copies × setprop every 120ms → input lag storm.

HOLD_DEADLINE_PROP="sys.solar.rescue.hold_deadline"
HUD_SECOND_PROP="sys.solar.rescue.hud_second"
HUD_RESTARTING=-1
TICK_ACTION="com.solar.launcher.action.RESCUE_HOLD_TICK"
HOLD_SVC="com.solar.launcher/.SolarRescueHoldService"
HUD_MAIN="com.solar.launcher.SolarRescueHudMain"
SOLAR_APK="/system/app/com.solar.launcher.apk"
PIDFILE="/data/local/tmp/solar-rescue-hud-watch.pid"
TAG="SolarRescueHudWatch"

# One HUD watch per device — duplicate sh loops were setprop-storming the system (2026-07-06).
if [ -f "$PIDFILE" ]; then
    _old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$_old" ] && kill -0 "$_old" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ >"$PIDFILE"

uptime_ms() {
    awk '{printf "%.0f\n", $1 * 1000}' /proc/uptime 2>/dev/null
}

hud_main_running() {
    for _pf in /proc/[0-9]*/cmdline; do
        tr '\0' ' ' < "$_pf" 2>/dev/null | grep -q 'SolarRescueHudMain' && return 0
    done
    return 1
}

start_hud_main() {
    _apk="$(pm path com.solar.launcher 2>/dev/null | head -1 | sed 's/package://' | tr -d '\r')"
    [ -z "$_apk" ] && _apk="$SOLAR_APK"
    [ -f "$_apk" ] || return 1
    export CLASSPATH="$_apk"
    app_process /system/bin "$HUD_MAIN" &
}

wake_hud_tiers() {
    am startservice -a "$TICK_ACTION" -n "$HOLD_SVC" 2>/dev/null
    if ! hud_main_running; then
        start_hud_main
    fi
}

set_hud_second_if_changed() {
    _want="$1"
    _cur=$(getprop "$HUD_SECOND_PROP" 0)
    [ "$_cur" = "$_want" ] && return 0
    setprop "$HUD_SECOND_PROP" "$_want"
}

log -p i -t "$TAG" "watch started pid=$$"

while true; do
    dl=$(getprop "$HOLD_DEADLINE_PROP" 0)
    # 2026-09-14 — uptime_ms forks awk; only needed while a hold deadline is armed.
    now=""
    sec=0
    rem=0
    if [ -n "$dl" ] && [ "$dl" != "0" ]; then
        now=$(uptime_ms)
    fi
    if [ -n "$dl" ] && [ "$dl" != "0" ] && [ -n "$now" ]; then
        rem=$((dl - now))
        if [ "$rem" -gt 0 ]; then
            sec=$(( (rem + 999) / 1000 ))
        fi
    fi
    if [ "$sec" -ge 1 ] && [ "$sec" -le 3 ]; then
        set_hud_second_if_changed "$sec"
        wake_hud_tiers
        sleep 0.25
    elif [ -n "$dl" ] && [ "$dl" != "0" ] && [ "$rem" -le 0 ]; then
        cur=$(getprop "$HUD_SECOND_PROP" 0)
        if [ "$cur" != "$HUD_RESTARTING" ]; then
            set_hud_second_if_changed "$HUD_RESTARTING"
            setprop sys.solar.rescue.fullscreen 1 2>/dev/null
            wake_hud_tiers
        fi
        sleep 0.25
    elif [ "$(getprop "$HUD_SECOND_PROP" 0)" = "$HUD_RESTARTING" ] && [ "$dl" = "0" ]; then
        set_hud_second_if_changed 0
        sleep 0.5
    elif [ "$(getprop "$HUD_SECOND_PROP" 0)" != "0" ] && [ "$(getprop "$HUD_SECOND_PROP" 0)" != "$HUD_RESTARTING" ]; then
        set_hud_second_if_changed 0
        sleep 0.5
    elif [ -z "$dl" ] || [ "$dl" = "0" ]; then
        set_hud_second_if_changed 0
        # 2026-09-14 — idle: was 1.0 s (getprop+setprop-check every second, forever).
        sleep 2
    else
        sleep 0.5
    fi
done
