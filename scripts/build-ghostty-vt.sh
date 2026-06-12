#!/bin/sh
# Cross-compile libghostty-vt.a for Android and refresh the committed
# prebuilts under native/ghostty-vt/ (archives + C headers).
#
# Needs Zig 0.15.x (Ghostty rejects other majors) and an Android NDK;
# Ghostty's build reads the NDK location from ANDROID_NDK_HOME for bionic
# libc paths (Zig has no bundled Android libc).
set -eu

ZIG=${ZIG:-zig}
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
GHOSTTY_SRC=${GHOSTTY_SRC:-$REPO_ROOT/third_party/ghostty}
OUT=$REPO_ROOT/native/ghostty-vt

if [ -z "${ANDROID_NDK:-}" ]; then
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        ANDROID_NDK=$ANDROID_NDK_HOME
    else
        # Newest NDK installed in the default SDK location.
        ANDROID_NDK=$(ls -d "$HOME"/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -1)
    fi
fi
[ -d "${ANDROID_NDK:-}" ] || { echo "error: set ANDROID_NDK to an NDK root" >&2; exit 1; }
[ -d "$GHOSTTY_SRC" ] || { echo "error: run scripts/fetch-ghostty.sh first" >&2; exit 1; }

echo "zig:     $($ZIG version)"
echo "ndk:     $ANDROID_NDK"
echo "ghostty: $(git -C "$GHOSTTY_SRC" rev-parse HEAD)"

build_abi() {
    abi=$1
    triple=$2
    echo "== $abi ($triple)"
    (cd "$GHOSTTY_SRC" && ANDROID_NDK_HOME=$ANDROID_NDK "$ZIG" build \
        -Demit-lib-vt=true "-Dtarget=$triple" -Doptimize=ReleaseFast)
    mkdir -p "$OUT/prebuilt/$abi"
    cp "$GHOSTTY_SRC/zig-out/lib/libghostty-vt.a" "$OUT/prebuilt/$abi/"
}

build_abi arm64-v8a aarch64-linux-android
build_abi x86_64    x86_64-linux-android

# Headers must match the archives, so refresh them together.
rm -rf "$OUT/include"
mkdir -p "$OUT/include/ghostty"
cp "$GHOSTTY_SRC/include/ghostty/vt.h" "$OUT/include/ghostty/"
cp -r "$GHOSTTY_SRC/include/ghostty/vt" "$OUT/include/ghostty/"

echo "Prebuilts updated in $OUT"
