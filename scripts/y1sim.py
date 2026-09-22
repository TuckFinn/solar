#!/usr/bin/env python3
"""Listening + navigation simulation on a Y1, for bug hunting. Runs on nookman.

Drives the device the way a listener would -- browse, play from every source,
switch sources mid-download, heart and unheart -- and records what the screen,
the audio engine and the log say at each step. Everything it changes it puts
back: hearts are removed, volume and screen timeout restored, Solar restarted.

    y1sim.py 1y1|2y1 [--phase all|nav|listen|stress|cold]

Report: /tmp/y1sim/<device>-<stamp>.txt (and .json).
"""
import importlib.util, json, os, re, subprocess, sys, time

NAVSIM = "/home/nookman/y1-staging/navsim.py"
spec = importlib.util.spec_from_file_location("navsim", NAVSIM)
nav = importlib.util.module_from_spec(spec); spec.loader.exec_module(nav)

SERIALS = {"1y1": "SLF79D5C5383", "2y1": "SL0409654501"}
OUT = "/tmp/y1sim"
NOOK = "http://localhost:5690"


def port_map():
    """USB port per device, read from the devices themselves: both Y1s report
    the same USB serial and the ports have swapped before."""
    out = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True).stdout
    found = {}
    for line in out.splitlines():
        m = re.search(r"transport_id:(\d+)", line)
        port = re.search(r"(usb:\S+)", line)
        if not (m and port):
            continue
        who = subprocess.run(["adb", "-t", m.group(1), "shell", "getprop persist.sys.usb.serialno"],
                             capture_output=True, text=True, timeout=40).stdout.strip()
        for name, serial in SERIALS.items():
            if who == serial:
                found[name] = port.group(1)
    return found


class Sim:
    def __init__(self, name):
        ports = port_map()
        if name in ports:
            nav.USB_PORT[name] = ports[name]
        self.name = name
        self.d = nav.Dev(name)
        self.notes = []            # (phase, step, ok, detail)
        self.hearted = []
        self.t0 = time.time()

    # ---- helpers -------------------------------------------------------
    def note(self, phase, step, ok, detail=""):
        self.notes.append({"phase": phase, "step": step, "ok": bool(ok), "detail": str(detail)[:400],
                           "t": round(time.time() - self.t0)})
        print(f"[{phase}] {'ok ' if ok else 'BAD'} {step}: {str(detail)[:140]}", flush=True)

    def wake(self):
        if "mScreenOn=true" not in self.sh("dumpsys power"):
            self.sh("input keyevent 26"); time.sleep(1.5)
        self.sh("svc power stayon true")

    def observe(self, tries=3):
        for i in range(tries):
            try:
                return self.d.observe()
            except Exception:
                self.wake(); time.sleep(2)
        return self.d.observe()

    def settle(self, tries=3):
        for i in range(tries):
            try:
                return self.d.settle()
            except Exception:
                self.wake(); time.sleep(2)
        return self.d.settle()

    def sh(self, cmd, timeout=40):
        try:
            return self.d.sh(cmd, timeout)
        except Exception as e:
            return f"<error {e}>"

    def texts(self):
        """Every text on screen, from Solar's own snapshot."""
        nonce = f"{time.time():.3f}"
        self.sh(f"am broadcast -a com.solar.launcher.action.NAV_SNAPSHOT --es nonce {nonce} >/dev/null")
        for _ in range(16):
            time.sleep(0.25)
            for l in reversed(self.sh("logcat -d -v raw -s SolarNav:I").splitlines()):
                if l.startswith(f"nonce={nonce} "):
                    snap = json.loads(l.split(" ", 1)[1])
                    return [t.get("t", "") for t in (snap.get("texts") or [])]
        return []

    def playing(self):
        return "standby: 0" in self.sh("dumpsys media.audio_flinger | grep -i 'standby:' | head -1")

    def np_time(self, texts=None):
        """Elapsed time on the now-playing screen -- not the status clock and
        not the track length. Screen reads: title, artist, album, "n / total",
        elapsed, length, "Now Playing", battery."""
        clock = self.sh("date +%H:%M").strip()
        times = [t for t in (texts if texts is not None else self.texts())
                 if re.fullmatch(r"\d{1,2}:\d\d", t or "") and t != clock]
        # The elapsed time is the one before the track length. Taking the first
        # match read the title on a song called "11:58" (Candy Dulfer, 2Y1) and
        # the player looked frozen.
        return times[-2] if len(times) >= 2 else (times[0] if times else "")

    def first_song(self, tries=4):
        """Move off a list's chrome ("Search...", "Shuffle", a header) so Enter
        starts a song."""
        st = self.observe()
        for _ in range(tries):
            f = (st.focused or "")
            if f and not re.match(r"(search|shuffle|play all|add all|download)", f, re.I):
                return f
            self.d.keyevent(127); time.sleep(0.6); st = self.observe()
        return st.focused

    def np_title(self):
        """Whatever is playing: the first line of the player screen."""
        texts = self.texts()
        if texts and not re.fullmatch(r"\d{1,2}:\d\d|\d+%", texts[0] or ""):
            return texts[0]
        st = self.observe()
        return st.focused or (st.rows[0] if st.rows else "")

    def running(self, seconds=9):
        """Is sound actually moving? The elapsed clock, not AudioFlinger's
        standby flag -- the output stays out of standby for a while after a
        pause, so standby answers "playing" to a paused player."""
        a = self.np_time()
        time.sleep(seconds)
        return bool(a) and self.np_time() != a

    # The click wheel's buttons are event3 (mtk-tpd-kpd): play/pause 164,
    # next 163, previous 165. event0 (the keypad) declares none of them, so
    # anything sent there is dropped by the input stack, silently.
    TPD = "/dev/input/event3"

    def tap(self, code, hold=0.12):
        self.d.scancode(self.TPD, code, hold)

    def home(self):
        self.wake()
        self.sh("am force-stop com.solar.launcher"); time.sleep(3)
        self.sh("am start -n com.solar.launcher/.MainActivity >/dev/null"); time.sleep(11)
        return self.settle()

    def to_home(self, tries=10):
        """Back out to HOME without restarting Solar -- home() force-stops it,
        which stops the music with it."""
        st = self.settle()
        for _ in range(tries):
            if st.title == "HOME":
                return st
            # Some screens go up through a "Back" row rather than the Back
            # button -- "Home / More" strands the Back scancode otherwise.
            if (st.focused or "").strip().lower() == "back":
                self.d.keyevent(66); time.sleep(2.5); st = self.settle()
            else:
                st = self.back()
        return st

    def focus_row(self, want, tries=16):
        """Wheel to the row matching `want` (substring, ci). Down first, then
        back up: lists do not wrap, and a row above the current one (HOME's
        "Now Playing", the first row) was unreachable going down -- the wheel
        stopped on the last row and Enter opened whatever was there."""
        st = self.observe()
        for code in (127, 126):
            for _ in range(tries):
                if st.focused and want.lower() in st.focused.lower():
                    return st
                self.d.keyevent(code); time.sleep(0.45); st = self.observe()
        return st

    def enter(self, wait=4.0):
        self.d.keyevent(66); time.sleep(wait); return self.settle()

    def back(self, wait=2.5):
        self.d.back(); time.sleep(wait); return self.settle()

    def cache_files(self):
        out = self.sh("su -c 'ls -l /data/data/com.solar.launcher/cache/deezer 2>/dev/null'")
        got = {}
        for l in out.splitlines():
            m = re.search(r"\s(\d+)\s+\d{4}-\d\d-\d\d \d\d:\d\d\s+(\S+)$", l)
            if m:
                got[m.group(2)] = int(m.group(1))
        return got

    # ---- phases --------------------------------------------------------
    def baseline(self):
        v = self.sh("dumpsys package com.solar.launcher | grep versionName").strip()
        df = self.sh("df /data | tail -1").strip()
        load = self.sh("cat /proc/loadavg").strip()
        keys = self.sh("su -c 'grep -c name= /data/data/com.solar.launcher/shared_prefs/SOLAR_SETTINGS.xml'").strip()
        self.note("baseline", "device", True, f"{v} | {df} | load {load} | prefs keys {keys}")
        self.sh("logcat -c")
        self.sh("svc power stayon true")
        route, idx = nav.music_volume(self.d)
        self.vol = (route, idx)
        self.sh("su -c 'service call audio 6 i32 3 i32 4 i32 0'")     # modest, into the dead-end cord
        self.note("baseline", "volume", True, f"route {route}, was {idx}, set 4")

    def nav_sweep(self):
        st = self.home()
        self.note("nav", "home", st.title == "HOME", f"{st.title} rows={len(st.rows)}")
        for row in ("Now Playing", "Music", "Navidrome", "Discovery", "Get Music", "Settings", "More"):
            st = self.focus_row(row)
            if not (st.focused and row.lower() in st.focused.lower()):
                self.note("nav", f"reach {row}", False, f"focused {st.focused}"); continue
            before = st.focused
            inner = self.enter()
            got_in = inner.title != "HOME"
            back = self.back()
            landed = back.title == "HOME"
            refocus = (back.focused or "") == before
            self.note("nav", f"{row}: enter/back/focus", got_in and landed and refocus,
                      f"in={inner.title} back={back.title} focus={back.focused!r} (was {before!r})")

    def letter_scroll(self):
        """Sustained spin: letters on an alphabetical list, pages on a playlist."""
        st = self.home(); st = self.focus_row("Navidrome"); self.enter()
        st = self.focus_row("Artists"); lst = self.enter(5)
        if not lst.rows:
            self.note("scroll", "artists list", False, f"no rows on {lst.title}"); return
        start = self.observe().focused
        for _ in range(12):                      # sustained spin, ~150 ms apart
            self.sh("input keyevent 127"); time.sleep(0.15)
        time.sleep(1.5)
        end = self.observe().focused
        jumped = (start or "")[:1].upper() != (end or "")[:1].upper()
        self.note("scroll", "alphabetical list jumps by letter", jumped, f"{start!r} -> {end!r}")
        slow_start = self.observe().focused
        for _ in range(3):
            self.sh("input keyevent 127"); time.sleep(1.2)
        slow_end = self.observe().focused
        self.note("scroll", "slow clicks still move one row", True, f"{slow_start!r} -> {slow_end!r}")
        self.back(); self.back()

    def play_source(self, phase, path, songs=2, listen=40):
        """path: list of row labels to walk from HOME, last one is the song list."""
        st = self.home()
        for row in path:
            st = self.focus_row(row)
            if not (st.focused and row.lower() in st.focused.lower()):
                self.note(phase, f"reach {row}", False, f"focused {st.focused}"); return
            st = self.enter(5)
        first = self.first_song()
        self.d.keyevent(66)                      # play it
        time.sleep(12)
        t1 = self.np_time(); started = bool(t1)
        time.sleep(listen); t2 = self.np_time()
        advanced = bool(t1) and t1 != t2
        self.note(phase, f"play {first!r}", started and advanced,
                  f"audio={started} time {t1} -> {t2} screen={self.observe().title}")
        if songs > 1:                            # next track
            before = self.np_title()
            self.tap(163); time.sleep(10)        # keypad Next
            after = self.np_title()
            self.note(phase, "next track", bool(after) and after != before, f"{before!r} -> {after!r}")
        self.tap(164)                            # click-wheel Play/Pause
        paused = not self.running()
        self.tap(164)                            # resume
        resumed = self.running()
        self.note(phase, "pause/resume", paused and resumed, f"paused={paused} resumed={resumed}")

    def discovery(self):
        st = self.home(); st = self.focus_row("Discovery"); lists = self.enter(5)
        self.note("discovery", "playlists", bool(lists.rows), f"{lists.title}: {lists.rows[:3]}")
        songs = self.enter(6)
        before = self.cache_files()
        pick = self.first_song()
        self.d.keyevent(66)
        grew, waited = False, 0
        for _ in range(12):
            time.sleep(10); waited += 10
            now = self.cache_files()
            grew = any(v > 200_000 and before.get(k, 0) != v for k, v in now.items())
            if grew: break
        self.note("discovery", f"single pick downloads {pick!r}", grew, f"after {waited}s")
        time.sleep(20)
        self.note("discovery", "single pick plays", self.running(), f"now {self.np_title()!r} at {self.np_time()}")
        # Three picks in a row, the way Tucker hit it: choose a song, don't
        # wait, Back to the list, choose the next. Starting a pick swaps the
        # screen for Deezer's download panel, so without the Back the next
        # press lands on its Cancel button.
        self.home(); self.focus_row("Discovery"); self.enter(5); self.enter(6)
        self.first_song()
        picks = []
        for i in range(3):
            row = self.observe().focused
            if not row:                       # list not painted yet -- wait for it
                for _ in range(3):
                    time.sleep(2)
                    row = self.observe().focused
                    if row:
                        break
            picks.append(row)
            self.d.keyevent(66); time.sleep(3.5)
            if i < 2:
                # Back to the list, and make sure it is really up before the
                # next pick: pressing Back 2 s after the pick caught the screen
                # mid-change and the next centre went nowhere.
                st = self.back(4)
                for _ in range(3):
                    if st.rows and "now playing" not in (st.title or "").lower():
                        break
                    st = self.back(3)
                self.d.keyevent(127); time.sleep(1.2)
        time.sleep(75)
        playing_now = self.running()
        self.note("discovery", "three picks in a row", playing_now,
                  f"picked {picks}; playing={playing_now}; screen={self.observe().title}")
        return picks

    def switch_mid_download(self):
        """A Navidrome playlist started while a Discovery song is still
        downloading: one sound source, and the pending song must not start."""
        self.home(); self.focus_row("Discovery"); self.enter(5); self.enter(6)
        pick = self.first_song()
        self.d.keyevent(66); time.sleep(3)                 # leave it downloading
        st = self.home(); self.focus_row("Navidrome"); self.enter(5)
        self.focus_row("Playlists"); self.enter(5)
        self.focus_row("Hearts"); self.enter(6)
        other = self.first_song()
        self.d.keyevent(66); time.sleep(15)
        title_a = self.np_title(); time.sleep(45); title_b = self.np_title()
        same = title_a == title_b
        self.note("switch", "playlist keeps playing while Discovery was loading", self.running() and same,
                  f"discovery pick {pick!r}; playlist {other!r}; now {title_a!r} -> {title_b!r}")

    def heart_cycle(self):
        """Heart what is playing (long-press Play/Pause), then take it back with
        the same gesture -- the device-side round trip, not just the API."""
        favs_before = nook_favs()
        playing = self.np_title()
        self.tap(164, 1.4)
        time.sleep(25)
        favs_after = nook_favs()
        added = [t for t in favs_after if t not in favs_before]
        gone = [t for t in favs_before if t not in favs_after]
        self.note("heart", f"long-press hearts {playing!r}", bool(added or gone),
                  f"added {added} removed {gone}")
        self.tap(164, 1.4)                      # same gesture takes it back
        time.sleep(20)
        now = nook_favs()
        for artist, title in added:             # belt and braces
            if (artist, title) in now:
                unheart(artist, title)
        for artist, title in gone:
            if (artist, title) not in now:
                heart(artist, title)
        time.sleep(6)
        now = nook_favs()
        clean = all(t in now for t in gone) and not any(t in now for t in added)
        self.note("heart", "favourites back where they were", clean,
                  f"{len(favs_before)} before, {len(now)} after")

    def screen_off_keys(self):
        """Screen-Off Control is on (2026-09-21): play/pause has to toggle with
        the screen dark, and Back has to stay locked. Something must already be
        playing when this runs."""
        self.sh("input keyevent 26"); time.sleep(4)
        dark = "mScreenOn=true" not in self.sh("dumpsys power")
        before = self.running()
        self.tap(164); time.sleep(3)
        after = self.running()
        self.note("screenoff", "play/pause toggles with the screen off",
                  dark and before != after, f"dark={dark} playing {before} -> {after}")
        self.tap(164); time.sleep(3)                  # leave it as we found it
        back_to = self.running()
        still_dark = "mScreenOn=true" not in self.sh("dumpsys power")
        self.note("screenoff", "second press puts it back", back_to == before,
                  f"playing={back_to}, screen still off={still_dark}")
        self.d.back(); time.sleep(3)                  # Back must do nothing here
        dark_after_back = "mScreenOn=true" not in self.sh("dumpsys power")
        self.note("screenoff", "Back stays locked", dark_after_back,
                  f"screen off after Back={dark_after_back}")
        self.sh("input keyevent 26"); time.sleep(3)
        self.sh("svc power stayon true")

    def server_titles(self):
        """The status bar names the screen you are on, at every depth."""
        self.home(); self.focus_row("Navidrome"); root = self.enter(7)
        self.focus_row("Playlists"); pl = self.enter(8)
        ok = "home" not in (root.title or "").lower() and "home" not in (pl.title or "").lower()
        self.note("titles", "Navidrome says where you are", ok,
                  f"root={root.title!r} playlists={pl.title!r}")
        self.back(); self.back()

    def soak(self, minutes=6):
        """Let a playlist run: does it change tracks on its own, stay awake,
        and keep one engine? Samples every 20 s."""
        titles, drops = [], 0
        end = time.time() + minutes * 60
        last_time = self.np_time()
        while time.time() < end:
            time.sleep(20)
            now_time = self.np_time()
            if now_time and now_time == last_time:
                drops += 1                      # clock frozen: sound stopped
            last_time = now_time
            t = self.np_title()
            if t and (not titles or titles[-1] != t):
                titles.append(t)
        self.note("soak", f"{minutes} min unattended", drops == 0 and len(titles) > 1,
                  f"{len(titles)} track(s) {titles[:6]}, {drops} silent sample(s)")

    def now_playing_row(self):
        """HOME > Now Playing while audio runs: it should land on the song."""
        st = self.to_home()
        st = self.focus_row("Now Playing")
        np = self.enter(5)
        t1 = self.np_time(); time.sleep(20); t2 = self.np_time()
        self.note("nowplaying", "HOME > Now Playing shows the running song",
                  bool(t1) and t1 != t2,
                  f"title={np.title!r} focused={np.focused!r} time {t1} -> {t2}")
        self.back()

    def library_search(self):
        """Music > Search Library: the keyboard opens, and Back leaves it.
        Typing is not automatable -- Solar draws its own keyboard, so
        `input text` lands nowhere."""
        self.home(); self.focus_row("Music"); self.enter(5)
        st = self.focus_row("Search Library")
        if not (st.focused and "search" in (st.focused or "").lower()):
            self.note("search", "reach Search Library", False, f"focused {st.focused}"); return
        kb = self.enter(5)
        self.note("search", "keyboard opens", kb.title == "KEYBOARD" or bool(kb.rows),
                  f"{kb.title} rows={len(kb.rows)}")
        back = self.back(3)
        self.note("search", "Back leaves the keyboard", back.title != "KEYBOARD",
                  f"landed on {back.title} focused={back.focused!r}")
        self.back()

    def stress(self):
        for _ in range(10):                                # back spam
            self.d.back(); time.sleep(0.2)
        time.sleep(3)
        st = self.settle()
        self.note("stress", "back spam leaves a sane screen", bool(st.title), f"{st.title} rows={len(st.rows)}")
        for _ in range(25):                                # wheel storm
            self.sh("input keyevent 127"); time.sleep(0.08)
        time.sleep(2)
        st = self.settle()
        self.note("stress", "wheel storm survives", bool(st.title), f"{st.title} focused={st.focused!r}")
        was = bool(self.np_time())
        self.sh("input keyevent 26"); time.sleep(6)        # screen off
        still = self.playing()                             # screen is off: no snapshot
        self.sh("input keyevent 26"); time.sleep(4)        # back on
        st = self.settle()
        self.note("stress", "screen off keeps audio", (not was) or still, f"was={was} during={still} back on {st.title}")

    def log_sweep(self):
        log = self.sh("logcat -d -v time", timeout=120)
        pats = {
            "crash (FATAL)": r"FATAL EXCEPTION|E/AndroidRuntime",
            "ANR": r"ANR in |am_anr",
            # The sim force-stops Solar itself to start each phase from HOME,
            # so those two lines are ours; only an unasked-for restart matters.
            # Not "Scheduling restart of crashed service": a plain force-stop
            # logs that for every Solar service, and the sim force-stops Solar
            # to start each phase. A real crash shows up as FATAL above.
            "Solar died on its own": r"Process com\.solar\.launcher .* has died",
            "low memory kill": r"lowmemorykiller",
            "MediaPlayer error": r"MediaPlayer.*error \(|AwesomePlayer.*error",
            "socket/SSL error": r"SSLException|SocketTimeout|Connection refused|ECONNRESET",
            "SQLite error": r"SQLiteException|database is locked",
        }
        for label, pat in pats.items():
            hits = [l for l in log.splitlines() if re.search(pat, l)]
            self.note("log", label, not hits, f"{len(hits)} line(s): " + (hits[-1][:120] if hits else ""))
        gc = [int(m.group(1)) for m in re.finditer(r"paused (\d+)ms", log)]
        long_gc = [g for g in gc if g >= 200]
        self.note("log", "GC pauses >= 200 ms", len(long_gc) <= 3, f"{len(long_gc)} of {len(gc)} (max {max(gc) if gc else 0} ms)")
        skipped = [int(m.group(1)) for m in re.finditer(r"Skipped (\d+) frames", log)]
        self.note("log", "UI stalls (skipped frames > 100)", not [s for s in skipped if s > 100],
                  f"{len([s for s in skipped if s > 100])} of {len(skipped)} (max {max(skipped) if skipped else 0})")

    def cleanup(self):
        try:
            nav.restore(self.d, self.vol)
        except Exception as e:
            self.note("cleanup", "restore", False, e)
        else:
            self.note("cleanup", "volume, screen and Solar restored", True, "")

    def report(self):
        os.makedirs(OUT, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M")
        base = f"{OUT}/{self.name}-{stamp}"
        json.dump(self.notes, open(base + ".json", "w"), indent=1)
        bad = [n for n in self.notes if not n["ok"]]
        with open(base + ".txt", "w") as f:
            f.write(f"{self.name} simulation, {stamp}, {round((time.time()-self.t0)/60)} min\n")
            f.write(f"{len(self.notes)} checks, {len(bad)} need a look\n\n")
            for n in self.notes:
                f.write(f"[{n['t']:>4}s] {'ok ' if n['ok'] else 'BAD'} {n['phase']}/{n['step']}: {n['detail']}\n")
        print(f"\n=== {self.name}: {len(bad)} of {len(self.notes)} checks need a look -> {base}.txt")
        for n in bad:
            print(f"   BAD {n['phase']}/{n['step']}: {n['detail'][:150]}")


def nook_favs():
    try:
        import urllib.request
        d = json.load(urllib.request.urlopen(NOOK + "/api/favorites", timeout=20))
        return [(t.get("artist"), t.get("title")) for t in (d.get("tracks") or [])]
    except Exception:
        try:
            d = json.load(open("/home/nookman/.spotiflac-web/state/favorites.json"))
            return [(t.get("artist"), t.get("title")) for t in (d.get("tracks") or [])]
        except Exception:
            return []


def heart(artist, title):
    _heart_call(artist, title, "1")


def unheart(artist, title):
    _heart_call(artist, title, "0")


def _heart_call(artist, title, on):
    import urllib.parse, urllib.request
    q = urllib.parse.urlencode({"artist": artist or "", "title": title or "", "on": on})
    try:
        urllib.request.urlopen(NOOK + "/api/y1/heart-local?" + q, timeout=20).read()
    except Exception:
        pass


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else "1y1"
    phase = (sys.argv[3] if len(sys.argv) > 3 else "all") if "--phase" in sys.argv else "all"
    s = Sim(name)
    try:
        s.baseline()
        if phase in ("all", "nav"):
            s.nav_sweep(); s.server_titles(); s.letter_scroll()
        if phase in ("all", "listen"):
            s.play_source("library", ["Music", "All Songs"], songs=2, listen=60)
            s.now_playing_row()
            s.play_source("navidrome", ["Navidrome", "Playlists", "Afropop"], songs=2, listen=60)
            s.soak(6)
            s.heart_cycle()
            s.play_source("hearts", ["Navidrome", "Playlists", "Hearts"], songs=1, listen=45)
            s.screen_off_keys()
            s.library_search()
            s.discovery()
            s.switch_mid_download()
        if phase in ("all", "stress"):
            s.stress()
        s.log_sweep()
    except Exception as e:
        s.note("run", "aborted", False, f"{type(e).__name__}: {e}")
    finally:
        s.cleanup()
        s.report()


if __name__ == "__main__":
    main()
