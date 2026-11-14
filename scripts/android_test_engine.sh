#!/usr/bin/env bash
# Cross-build the engine's unit tests and headless runner for arm64 Android, push them to a
# connected device or emulator (adb), and run the core suites there.
#   ANDROID_SERIAL=emulator-5554 scripts/android_test_engine.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$( [ -d "$HOME/Library/Android/sdk" ] && echo "$HOME/Library/Android/sdk" || echo "$HOME/Android/Sdk" )}}"  # Android Studio: macOS, else Linux
NDK="${ANDROID_NDK:-$SDK/ndk/30.0.16248370}"
CMAKE_BIN="${CMAKE_BIN:-$SDK/cmake/4.1.2/bin}"
ADB="$SDK/platform-tools/adb"
B="$ROOT/build/android"
DEV=/data/local/tmp/winvm

"$CMAKE_BIN/cmake" -S "$ROOT" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 >/dev/null
"$CMAKE_BIN/ninja" -C "$B"

TESTS="test_classify test_jit_region test_operands test_jit test_mem test_mmu test_exc"
"$ADB" shell mkdir -p $DEV
for t in $TESTS test_bench winvm hvsparse; do "$ADB" push "$B/$t" $DEV/ >/dev/null; done
"$ADB" shell "getprop ro.product.model; getconf PAGESIZE; grep -m1 Features /proc/cpuinfo"
# The tests need a deep stack (the harness keeps whole CPUs on it), as on macOS.
"$ADB" shell "cd $DEV && chmod +x * && ulimit -s 65520 && fail=0 && for t in $TESTS; do echo == \$t; ./\$t | tail -1 || fail=1; done; ./test_bench | tail -5; exit \$fail"
