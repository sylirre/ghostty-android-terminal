# Native build

Two native artifacts ship in the app:

1. **`libghostty-vt.a`** — Ghostty's VT core, cross-compiled with Zig.
   Prebuilt per ABI and committed under `native/ghostty-vt/prebuilt/`.
2. **`libterm.so`** — JNI glue (`app/src/main/cpp/`), built by the normal
   AGP/CMake pipeline against the prebuilt archive and the vendored headers
   in `native/ghostty-vt/include/`.

Day-to-day app builds only need the Android SDK + NDK. Zig is needed only
to regenerate the prebuilts (e.g. to bump the Ghostty version).

## Regenerating libghostty-vt

Requirements:

- Zig **0.15.x** (Ghostty pins its minimum and rejects newer majors —
  0.16 does not work).
- Android NDK (for bionic libc headers/stubs; r27+ tested with r28c).
- Network access on first run (`zig build` fetches Ghostty's Zig deps).

```sh
scripts/fetch-ghostty.sh                  # clone + checkout pinned commit
scripts/build-ghostty-vt.sh               # build arm64-v8a + x86_64, copy
                                          # .a and headers into native/
```

Both scripts read these environment variables (defaults in parentheses):

- `ZIG` — zig executable (`zig`)
- `ANDROID_NDK` — NDK root (`$HOME/android-tools/android-ndk-r28c`)
- `GHOSTTY_SRC` — checkout location (`$HOME/android-tools/ghostty-src`)

The pinned Ghostty commit lives at the top of `scripts/fetch-ghostty.sh`.
To upgrade: change the hash, rerun both scripts, run the integration tests,
commit the regenerated `native/ghostty-vt/` tree.

### How the cross-compile works

Zig does not bundle Android's bionic libc, so the build script generates a
`libc.txt` per ABI pointing into the NDK sysroot and passes it via the
`ZIG_LIBC` environment variable:

```
include_dir=$NDK/.../sysroot/usr/include
sys_include_dir=$NDK/.../sysroot/usr/include/<triple>
crt_dir=$NDK/.../sysroot/usr/lib/<triple>/<api-level>
```

The actual library build is Ghostty's own target:

```sh
zig build -Demit-lib-vt=true -Dtarget=aarch64-linux-android -Doptimize=ReleaseFast
```

which installs `lib/libghostty-vt.a` into `zig-out/`.

## libterm.so

Standard AGP `externalNativeBuild` with CMake. `CMakeLists.txt` imports the
prebuilt archive per `${ANDROID_ABI}` and links it into one shared library
together with the two JNI translation units. ABIs are restricted to
`arm64-v8a` and `x86_64` (the prebuilt set); add more by extending the build
script's target list.

The host `cmake`/`ninja` are used via `cmake.dir` in `local.properties` if
the SDK has no cmake package installed.
