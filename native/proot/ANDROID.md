# Vendored PRoot (Termux fork)

`src/`, `COPYING`, and `README.md` are copied verbatim from
https://github.com/termux/proot at `a7147b5` (v5.1.107.78), the fork that
carries the Android-specific extensions (link2symlink, ashmem_memfd,
sysvipc, the arm64 POKEDATA workaround). Built by
`app/src/main/cpp/CMakeLists.txt`, not by `src/GNUmakefile`.

Local modifications, all under `#ifdef PROOT_JNI`:

- `src/cli/cli.c` — `main()` is compiled as `proot_main()` so PRoot can be
  linked into `libterm.so` and entered from the fork()ed PTY child
  (`pty_jni.c`); the final `exit()` calls become `kill_all_tracees()` +
  `_exit()` so the never-exec'd child does not run atexit handlers and DSO
  destructors inherited from the Android runtime.

Plain portability fixes (unconditional):

- `src/extension/ashmem_memfd/ashmem_memfd.c` — added the missing
  `#include <string.h>`; clang ≥ 16 (NDK r28) makes the implicit
  declarations of `memset`/`strcmp` a hard error.

Known limitation of the no-exec model: `--sysvipc` must not be used — its
shared-memory helper re-execs `/proc/self/exe`, which in this process is
the Android runtime, not PRoot.

Build-system substitutes (no upstream file touched):

- `android/build.h` — replaces the GNUmakefile-generated `build.h`.
- The loader is built unbundled (`PROOT_UNBUNDLE_LOADER`) as a separate
  `libproot-loader.so` executable; its path is passed at runtime via the
  `PROOT_LOADER` environment variable. Embedding it (upstream default)
  would require extracting it to app data at runtime, where Android's
  W^X policy (targetSdk >= 29) forbids execve(). It is linked with
  `--image-base` instead of upstream's `-Ttext` — see the PRoot section
  of `docs/native-build.md` for why (`-Ttext` + the NDK's
  `--no-rosegment` makes lld emit a ~128 GiB file).
- `scripts/gen-loader-info.sh` replaces the `loader/loader-info.awk`
  GNUmakefile rule (arm64 POKEDATA workaround) without needing gawk.

PRoot is GPL-2.0-or-later (see `COPYING`); talloc (`native/talloc/`) is
LGPL-3.0-or-later.
