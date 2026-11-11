#!/usr/bin/env bash
# Fast on-device tests.
#
#   scripts/devtest.sh                          # every test
#   scripts/devtest.sh UiTest                   # one class (in io.github.capstasher.gako)
#   scripts/devtest.sh UiTest#warpStrokeAndUndo # one test
#   scripts/devtest.sh FileRoundTripTest UiTest # several
#
# Uses every connected device or emulator (scripts/emulator.sh starts as many as the machine runs
# well) and splits the tests between them so each gets about the same total time, using how long
# each test took on earlier runs. Builds only the ABIs those devices need (with the warm Gradle
# daemon and its configuration cache), installs the app and the test APK only if they changed (by
# plain adb, never uninstalling, so the app keeps its data), and waits for an emulator that's
# restarting.
#
# Needs adb and gradle on PATH (bash 3.2+; python3 for the even split, else Android's own sharding).
# GAKO_DEVICES limits the run to some devices (comma-separated serials); GAKO_ABIS overrides the ABIs.
# A run with no test names also runs the JVM unit tests alongside (GAKO_NO_UNIT=1 skips them).
set -euo pipefail
cd "$(dirname "$0")/.."
APP=io.github.capstasher.gako
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
STATE=app/build/devtest
TIMINGS="$STATE/timings.tsv"
mkdir -p "$STATE"
PY=${GAKO_PYTHON-$(command -v python3 || true)}   # GAKO_PYTHON= (empty) forces the no-python path
started=$(date +%s)

hash_of() { if command -v sha1sum >/dev/null; then sha1sum "$1"; else shasum "$1"; fi | cut -c1-40; }

# ---- devices: wait up to 90 s for booted ones (an emulator may be restarting)
probe="$STATE/probe"
devices=()
for _ in $(seq 180); do
  rm -rf "$probe"; mkdir -p "$probe"
  for d in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
    if [ -n "${GAKO_DEVICES:-}" ] && ! echo ",$GAKO_DEVICES," | tr -d ' ' | grep -qF ",$d,"; then continue; fi
    # One call per device, all at once: booted? and its ABI.
    adb -s "$d" shell 'echo $(getprop sys.boot_completed) $(getprop ro.product.cpu.abi)' < /dev/null 2>/dev/null |
      tr -d '\r' > "$probe/$d" &
  done
  wait
  devices=(); abis_seen=""
  for f in "$probe"/*; do
    [ -f "$f" ] || continue
    read -r up abi < "$f" || true
    [ "${up:-}" = 1 ] || continue
    devices+=("$(basename "$f")"); abis_seen="$abis_seen $abi"
  done
  [ ${#devices[@]} -gt 0 ] && break
  sleep 0.5
done
[ ${#devices[@]} -gt 0 ] || { echo "No booted device or emulator (scripts/emulator.sh start)" >&2; exit 1; }
n=${#devices[@]}

# ---- build: just the ABIs these devices run
abis=${GAKO_ABIS:-$(echo $abis_seen | tr ' ' '\n' | sort -u | grep . | paste -sd, -)}
for a in ${abis//,/ }; do
  [ -d "app/src/main/jniLibs/$a" ] || { echo "No engine built for $a: native/build-native.sh android-${a%%-*} && native/stage-jnilibs.sh …" >&2; exit 1; }
done
gradle --daemon --configuration-cache -q :app:assembleDebug :app:assembleDebugAndroidTest -Pgako.abis="$abis"

# ---- install where changed: same file hash, and the package not reinstalled since by anything else
install_if_changed() { # device apk package
  local dev=$1 apk=$2 pkg=$3 stamp="$STATE/$1-$3" sum installed
  sum=$(hash_of "$apk")
  installed=$(adb -s "$dev" shell dumpsys package "$pkg" | grep -m1 lastUpdateTime | tr -d ' \r' || true)
  if [ -n "$installed" ] && [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$sum $installed" ]; then return 0; fi
  adb -s "$dev" install -r -t "$apk" >/dev/null
  installed=$(adb -s "$dev" shell dumpsys package "$pkg" | grep -m1 lastUpdateTime | tr -d ' \r')
  echo "$sum $installed" > "$stamp"
}
for d in "${devices[@]}"; do
  install_if_changed "$d" "$APP_APK" "$APP" &
  install_if_changed "$d" "$TEST_APK" "$APP.test" &
  adb -s "$d" logcat -c >/dev/null 2>&1 &
done
wait

# ---- what each device runs
plans=()
list=""
if [ "$n" -gt 1 ] && [ -n "$PY" ]; then
  # The test list, worked out once per test APK.
  list="$STATE/tests-$(hash_of "$TEST_APK")"
  if [ ! -s "$list" ]; then
    adb -s "${devices[0]}" shell am instrument -w -r -e log true "$RUNNER" | "$PY" scripts/devtest_plan.py list > "$list"
  fi
  while IFS= read -r line; do plans+=("$line"); done < <("$PY" scripts/devtest_plan.py plan "$n" "$TIMINGS" "$@" < "$list")
fi

filter=()
if [ $# -gt 0 ]; then
  f=""
  for t in "$@"; do f+="$APP.$t,"; done
  filter=(-e class "${f%,}")
fi

tmp=$(mktemp -d)
# A full run also runs the JVM unit tests, while the devices work (no extra wall time).
unit=""
if [ $# -eq 0 ] && [ -z "${GAKO_NO_UNIT:-}" ]; then
  gradle --daemon --configuration-cache -q :app:testDebugUnitTest > "$tmp/unit" 2>&1 &
  unit=$!
fi
ran=0
device_jobs=()
for i in "${!devices[@]}"; do
  d=${devices[$i]}
  if [ ${#plans[@]} -gt 0 ]; then
    [ -n "${plans[$i]}" ] || continue
    adb -s "$d" shell am instrument -w -e class "${plans[$i]}" "$RUNNER" > "$tmp/$i" 2>&1 &
  elif [ "$n" -gt 1 ]; then
    adb -s "$d" shell am instrument -w -e numShards "$n" -e shardIndex "$i" ${filter[@]+"${filter[@]}"} "$RUNNER" > "$tmp/$i" 2>&1 &
  else
    adb -s "$d" shell am instrument -w ${filter[@]+"${filter[@]}"} "$RUNNER" > "$tmp/$i" 2>&1 &
  fi
  device_jobs+=($!)
  ran=$((ran + 1))
done
for j in ${device_jobs[@]+"${device_jobs[@]}"}; do wait "$j" || true; done

ok=0; total=0; failed=0
for i in "${!devices[@]}"; do
  [ -f "$tmp/$i" ] || continue
  [ "$n" -gt 1 ] && echo "== ${devices[$i]}"
  cat "$tmp/$i"
  if grep -q "^OK (" "$tmp/$i"; then
    ok=$((ok + 1))
    total=$((total + $(sed -nE 's/^OK \(([0-9]+) tests?\)/\1/p' "$tmp/$i")))
  else
    failed=1
    adb -s "${devices[$i]}" get-state >/dev/null 2>&1 ||
      echo "NOTE: ${devices[$i]} went away during the run (an emulator crash, not a test failure); run again." >&2
  fi
done
unit_note=""
if [ -n "$unit" ]; then
  if wait "$unit"; then
    unit_count=$(cat app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | grep -o 'testsuite [^>]*tests="[0-9]*"' |
      grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | awk '{ s += $1 } END { print s + 0 }')
    unit_note=" (+ $unit_count JVM unit tests)"
  else
    echo "== JVM unit tests"; cat "$tmp/unit"; failed=1
  fi
fi
rm -rf "$tmp"

# Remember how long each test took, for the next split.
if [ -n "$PY" ]; then
  for d in "${devices[@]}"; do adb -s "$d" logcat -d -v epoch -s TestRunner 2>/dev/null; done |
    "$PY" scripts/devtest_plan.py record "$TIMINGS" ${list:+"$list"} || true
fi

[ "$failed" = 0 ] || exit 1
if [ "$total" = 1 ]; then what="1 test passed"; else what="All $total tests passed"; fi
echo "$what on $ran device(s)$unit_note in $(( $(date +%s) - started )) s"
