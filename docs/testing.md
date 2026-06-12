# Testing

All meaningful behavior crosses the JNI boundary (PTY, Ghostty VT state),
so the test suite is **instrumented integration tests** under
`app/src/androidTest/` — they run on a device/emulator against the real
native libraries and the real `/system/bin/sh`.

## Suites

| Class | What it proves |
|---|---|
| `EmulatorVtTest` | Ghostty VT correctness through JNI: plain text, SGR colors/attributes, cursor movement, clear/erase, line wrap, resize, scrollback, alt screen, terminal query responses (DA1), key encoding incl. mode-dependent arrows |
| `ShellSessionTest` | End-to-end PTY: spawns `/system/bin/sh`, runs commands, asserts output reaches the screen; verifies `PATH=/system/bin`, working directory, resize delivery (`stty size`), exit reporting |
| `DebianSessionTest` | Debian-under-PRoot: installs the rootfs from the APK asset, spawns `proot_main()` + bash login, proves guest binaries exec through the loader, fake uid 0, `--link2symlink` hard links, dpkg/apt, exit propagation. **Skipped** (JUnit assumption) when no rootfs asset is bundled |
| `TerminalUiTest` | Activity-level: typing via the view's `InputConnection`, extra-keys toolbar (ESC, CTRL+ combo), tab create/switch/close. Launches with `EXTRA_FORCE_SHELL` so it tests `/system/bin/sh` regardless of rootfs presence |

Polling helper: shell output is asynchronous, so assertions use a small
`waitFor(condition, timeout)` spin instead of fixed sleeps.

## CI

`.github/workflows/ci.yml` runs on every push: one job builds and uploads
the debug APK as an artifact, another runs this whole suite on an API 34
x86_64 emulator (KVM-accelerated, animations disabled). Test reports are
uploaded as an artifact on failure.

## Running

```sh
scripts/setup-emulator.sh        # one-time: create AVD (API 34, x86_64)
scripts/run-emulator.sh          # boot headless emulator, wait for boot
./gradlew connectedDebugAndroidTest
```

Results land in `app/build/reports/androidTests/connected/`.

Notes:

- The emulator needs KVM (`/dev/kvm` writable).
- Tests assume an Android image where `/system/bin/sh` exists — i.e. any
  Android image; the suite does not require root.
- `EmulatorVtTest` drives the `TerminalEmulator` directly (no shell), so
  its assertions are deterministic; only `ShellSessionTest`/`TerminalUiTest`
  depend on shell timing, via the polling helper.
- Espresso needs device animations off for reliable clicks:
  `adb shell settings put global window_animation_scale 0` (and the
  `transition_animation_scale`/`animator_duration_scale` equivalents).
- `DebianSessionTest` needs the rootfs tarballs in `DebianRootfs/` at the
  repo root **at build time** (they are gitignored — obtain them
  separately). The first Debian test of a run pays the one-time rootfs
  extraction on the device (~15 s on an emulator); reruns reuse it until
  the app's data is cleared. Without the tarballs the tests are reported
  as skipped — this is how CI runs.
