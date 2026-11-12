#!/usr/bin/env python3
"""Helpers for scripts/devtest.sh: list the tests, split them evenly across devices, learn timings.

  devtest_plan.py list                      raw `am instrument -r -e log true` output on stdin → class#method lines
  devtest_plan.py plan N TIMINGS [FILTER…]  tests on stdin → N lines of comma-separated tests with about
                                            equal total time each (longest first onto the least-loaded
                                            device), from the durations measured on earlier runs
  devtest_plan.py record TIMINGS [LIST]     `adb logcat -v epoch -s TestRunner` on stdin → remembers how
                                            long each test took (forgetting tests not in LIST, if given)
"""
import re
import sys

PKG = "io.github.capstasher.gako."


def load(path):
    t = {}
    try:
        for line in open(path):
            name, _, secs = line.rstrip("\n").partition("\t")
            if secs:
                t[name] = float(secs)
    except OSError:
        pass
    return t


def main():
    cmd = sys.argv[1]
    if cmd == "list":
        cls = None
        seen = []
        for line in sys.stdin:
            m = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.+)", line.strip())
            if not m:
                continue
            if m.group(1) == "class":
                cls = m.group(2)
            elif cls:
                name = f"{cls}#{m.group(2)}"
                if name not in seen:
                    seen.append(name)
        print("\n".join(seen))
    elif cmd == "plan":
        n, times = int(sys.argv[2]), load(sys.argv[3])
        wanted = [f if f.startswith(PKG) else PKG + f for f in sys.argv[4:]]
        tests = [t.strip() for t in sys.stdin if t.strip()]
        if wanted:
            tests = [t for t in tests if any(t == w or t.startswith(w + "#") for w in wanted)]
        known = sorted(times.values())
        guess = known[len(known) // 2] if known else 1.0      # a new test: assume a typical one
        shards = [[0.0, []] for _ in range(n)]
        for t in sorted(tests, key=lambda t: -times.get(t, guess)):
            s = min(shards, key=lambda s: s[0])
            s[0] += times.get(t, guess)
            s[1].append(t)
        for s in shards:
            print(",".join(s[1]))
    elif cmd == "record":
        path = sys.argv[2]
        times = load(path)
        if len(sys.argv) > 3:
            try:
                current = {line.strip() for line in open(sys.argv[3]) if line.strip()}
                times = {k: v for k, v in times.items() if k in current}
            except OSError:
                pass
        started = {}
        for line in sys.stdin:
            m = re.match(r"\s*(\d+\.\d+).*TestRunner: (started|finished): (\w+)\(([\w.$]+)\)", line)
            if not m:
                continue
            ts, what, method, cls = float(m.group(1)), m.group(2), m.group(3), m.group(4)
            name = f"{cls}#{method}"
            if what == "started":
                started[name] = ts
            elif name in started:
                took = ts - started.pop(name)
                # Smooth a little: one slow run shouldn't reshuffle everything.
                times[name] = round(took if name not in times else 0.5 * times[name] + 0.5 * took, 3)
        with open(path, "w") as f:
            for name in sorted(times):
                f.write(f"{name}\t{times[name]}\n")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
