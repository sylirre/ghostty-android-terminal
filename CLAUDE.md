# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android terminal emulator backed by Ghostty's VT engine (`libghostty-vt`).
Runs a Debian userland under PRoot (rootfs bundled as an optional,
gitignored APK asset from `DebianRootfs/`) and/or `/system/bin/sh` with
`PATH=/system/bin`; session tabs; extra-keys toolbar above the soft
keyboard. minSdk 29, targetSdk 36, ABIs arm64-v8a + x86_64.

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
  -Pandroid.testInstrumentationRunnerArguments.class=dev.androidterm.EmulatorVtTest#lineWrap

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
Java  app/src/main/java/dev/androidterm/
  term/  TerminalNative (JNI surface + shared constants)
         TerminalEmulator (owns the native handle, all calls synchronized)
         TerminalSession (PTY + shell pid + reader thread)
         SessionCommand (execve command or PRoot argv + env + tab label)
         DebianRootfs (rootfs asset → tar.xz install → PRoot command line)
         SessionManager (process singleton: sessions survive Activity recreation)
         ScreenSnapshot (flat viewport arrays for rendering)
  ui/    TerminalView (Canvas grid renderer + TYPE_NULL InputConnection)
         ExtraKeysView, TabStripView, MainActivity
JNI   app/src/main/cpp/   → libterm.so (CMake, NDK)
  pty_jni.c       openpt/fork + execve(sh) or proot_main(), TIOCSWINSZ, waitpid/kill
  terminal_jni.c  libghostty-vt bindings, snapshot flattening, key encoding
C     native/proot/       → PRoot (Termux fork) linked into libterm.so,
                            plus the libproot-loader.so executable
      native/talloc/      → talloc (PRoot dependency); local mods in
                            native/proot/ANDROID.md
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
  palette lookups). The `meta[]` layout is defined in `terminal_jni.c` and
  mirrored by `ScreenSnapshot` accessors; `ATTR_*`/`EVENT_*`/`MOD_*`/`SEL_*`
  constants must stay in sync between `terminal_jni.c` and `TerminalNative`.
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
  keycode → GhosttyKey mapping lives in C (`map_keycode`).
- **Nothing proot-shaped is ever exec'd.** W^X (targetSdk ≥ 29) forbids
  exec under app data, so PRoot is linked into libterm.so and entered via
  `proot_main()` in the fork()ed PTY child; only its loader — a static
  executable packaged as `libproot-loader.so` so it lands in
  nativeLibraryDir — is execve'd (path via `PROOT_LOADER`). Consequences:
  `useLegacyPackaging true` must stay; `--sysvipc` must stay unused (its
  helper re-execs /proc/self/exe = the Android runtime); PRoot exits via
  `_exit()` (see native/proot/ANDROID.md).
- **The loader links with `--image-base`, not upstream's `-Ttext`.** The
  NDK adds `--no-rosegment`; combined with `-Ttext` lld emits a file
  spanning the ~128 GiB to the loader address (this filled a disk once).

### Behavior constraints discovered the hard way

- **mksh wipes its prompt on SIGWINCH** (`\r` + spaces, no reprint).
  Sessions therefore spawn only after `TerminalView`'s first layout
  (`MainActivity`), and `TerminalSession.resize` skips no-op resizes.
  Don't reintroduce resizes at spawn time.
- **minSdk must stay ≥ 29**: the Zig-built archive uses ELF TLS, which
  bionic supports only from API 29.
- `TERM=xterm-256color` (not `xterm-ghostty`): Android has no terminfo.
- **Apps can't hard-link or create device nodes**: the rootfs installer
  copies tar hard-link entries and skips device nodes; at runtime PRoot
  runs with `--link2symlink` and binds the host /dev, /proc, /sys.

## Test conventions

- Suites: `EmulatorVtTest` (deterministic VT/encoder through JNI, no
  shell), `ShellSessionTest` (real `sh` over a PTY), `DebianSessionTest`
  (bash under PRoot; skips itself when no rootfs asset is bundled),
  `TerminalUiTest` (ActivityScenario + Espresso; launches with
  `MainActivity.EXTRA_FORCE_SHELL` so it always tests plain sh).
- Shell output is asynchronous: poll with `TestUtil.waitFor`, never fixed
  sleeps. Pass the optional diagnostic supplier so timeouts dump the screen.
- Write escape sequences as `\u001b` string escapes, never raw control
  bytes in source files.
- Toolbar keys at the right end of the strip must be scrolled into view
  before Espresso can click them (see `extraKeysTypeIntoShell`).
- Assert on the app-id path suffix (`dev.androidterm/files`), not on
  `getFilesDir()` verbatim — the kernel resolves cwd through the
  `/data/data` symlink.

## CI

`.github/workflows/ci.yml`: a build job uploads the debug APK artifact; an
emulator job (KVM, animations off) runs the full instrumented suite and
uploads test reports on failure. Zig is not needed in CI — the Ghostty
prebuilts are committed. The Debian rootfs tarballs are NOT in the repo,
so CI builds without Debian assets and `DebianSessionTest` is skipped;
run it locally with the tarballs in `DebianRootfs/` (docs/testing.md).
