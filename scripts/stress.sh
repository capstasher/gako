#!/usr/bin/env bash
# Random-input stress test ("monkey") of the installed app, reporting crashes and freezes.
#
#   scripts/stress.sh [events=5000] [seed=random]
#
# Runs on every connected device or emulator at once, each with its own seed (seed, seed+1, …) and
# the full number of events, so N emulators give N times the coverage in the same time. Only
# touches, drags and pinches: the monkey's rotation/flip events crash some emulators themselves.
# GAKO_DEVICES limits it to some devices (comma-separated serials).
set -uo pipefail
events=${1:-5000}
seed=${2:-$RANDOM}
pkg=io.github.capstasher.gako
devices=()
for d in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
  if [ -n "${GAKO_DEVICES:-}" ] && ! echo ",$GAKO_DEVICES," | tr -d ' ' | grep -qF ",$d,"; then continue; fi
  devices+=("$d")
done
[ ${#devices[@]} -gt 0 ] || { echo "No device or emulator (scripts/emulator.sh start)" >&2; exit 1; }

tmp=$(mktemp -d)
for i in "${!devices[@]}"; do
  (
    d=${devices[$i]}; s=$((seed + i))
    adb -s "$d" shell am start -n "$pkg/.MainActivity" >/dev/null 2>&1
    sleep 3
    adb -s "$d" logcat -c
    adb -s "$d" shell monkey -p "$pkg" -s "$s" --throttle 30 \
      --pct-touch 55 --pct-motion 32 --pct-pinchzoom 8 --pct-majornav 5 \
      --pct-rotation 0 --pct-flip 0 --pct-appswitch 0 --pct-syskeys 0 --pct-nav 0 --pct-anyevent 0 --pct-trackball 0 \
      "$events" > "$tmp/$i.monkey" 2>&1
    adb -s "$d" shell cmd statusbar collapse >/dev/null 2>&1
    # Only GAKO's own crashes (the monkey can also upset the launcher in the background).
    adb -s "$d" logcat -d | grep -E -A 25 "FATAL EXCEPTION|ANR in $pkg" | grep -B2 -A25 -E "Process: $pkg|ANR in $pkg" |
      grep -E "FATAL|ANR|Exception|Error|at io\.github" | head -30 > "$tmp/$i.problems"
  ) &
done
wait

bad=0
for i in "${!devices[@]}"; do
  d=${devices[$i]}; s=$((seed + i))
  injected=$(grep -o "Events injected: [0-9]*" "$tmp/$i.monkey" | grep -o "[0-9]*$" || echo 0)
  if [ -s "$tmp/$i.problems" ] || grep -qE "(CRASH|NOT RESPONDING): $pkg" "$tmp/$i.monkey"; then
    echo "PROBLEMS on $d (seed $s, $injected events):"; cat "$tmp/$i.problems"; grep -E "(CRASH|NOT RESPONDING): $pkg" "$tmp/$i.monkey"
    bad=1
  else
    echo "$d: no crashes or freezes in $injected events (seed $s)"
  fi
done
rm -rf "$tmp"
exit $bad
