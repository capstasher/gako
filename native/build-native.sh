#!/usr/bin/env bash
# Builds GAKO's native engine and bridge.
#
#   native/build-native.sh android-arm64   # cross-compile with the Android NDK
#   native/build-native.sh host            # build for this Linux machine (for testing the bridge)
#
# Output: build/native/<target>/prefix  (headers, libs, GEGL ops, babl extensions)
#
# Android needs ANDROID_NDK_HOME (or ANDROID_NDK_LATEST_HOME) pointing at an NDK.
# The host target uses the system glib and only builds json-glib, libjpeg-turbo, babl and GEGL.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
# shellcheck source=versions.env
source "$HERE/versions.env"

TARGET="${1:?usage: build-native.sh <android-arm64|host>}"
ROOT="${GAKO_NATIVE_ROOT:-$REPO/build/native}"
SRC="$ROOT/src"
BLD="$ROOT/$TARGET/build"
PREFIX="$ROOT/$TARGET/prefix"
mkdir -p "$SRC" "$BLD" "$PREFIX"
JOBS="$(nproc)"

log() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }

fetch() { # dir repo tag
  if [ ! -d "$SRC/$1" ]; then
    log "fetch $2 @ $3"
    git -c advice.detachedHead=false clone -q --depth 1 --branch "$3" "https://github.com/$2.git" "$SRC/$1"
  fi
}

fetch glib           GNOME/glib                  "$GLIB_TAG"
fetch json-glib      GNOME/json-glib             "$JSON_GLIB_TAG"
fetch libpng         pnggroup/libpng             "$LIBPNG_TAG"
fetch libjpeg-turbo  libjpeg-turbo/libjpeg-turbo "$LIBJPEG_TURBO_TAG"
fetch babl           GNOME/babl                  "$BABL_TAG"
fetch gegl           GNOME/gegl                  "$GEGL_TAG"

# --------------------------------------------------------------------------
# Toolchain
MESON_ARGS=(--prefix "$PREFIX" --libdir lib --buildtype release -Ddefault_library=shared -Db_ndebug=if-release)
CMAKE_ARGS=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib
            -DCMAKE_PREFIX_PATH="$PREFIX")

case "$TARGET" in
  android-arm64)
    NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
    [ -d "$NDK" ] || { echo "Set ANDROID_NDK_HOME to an Android NDK" >&2; exit 1; }
    TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    log "NDK $(grep -m1 Pkg.Revision "$NDK/source.properties" | cut -d= -f2 | tr -d ' ') — API $ANDROID_API"
    CROSS="$BLD/android-arm64.cross"
    mkdir -p "$BLD"
    cat > "$CROSS" <<EOF
[binaries]
c = '$TC/bin/aarch64-linux-android$ANDROID_API-clang'
cpp = '$TC/bin/aarch64-linux-android$ANDROID_API-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
ranlib = '$TC/bin/llvm-ranlib'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-fPIC', '-O2']
cpp_args = ['-fPIC', '-O2']
c_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1']
cpp_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1']

[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['$PREFIX/lib/pkgconfig']

[host_machine]
system = 'android'
subsystem = 'android'
kernel = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
    MESON_ARGS+=(--cross-file "$CROSS")
    CMAKE_ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
                 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$ANDROID_API"
                 -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
                 -DCMAKE_FIND_ROOT_PATH="$PREFIX")
    export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
    unset PKG_CONFIG_PATH || true
    ;;
  host)
    export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
    ;;
  *) echo "unknown target $TARGET" >&2; exit 1 ;;
esac

meson_build() { # name srcdir [options...]
  local name=$1 src=$2; shift 2
  if [ -f "$BLD/$name/.gako-installed" ]; then log "$name (cached)"; return; fi
  log "build $name"
  rm -rf "${BLD:?}/$name"
  meson setup "$BLD/$name" "$src" "${MESON_ARGS[@]}" "$@"
  meson compile -C "$BLD/$name" -j "$JOBS"
  meson install -C "$BLD/$name" --no-rebuild
  touch "$BLD/$name/.gako-installed"
}

cmake_build() { # name srcdir [options...]
  local name=$1 src=$2; shift 2
  if [ -f "$BLD/$name/.gako-installed" ]; then log "$name (cached)"; return; fi
  log "build $name"
  rm -rf "${BLD:?}/$name"
  cmake -S "$src" -B "$BLD/$name" "${CMAKE_ARGS[@]}" "$@"
  cmake --build "$BLD/$name" -j "$JOBS"
  cmake --install "$BLD/$name"
  touch "$BLD/$name/.gako-installed"
}

# --------------------------------------------------------------------------
# glib (Android only; the host build uses the system copy).
# libffi, pcre2 and proxy-libintl come from glib's own meson subprojects.
if [ "$TARGET" != host ]; then
  meson_build glib "$SRC/glib" \
    --wrap-mode=default --force-fallback-for=libffi,pcre2,proxy-libintl \
    -Dtests=false -Dinstalled_tests=false -Dintrospection=disabled -Dnls=disabled \
    -Dselinux=disabled -Dxattr=false -Dlibmount=disabled -Dman-pages=disabled \
    -Ddocumentation=false -Dsysprof=disabled -Dlibelf=disabled -Ddtrace=disabled \
    -Dsystemtap=disabled -Dglib_debug=disabled -Dfile_monitor_backend=auto
fi

meson_build json-glib "$SRC/json-glib" \
  -Dintrospection=disabled -Ddocumentation=disabled -Dgtk_doc=disabled -Dman=false \
  -Dtests=false -Dconformance=false -Dnls=disabled -Dinstalled_tests=false

if [ "$TARGET" != host ] || ! pkg-config --exists libpng16; then
  cmake_build libpng "$SRC/libpng" \
    -DPNG_SHARED=ON -DPNG_STATIC=OFF -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_FRAMEWORK=OFF
fi

cmake_build libjpeg-turbo "$SRC/libjpeg-turbo" \
  -DENABLE_SHARED=ON -DENABLE_STATIC=OFF -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF \
  -DREQUIRE_SIMD=OFF

meson_build babl "$SRC/babl" \
  -Dwith-docs=false -Denable-gir=false -Denable-vapi=false -Dgi-docgen=disabled -Dwith-lcms=disabled

GEGL_OFF=()
for f in gdk-pixbuf gexiv2 graphviz jasper lcms lensfun libav libraw librsvg libspiro libtiff \
         libv4l libv4l2 lua mrg maxflow openexr openmp cairo pango pangocairo poppler pygobject \
         sdl1 sdl2 sdl3 umfpack webp gi-docgen vapigen; do
  GEGL_OFF+=("-D$f=disabled")
done
meson_build gegl "$SRC/gegl" \
  -Ddocs=false -Dgtk-doc=false -Dintrospection=false -Dworkshop=false \
  -Dparallel-tests=false "${GEGL_OFF[@]}"

# --------------------------------------------------------------------------
# GAKO's bridge (always rebuilt: it's ours and small).
log "build gako bridge"
rm -rf "$BLD/bridge"
meson setup "$BLD/bridge" "$HERE/bridge" "${MESON_ARGS[@]}" -Dtarget="$TARGET"
meson compile -C "$BLD/bridge" -j "$JOBS"
meson install -C "$BLD/bridge" --no-rebuild

log "done: $PREFIX"
