#!/usr/bin/env bash
# Fast on-device tests. Builds with the (warm) Gradle daemon, installs the app and the test APK
# without uninstalling anything afterwards (so the app keeps its data), and runs the instrumented
# tests with `am instrument`.
#
#   scripts/devtest.sh                          # every test
#   scripts/devtest.sh UiTest                   # one class (in io.github.capstasher.gako)
#   scripts/devtest.sh UiTest#warpAndUndo       # one test
#   scripts/devtest.sh FileRoundTripTest UiTest # several
#
# Needs adb and gradle on PATH and a device or emulator. GAKO_ABIS picks the ABI (default x86_64).
set -euo pipefail
cd "$(dirname "$0")/.."
gradle --daemon -q :app:installDebug :app:installDebugAndroidTest -Pgako.abis="${GAKO_ABIS:-x86_64}"
args=()
if [ $# -gt 0 ]; then
  list=""
  for t in "$@"; do list+="io.github.capstasher.gako.$t,"; done
  args=(-e class "${list%,}")
fi
out=$(adb shell am instrument -w "${args[@]}" io.github.capstasher.gako.test/androidx.test.runner.AndroidJUnitRunner)
echo "$out"
# am instrument exits 0 even when tests fail: make failures fail the script.
echo "$out" | grep -q "^OK (" || exit 1
