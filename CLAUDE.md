# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android terminal emulator backed by Ghostty's VT engine (`libghostty-vt`).
Runs a Linux userland under `arm64chroot` — a bundled from-scratch AArch64
Linux user-space emulator (qemu-user-style ISA emulation + proot-style rootfs
containment, optional `--jit`) — so the aarch64 rootfs runs on both arm64-v8a
(JIT arm64→arm64) and x86_64 (JIT arm64→x86_64) hosts. Rootfs tarballs are
optional, gitignored APK assets from `UserlandRootfs/` — one per distro,
named `<id>_<version>_aarch64_rootfs.tar.xz` (built by
`scripts/build-alpine-rootfs.sh` / `scripts/build-debian-rootfs.sh`; aarch64
only, always); a first-run onboarding wizard (`OnboardingActivity`) explains
the app and installs the chosen distro. Also `/system/bin/sh` with
`PATH=/system/bin`; session tabs; extra-keys toolbar above the soft keyboard.
minSdk 29, targetSdk 36, ABIs arm64-v8a + x86_64.

Docs: [docs/architecture.md](docs/architecture.md) (design, data flow, key
decisions), [docs/native-build.md](docs/native-build.md) (Ghostty
cross-compile pipeline), [docs/testing.md](docs/testing.md) (test suites and
emulator setup), [README.md](README.md) (build requirements, usage).

## Commands

Gradle must run on JDK 17–21. If the system `java` is newer, prefix every
gradlew call, e.g.:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

```sh
./gradlew :app:assembleDebug                  # build APK (also compiles JNI via CMake)
./gradlew connectedDebugAndroidTest           # all integration tests (needs device/emulator)

# Single class or test:
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.sylirre.terminal.EmulatorVtTest#lineWrap

scripts/setup-emulator.sh                     # one-time AVD creation (API 34 x86_64)
scripts/run-emulator.sh                       # boot it headless; needs /dev/kvm
```

There are no JVM unit tests: everything meaningful crosses JNI, so the whole
suite is instrumented (`app/src/androidTest/`). Espresso requires device
animations off (`adb shell settings put global window_animation_scale 0`,
plus `transition_animation_scale` and `animator_duration_scale`).

Regenerating the Ghostty prebuilts is only needed when bumping the pinned
commit (top of `scripts/fetch-ghostty.sh`) and requires Zig **0.15.x
exactly** — Ghostty rejects other majors at build time:

```sh
scripts/fetch-ghostty.sh && ZIG=/path/to/zig-0.15 scripts/build-ghostty-vt.sh
```

## Architecture

Three layers; native code is limited to what Java cannot do.

```
Java  app/src/main/java/io/github/sylirre/terminal/
  term/  TerminalNative (JNI surface + shared constants)
         TerminalEmulator (owns the native handle, all calls synchronized)
         TerminalSession (PTY + shell pid + reader thread)
         SessionCommand (execve command or arm64chroot argv + env + tab label)
         UserlandDistro (bundled rootfs asset discovery for the distro chooser)
         UserlandRootfs (rootfs asset → tar.xz install → arm64chroot command line)
         SessionManager (process singleton: sessions survive Activity recreation)
         ScreenSnapshot (flat viewport arrays for rendering)
  ui/    TerminalView (Canvas grid renderer + TYPE_NULL InputConnection)
         ExtraKeysView, TabStripView, MainActivity
         OnboardingActivity (first-run intro + distro chooser + install)
         Chrome/ChromePalette/TopBarView/EdgeInsets/Dialogs/KeyCaps (shared
         chrome: drawable factories + design tokens, theme-derived main-screen
         palette, secondary-screen top bar + insets, dialog kit, keycap factory)
JNI   app/src/main/cpp/   → libterm.so (CMake, NDK)
  pty_jni.c       openpt/fork + execve(sh) or arm64chroot_main(), TIOCSWINSZ, waitpid/kill
  terminal_jni.c  libghostty-vt bindings, snapshot flattening, key encoding
C     native/arm64chroot/ → arm64chroot (AArch64 user-space emulator) linked
                            into libterm.so; vendored from a sibling project,
                            `main` renamed under ANDROID_JNI. No loader.
Zig   native/ghostty-vt/  → libghostty-vt.a prebuilt per ABI + vendored headers
```

Data flow: reader thread reads the PTY → `emulator.feed()` → response bytes
(DA/DSR replies) written back to the PTY → coalesced `onUpdate` on the main
thread → `TerminalView` pulls a fresh `ScreenSnapshot` in `onDraw`.

### Invariants that hold the design together

- **libghostty-vt is not thread-safe.** Every native call goes through a
  synchronized `TerminalEmulator` method; after `close()` the handle is 0
  and all methods no-op. The reader thread frees the emulator on PTY EOF;
  racing UI calls are fenced by the same lock.
- **Ghostty effects are polled, not pushed.** Write-pty bytes, bell, and
  title-change are buffered in the native `TermCtx` during
  `ghostty_terminal_vt_write` and consumed by Java right after `feed()`.
  There are deliberately no native→Java upcalls.
- **Java is a dumb renderer.** The snapshot resolves colors to final ARGB
  natively (defaults, inverse, faint, invisible, selection highlight,
  palette lookups). The default fg/bg/cursor/palette are themeable:
  `TerminalEmulator.setColors` pushes them into libghostty-vt and the render
  state resolves cells through them (UI in `ui/TerminalTheme` +
  `ThemeActivity`). The main screen's chrome (bars, tabs, keycaps, search)
  recolors from the theme background via `ui/ChromePalette` — dark themes
  resolve to the stock token chrome, light ones derive a light chrome; the
  secondary screens keep the fixed dark tokens. The `meta[]` array is 16 ints (`[15]` = effective cursor
  color, 0 = unset); its layout is defined in `terminal_jni.c` and mirrored by
  `ScreenSnapshot` accessors; `ATTR_*`/`EVENT_*`/`MOD_*`/`SEL_*` constants
  must stay in sync between `terminal_jni.c` and `TerminalNative`.
- **The selection lives in the terminal, not the view.** Long-press installs
  it via `GHOSTTY_TERMINAL_OPT_SELECTION` (tracked grid refs), so it follows
  its text across scroll/output/reflow; `TerminalView` only draws handles at
  the endpoint coordinates the snapshot reports and never stores cell
  positions (docs/architecture.md, "Selection and clipboard").
- **Ghostty C callbacks must be assigned through their typedefs** (see
  `write_pty_fn` etc. in terminal_jni.c). `ghostty_terminal_set` takes
  `void*`, so a signature mismatch compiles silently and SIGSEGVs at
  runtime — this happened once already.
- **Input has two paths.** Printable text is written to the PTY as raw
  UTF-8; special keys and ctrl/alt combos go through Ghostty's key encoder,
  which honors terminal modes (e.g. DECCKM arrow encoding). The Android
  keycode → GhosttyKey mapping lives in C (`map_keycode`). An opt-in *rich
  keyboard* mode (off by default) adds a third path: a `TYPE_CLASS_TEXT`
  composing `InputConnection` whose local `Editable` is mirrored to the PTY
  by diffing (`reconcileRich` in `TerminalView`). It auto-disables inside
  full-screen/raw-key apps via `meta[14]` mode flags (alt-screen, DECCKM)
  and resets on any special key, Enter, or mode change — it's a best-effort
  mirror, not a source of truth (docs/architecture.md, "Keyboard").
- **Nothing is ever exec'd for the userland session.** W^X (targetSdk ≥ 29)
  forbids exec under app data, and arm64chroot is a pure emulator — guest
  `execve` is an in-process reload, so no host binary is ever exec'd. It is
  linked into libterm.so and entered via `arm64chroot_main()` in the fork()ed
  PTY child (`pty_jni.c`), which `_exit()`s its return. There is no loader and
  no `PROOT_*` environment; the old loader packaging (`useLegacyPackaging`) is
  gone. `--jit` is W^X-aware: its code cache tries RWX anon, falls back to a
  `memfd` dual-map under SELinux `execmem`, then to the interpreter — safe to
  leave on.
- **arm64chroot's `--jit` is W^X-aware.** Its code cache tries RWX anon,
  falls back to a `memfd` dual-map under SELinux `execmem`, then to the
  interpreter — so `--jit` is safe to leave on across devices.

### Behavior constraints discovered the hard way

- **mksh wipes its prompt on SIGWINCH** (`\r` + spaces, no reprint).
  Sessions therefore spawn only after `TerminalView`'s first layout
  (`MainActivity`), and `TerminalSession.resize` skips no-op resizes.
  Don't reintroduce resizes at spawn time.
- **minSdk must stay ≥ 29**: the Zig-built archive uses ELF TLS, which
  bionic supports only from API 29.
- `TERM=xterm-256color` (not `xterm-ghostty`): Android has no terminfo.
- **Apps can't hard-link or create device nodes**: the rootfs installer
  copies tar hard-link entries and skips device nodes; at runtime arm64chroot
  runs with `--link2symlink` (hard links via tracked `.l2s.*` symlinks),
  synthesizes a guest-correct `/proc`, and whitelists the host `/dev`, so only
  `/sys` needs binding in (`--bind /sys:/sys`; `/dev` and `/proc` do not).

## Test conventions

- Suites: `EmulatorVtTest` (deterministic VT/encoder through JNI, no
  shell), `ShellSessionTest` (real `sh` over a PTY), `UserlandSessionTest`
  (bash under arm64chroot; skips itself when no Debian rootfs asset is
  bundled or another distro is installed), `TerminalUiTest`
  (ActivityScenario + Espresso; launches with
  `MainActivity.EXTRA_FORCE_SHELL` so it always tests plain sh and never
  sees onboarding), `OnboardingActivityTest` (wizard flows that install
  nothing; skips itself when a rootfs is already installed).
- Shell output is asynchronous: poll with `TestUtil.waitFor`, never fixed
  sleeps. Pass the optional diagnostic supplier so timeouts dump the screen.
- Write escape sequences as `\u001b` string escapes, never raw control
  bytes in source files.
- Toolbar keys at the right end of the strip must be scrolled into view
  before Espresso can click them (see `extraKeysTypeIntoShell`).
- Assert on the app-id path suffix (`io.github.sylirre.terminal/files`), not on
  `getFilesDir()` verbatim — the kernel resolves cwd through the
  `/data/data` symlink.

## CI

`.github/workflows/ci.yml`: a build job builds both rootfs tarballs (Debian
via mmdebstrap, Alpine via the minirootfs repackage) and uploads a debug APK
bundling them; an emulator job (KVM, animations off) runs the full
instrumented suite and uploads test reports on failure. Zig is not needed in
CI — the Ghostty prebuilts are committed. The rootfs tarballs are NOT in the
repo (built fresh per run); the emulator job bundles only the small Alpine
one, so `UserlandAlpineSessionTest` boots the userland in CI while the
Debian `UserlandSessionTest` skips there — run that one locally with the
tarball in `UserlandRootfs/` (docs/testing.md).
