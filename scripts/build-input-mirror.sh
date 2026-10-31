#!/bin/sh
# macOS/Linux twin of build-input-mirror.ps1: same flags, same output, same .sha256.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set}"
HOST=$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64  # NDK ships darwin-x86_64 even for Apple silicon
CLANG="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android23-clang"
OUT="$ROOT/android/app/src/main/assets/input_mirror/input_mirror"
mkdir -p "$(dirname "$OUT")"
"$CLANG" -O3 -Wall -Wextra -Werror -std=c11 -D_GNU_SOURCE -D_FORTIFY_SOURCE=2 \
    -fstack-protector-strong -fPIE -Wl,-pie -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack \
    "$ROOT"/android/app/src/main/native/*.c -o "$OUT"
HASH=$(shasum -a 256 "$OUT" | cut -d' ' -f1)
printf '%s' "$HASH" > "$OUT.sha256"
echo "Built $OUT"
echo "  sha256 $HASH"
