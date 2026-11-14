#!/usr/bin/env bash
# Build the UEFI firmware (code.fd): EDK2 ArmVirtQemu with one patch to its ramfb driver,
# so the host can choose the screen resolution Windows boots at (the stock driver offers
# only 640x480, 800x600 and 1024x768, and Windows keeps whatever mode it booted in).
#
#   scripts/build_firmware.sh            # -> work/code.fd (64 MB, padded like QEMU's)
#
# The engine passes the resolution in the fw_cfg file opt/org.windowsandroid/ramfb-resolution
# ("1920x1080"); without that file the firmware behaves exactly like stock (800x600).
# Toolchain: the NDK's clang/lld (EDK2's CLANGDWARF profile); needs git, make, python3, iasl
# (Homebrew: acpica).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EDK2_TAG="${EDK2_TAG:-edk2-stable202608}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$( [ -d "$HOME/Library/Android/sdk" ] && echo "$HOME/Library/Android/sdk" || echo "$HOME/Android/Sdk" )}}"  # Android Studio: macOS, else Linux
NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-$SDK/ndk/30.0.16248370}}"
case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  Linux) HOST_TAG=linux-x86_64 ;;
  *) echo "unsupported build host"; exit 1 ;;
esac
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"  # Homebrew on Apple silicon or Intel; harmless elsewhere
export CLANGDWARF_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/"
[ -x "${CLANGDWARF_BIN}clang" ] || { echo "NDK clang not found under $NDK (set ANDROID_NDK)"; exit 1; }

WORK="$ROOT/work/edk2build"
SRC="$WORK/edk2"
OUT="${1:-$ROOT/work/code.fd}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
NCPU="$(getconf _NPROCESSORS_ONLN)"
mkdir -p "$WORK"

if [ ! -d "$SRC/.git" ]; then
  git clone -q --depth 1 --branch "$EDK2_TAG" https://github.com/tianocore/edk2.git "$SRC"
fi
cd "$SRC"
# Package declarations reference every submodule's include directory, so fetch them all (shallow).
git -c advice.detachedHead=false submodule update -q --init --depth 1 --jobs 8

for p in "$ROOT"/scripts/firmware/*.patch; do
  if git apply --check "$p" 2>/dev/null; then
    git apply "$p"
  elif ! git apply --check -R "$p" 2>/dev/null; then
    echo "patch does not apply: $p"; exit 1
  fi
done

# BaseTools' C utilities (GenFw, GenFv, LzmaCompress, ...) for the build host.
[ -x BaseTools/Source/C/bin/GenFw ] || make -C BaseTools/Source/C -j"$NCPU" >"$WORK/basetools.log" 2>&1 \
  || { tail -30 "$WORK/basetools.log"; exit 1; }

set +u
# shellcheck disable=SC1091
source ./edksetup.sh BaseTools >/dev/null
set -u
# Release build, no debug output on the serial port (like QEMU's edk2-aarch64-code.fd).
build -q -n "$NCPU" -a AARCH64 -t CLANGDWARF -b RELEASE -p ArmVirtPkg/ArmVirtQemu.dsc \
  -D DEBUG_ON_SERIAL_PORT=FALSE >"$WORK/build.log" 2>&1 || { tail -40 "$WORK/build.log"; exit 1; }

FD="$SRC/Build/ArmVirtQemu-AArch64/RELEASE_CLANGDWARF/FV/QEMU_EFI.fd"
cp "$FD" "$OUT.tmp"
truncate -s 64M "$OUT.tmp"   # pflash bank 0 is 64 MB
mv "$OUT.tmp" "$OUT"
echo "firmware: $OUT ($EDK2_TAG + $(ls "$ROOT"/scripts/firmware/*.patch | wc -l | tr -d ' ') patch)"
