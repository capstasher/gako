#!/usr/bin/env bash
# Copies the cross-built engine into the app as jniLibs.
#
#   native/stage-jnilibs.sh android-arm64 arm64-v8a
#
# Android only packages files named lib*.so from jniLibs, so GEGL operations and
# babl extensions are renamed with a prefix; at startup the app symlinks them back
# into directories named the way GEGL and babl expect (see Engine.kt).
#   gegl-0.4/gegl-common.so  ->  libgeglop_gegl-common.so
#   babl-0.1/u8.so           ->  libbablext_u8.so
# Shared libraries are pulled in by following DT_NEEDED from libgako.so, the ops and
# the extensions, and the result is checked so nothing is missing at runtime.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
TARGET="${1:?target, e.g. android-arm64}"
ABI="${2:?abi, e.g. arm64-v8a}"
ROOT="${GAKO_NATIVE_ROOT:-$REPO/build/native}"
PREFIX="$ROOT/$TARGET/prefix"
OUT="$REPO/app/src/main/jniLibs/$ABI"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
READELF="$TC/bin/llvm-readelf"
STRIP="$TC/bin/llvm-strip"
case "$ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android ;;
  x86_64)    TRIPLE=x86_64-linux-android ;;
  *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
esac

# Libraries the platform provides to apps (NDK stable APIs).
SYSTEM_LIBS=" libc.so libm.so libdl.so liblog.so libz.so libandroid.so libjnigraphics.so libEGL.so
 libGLESv1_CM.so libGLESv2.so libGLESv3.so libvulkan.so libOpenSLES.so libmediandk.so
 libnativewindow.so libsync.so libcamera2ndk.so libbinder_ndk.so libaaudio.so libicu.so "

rm -rf "$OUT"
mkdir -p "$OUT"

needed() { "$READELF" -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'; }

declare -A STAGED=()
QUEUE=()

stage() { # source-file dest-name
  [ -n "${STAGED[$2]:-}" ] && return
  cp -L "$1" "$OUT/$2"
  chmod 644 "$OUT/$2"
  "$STRIP" --strip-unneeded "$OUT/$2"
  STAGED[$2]=1
  QUEUE+=("$OUT/$2")
}

stage "$PREFIX/lib/libgako.so" libgako.so
for f in "$PREFIX"/lib/gegl-0.4/*.so; do stage "$f" "libgeglop_$(basename "$f")"; done
for f in "$PREFIX"/lib/babl-0.1/*.so; do stage "$f" "libbablext_$(basename "$f")"; done

# Follow DT_NEEDED until every dependency is staged or provided by the system.
missing=0
while [ ${#QUEUE[@]} -gt 0 ]; do
  lib="${QUEUE[0]}"; QUEUE=("${QUEUE[@]:1}")
  for dep in $(needed "$lib"); do
    [ -n "${STAGED[$dep]:-}" ] && continue
    case "$SYSTEM_LIBS" in *" $dep "*) continue ;; esac
    if [ -e "$PREFIX/lib/$dep" ]; then
      stage "$PREFIX/lib/$dep" "$dep"
    elif [ "$dep" = libc++_shared.so ]; then
      stage "$TC/sysroot/usr/lib/$TRIPLE/libc++_shared.so" libc++_shared.so
    else
      echo "MISSING: $(basename "$lib") needs $dep" >&2
      missing=1
    fi
  done
done
[ "$missing" = 0 ] || { echo "Some dependencies could not be found; refusing to build a broken APK." >&2; exit 1; }

# Any soname that isn't a plain lib*.so would never be found inside an APK.
for f in "$OUT"/*.so; do
  soname=$("$READELF" -d "$f" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')
  case "$soname" in ""|lib*.so) ;; *) echo "BAD SONAME $soname in $(basename "$f")" >&2; exit 1 ;; esac
done

count=$(ls "$OUT" | wc -l)
size=$(du -sh "$OUT" | cut -f1)
echo "Staged $count libraries ($size) into $OUT"
ls "$OUT" | sed 's/^/  /'
