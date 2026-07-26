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
| `UserlandSessionTest` | Debian-under-arm64chroot: installs the rootfs from the APK asset, spawns `arm64chroot_main()` + bash login, proves guest ELF binaries run emulated, `--fake-id` uid 0, `--link2symlink` hard links, dpkg/apt, exit propagation. **Skipped** (JUnit assumption) when no *Debian* rootfs asset is bundled, or when a different distro is already installed on the device |
| `UserlandAlpineSessionTest` | Alpine-under-arm64chroot smoke — the suite that boots the userland in CI (the workflow bundles only the small Alpine asset there): install, BusyBox ash login, emulated `uname`/`id`, offline `apk info`. **Skipped** when no Alpine asset is bundled, when a Debian asset is bundled too and nothing is installed yet (local runs leave the shared rootfs dir to `UserlandSessionTest`), or when the installed rootfs is not Alpine |
| `TerminalUiTest` | Activity-level: typing via the view's `InputConnection`, extra-keys toolbar (ESC, CTRL+ combo), tab create/switch/close. Launches with `EXTRA_FORCE_SHELL` so it tests `/system/bin/sh` regardless of rootfs presence (and never sees onboarding) |
| `OnboardingActivityTest` | First-run wizard flows that install nothing: welcome → chooser walk-through, bundled-distro cards, shell-only completion persisting `onboardingCompleted`, setup-only mode starting at the chooser. **Skipped** when a rootfs is already installed (the wizard then finishes immediately by design) |
| `UserlandDistroTest` | Rootfs asset-name parsing (`<id>_<version>_aarch64_rootfs.tar.xz`) behind the distro chooser |

Polling helper: shell output is asynchronous, so assertions use a small
`waitFor(condition, timeout)` spin instead of fixed sleeps.

## CI

`.github/workflows/ci.yml` runs on every push: one job builds both rootfs
tarballs (Debian via mmdebstrap, Alpine via the minirootfs repackage) and
uploads a debug APK bundling them as an artifact; another runs this whole
suite on an API 34 x86_64 emulator (KVM-accelerated, animations disabled).
The emulator job bundles only the small Alpine rootfs, so
`UserlandAlpineSessionTest` boots the userland in CI while the Debian
`UserlandSessionTest` stays local-only. Test reports are uploaded as an
artifact on failure.

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
- `UserlandSessionTest` needs the Debian rootfs tarball in `UserlandRootfs/`
  at the repo root **at build time** (gitignored — produce it with
  `scripts/build-debian-rootfs.sh`; `scripts/build-alpine-rootfs.sh` builds
  the Alpine one the onboarding chooser offers). The first Debian test of a
  run pays the one-time rootfs extraction on the device (~15 s on an
  emulator); reruns reuse it until the app's data is cleared. Without the
  tarballs the tests are reported as skipped — this is how CI runs.
