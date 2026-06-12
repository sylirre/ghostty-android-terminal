#!/bin/sh
# One-time AVD creation for the integration tests (API 34, x86_64).
# Requires SDK cmdline-tools and the system image listed below.
set -eu

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
AVD_NAME=${AVD_NAME:-androidterm-test}
IMAGE="system-images;android-34;google_apis_playstore;x86_64"

AVDMANAGER=$SDK/cmdline-tools/latest/bin/avdmanager
[ -x "$AVDMANAGER" ] || { echo "error: cmdline-tools not installed in $SDK" >&2; exit 1; }

if "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $AVD_NAME$"; then
    echo "AVD $AVD_NAME already exists"
    exit 0
fi

echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE"
echo "Created AVD $AVD_NAME"
