#!/system/bin/sh
# 2026-07-06 — Boot watchdog for GlobalOverlayTriggerMain (tier-3 evdev overlay fallback).
# Layman: tiny root babysitter so hold-to-menu and hold-to-rescue work even if Solar crashed.
# Technical: uses /system/app APK classpath; restarts evdev daemon if dead.
# Hold timing: GlobalInputPolicy + Xposed PWM; 10s → solar-rescue-exec.sh; HUD 3-2-1 in last 3s.
# 2026-07-06 — Singleton + hud-watch cmdline detect — was duplicate app_process / setprop storms.

SOLAR_APK="/system/app/com.solar.launcher.apk"
COMPANION_APK="/system/app/SolarGlobalContextModal.apk"
# Phase 2c — companion-owned app_process entry; Solar APK still on classpath for evdev loop.
DAEMON_CLASS="com.solar.launcher.globalcontext.CompanionRootInputDaemon"
DAEMON_MARKER="CompanionRootInputDaemon"
HUD_WATCH="/system/etc/solar/solar-rescue-hud-watch.sh"
RESCUE_EXEC="/system/etc/solar/solar-rescue-exec.sh"
PID_TAG="SolarRescueDaemon"
DAEMON_PIDFILE="/data/local/tmp/solar-rescue-daemon.pid"

if [ -f "$DAEMON_PIDFILE" ]; then
    _old=$(cat "$DAEMON_PIDFILE" 2>/dev/null)
    if [ -n "$_old" ] && kill -0 "$_old" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ >"$DAEMON_PIDFILE"

resolve_companion_apk() {
    _pm="$(pm path com.solar.launcher.globalcontext 2>/dev/null | head -1 | sed 's/package://' | tr -d '\r')"
    if [ -n "$_pm" ] && [ -f "$_pm" ]; then
        echo "$_pm"
        return 0
    fi
    if [ -f "$COMPANION_APK" ]; then
        echo "$COMPANION_APK"
        return 0
    fi
    return 1
}

resolve_solar_apk() {
    _pm="$(pm path com.solar.launcher 2>/dev/null | head -1 | sed 's/package://' | tr -d '\r')"
    if [ -n "$_pm" ] && [ -f "$_pm" ]; then
        echo "$_pm"
        return 0
    fi
    if [ -f "$SOLAR_APK" ]; then
        echo "$SOLAR_APK"
        return 0
    fi
    return 1
}

log -p i -t "$PID_TAG" "starting watchdog pid=$$"

# 2026-09-14 — Process scans: one grep -l over /proc/*/cmdline instead of tr|grep per
# process (the old loops forked ~2× the process count, every pass, in four places).
# grep -l matches across the NUL-separated argv. pids_matching prints one pid per line.
pids_matching() {
    grep -lE "$1" /proc/[0-9]*/cmdline 2>/dev/null | cut -d/ -f3
}
daemon_running() {
    pids_matching 'CompanionRootInputDaemon|GlobalOverlayTriggerMain' | grep -q .
}
hud_watch_running() {
    pids_matching 'solar-rescue-hud-watch|SolarRescueHudMain' | grep -q .
}
prune_extra() {
    _keep=""
    for _pid in $(pids_matching "$1"); do
        if [ -z "$_keep" ]; then
            _keep="$_pid"
        else
            kill "$_pid" 2>/dev/null
        fi
    done
}
prune_extra_daemons() {
    prune_extra 'CompanionRootInputDaemon|GlobalOverlayTriggerMain'
}
prune_extra_hud_watches() {
    prune_extra 'solar-rescue-hud-watch'
}
# 2026-09-14 — Never spawn helpers into an already-thrashing system (see platform daemon).
load_ok() {
    read _l1 _rest < /proc/loadavg
    [ "${_l1%%.*}" -lt 8 ]
}

start_daemon() {
    _comp="$(resolve_companion_apk)" || _comp=""
    _solar="$(resolve_solar_apk)" || {
        log -p e -t "$PID_TAG" "missing Solar APK"
        return 1
    }
    if [ -n "$_comp" ]; then
        export CLASSPATH="$_comp:$_solar"
    else
        export CLASSPATH="$_solar"
        DAEMON_CLASS="com.solar.launcher.GlobalOverlayTriggerMain"
    fi
    app_process /system/bin "$DAEMON_CLASS" &
    log -p i -t "$PID_TAG" "spawned $DAEMON_CLASS comp=${_comp:-none} solar=$_solar"
}

start_hud_watch() {
    if [ -f "$HUD_WATCH" ]; then
        sh "$HUD_WATCH" &
        log -p i -t "$PID_TAG" "spawned hud-watch"
    fi
}

i=0
while [ "$i" -lt 90 ]; do
    resolve_solar_apk >/dev/null && break
    sleep 2
    i=$((i + 1))
done

prune_extra_hud_watches
if ! hud_watch_running; then
    start_hud_watch
fi

while true; do
    prune_extra_daemons
    prune_extra_hud_watches
    if ! daemon_running && load_ok; then
        start_daemon
    fi
    _dc=$(pids_matching 'CompanionRootInputDaemon|GlobalOverlayTriggerMain' | grep -c .)
    if [ "$_dc" -gt 1 ]; then
        log -p w -t "$PID_TAG" "daemon_count=$_dc pruning"
    fi
    if ! hud_watch_running; then
        start_hud_watch
    fi
    # Keepalive only while rescue hold armed — was every 20s fork storm (2026-07-06).
    _hold_dl=$(getprop sys.solar.rescue.hold_deadline 0)
    if [ -n "$_hold_dl" ] && [ "$_hold_dl" != "0" ]; then
        am startservice -a com.solar.launcher.globalcontext.action.RESCUE_HOLD_KEEPALIVE \
            -n com.solar.launcher.globalcontext/.RescueHoldService 2>/dev/null \
            || am startservice -a com.solar.launcher.action.RESCUE_HOLD_KEEPALIVE \
            -n com.solar.launcher/.SolarRescueHoldService 2>/dev/null
        sleep 15
    elif [ "$_dc" -gt 1 ]; then
        sleep 10
    else
        sleep 60
    fi
done
