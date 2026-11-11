#!/usr/bin/env bash
# Test emulators for GAKO: starts the best setup this machine supports and keeps it running.
#
#   scripts/emulator.sh start [N]   start N emulators (default: as many as this machine runs well)
#                                   and wait until they're booted; they run in the background
#   scripts/emulator.sh stop        stop them
#   scripts/emulator.sh status      list them
#
# What it picks, and how to override it:
#   SDK    $ANDROID_HOME, $ANDROID_SDK_ROOT, ~/Android/Sdk, ~/Library/Android/sdk
#   AVD    $GAKO_AVD; else one named "gako"; else the first there is; else it creates "gako" from
#          the newest system image installed for this CPU (x86_64, or arm64-v8a on ARM machines)
#   GPU    $GAKO_EMU_GPU; else what worked here last time; else the first that boots and draws of:
#          host (the real GPU: by far the fastest; on Linux it needs a desktop session),
#          swangle_indirect, swiftshader_indirect (software)
#   Count  the argument or $GAKO_EMULATORS; else what the CPU and free memory allow (at most 4),
#          and 1 without hardware virtualisation (KVM/HVF/WHPX)
#   Boot   from a snapshot saved after the first boot (~5 s instead of ~30 s)
# Every emulator is restarted from the snapshot if it dies. State and logs: ~/.cache/gako-emulator.
set -uo pipefail

STATE="${XDG_CACHE_HOME:-$HOME/.cache}/gako-emulator"
mkdir -p "$STATE"
touch "$STATE/restarts.log"
say() { echo "emulator.sh: $*" >&2; }
die() { say "$*"; exit 1; }

find_sdk() {
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$d" ] && [ -x "$d/emulator/emulator" ] && { echo "$d"; return 0; }
  done
  return 1
}
SDK=$(find_sdk) || die "no Android SDK with the emulator found: set ANDROID_HOME"
EMU="$SDK/emulator/emulator"
ADB=$(command -v adb || echo "$SDK/platform-tools/adb")
[ -x "$ADB" ] || die "adb not found (install the SDK's platform-tools)"
adb() { "$ADB" "$@"; }

host_abi() { case "$(uname -m)" in arm64|aarch64) echo arm64-v8a ;; *) echo x86_64 ;; esac; }

create_avd() {
  local abi img api tag mgr
  abi=$(host_abi)
  img=$(ls -d "$SDK"/system-images/android-*/*/"$abi" 2>/dev/null | sed -E 's#.*/android-([0-9]+)/#\1 &#' | sort -n | tail -1 | cut -d' ' -f2)
  [ -n "$img" ] || die "no $abi system image installed; e.g. sdkmanager 'system-images;android-35;google_apis;$abi'"
  api=$(echo "$img" | sed -E 's#.*/android-([0-9]+)/.*#\1#')
  tag=$(basename "$(dirname "$img")")
  mgr=$(ls "$SDK"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1)
  [ -n "$mgr" ] || die "avdmanager not found (install the SDK's cmdline-tools) to create an AVD"
  say "creating AVD gako from system-images;android-$api;$tag;$abi"
  echo no | "$mgr" create avd -n gako -k "system-images;android-$api;$tag;$abi" >/dev/null || die "couldn't create the AVD"
  echo gako
}

pick_avd() {
  local avds
  [ -n "${GAKO_AVD:-}" ] && { echo "$GAKO_AVD"; return; }
  avds=$("$EMU" -list-avds 2>/dev/null | grep -E '^[A-Za-z0-9._-]+$')
  if echo "$avds" | grep -qx gako; then echo gako
  elif [ -n "$avds" ]; then echo "$avds" | head -1
  else create_avd
  fi
}

avd_config() { # key default
  local cfg="$HOME/.android/avd/$AVD.avd/config.ini" v=""
  [ -f "$cfg" ] && v=$(grep -E "^$1 *=" "$cfg" | head -1 | sed -E 's/^[^=]*= *//; s/[^0-9].*$//')
  echo "${v:-$2}"
}

# How many emulators this machine runs well: a quarter of the cores spare, memory for each one's RAM
# plus ~1.5 GB of emulator overhead, at most 4. Without hardware acceleration, one.
auto_count() {
  local cores ncore ram free_mb n_cpu n_mem
  "$EMU" -accel-check >/dev/null 2>&1 || { echo 1; return; }
  cores=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
  ncore=$(avd_config hw.cpu.ncore 2)
  ram=$(avd_config hw.ramSize 2048)
  if [ -r /proc/meminfo ]; then free_mb=$(( $(awk '/MemAvailable/ { print $2 }' /proc/meminfo) / 1024 ))
  else free_mb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 8589934592) / 1048576 / 2 ))
  fi
  n_cpu=$(( cores * 3 / 4 / ncore ))
  n_mem=$(( free_mb / (ram + 1536) ))
  local n=$(( n_cpu < n_mem ? n_cpu : n_mem ))
  [ "$n" -gt 4 ] && n=4
  [ "$n" -lt 1 ] && n=1
  echo "$n"
}

has_display() { [ "$(uname -s)" != Linux ] || [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; }

gpu_candidates() {
  if [ -n "${GAKO_EMU_GPU:-}" ]; then echo "$GAKO_EMU_GPU"; return; fi
  { [ -f "$STATE/gpu-$AVD" ] && cat "$STATE/gpu-$AVD"
    echo host
    echo swangle_indirect
    echo swiftshader_indirect
  } | awk '!seen[$0]++' |
    # The host GPU needs a desktop session on Linux (this emulator can't use it headless).
    { if has_display; then cat; else grep -vx host; fi; }
}

port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

booted() { [ "$(adb -s "emulator-$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

wait_booted() { # timeout-seconds port...
  local limit=$(( $(date +%s) + $1 )) p all restarts0 restarts; shift
  restarts0=$(wc -l < "$STATE/restarts.log" 2>/dev/null || echo 0)
  while [ "$(date +%s)" -lt "$limit" ]; do
    all=1
    for p in "$@"; do booted "$p" || all=0; done
    [ "$all" = 1 ] && return 0
    # Give up early if they keep dying (this GPU mode doesn't work here).
    restarts=$(wc -l < "$STATE/restarts.log" 2>/dev/null || echo 0)
    [ $((restarts - restarts0)) -ge $(( 2 * $# )) ] && return 1
    sleep 1
  done
  return 1
}

prepare() { # port: what tests want on a fresh device
  adb -s "emulator-$1" shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
  adb -s "emulator-$1" shell svc power stayon true >/dev/null 2>&1
  adb -s "emulator-$1" shell settings put global window_animation_scale 1 >/dev/null 2>&1
}

# Boots once with this GPU mode from cold, checks it can draw, and saves the snapshot the test
# emulators start from. Fails if the emulator can't boot or dies drawing.
make_snapshot() { # gpu
  local gpu=$1 port=5554 pid
  while ! port_free "$port"; do port=$((port + 2)); done
  say "first boot with -gpu $gpu (to save a snapshot)…"
  "$EMU" -avd "$AVD" -port "$port" -no-window -no-audio -no-boot-anim -gpu "$gpu" -no-snapshot-load \
    > "$STATE/snapshot-$gpu.log" 2>&1 &
  pid=$!
  if ! wait_booted 240 "$port" || ! kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; return 1
  fi
  prepare "$port"
  # Draw something real for a few seconds: some software renderers only fail once apps draw.
  adb -s "emulator-$port" shell am start -a android.settings.SETTINGS >/dev/null 2>&1
  sleep 4
  adb -s "emulator-$port" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 1
  kill -0 "$pid" 2>/dev/null || return 1
  adb -s "emulator-$port" emu avd snapshot save "gako-$gpu" >/dev/null 2>&1 || { kill "$pid"; return 1; }
  adb -s "emulator-$port" emu kill >/dev/null 2>&1
  wait "$pid" 2>/dev/null
  touch "$STATE/snapshot-$AVD-$gpu"
  return 0
}

supervise() { # port gpu: run one emulator, restarting it from the snapshot if it dies
  local port=$1 gpu=$2 start pid
  while [ ! -f "$STATE/stop" ]; do
    start=$(date +%s)
    "$EMU" -avd "$AVD" -port "$port" -read-only -no-window -no-audio -no-boot-anim -gpu "$gpu" \
      -snapshot "gako-$gpu" -no-snapshot-save > "$STATE/emulator-$port.log" 2>&1 &
    pid=$!
    echo "$pid" > "$STATE/pid-$port"
    # Set it up once it's booted (a restarted one too).
    for _ in $(seq 120); do
      booted "$port" && { prepare "$port"; break; }
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    wait "$pid"
    [ -f "$STATE/stop" ] && break
    echo "$(date '+%F %T') emulator-$port exited after $(( $(date +%s) - start )) s; restarting" >> "$STATE/restarts.log"
    sleep 2
  done
  rm -f "$STATE/pid-$port"
}

stop_all() {
  local p f pids=""
  touch "$STATE/stop"
  for p in $(cat "$STATE/ports" 2>/dev/null); do adb -s "emulator-$p" emu kill >/dev/null 2>&1 & done
  for f in "$STATE"/pid-*; do [ -f "$f" ] && pids="$pids $(cat "$f")"; done
  wait
  # A few seconds to shut down cleanly (they're read-only: nothing to save), then make sure.
  for _ in $(seq 50); do
    alive=""
    for p in $pids; do kill -0 "$p" 2>/dev/null && alive="$alive $p"; done
    [ -z "$alive" ] && break
    sleep 0.1
  done
  for p in ${alive:-}; do kill -TERM "$p" 2>/dev/null; done
  # The supervisors see the stop file and exit once their emulator has gone.
  local sups=""
  for f in "$STATE"/sup-*; do [ -f "$f" ] && sups="$sups $(cat "$f")"; done
  for _ in $(seq 50); do
    alive=""
    for p in $sups; do kill -0 "$p" 2>/dev/null && alive="$alive $p"; done
    [ -z "$alive" ] && break
    sleep 0.1
  done
  for p in ${alive:-}; do kill -TERM "$p" 2>/dev/null; done
  rm -f "$STATE/ports" "$STATE"/pid-* "$STATE"/sup-*
}

start_all() { # count
  local n=$1 gpu ports p i launch
  launch=(nohup)
  command -v setsid >/dev/null && launch=(setsid nohup)
  for gpu in $(gpu_candidates); do
    if [ ! -f "$STATE/snapshot-$AVD-$gpu" ]; then
      make_snapshot "$gpu" || { say "-gpu $gpu doesn't work here; trying the next"; continue; }
    fi
    rm -f "$STATE/stop"
    ports=(); p=5554
    for i in $(seq "$n"); do
      while ! port_free "$p" || ! port_free $((p + 1)); do p=$((p + 2)); done
      ports+=("$p"); p=$((p + 2))
    done
    echo "${ports[*]}" > "$STATE/ports"
    for p in "${ports[@]}"; do
      "${launch[@]}" "$0" _supervise "$p" "$gpu" > /dev/null 2>&1 < /dev/null &
      echo $! > "$STATE/sup-$p"
    done
    if wait_booted 120 "${ports[@]}"; then
      for p in "${ports[@]}"; do prepare "$p"; done
      echo "$gpu" > "$STATE/gpu-$AVD"
      say "running ${#ports[@]} × $AVD with -gpu $gpu: $(printf 'emulator-%s ' "${ports[@]}")"
      return 0
    fi
    say "-gpu $gpu didn't boot from its snapshot; trying the next"
    stop_all
    rm -f "$STATE/snapshot-$AVD-$gpu" "$STATE/gpu-$AVD"
  done
  die "no emulator setup worked; see the logs in $STATE"
}

AVD=$(pick_avd) || exit 1
case "${1:-start}" in
  start)
    n=${2:-${GAKO_EMULATORS:-$(auto_count)}}
    if [ -s "$STATE/ports" ]; then
      running=0
      for p in $(cat "$STATE/ports"); do booted "$p" && running=$((running + 1)); done
      if [ "$running" -ge "$n" ]; then say "already running: $(cat "$STATE/ports")"; exit 0; fi
      stop_all
    fi
    start_all "$n" ;;
  stop) stop_all; say "stopped" ;;
  status)
    say "AVD $AVD, gpu $(cat "$STATE/gpu-$AVD" 2>/dev/null || echo '?'), ports: $(cat "$STATE/ports" 2>/dev/null || echo none)"
    adb devices | tail -n +2 ;;
  _supervise) supervise "$2" "$3" ;;
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac
