#!/system/bin/sh
# 2026-07-06 — Supervises helper enforcer, companion overlay daemons, and rescue evdev tier.
# Layman: keeps hold-to-menu and launcher enforcement alive even when Solar is not foreground.
# Technical: hold timing lives in GlobalInputPolicy + Xposed; this script only babysits processes.
# Reversal: remove from 99SolarInit.sh; apply-preferred-home-boot starts services directly.

RESCUE_DAEMON="/system/etc/solar/solar-rescue-daemon.sh"
LAUNCHER_EXEC="/system/etc/solar/solar-launcher-exec.sh"
HELPER_ENFORCER="com.solar.launcher.homehelper/.LauncherEnforcerService"
COORDINATOR="com.solar.launcher.globalcontext/.GlobalInputCoordinatorService"
PIDFILE="/data/local/tmp/solar-platform-daemon.pid"
TAG="SolarPlatformDaemon"

if [ -f "$PIDFILE" ]; then
    _old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$_old" ] && kill -0 "$_old" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ >"$PIDFILE"

log -p i -t "$TAG" "platform supervisor pid=$$"

start_rescue_daemon() {
    if [ -f "$RESCUE_DAEMON" ]; then
        sh "$RESCUE_DAEMON" &
    fi
}

# 2026-09-14 — One grep over /proc/*/cmdline instead of tr|grep per process (~2 forks ×
# every process, every loop). grep -l matches across the NUL-separated argv just fine.
rescue_running() {
    # 2026-09-15 — bracketed so grep does not match its own argv; unbracketed it
    # always reported the rescue daemon alive, so a dead one was never restarted.
    grep -l '[s]olar-rescue-daemon' /proc/[0-9]*/cmdline 2>/dev/null | grep -q .
}
# 2026-09-14 — Do not poke services while the system is already struggling: each
# `am startservice` is a fresh app_process VM (~15 MB, seconds of CPU on an MT6572) and
# they were piling up 20+ deep behind a starved system_server. Pure-shell load read.
load_ok() {
    read _l1 _rest < /proc/loadavg
    [ "${_l1%%.*}" -lt 8 ]
}

start_rescue_daemon

while true; do
    if load_ok; then
        am startservice -n "$HELPER_ENFORCER" 2>/dev/null
        am startservice -n "$COORDINATOR" 2>/dev/null
        if ! rescue_running; then
            start_rescue_daemon
        fi
    else
        log -p w -t "$TAG" "load high ($(cut -d' ' -f1 /proc/loadavg)) — skipping service pokes"
    fi
    # Was: 30 s. The services are sticky once started; 60 s is plenty for re-assertion.
    sleep 60
done
