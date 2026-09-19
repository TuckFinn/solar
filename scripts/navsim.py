#!/usr/bin/env python3
"""Click-through navigation simulation for Solar on a Y1. Runs on nookman.

For every step it tests, it stands on a list, enters one row, presses Back
once, and checks two things:

  1. one step only -- Back lands on the screen the row was entered from;
  2. focus returns -- the row that was entered is the one focused again.

The screen is read from Solar's own NAV_SNAPSHOT broadcast: uiautomator on
Android 4.2 cannot serialise the library's emoji icons. Nothing is ever pressed on a row
that could change a setting or data: see UNSAFE, SETTINGS_OK and the
toggle check. Audio is muted and the screen held on for the run; both are
put back afterwards, and Solar is restarted so nothing keeps playing.

  navsim.py --plan            list the steps, touch nothing
  navsim.py --check           read the device and the current screen only
  navsim.py [--device 1y1]    run it; report in /tmp/navsim/
"""
import argparse, datetime, json, os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

USB_PORT = {"1y1": "usb:1-1.1", "2y1": "usb:1-1.2"}
TCP = {"1y1": "100.107.242.104:5555", "2y1": "100.85.5.19:5555"}
SERIAL = {"1y1": "SLF79D5C5383", "2y1": "SL0409654501"}
OUT = "/tmp/navsim"

UNSAFE = re.compile(
    r"reset|delete|remove|clear|wipe|format|factory|update|install|upgrade|"
    r"shut ?down|power off|reboot|restart|sign ?out|log ?out|forget|unpair|"
    r"disconnect|download|save|add to|favou?rite|heart(?!s)|stems?\b|lalal|"
    r"new local playlist|playback queue|turn on|usb|keyboard|search|scan|"
    r"timer|sleep|alarm|flow|wi-?fi|bluetooth|theme|language|brightness|"
    r"\bapps?\b|eject|sync|transfer|shuffle|repeat", re.I)
# Settings: enter a category and come straight back; never a row inside.
SETTINGS_OK = ["Playback", "Device", "Connections", "Library", "Appearance",
               "Media", "Reach", "Power"]
STATE_WORDS = re.compile(r"^(on|off|✓|✗|auto|none|\d+(\.\d+)?\s*(%|s|min|ms)?)$", re.I)


def plan():
    """(parent path, row to enter, how deep to follow) -- a row given as
    "#N" means the Nth row of that list, whatever it is called."""
    steps = []
    for r in ["Music", "Navidrome", "Discovery", "Get Music", "Settings", "More", "Now Playing"]:
        steps.append(([], r))
    for r in ["Artists", "Albums", "Folders", "Recently Added", "Genres", "Years",
              "Favorites", "Playlists", "All Songs"]:
        steps.append((["Music"], r))
    steps += [(["Music", "Artists"], "#0"), (["Music", "Artists"], "#1"),
              (["Music", "Artists", "#0"], "#0"),
              (["Music", "Artists", "#0", "#0"], "#0"),          # a song: Now Playing
              (["Music", "Albums"], "#0"),
              (["Music", "Albums", "#0"], "#0"),
              (["Music", "Playlists"], "^Hearts"),
              (["Music", "Playlists", "^Hearts"], "#0")]
    steps += [(["Discovery"], "Ska"), (["Discovery"], "Motown"), (["Discovery"], "Algeria"),
              (["Discovery", "Ska"], "#1"),                         # a song
              (["Navidrome"], "#0"), (["Navidrome"], "#1"), (["Navidrome", "#0"], "#0"),
              (["Get Music"], "#0")]
    steps += [(["Settings"], c) for c in SETTINGS_OK]
    steps += [(["More"], "#0"), (["More"], "#1"), (["More"], "#2")]
    return steps


def label(raw):
    """A row's words, without the icon glyphs some rows start with."""
    return re.sub(r"^[\W_]+", "", raw, flags=re.UNICODE).strip()


class State:
    """One reading of the screen, from Solar's own NAV_SNAPSHOT (uiautomator
    cannot serialise the library's emoji icons on Android 4.2)."""

    def __init__(self, snap, resumed=""):
        self.resumed = resumed
        self.overlay = bool(snap.get("overlay"))
        self.screen = snap.get("screen")
        texts = snap.get("texts") or []
        if "title" in snap:
            # Solar's own status-bar text: the title, or the clock on Home and keyboards.
            title = snap.get("title") or ""
        else:
            # Older builds: the status-bar text, not a scrolled row half under it.
            boxes = [r for r in snap.get("rows") or []]
            def in_row(t):
                return any(r["y"] <= t["y"] < r["y"] + r["h"] for r in boxes)
            title = next((t["t"] for t in texts
                          if t["y"] < 40 and t["x"] < 100 and not in_row(t)), "")
        if self.screen == 1:
            self.title = "HOME"
        elif self.screen == 7:
            self.title = "KEYBOARD"
        else:
            self.title = "HOME" if re.fullmatch(r"\d{1,2}:\d\d( [AP]M)?", title) else title
        self.rows, self.focused, self.row_state = [], None, {}
        for r in snap.get("rows") or []:
            raw = (r.get("t") or "").strip()
            if not raw or raw.startswith("←") or r["y"] < 40 or r["x"] > 60:
                continue
            lab = label(raw)
            self.rows.append(lab)
            if r.get("focused"):
                self.focused = lab
            mid = r["y"] + r["h"] / 2
            side = [t["t"] for t in texts if 150 <= t["x"] < 260 and abs(t["y"] + t["h"] / 2 - mid) < 12]
            if side:
                self.row_state[lab] = side[0]

    @property
    def foreign(self):
        return bool(self.resumed) and "com.solar.launcher" not in self.resumed

    def key(self):
        return (self.title, tuple(self.rows))

    def short(self):
        return {"title": self.title, "focused": self.focused, "rows": self.rows[:6]}


class Dev:
    def __init__(self, name):
        self.name = name
        out = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True).stdout
        tid = next((re.search(r"transport_id:(\d+)", l).group(1) for l in out.splitlines()
                    if USB_PORT[name] in l and "device " in l), None)
        self.sel = ["-t", tid] if tid else ["-s", TCP[name]]
        if not tid:
            subprocess.run(["adb", "connect", TCP[name]], capture_output=True, timeout=20)
        who = self.sh("getprop persist.sys.usb.serialno").strip()
        if who != SERIAL[name]:
            sys.exit(f"{name}: expected serial {SERIAL[name]}, device says {who!r}")

    def sh(self, cmd, timeout=40):
        # Android logs Java strings with surrogate pairs as separate 3-byte
        # sequences (CESU-8), which strict UTF-8 refuses: emoji become U+FFFD.
        r = subprocess.run(["adb"] + self.sel + ["shell", cmd], capture_output=True,
                           timeout=timeout, stdin=subprocess.DEVNULL)
        return r.stdout.decode("utf-8", "replace").replace("\r", "")

    def keyevent(self, code, n=1):
        for _ in range(n):
            self.sh(f"input keyevent {code}")
            time.sleep(0.6)

    def scancode(self, dev, code, hold):
        self.sh(f"su -c 'sendevent {dev} 1 {code} 1; sendevent {dev} 0 0 0; sleep {hold}; "
                f"sendevent {dev} 1 {code} 0; sendevent {dev} 0 0 0'")

    def back(self):
        self.scancode("/dev/input/event0", 158, 0.15)

    def screen_on(self):
        return "mScreenOn=true" in self.sh("dumpsys power")

    def observe(self):
        nonce = f"{time.time():.3f}"
        self.sh(f"am broadcast -a com.solar.launcher.action.NAV_SNAPSHOT --es nonce {nonce} >/dev/null")
        for _ in range(16):
            time.sleep(0.25)
            tag = f"nonce={nonce} "
            for l in reversed(self.sh("logcat -d -v raw -s SolarNav:I").splitlines()):
                if l.startswith(tag):
                    m = re.search(r"mResumedActivity: \S+ \S+ (\S+)", self.sh("dumpsys activity activities"))
                    return State(json.loads(l[len(tag):]), m.group(1) if m else "")
        raise RuntimeError("no snapshot from Solar")

    def settle(self, first=2.0, limit=20.0):
        """Observe until two readings agree, or the limit passes."""
        time.sleep(first)
        a = self.observe()
        t0 = time.time()
        while time.time() - t0 < limit:
            if a.foreign:
                return a
            time.sleep(1.5)
            b = self.observe()
            if b.key() == a.key() and b.focused == a.focused and not any("Loading" in r for r in b.rows):
                return b
            a = b
        return a

    def shot(self, path):
        self.sh("su -c 'screencap -p /data/local/tmp/navsim.png'")
        subprocess.run(["adb"] + self.sel + ["pull", "/data/local/tmp/navsim.png", path],
                       capture_output=True, timeout=40)


class Sim:
    def __init__(self, dev):
        self.d = dev
        self.results = []
        self.titles = {}          # path tuple -> title seen there
        self.restarts = 0

    # --- moving around -------------------------------------------------
    def home(self):
        """Back to the home menu: Back a few times; then the long-press
        overlay, which opens on its Home tile; then a restart of Solar, which
        always comes up on the home menu."""
        s = self.d.observe()
        for _ in range(4):
            if s.title == "HOME" and not s.overlay:
                return s
            self.d.back()
            s2 = self.d.settle(1.5, 6)
            if s2.key() == s.key():
                break                               # Back does nothing here
            s = s2
        if s.title != "HOME" and s.title != "Now Playing":
            self.d.scancode("/dev/input/event0", 158, 1.2)
            time.sleep(1.5)
            self.d.keyevent(23)
            s = self.d.settle(1.5, 8)
        if s.title != "HOME" or s.overlay:
            self.d.sh("am force-stop com.solar.launcher"); time.sleep(3)
            self.d.sh("am start -n com.solar.launcher/.MainActivity"); time.sleep(8)
            s = self.d.settle(1.5, 10)
            self.restarts += 1
        if s.title != "HOME":
            raise RuntimeError(f"cannot get home (at {s.title!r})")
        return s

    def resolve(self, s, want):
        """The row label `want` names on this screen."""
        if want.startswith("#"):
            # Nth row that is safe to open: never Search (a keyboard), Shuffle, etc.
            i = int(want[1:])
            rows = [r for r in s.rows if not r.startswith("←") and not UNSAFE.search(r)
                    and r.lower() != "back"]
            return rows[i] if i < len(rows) else None
        if want.startswith("^"):
            return next((r for r in s.rows if re.match(want, r)), None)
        return want if want in s.rows else None

    def focus(self, label, limit=60):
        s = self.d.observe()
        for _ in range(limit):
            if s.focused == label:
                return s
            if label in s.rows and s.focused in s.rows:
                gap = s.rows.index(label) - s.rows.index(s.focused)
                self.d.keyevent(20 if gap > 0 else 19, abs(gap))
            else:
                self.d.keyevent(20, 4)
            s2 = self.d.observe()
            if s2.focused == s.focused and s2.rows == s.rows:        # bottom: go up instead
                self.d.keyevent(19, 6)
                s2 = self.d.observe()
            s = s2
        raise RuntimeError(f"cannot focus {label!r} (focused {s.focused!r})")

    def goto(self, path):
        s = self.home()
        for want in path:
            label = self.resolve(s, want) if s else None
            if label is None:
                # the list may not show it yet: scroll to find it, down then up
                # (Home and Back-restored lists can start anywhere)
                for key in (20, 19):
                    for _ in range(15):
                        before = (s.rows, s.focused)
                        self.d.keyevent(key, 5)
                        s = self.d.observe()
                        label = self.resolve(s, want)
                        if label or (s.rows, s.focused) == before:   # at the end
                            break
                    if label:
                        break
            if label is None:
                raise RuntimeError(f"{want!r} not found under {s.title!r}")
            self.focus(label)
            self.d.keyevent(23)
            s = self.d.settle(3, 20)
        return s

    # --- one step ------------------------------------------------------
    def step(self, path, want):
        rec = {"path": path, "want": want}
        try:
            # Already standing on this list after the last step? Skip the trip.
            cur = self.d.observe()
            known = self.titles.get(tuple(path))
            if known is not None and cur.title == known and not cur.overlay and not cur.foreign:
                p = cur
            else:
                p = self.goto(path)
                self.titles[tuple(path)] = p.title
            label = self.resolve(p, want)
            if label is None:
                # Focus may sit anywhere (Back restores it): look down, then up.
                for key in (20, 19):
                    for _ in range(12):
                        before = (p.rows, p.focused)
                        self.d.keyevent(key, 5)
                        p = self.d.observe()
                        label = self.resolve(p, want)
                        if label or (p.rows, p.focused) == before:   # at the end
                            break
                    if label:
                        break
            if label is None:
                rec.update(result="SKIP", why="row not found"); return rec
            rec["row"] = label
            if (UNSAFE.search(label) and not (path == ["Settings"] and label in SETTINGS_OK)) \
                    or (path and path[0] == "Settings" and len(path) > 1) \
                    or STATE_WORDS.match(p.row_state.get(label, "") or "x") and path[:1] != ["Settings"]:
                rec.update(result="SKIP", why="unsafe or a switch"); return rec
            p = self.focus(label)
            rec["parent"] = p.short()
            self.d.keyevent(23)
            c = self.d.settle(3, 20)
            if path[:1] == ["Discovery"] and len(path) == 2:
                # A Discovery song searches and downloads before Now Playing opens.
                for _ in range(20):
                    if c.screen == 3:
                        break
                    time.sleep(2)
                    c = self.d.observe()
                c = self.d.settle(2, 10)
            rec["child"] = c.short()
            if c.foreign:
                self.d.back(); time.sleep(2)
                rec.update(result="FOREIGN", why=f"left Solar: {c.resumed}"); return rec
            if c.key() == p.key():
                rec.update(result="NO_NAV", why="row did not open anything"); return rec
            self.d.back()
            b = self.d.settle(2.5, 12)
            rec["after_back"] = b.short()
            if b.key() == c.key():
                rec.update(result="BACK_NO_OP", why="Back did nothing")
            elif b.title == p.title and b.focused == label:
                rec.update(result="OK")
            elif b.title == p.title:
                rec.update(result="FOCUS_LOST", why=f"focus on {b.focused!r}, not {label!r}")
            else:
                rec.update(result="WRONG_PLACE", why=f"went to {b.title!r}, expected {p.title!r}")
        except Exception as e:                                    # keep going
            rec.update(result="ERROR", why=str(e)[:200])
        if rec["result"] not in ("OK", "SKIP", "NO_NAV"):
            n = sum(1 for r in self.results if r.get("shot")) + 1
            rec["shot"] = f"{OUT}/fail-{n:02d}.png"
            try:
                self.d.shot(rec["shot"])
            except Exception:
                rec.pop("shot", None)
        return rec


def music_volume(d):
    """(route key, index) for STREAM_MUSIC on the route in use: 8 is
    headphones, 2 the speaker."""
    route = "8" if d.sh("cat /sys/class/switch/h2w/state").strip() not in ("", "0") else "2"
    m = re.search(r"- STREAM_MUSIC:\s*\n\s*Mute count: \d+\s*\n\s*Current: ([^\n]+)", d.sh("dumpsys audio"))
    idx = re.search(r"\b" + route + r": (\d+)", m.group(1)) if m else None
    return route, (int(idx.group(1)) if idx else None)


def restore(d, vol):
    d.sh("am force-stop com.solar.launcher"); time.sleep(3)
    d.sh("am start -n com.solar.launcher/.MainActivity"); time.sleep(8)
    route, idx = vol
    if idx is not None and music_volume(d)[0] == route:
        d.sh(f"su -c 'service call audio 6 i32 3 i32 {idx} i32 0'")
    d.sh("svc power stayon false")
    d.sh("su -c 'rm -f /data/local/tmp/navsim.png'")


def report(results, started):
    os.makedirs(OUT, exist_ok=True)
    with open(f"{OUT}/results.jsonl", "w") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    lines = [f"# Navigation simulation — {started}", "",
             "| From | Entered | Result | Detail |", "|---|---|---|---|"]
    for r in results:
        frm = " › ".join(["Home"] + [str(x) for x in r["path"]])
        lines.append(f"| {frm} | {r.get('row', r['want'])} | {r['result']} | {r.get('why', '')} |")
    counts = {}
    for r in results:
        counts[r["result"]] = counts.get(r["result"], 0) + 1
    lines += ["", "Totals: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()))]
    open(f"{OUT}/report.md", "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="1y1", choices=["1y1", "2y1"])
    ap.add_argument("--plan", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--only", help="run steps whose path starts with this top-level row")
    a = ap.parse_args()
    steps = plan()
    if a.only:
        steps = [s for s in steps if (s[0][:1] or [s[1]])[0] == a.only]
    if a.plan:
        for p, w in steps:
            print(" › ".join(["Home"] + p), "→", w)
        print(len(steps), "steps")
        return
    d = Dev(a.device)
    ver = re.search(r"versionName=(\S+)", d.sh("dumpsys package com.solar.launcher"))
    print(f"{a.device}: Solar {ver.group(1) if ver else '?'}")
    if a.check:
        s = d.observe()
        print("screen:", json.dumps(s.short(), ensure_ascii=False), "| overlay" if s.overlay else "",
              "| FOREIGN " + s.resumed if s.foreign else "")
        return
    os.makedirs(OUT, exist_ok=True)
    started = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    vol = music_volume(d)
    print(f"volume on route {vol[0]}: {vol[1]} (muted for the run, put back after)")
    d.sh("su -c 'service call audio 6 i32 3 i32 0 i32 0'")
    d.sh("svc power stayon true")
    if not d.screen_on():
        d.keyevent(26); time.sleep(2)
    s = d.observe()
    if s.foreign:                               # e.g. the USB storage prompt: never press it
        d.back(); time.sleep(2)
    sim = Sim(d)
    try:
        for i, (p, w) in enumerate(steps, 1):
            r = sim.step(p, w)
            sim.results.append(r)
            print(f"[{i}/{len(steps)}] {' › '.join(['Home'] + p)} → {r.get('row', w)}: "
                  f"{r['result']} {r.get('why', '')}", flush=True)
    finally:
        restore(d, vol)
        print(f"Solar restarts to recover: {sim.restarts}")
        report(sim.results, started)


if __name__ == "__main__":
    main()
