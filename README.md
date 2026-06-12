# AndroidTerm

Terminal emulator for Android backed by the
[Ghostty](https://github.com/ghostty-org/ghostty) VT engine
(`libghostty-vt`). Runs the system shell — no bundled userland.

- Shell: `/system/bin/sh` with `PATH=/system/bin`
- Multiple sessions in tabs
- Special-key toolbar (ESC, CTRL, ALT, TAB, arrows, …) above the touch
  keyboard
- VT emulation, key encoding and scrollback come from Ghostty's terminal
  core via JNI; UI and session management are plain Java

## Requirements

- Android SDK (platform 36, build-tools) and NDK r27+
- JDK 17–21 to run Gradle
- Host `cmake` ≥ 3.22 and `ninja` (or install the SDK cmake package)
- Device/emulator with API 29+ (the Zig-built Ghostty library needs
  bionic ELF TLS support)

Zig is **not** required to build the app; it is only needed to regenerate
the prebuilt Ghostty library (see [docs/native-build.md](docs/native-build.md)).

## Build

Create `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
# Only if the SDK has no cmake package; directory must contain bin/cmake:
cmake.dir=/usr
```

If the NDK is not installed inside the SDK, set `ndkPath` via
`ANDROID_NDK_HOME` or add `android.ndkPath` in `local.properties`.

```sh
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

- **Typing**: tap the terminal to open the keyboard. Text goes straight to
  the shell; arrows/ESC/etc. are encoded per active terminal modes.
- **Toolbar**: `CTRL` and `ALT` are sticky — tap `CTRL`, then `c` to send
  Ctrl-C. Other keys (ESC, TAB, arrows, HOME/END, PGUP/PGDN) send
  immediately.
- **Tabs**: `+` opens a new shell session; tap a tab to switch; `×` closes
  the current one. Closing the last tab exits the app.
- **Scrollback**: drag vertically on the terminal. Any key press snaps back
  to the bottom.
- **Lifecycle**: sessions survive rotation but not process death; there is
  no background service keeping shells alive once the app is killed.

## Tests

Integration tests run on a connected device/emulator:

```sh
scripts/setup-emulator.sh   # one-time AVD creation (needs KVM)
scripts/run-emulator.sh
./gradlew connectedDebugAndroidTest
```

See [docs/testing.md](docs/testing.md).

## Documentation

- [docs/architecture.md](docs/architecture.md) — components, data flow, key
  decisions
- [docs/native-build.md](docs/native-build.md) — how the Ghostty library is
  cross-compiled and how to upgrade it
- [docs/testing.md](docs/testing.md) — test suites and how to run them
