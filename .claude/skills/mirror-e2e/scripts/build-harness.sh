#!/bin/sh
# macOS/Linux twin of build-harness.ps1: builds vgamepad and pushes the suite to the device.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set}"
HOST=$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64
CLANG="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android23-clang"
mkdir -p "$ROOT/build"
"$CLANG" -O2 -Wall -Wextra -std=c11 -D_GNU_SOURCE "$ROOT/scripts/vgamepad.c" -o "$ROOT/build/vgamepad"
adb push "$ROOT/build/vgamepad" /data/local/tmp/vgamepad >/dev/null
# The daemon the APK ships, for devices whose shell cannot read the app's private copy.
adb push "$ROOT/../../../android/app/src/main/assets/input_mirror/input_mirror" /data/local/tmp/input_mirror >/dev/null
adb push "$ROOT/scripts/e2e.sh" /data/local/tmp/e2e.sh >/dev/null
adb push "$ROOT/scripts/latency.sh" /data/local/tmp/latency.sh >/dev/null
adb shell chmod 755 /data/local/tmp/input_mirror /data/local/tmp/vgamepad /data/local/tmp/e2e.sh /data/local/tmp/latency.sh
echo "Harness ready on device: /data/local/tmp/{vgamepad,e2e.sh,latency.sh}"
