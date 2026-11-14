#!/usr/bin/env bash
# Copy a machine (UEFI vars + Windows disk, optionally UEFI code) into the Android app over adb.
# The disk travels as .hvsparse (holes left out: a 32 GB image with 10 GB of data moves 10 GB)
# and is expanded on the device as it arrives, by the engine's own hvsparse tool reading from
# the adb stream, so the phone never holds two copies.
#   scripts/android_push_machine.sh - work/vars.fd work/windows11.img
# The first argument is a code.fd to use instead of the firmware built into the app, or "-" for
# the built-in one (which can boot Windows at an external display's resolution).
# Launch the app once first so that it owns its Android/data folder.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$( [ -d "$HOME/Library/Android/sdk" ] && echo "$HOME/Library/Android/sdk" || echo "$HOME/Android/Sdk" )}}"  # Android Studio: macOS, else Linux
ADB="$SDK/platform-tools/adb"
CODE="$1" VARS="$2" DISK="${3:-}"
DEST=/sdcard/Android/data/com.windowsandroid/files/Machines/Win11

"$ADB" shell "ls $DEST >/dev/null" || {
  echo "Open WindowsAndroid on the device once (it creates $DEST), then run this again."; exit 1; }
"$ADB" shell am force-stop com.windowsandroid
if [ "$CODE" = "-" ]; then
  "$ADB" shell rm -f "$DEST/code.fd"
else
  "$ADB" push "$CODE" "$DEST/code.fd"
  "$ADB" shell chmod 666 "$DEST/code.fd"
fi
"$ADB" push "$VARS" "$DEST/vars.fd"
"$ADB" shell chmod 666 "$DEST/vars.fd"
if [ -n "$DISK" ]; then
  case "$DISK" in
    *.hvsparse) SPARSE="$DISK" ;;
    *)
      SPARSE="${DISK%.*}.hvsparse"
      [ -f "$ROOT/build/hvsparse" ] || make -C "$ROOT" build/hvsparse
      if [ ! -f "$SPARSE" ] || [ "$DISK" -nt "$SPARSE" ]; then "$ROOT/build/hvsparse" pack "$DISK" "$SPARSE"; fi ;;
  esac
  B="$ROOT/build/android"
  [ -x "$B/hvsparse" ] || "$ROOT/scripts/android_test_engine.sh" >/dev/null
  "$ADB" shell mkdir -p /data/local/tmp/winvm
  "$ADB" push "$B/hvsparse" /data/local/tmp/winvm/hvsparse >/dev/null
  "$ADB" shell rm -f "$DEST/windows11.img" "$DEST/windows11.hvsparse" "$DEST/windows11.img.unpacking"
  echo "Streaming $(du -h "$SPARSE" | cut -f1) into $DEST/windows11.img ..."
  "$ADB" exec-in "/data/local/tmp/winvm/hvsparse unpack /dev/stdin $DEST/windows11.img" < "$SPARSE"
  # Written by adb's shell user: let the app (another user in the same group) write it too.
  "$ADB" shell chmod 666 "$DEST/windows11.img"
  "$ADB" shell ls -ls "$DEST"
fi
echo "Done. Start Windows in the app."
