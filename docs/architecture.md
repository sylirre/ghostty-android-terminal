# Architecture

AndroidTerm is a terminal emulator for Android built on the
[Ghostty](https://github.com/ghostty-org/ghostty) VT engine (`libghostty-vt`).
It runs a full Debian userland under [PRoot](https://proot-me.github.io/)
(when a rootfs is bundled) or the stock `/system/bin/sh`, supports multiple
sessions in tabs, and shows a special-key toolbar above the touch keyboard.

## Layering

```
┌─────────────────────────────────────────────────────────┐
│ Java (UI + session management)                          │
│  MainActivity ─ TabStripView ─ TerminalView ─ ExtraKeys │
│  SessionManager ─ TerminalSession ─ TerminalEmulator    │
│  DebianRootfs (rootfs install + PRoot command line)     │
├──────────────────────── JNI ────────────────────────────┤
│ libterm.so (C, built by NDK/CMake)                      │
│  pty_jni.c      — PTY create/resize, fork + exec sh     │
│                   or fork + proot_main() for Debian     │
│  terminal_jni.c — bindings to libghostty-vt             │
│  PRoot + talloc — linked in as static libraries         │
├─────────────────────────────────────────────────────────┤
│ libghostty-vt.a (Zig, prebuilt per ABI)                 │
│  VT parser, screen state, render state, key encoder     │
└─────────────────────────────────────────────────────────┘
   + libproot-loader.so — a tiny static *executable* in
     jniLibs clothing, exec'd by PRoot tracees
```

Native code is limited to what Java cannot do: PTY syscalls, the Ghostty
C API, and PRoot's ptrace machinery. Everything else (rendering, tabs,
input, key toolbar, rootfs install) is Java.

## Native layer

### libghostty-vt

Ghostty's terminal core as a C library. We use:

- `ghostty_terminal_new/free/resize` — terminal lifecycle.
- `ghostty_terminal_vt_write` — feed bytes read from the PTY.
- `GHOSTTY_TERMINAL_OPT_WRITE_PTY_CALLBACK` — the terminal's answers to
  queries (DA, DSR, …) must be written back to the shell; the callback fires
  synchronously inside `vt_write`, so the JNI layer collects the bytes into a
  buffer and returns them from the same JNI call. This avoids native→Java
  upcalls entirely.
- `ghostty_render_state_*` — row/cell iteration for drawing. The JNI layer
  flattens the viewport into flat Java arrays (codepoint, fg ARGB, bg ARGB,
  attribute bits) per snapshot; colors are resolved to concrete RGB natively
  so the Java renderer needs no palette logic.
- `ghostty_key_encoder_*` — converts key events to escape sequences honoring
  terminal modes (DECCKM etc.). Synced with `setopt_from_terminal` before
  each encode.
- `ghostty_terminal_scroll_viewport` + scrollbar state — scrollback.

The library is built from a pinned Ghostty commit with Zig (see
[native-build.md](native-build.md)) and the resulting `libghostty-vt.a` is
committed per ABI so app builds need only the Android SDK/NDK, not Zig.

### libterm.so (JNI glue)

- `pty_jni.c`: `posix_openpt` + `fork` + `execve("/system/bin/sh")` with a
  minimal environment (`PATH=/system/bin`, `TERM=xterm-256color`,
  `HOME=<app files dir>`). The child becomes session leader and sets the PTY
  as controlling TTY. The master fd is returned to Java, which wraps it in a
  `ParcelFileDescriptor` so reads/writes are plain Java streams.
- `terminal_jni.c`: thin wrappers over the libghostty-vt calls above. One
  `long` handle per terminal; no global state besides the JNI references.

Threading: libghostty-vt is not thread-safe. Java serializes all native
calls per session with a single lock (`TerminalEmulator` monitor); the PTY
reader thread feeds bytes, the UI thread takes snapshots.

### Debian under PRoot

PRoot fakes a chroot with `ptrace`: it intercepts every syscall of its
tracees and rewrites paths so the Debian rootfs (extracted to
`filesDir/debian`) appears as `/`. The integration has three pieces, all
shaped by Android's W^X rule (targetSdk ≥ 29 cannot `execve()` anything
under app data):

1. **PRoot is linked into `libterm.so`** (vendored Termux fork,
   `native/proot/`; its `main()` becomes `proot_main()` under
   `#ifdef PROOT_JNI`). There is no proot binary to exec — the fork()ed
   PTY child calls `proot_main()` directly and becomes the tracer. Because
   that child never execs, PRoot's exits are `_exit()` (not `exit()`) so
   atexit handlers and DSO destructors inherited from the Android runtime
   never run. See `native/proot/ANDROID.md`.
2. **The loader rides the jniLibs pipeline.** Tracees must exec a real
   loader executable (it maps the guest ELF into the tracee image), and the
   only exec-allowed app location is `nativeLibraryDir`. So the loader is
   built as a static executable *named* `libproot-loader.so`, packaged as
   if it were a library (`useLegacyPackaging true` forces extraction to
   disk), and handed to PRoot via the `PROOT_LOADER` environment variable.
   The upstream default — embed the loader and extract it to a temp file at
   runtime — would die on W^X.
3. **The rootfs is an optional APK asset.** `DebianRootfs` extracts
   `debian_trixie_<arch>_rootfs.tar.xz` (bundled from `DebianRootfs/` at
   the repo root when present; never committed) on first launch with a
   minimal tar reader over `org.tukaani:xz`. Hard-link entries are copied
   (apps cannot `link(2)`); device nodes are skipped (PRoot binds the host
   `/dev`, `/proc`, `/sys`). At runtime `--link2symlink` translates guest
   hard links and `-0` fakes uid 0, which keeps dpkg/apt working.

The session command is `proot --kill-on-exit --link2symlink -0 -r <rootfs>
-w /root -b /dev -b /proc -b /sys /usr/bin/env -i HOME=/root … /bin/bash
--login`; `env -i` keeps host/PROOT variables out of the guest. PRoot's
`--sysvipc` is deliberately not used: its shm helper re-execs
`/proc/self/exe`, which is the Android runtime (not proot) in this
no-exec model.

## Java layer

| Class | Role |
|---|---|
| `TerminalNative` | `static native` declarations, `System.loadLibrary` |
| `TerminalEmulator` | Owns the native terminal handle; feed/resize/snapshot/encode under one lock |
| `ScreenSnapshot` | Reusable flat-array copy of the viewport + cursor for rendering |
| `SessionCommand` | What to spawn: execve command or PRoot argv, env, cwd, tab label |
| `DebianRootfs` | Rootfs asset detection + tar.xz install + PRoot command construction |
| `TerminalSession` | PTY fd + shell pid + reader thread; writes input; reports exit |
| `SessionManager` | Process-wide session list; survives Activity recreation |
| `TerminalView` | Canvas grid renderer, IME connection, scroll + pinch-zoom gestures |
| `ExtraKeysView` | ESC/CTRL/ALT/TAB/arrows… toolbar; CTRL/ALT are sticky modifiers |
| `TabStripView` | Horizontal session tabs + new-tab button |
| `MainActivity` | Wires the above, handles window insets |

### Data flow

```
shell output:  PTY master ──reader thread──▶ emulator.feed()
                  ▲                              │ response bytes (DA/DSR…)
                  └──────────────────────────────┘
               feed() marks dirty ──▶ main thread ──▶ TerminalView.invalidate()
               onDraw: snapshot under lock, draw grid

user input:    IME text ──▶ session.write(utf8)
               special keys ──▶ emulator.encodeKey() ──▶ session.write(bytes)
```

Render updates are coalesced: the reader thread posts at most one pending
UI callback; `onDraw` always pulls the latest snapshot, so intermediate
frames are skipped naturally under load.

### Rendering

`TerminalView` draws a monospace cell grid with `Canvas.drawText`, batching
consecutive cells that share fg color and text attributes into single draw
calls. Cell size derives from font metrics; on layout the view computes
cols/rows and resizes the PTY + terminal. Wide (CJK) glyphs occupy two cells
(the trailing spacer cell has codepoint 0 and is skipped).

### Kitty graphics

libghostty-vt parses and stores images and placements for the [Kitty
graphics protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/); the
app supplies only enablement, a PNG decoder, and compositing. Image tools
also need the terminal's pixel size, reported two ways. The PTY winsize
carries `ws_xpixel`/`ws_ypixel` (cols/rows × cell size) so programs like
Kitty's `icat` can read them via `TIOCGWINSZ`; they are seeded into the
initial winsize at spawn because the session starts at its final grid size
and never resizes. The escape-query path is answered too: a
`GHOSTTY_TERMINAL_OPT_SIZE` callback fills the current grid and cell pixel
size for XTWINOPS queries (`CSI 14 t` text-area pixels, `CSI 16 t` cell
pixels, `CSI 18 t` text-area cells), and ghostty encodes the reply back
through the write-pty path. `terminalNew`
sets a non-zero `GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT` (zero would
keep the protocol off) and installs a process-global PNG decode callback —
vendored stb_image, in its own `pngdec` target so the third-party header
compiles clear of libterm.so's `-Wall -Werror`. The decoder is needed only
for PNG payloads (`f=100`); ghostty decompresses and PNG-decodes on store,
so stored images are always uncompressed gray/gray+alpha/rgb/rgba.

Images are a second snapshot channel, separate from the cell grid.
`terminalGraphics` packs per-placement geometry (image id + dimensions,
viewport cell position, rendered pixel size, source rect, z, sub-cell pixel
offset) into a flat `int[]`, mirrored by `TerminalNative.GFX_*` — same
grow-and-retry contract as the cell snapshot. `terminalImage` returns one
image's pixels as RGBA8888 (the in-memory order of Android's `ARGB_8888`).
Borrowed handles and pixel pointers are invalidated by the next mutating
call, so each function consumes them within a single JNI call. `TerminalView`
keeps a `Bitmap` cache keyed by image id (re-fetched only when the id is new
or its dimensions changed, evicted when no longer placed) and draws
placements in two `onDraw` passes — z<0 below the text, z≥0 (the Kitty
default) above it. Viewport col/row go negative for images scrolled off the
top/left; the canvas clip handles the partial draw.

Virtual placements (Unicode placeholders) have no position of their own —
the image appears wherever the program prints the placeholder codepoint
`U+10EEEE`, with the image id in the cell's foreground color and the image
row/col fragment in combining "rowcolumn" diacritics. `terminalGraphics`
handles them in a second pass: it collects the virtual placements, scans the
viewport for placeholder cells, groups horizontally adjacent cells that
continue the same fragment into runs, and emits each run as an ordinary GFX
record so the renderer stays oblivious. The decode and aspect-ratio layout
math (`kitty_unicode.c`) is a direct port of ghostty's
`graphics_unicode.zig`, including its 297-entry diacritic table.

### Selection and clipboard

Long-press selects the word under the finger (Ghostty's
`ghostty_terminal_select_word`; a blank cell falls back to selecting just
that cell so the gesture always yields a paste anchor). The selection is
installed as the *terminal's* active selection
(`GHOSTTY_TERMINAL_OPT_SELECTION`), where Ghostty converts it to tracked
grid refs — so it stays glued to its text across scrolling, new output,
and resize/reflow with no Java-side bookkeeping. The snapshot reports
selected cells with fg/bg swapped (inverse video, Ghostty's default
selection style — the Java renderer needed zero changes) and the
forward-ordered endpoint viewport coordinates in `meta[9..13]` for handle
placement.

`TerminalView` draws the system `textSelectHandleLeft/Right` drawables
under the endpoints and shows a floating `ActionMode` toolbar: Copy
always, Paste only when the clipboard advertises text (checked via
`ClipDescription` so the button itself doesn't trigger Android's
clipboard-access toast). Dragging a handle first reorders the selection so
the grabbed endpoint is the logical end (`terminalSelectionAnchor`), then
moves only that end (`terminalSelectionDrag`) — dragging across the other
endpoint flips the selection naturally, and dragging past the top/bottom
edge scrolls the viewport. Copy extracts text with
`ghostty_terminal_selection_format_alloc` (unwrapped, trimmed); Paste runs
the clip through `ghostty_paste_encode`, which strips unsafe control bytes
and applies bracketed-paste markers (mode 2004) or newline→CR. Typing, a
tap outside the handles, or switching sessions dismisses the selection.

### Keyboard and extra keys

The view's `InputConnection` uses `TYPE_NULL` so soft keyboards deliver
plain key events and `commitText` instead of rich editing — the standard
trick for terminal apps. Printable text is written to the PTY as UTF-8;
navigation/function keys go through the Ghostty key encoder so applications
that switch modes (e.g. vi's DECCKM cursor keys) get correct sequences.

`ExtraKeysView` sits between the terminal and the IME. The activity is
edge-to-edge (targetSdk 36 enforces it); an insets listener pads the root by
`max(ime, navigationBars)` so the toolbar always rides directly above the
soft keyboard.

### Sessions and tabs

`SessionManager` is a process singleton, so rotation/recreation keeps shells
alive. Sessions end when the process is killed (no foreground service —
a deliberate scope cut, documented in the README). Closing the last tab
finishes the activity.

When a Debian rootfs is installed, new tabs default to Debian and
long-pressing `+` opens an Android `/system/bin/sh` tab (and vice versa
when it isn't). On first launch with a bundled-but-uninstalled rootfs,
`MainActivity` extracts it on a background thread behind a progress
overlay, then opens the first Debian tab. `MainActivity.EXTRA_FORCE_SHELL`
pins the default to the Android shell — a test seam so UI tests don't
depend on whether a rootfs is bundled.

## Decisions worth remembering

- **Prebuilt `libghostty-vt.a` is committed.** Building it needs an exact
  Zig version; vendoring ~2 build inputs (Zig + Ghostty checkout) into every
  app build would be fragile. The script + pinned commit make rebuilds
  reproducible.
- **Effects are polled, not pushed.** All Ghostty callbacks (write-pty,
  title, bell) are buffered natively and consumed by Java right after
  `feed()` — no `AttachCurrentThread` juggling.
- **`TERM=xterm-256color`**, not `xterm-ghostty`: Android has no terminfo
  database and the stock toybox/mksh tools only assume xterm-ish behavior.
- **Shells spawn after the first view layout, and no-op resizes are
  skipped.** Android's `/system/bin/sh` (mksh) reacts to SIGWINCH by wiping
  its prompt line (`\r` + spaces) *without* reprinting it. Spawning at a
  guessed size and resizing on layout left users staring at a blank screen
  until the first keypress.
- **No appcompat/material dependency.** All views are custom-drawn anyway;
  plain `android.app.Activity` keeps the dependency graph and build minimal.
- **PRoot runs in a fork()ed child, never exec'd.** W^X leaves nowhere in
  app data to exec a proot binary from, and shipping it as a fake jniLib
  would still waste a copy — linking it into `libterm.so` and calling
  `proot_main()` after `fork()` needs no exec at all. The child only uses
  fork-safe machinery (bionic takes its allocator locks across fork), and
  the tracees exec fresh images anyway.
- **The loader links with `--image-base`, not upstream's `-Ttext`.** The
  NDK toolchain passes `--no-rosegment`, which folds the ELF headers (at
  the default low base) and `.text` (at the loader address) into a single
  load segment; lld then emits a file spanning the ~128 GiB between them.
  `--image-base` puts the entire image at the loader address: ~4 KiB file,
  and no loader mappings down in ranges where non-PIE guests load.
- **The rootfs is an optional asset, not a download.** Builds stay
  hermetic and offline-testable; a missing tarball just disables Debian
  (CI builds this way). The cost — a fatter APK — is acceptable for a
  development project. The tar reader is local code over `org.tukaani:xz`
  rather than commons-compress: the rootfs only needs files, dirs and
  links, and the dependency graph stays small.
