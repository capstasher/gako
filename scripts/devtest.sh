#!/usr/bin/env bash
# Fast on-device tests. Builds with the (warm) Gradle daemon, installs the app and the test APK
# only if they changed (and never uninstalls, so the app keeps its data), and runs the
# instrumented tests with `am instrument`. With several devices/emulators connected, the tests are
# split between them and run in parallel.
#
#   scripts/devtest.sh                          # every test
#   scripts/devtest.sh UiTest                   # one class (in io.github.capstasher.gako)
#   scripts/devtest.sh UiTest#warpStrokeAndUndo # one test
#   scripts/devtest.sh FileRoundTripTest UiTest # several
#
# Needs adb and gradle on PATH. GAKO_ABIS picks the ABI (default x86_64).
set -euo pipefail
cd "$(dirname "$0")/.."
APP=io.github.capstasher.gako
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
STAMPS=app/build/devtest
mkdir -p "$STAMPS"

gradle --daemon -q :app:assembleDebug :app:assembleDebugAndroidTest -Pgako.abis="${GAKO_ABIS:-x86_64}"

# Wait up to 90 s for a booted device: an emulator may be restarting.
devices=()
for _ in $(seq 180); do
  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  booted=0
  for d in "${devices[@]}"; do
    [ "$(adb -s "$d" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && booted=$((booted + 1))
  done
  [ ${#devices[@]} -gt 0 ] && [ "$booted" -eq ${#devices[@]} ] && break
  sleep 0.5
done
[ ${#devices[@]} -gt 0 ] || { echo "No device or emulator connected" >&2; exit 1; }

# Installs $2 on device $1 unless that exact APK is already what's installed there (same file
# hash, and the package hasn't been reinstalled since by anything else).
install_if_changed() {
  local dev=$1 apk=$2 pkg=$3 stamp="$STAMPS/$1-$3"
  local sum installed
  sum=$(sha1sum "$apk" | cut -c1-40)
  installed=$(adb -s "$dev" shell dumpsys package "$pkg" | grep -m1 lastUpdateTime | tr -d ' \r' || true)
  if [ -n "$installed" ] && [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$sum $installed" ]; then return; fi
  adb -s "$dev" install -r -t "$apk" >/dev/null
  installed=$(adb -s "$dev" shell dumpsys package "$pkg" | grep -m1 lastUpdateTime | tr -d ' \r')
  echo "$sum $installed" > "$stamp"
}
for d in "${devices[@]}"; do
  install_if_changed "$d" "$APP_APK" "$APP" &
  install_if_changed "$d" "$TEST_APK" "$APP.test" &
done
wait

args=()
if [ $# -gt 0 ]; then
  list=""
  for t in "$@"; do list+="$APP.$t,"; done
  args=(-e class "${list%,}")
fi

run() { # device shard-args...
  local dev=$1; shift
  adb -s "$dev" shell am instrument -w "$@" "${args[@]}" "$APP.test/androidx.test.runner.AndroidJUnitRunner"
}

n=${#devices[@]}
if [ "$n" -eq 1 ]; then
  out=$(run "${devices[0]}")
else
  tmp=$(mktemp -d)
  for i in "${!devices[@]}"; do
    run "${devices[$i]}" -e numShards "$n" -e shardIndex "$i" > "$tmp/$i" &
  done
  wait
  out=$(for i in "${!devices[@]}"; do echo "== ${devices[$i]} (shard $((i + 1))/$n)"; cat "$tmp/$i"; done)
  rm -rf "$tmp"
fi
echo "$out"
# am instrument exits 0 even when tests fail: fail if any shard didn't end OK.
shards_ok=$(echo "$out" | grep -c "^OK (" || true)
if [ "$shards_ok" -ne "$n" ]; then
  for d in "${devices[@]}"; do
    adb -s "$d" get-state >/dev/null 2>&1 || echo "NOTE: $d went away during the run (an emulator crash, not a test failure); run again." >&2
  done
  exit 1
fi
