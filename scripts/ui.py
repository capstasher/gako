#!/usr/bin/env python3
"""Drive GAKO on a device or emulator by what's on the screen, not by pixel coordinates.

Buttons, menu items and messages are found by their text or accessibility label, so a layout
change doesn't break a script. Points on the image are given in image pixels and mapped to the
screen. Debug builds answer from an inspector inside the app (milliseconds, any zoom, plus the
editor's state); otherwise the script falls back to uiautomator (~2 s a look, view at "Fit").

  scripts/ui.py ls                         # what's on screen: labels and where they are
  scripts/ui.py state                      # the editor's state: image, layers, selection, paths, tool…
  scripts/ui.py text                       # just the texts (messages included)
  scripts/ui.py tap "Layers"               # tap by label (exact, else contains; --2 for the 2nd match)
  scripts/ui.py long "Background"          # long-press by label
  scripts/ui.py wait "Saved" 10            # wait up to 10 s for a label (exit 1 if it never shows)
  scripts/ui.py has "Discard changes?"     # exit 0 if it's there, 1 if not
  scripts/ui.py img 120 80                 # tap image pixel (120, 80)
  scripts/ui.py imglong 120 80             # long-press image pixel (120, 80)
  scripts/ui.py drag 10 10 200 150 [ms]    # drag between image pixels
  scripts/ui.py path 10,10 200,10 100,150  # one finger through image pixels (lassos, shapes)
  scripts/ui.py type "Hello" | key ENTER | back
  scripts/ui.py open photo.jpg             # copy a file into the app and open it (answers "Discard")
  scripts/ui.py shot name                  # screenshot (40%) into $GAKO_SHOTS (default /tmp/gako-shots)
  scripts/ui.py start                      # launch GAKO

Join several commands with "+":  scripts/ui.py tap Layers + tap "More layer actions" + text

With several devices connected it uses $ANDROID_SERIAL if set, else the first booted one
(scripts/ui.py -s emulator-5556 … picks one).
"""
import json
import os
import re
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.github.capstasher.gako"
SHOTS = os.environ.get("GAKO_SHOTS", "/tmp/gako-shots")


SERIAL = None          # the device everything goes to (see pick_device)
_port = None


def pick_device():
    """$ANDROID_SERIAL, else the first device that's booted (adb refuses to guess between several)."""
    global SERIAL
    if SERIAL:
        return
    SERIAL = os.environ.get("ANDROID_SERIAL")
    if SERIAL:
        return
    lines = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    serials = [l.split()[0] for l in lines if l.strip().endswith("device")]
    if not serials:
        sys.exit("No device or emulator (scripts/emulator.sh start)")
    SERIAL = serials[0]


def inspector(request):
    """Ask the debug build's inspector (see app/src/debug/.../Inspector.kt); None if it isn't there."""
    global _port
    if _port is None:
        # Reuse this device's forward if adb already has one, else let adb pick a free local port.
        listed = subprocess.run(["adb", "forward", "--list"], capture_output=True, text=True).stdout
        for line in listed.splitlines():
            parts = line.split()
            if len(parts) == 3 and parts[0] == SERIAL and parts[2] == "localabstract:gako-inspector":
                _port = int(parts[1].split(":")[1])
        if _port is None:
            out = subprocess.run(["adb", "-s", SERIAL, "forward", "tcp:0", "localabstract:gako-inspector"],
                                 capture_output=True, text=True).stdout.strip()
            if not out.isdigit():
                return None
            _port = int(out)
    try:
        with socket.create_connection(("127.0.0.1", _port), timeout=4) as s:
            s.sendall((request + "\n").encode())
            data = b""
            while True:
                chunk = s.recv(65536)
                if not chunk:
                    break
                data += chunk
        d = json.loads(data)
    except (OSError, ValueError):
        return None
    if "error" in d:
        print(f"inspector: {d['error']}", file=sys.stderr)
        return None
    return d


def adb(*args, check=False):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


# ---------------------------------------------------------------- the screen

class Node:
    def __init__(self, text, desc, clickable, box, win=0):
        self.text, self.desc, self.clickable, self.box, self.win = text, desc, clickable, tuple(box), win

    @staticmethod
    def from_xml(e):
        return Node(e.get("text", ""), e.get("content-desc", ""), e.get("clickable") == "true",
                    map(int, re.findall(r"\d+", e.get("bounds", "[0,0][0,0]"))))

    @property
    def label(self):
        return self.text or self.desc

    @property
    def centre(self):
        l, t, r, b = self.box
        return (l + r) // 2, (t + b) // 2

    @property
    def area(self):
        l, t, r, b = self.box
        return max(0, r - l) * max(0, b - t)


def screen():
    fast = inspector("ui")
    if fast is not None:
        return [Node(n["text"], n["desc"], n["click"], n["box"], n["win"]) for n in fast["nodes"]]
    raw = subprocess.run(["adb", "-s", SERIAL, "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, text=True).stdout
    raw = raw[: raw.rfind(">") + 1] if ">" in raw else raw
    try:
        root = ET.fromstring(raw)
    except ET.ParseError:
        return []
    return [Node.from_xml(e) for e in root.iter("node")]


def find(label, nth=1, nodes=None):
    nodes = nodes if nodes is not None else screen()
    labelled = [n for n in nodes if n.label and n.area > 0]
    exact = [n for n in labelled if label in (n.text, n.desc)]
    loose = [n for n in labelled if label.lower() in n.text.lower() or label.lower() in n.desc.lower()]
    hits = exact or loose
    # Several nodes can carry the same label: prefer the top window (a dialog over the screen),
    # then clickable, then the smallest.
    hits.sort(key=lambda n: (-n.win, not n.clickable, n.area))
    return hits[nth - 1] if len(hits) >= nth else None


def need(label, nth=1):
    n = find(label, nth)
    if n is None:
        shown = ", ".join(sorted({x.label for x in screen() if x.label})[:40])
        sys.exit(f'Not on screen: "{label}". Showing: {shown}')
    return n


# ---------------------------------------------------------------- the image

def canvas():
    """Image pixel → screen pixel: from the inspector at any zoom, else worked out at Fit."""
    if inspector("img 0 0") is not None:
        def via_inspector(x, y):
            p = inspector(f"img {x} {y}")
            return p["x"], p["y"]
        return via_inspector
    nodes = screen()
    c = next((n for n in nodes if n.desc.startswith("Image, ")), None)
    if c is None:
        sys.exit("No image open")
    m = re.search(r"(\d+) by (\d+)", c.desc)
    w, h = int(m.group(1)), int(m.group(2))
    if not any(n.label.startswith("Fit") for n in nodes):
        print("warning: the view isn't at Fit; tap the zoom chip first", file=sys.stderr)
    l, t, r, b = c.box
    cw, ch = r - l, b - t
    s = min(cw / w, ch / h) * 0.94          # ImageCanvas.fit
    return lambda x, y: (int(l + cw / 2 + (x - w / 2) * s), int(t + ch / 2 + (y - h / 2) * s))


# ---------------------------------------------------------------- commands

def cmd(args):
    c, rest = args[0], args[1:]
    nth = 1
    for a in list(rest):
        if re.fullmatch(r"--\d+", a):
            nth = int(a[2:])
            rest.remove(a)
    if c == "ls":
        for n in screen():
            if n.label:
                x, y = n.centre
                print(f"{n.label!r:50.50} at {x},{y}{'  (tap)' if n.clickable else ''}")
    elif c == "state":
        st = inspector("state")
        if st is None:
            sys.exit("No inspector (it's in debug builds): can't read the editor's state")
        print(json.dumps(st, indent=1))
    elif c == "text":
        seen = []
        for n in screen():
            if n.text and n.text not in seen:
                seen.append(n.text)
        print("\n".join(seen))
    elif c == "tap":
        x, y = need(rest[0], nth).centre
        adb("shell", "input", "tap", str(x), str(y))
    elif c == "long":
        x, y = need(rest[0], nth).centre
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    elif c == "has":
        sys.exit(0 if find(rest[0], nth) else 1)
    elif c == "wait":
        limit = time.time() + (float(rest[1]) if len(rest) > 1 else 10)
        while time.time() < limit:
            if find(rest[0], nth):
                return
            time.sleep(0.1)
        sys.exit(f'Timed out waiting for "{rest[0]}"')
    elif c == "gone":
        limit = time.time() + (float(rest[1]) if len(rest) > 1 else 10)
        while time.time() < limit:
            if not find(rest[0], nth):
                return
            time.sleep(0.1)
        sys.exit(f'Still showing "{rest[0]}"')
    elif c == "img":
        x, y = canvas()(float(rest[0]), float(rest[1]))
        adb("shell", "input", "tap", str(x), str(y))
    elif c == "imglong":
        x, y = canvas()(float(rest[0]), float(rest[1]))
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    elif c == "drag":
        f = canvas()
        x1, y1 = f(float(rest[0]), float(rest[1]))
        x2, y2 = f(float(rest[2]), float(rest[3]))
        adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), rest[4] if len(rest) > 4 else "400")
    elif c == "path":
        f = canvas()
        pts = [f(*map(float, p.split(","))) for p in rest]
        steps = [f"input motionevent DOWN {pts[0][0]} {pts[0][1]}"]
        steps += [f"input motionevent MOVE {x} {y}" for x, y in pts[1:]]
        steps.append(f"input motionevent UP {pts[-1][0]} {pts[-1][1]}")
        adb("shell", "; ".join(steps))
    elif c == "type":
        adb("shell", "input", "text", rest[0].replace(" ", "%s"))
    elif c == "key":
        adb("shell", "input", "keyevent", rest[0] if rest[0].startswith("KEYCODE_") else "KEYCODE_" + rest[0].upper())
    elif c == "back":
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    elif c == "start":
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    elif c == "open":
        path = rest[0]
        name = os.path.basename(path)
        adb("push", path, f"/data/local/tmp/{name}", check=True)
        adb("shell", f"run-as {PKG} sh -c 'mkdir -p cache/in && cp /data/local/tmp/{name} cache/in/{name}'")
        uri = f"file:///data/user/0/{PKG}/cache/in/{name}"
        for _ in range(20):                       # wait out a restore in progress
            adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", uri, "-n", f"{PKG}/.MainActivity")
            time.sleep(0.8)
            nodes = screen()
            if find("Still restoring", nodes=nodes):
                time.sleep(1)
                continue
            d = find("Discard", nodes=nodes)
            if d is not None and find("Discard changes?", nodes=nodes):
                adb("shell", "input", "tap", *map(str, d.centre))
            break
    elif c == "shot":
        os.makedirs(SHOTS, exist_ok=True)
        out = os.path.join(SHOTS, (rest[0] if rest else time.strftime("%H%M%S")) + ".png")
        png = subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout
        try:
            from PIL import Image
            import io
            im = Image.open(io.BytesIO(png))
            im.resize((im.width * 2 // 5, im.height * 2 // 5)).save(out)
        except ImportError:
            open(out, "wb").write(png)
        print(out)
    elif c == "sleep":
        time.sleep(float(rest[0]))
    else:
        sys.exit(f"Unknown command {c!r}\n\n{__doc__}")


def main(argv):
    global SERIAL
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return
    if argv[0] == "-s" and len(argv) > 2:
        SERIAL, argv = argv[1], argv[2:]
    pick_device()
    group = []
    for a in argv + ["+"]:
        if a == "+":
            if group:
                cmd(group)
            group = []
        else:
            group.append(a)


if __name__ == "__main__":
    main(sys.argv[1:])
