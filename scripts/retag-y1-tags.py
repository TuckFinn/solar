#!/usr/bin/env python3
"""Put the artist back on songs a Y1 kept from Discovery.

SpotiFLAC's Qobuz files arrive with ARTIST=Unknown, and until today the tag
was only fixed when a song graduated into the library -- a song kept on the
device kept the bad tag, so the Y1's own Artists list grew an "Unknown" pile.
This repairs what is already on a device: read each file's tags, look the
title up in nookTunes' Discovery metadata, write the artist back.

    retag_y1.py 1y1|2y1 [--apply]      (default: report only)

Originals are copied to ~/.spotiflac-web/trash/y1-retag-<stamp>/ first.
"""
import json, re, shutil, subprocess, sys, time
from pathlib import Path

STATE = Path("/home/nookman/.spotiflac-web/state")
TRASH = Path("/home/nookman/.spotiflac-web/trash") / ("y1-retag-" + time.strftime("%Y%m%d-%H%M"))
WORK = Path("/tmp/y1retag")
SERIALS = {"1y1": "SLF79D5C5383", "2y1": "SL0409654501"}
MUSIC = "/storage/sdcard0/Music"
BAD = {"", "unknown", "unknown artist", "various artists"}


def transport(name):
    out = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        m = re.search(r"transport_id:(\d+)", line)
        if not (m and "usb:" in line):
            continue
        who = subprocess.run(["adb", "-t", m.group(1), "shell", "getprop persist.sys.usb.serialno"],
                             capture_output=True, text=True, timeout=40).stdout.strip()
        if who == SERIALS[name]:
            return m.group(1)
    raise SystemExit(f"{name} is not on USB")


def sh(t, cmd, timeout=180):
    return subprocess.run(["adb", "-t", t, "shell", cmd], capture_output=True,
                          text=True, timeout=timeout).stdout.replace("\r", "")


def norm(s):
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def known_tracks():
    """Every (title -> artist) nookTunes knows, Discovery first."""
    out = {}
    for fname, dig in (("discovery_track_meta.json", "tracks"),
                       ("discovery_slots.json", None),
                       ("favorites.json", "tracks")):
        try:
            doc = json.loads((STATE / fname).read_text())
        except Exception:
            continue
        rows = doc.get(dig) if dig else doc
        if isinstance(rows, dict):
            rows = list(rows.values())
        if not isinstance(rows, list):
            continue
        for r in rows:
            if not isinstance(r, dict):
                continue
            t, a = norm(r.get("title")), (r.get("artist") or "").strip()
            if t and a and t not in out:
                out[t] = a
    return out


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else "1y1"
    apply = "--apply" in sys.argv
    t = transport(name)
    import mutagen
    titles = known_tracks()
    print(f"{len(titles)} titles known to nookTunes")
    WORK.mkdir(parents=True, exist_ok=True)
    files = [l.strip() for l in sh(t, f"su -c 'ls {MUSIC}'").splitlines() if l.strip()]
    audio = [f for f in files if f.lower().endswith((".flac", ".mp3", ".m4a", ".ogg"))]
    print(f"{len(audio)} song(s) on {name}")
    fixed, missed, fine = [], [], 0
    for fn in audio:
        local = WORK / fn
        sh(t, f"su -c 'cp \"{MUSIC}/{fn}\" /data/local/tmp/r.bin; chmod 666 /data/local/tmp/r.bin'")
        subprocess.run(["adb", "-t", t, "pull", "/data/local/tmp/r.bin", str(local)],
                       capture_output=True, timeout=300)
        if not local.is_file():
            missed.append((fn, "could not read it off the device")); continue
        try:
            f = mutagen.File(str(local), easy=True)
            artist = ((f.get("artist") or [""])[0] or "").strip()
            title = ((f.get("title") or [""])[0] or "").strip()
            # Some files kept the real name in ALBUM_ARTIST and lost only
            # ARTIST -- that is a better source than any lookup.
            album_artist = ((f.get("albumartist") or [""])[0] or "").strip()
        except Exception as e:
            missed.append((fn, f"tags unreadable: {e}")); continue
        if artist.lower() not in BAD:
            fine += 1; local.unlink(missing_ok=True); continue
        want = (titles.get(norm(title))
                or titles.get(norm(Path(fn).stem.split(" - ", 1)[-1]))
                or (album_artist if album_artist.lower() not in BAD else ""))
        if not want:
            missed.append((fn, f"no artist known for {title!r}")); continue
        print(f"  {fn}: {artist or '(none)'} -> {want}")
        fixed.append((fn, want))
        if apply:
            TRASH.mkdir(parents=True, exist_ok=True)
            shutil.copy2(str(local), str(TRASH / fn))      # the file as it was
            f["artist"] = want
            if not album_artist:
                f["albumartist"] = want
            f.save()
            subprocess.run(["adb", "-t", t, "push", str(local), "/data/local/tmp/w.bin"],
                           capture_output=True, timeout=300)
            sh(t, f"su -c 'cp /data/local/tmp/w.bin \"{MUSIC}/{fn}\"; rm /data/local/tmp/w.bin'")
        local.unlink(missing_ok=True)
    print(f"\n{fine} already tagged, {len(fixed)} {'repaired' if apply else 'would be repaired'}, "
          f"{len(missed)} left alone")
    for fn, why in missed:
        print(f"   skipped {fn}: {why}")
    if apply and fixed:
        print(f"originals: {TRASH}")
        print("rescan the device library in Solar to pick the new tags up")


if __name__ == "__main__":
    main()
