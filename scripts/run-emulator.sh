#!/bin/sh
# Boot the test AVD headless and wait until Android finishes booting.
# Needs KVM. No-op if a device is already connected.
set -eu

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
AVD_NAME=${AVD_NAME:-androidterm-test}
ADB=$SDK/platform-tools/adb

if [ -n "$("$ADB" devices | sed -n '2p')" ]; then
    echo "Device already connected"
    exit 0
fi

"$SDK/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot &

"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done
echo "Emulator booted"
