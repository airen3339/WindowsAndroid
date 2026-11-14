#!/usr/bin/env bash
# Cross-compile libffi + glib + libslirp as static arm64 Android libraries for the
# guest's user-mode network (host/net_slirp.c), into android-deps/arm64-v8a/.
# The engine's CMake build picks them up from there automatically (WINVM_NET=ON).
#
#   ANDROID_NDK=/path/to/ndk/30.0.16248370 scripts/build_android_deps.sh   (default: the SDK's NDK r30)
#
# Needs: meson, ninja, pkg-config, python3, curl. Everything is built in work/android_deps_build/.
set -euo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"  # Homebrew on Apple silicon or Intel; harmless elsewhere

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API="${ANDROID_API:-34}"
ABI=arm64-v8a
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$( [ -d "$HOME/Library/Android/sdk" ] && echo "$HOME/Library/Android/sdk" || echo "$HOME/Android/Sdk" )}}"  # Android Studio: macOS, else Linux
NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-$SDK/ndk/30.0.16248370}}"
[ -d "$NDK" ] || { echo "NDK not found at $NDK (set ANDROID_NDK)"; exit 1; }
case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;   # the NDK ships universal binaries under this name
  Linux) HOST_TAG=linux-x86_64 ;;
  *) echo "unsupported build host"; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
TRIPLE=aarch64-linux-android

WORK="$ROOT/work/android_deps_build"
DL="$WORK/sources"
SRC="$WORK/src"
PREFIX="$ROOT/android-deps/$ABI"
STAMPS="$WORK/stamps"
mkdir -p "$DL" "$SRC" "$PREFIX" "$STAMPS"
NCPU="$(getconf _NPROCESSORS_ONLN)"

FFI_VER=3.8.0
GLIB_VER=2.90.1
SLIRP_VER=4.9.7
FFI_URL="https://github.com/libffi/libffi/releases/download/v$FFI_VER/libffi-$FFI_VER.tar.gz"
GLIB_URL="https://download.gnome.org/sources/glib/${GLIB_VER%.*}/glib-$GLIB_VER.tar.xz"
SLIRP_URL="https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v$SLIRP_VER/libslirp-v$SLIRP_VER.tar.gz"

export CC="$TC/bin/clang --target=$TRIPLE$API"
export CXX="$TC/bin/clang++ --target=$TRIPLE$API"
export AR="$TC/bin/llvm-ar"
export NM="$TC/bin/llvm-nm"
export RANLIB="$TC/bin/llvm-ranlib"
export STRIP="$TC/bin/llvm-strip"
# -fPIC: these end up inside libwinandroid.so. 16 KB pages: Android 15+ devices may use them.
export CFLAGS="-O2 -fPIC -I$PREFIX/include"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-L$PREFIX/lib -Wl,-z,max-page-size=16384"
export PKG_CONFIG="$(command -v pkg-config)"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
unset PKG_CONFIG_PATH

fetch_unpack() {
  local url="$1" name="$2"
  local file="$DL/$name"
  if [ ! -s "$file" ]; then
    echo "[fetch] $name"
    curl -fL --retry 3 -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  if [ ! -f "$STAMPS/unpack-$name" ]; then
    echo "[unpack] $name"
    tar -xf "$file" -C "$SRC"
    touch "$STAMPS/unpack-$name"
  fi
}

fetch_unpack "$FFI_URL" "libffi-$FFI_VER.tar.gz"
fetch_unpack "$GLIB_URL" "glib-$GLIB_VER.tar.xz"
fetch_unpack "$SLIRP_URL" "libslirp-v$SLIRP_VER.tar.gz"

CROSS="$WORK/cross-android.meson"
q() { printf "'%s'," "$@" | sed 's/,$//'; }
cat > "$CROSS" <<EOF
[properties]
needs_exe_wrapper = true
[built-in options]
c_args = [$(q $CFLAGS)]
cpp_args = [$(q $CXXFLAGS)]
c_link_args = [$(q $LDFLAGS)]
cpp_link_args = [$(q $LDFLAGS)]
[binaries]
c = [$(q $CC)]
cpp = [$(q $CXX)]
ar = '$AR'
nm = '$NM'
ranlib = '$RANLIB'
strip = '$STRIP'
pkg-config = '$PKG_CONFIG'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

if [ ! -f "$STAMPS/libffi" ]; then
  echo "[build] libffi-$FFI_VER"
  ( cd "$SRC/libffi-$FFI_VER" \
    && ./configure --host=$TRIPLE --prefix="$PREFIX" --enable-static --disable-shared --disable-docs \
    && make -j"$NCPU" && make install ) > "$WORK/libffi.log" 2>&1 || { tail -30 "$WORK/libffi.log"; exit 1; }
  touch "$STAMPS/libffi"
fi

if [ ! -f "$STAMPS/glib" ]; then
  echo "[build] glib-$GLIB_VER"
  rm -rf "$SRC/glib-$GLIB_VER/_android"
  ( cd "$SRC/glib-$GLIB_VER" \
    && meson setup _android --cross-file "$CROSS" --prefix="$PREFIX" --libdir=lib \
         --buildtype=release --default-library=static --wrap-mode=default \
         -Dtests=false -Dnls=disabled -Dintrospection=disabled -Dselinux=disabled \
         -Dlibmount=disabled -Ddtrace=disabled -Dsystemtap=disabled -Dman-pages=disabled \
         -Ddocumentation=false -Dglib_debug=disabled -Dxattr=false -Dsysprof=disabled \
    && meson compile -C _android -j "$NCPU" \
    && meson install -C _android ) > "$WORK/glib.log" 2>&1 || { tail -40 "$WORK/glib.log"; exit 1; }
  touch "$STAMPS/glib"
fi

# Android has no /etc/resolv.conf, which is where libslirp looks for the host's DNS servers
# (the guest's 10.0.2.3 forwards there). Let the app point it at a file it keeps up to date from
# ConnectivityManager: SLIRP_RESOLV_CONF, falling back to the usual path.
SLIRP_C="$SRC/libslirp-v$SLIRP_VER/src/slirp.c"
if ! grep -q SLIRP_RESOLV_CONF "$SLIRP_C"; then
  python3 - "$SLIRP_C" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '#define RESOLV_CONF_PATH "/etc/resolv.conf"'
new = ('#if defined(__ANDROID__)\n'
       '#define RESOLV_CONF_PATH (getenv("SLIRP_RESOLV_CONF") ? getenv("SLIRP_RESOLV_CONF") : "/etc/resolv.conf")\n'
       '#else\n' + old + '\n#endif')
assert old in s
open(p, "w").write(s.replace(old, new, 1))
PY
  rm -f "$STAMPS/libslirp"
fi

if [ ! -f "$STAMPS/libslirp" ]; then
  echo "[build] libslirp-$SLIRP_VER"
  rm -rf "$SRC/libslirp-v$SLIRP_VER/_android"
  ( cd "$SRC/libslirp-v$SLIRP_VER" \
    && meson setup _android --cross-file "$CROSS" --prefix="$PREFIX" --libdir=lib \
         --buildtype=release --default-library=static \
    && meson compile -C _android -j "$NCPU" \
    && meson install -C _android ) > "$WORK/libslirp.log" 2>&1 || { tail -40 "$WORK/libslirp.log"; exit 1; }
  touch "$STAMPS/libslirp"
fi

echo "Android deps in $PREFIX:"
ls -la "$PREFIX/lib"/*.a
