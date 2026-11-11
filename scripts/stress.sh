#!/usr/bin/env bash
# Random-input stress test ("monkey") of the installed app, reporting crashes and freezes.
#   scripts/stress.sh [events=5000] [seed=random]
# Only touches, drags and pinches: the monkey's rotation/flip events crash some emulators themselves.
set -uo pipefail
events=${1:-5000}
seed=${2:-$RANDOM}
pkg=io.github.capstasher.gako
adb shell am start -n $pkg/.MainActivity >/dev/null
sleep 3
adb logcat -c
adb shell monkey -p $pkg -s "$seed" --throttle 30 \
  --pct-touch 55 --pct-motion 32 --pct-pinchzoom 8 --pct-majornav 5 \
  --pct-rotation 0 --pct-flip 0 --pct-appswitch 0 --pct-syskeys 0 --pct-nav 0 --pct-anyevent 0 --pct-trackball 0 \
  "$events" 2>&1 | grep -E "Events injected|CRASH|NOT RESPONDING" 
adb shell cmd statusbar collapse >/dev/null 2>&1
problems=$(adb logcat -d | grep -E -A 25 "FATAL EXCEPTION|ANR in $pkg" | grep -E "FATAL|ANR|Exception|Error|at io\.github" | head -30)
if [ -n "$problems" ]; then
  echo "PROBLEMS (seed $seed):"; echo "$problems"; exit 1
fi
echo "No crashes or freezes (seed $seed)"
