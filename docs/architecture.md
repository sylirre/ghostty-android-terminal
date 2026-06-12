# Architecture

AndroidTerm is a terminal emulator for Android built on the
[Ghostty](https://github.com/ghostty-org/ghostty) VT engine (`libghostty-vt`).
It runs `/system/bin/sh` with `PATH=/system/bin`, supports multiple sessions
in tabs, and shows a special-key toolbar above the touch keyboard.

## Layering

```
┌─────────────────────────────────────────────────────────┐
│ Java (UI + session management)                          │
│  MainActivity ─ TabStripView ─ TerminalView ─ ExtraKeys │
│  SessionManager ─ TerminalSession ─ TerminalEmulator    │
├──────────────────────── JNI ────────────────────────────┤
│ libterm.so (C, built by NDK/CMake)                      │
│  pty_jni.c      — PTY create/resize, fork+exec sh       │
│  terminal_jni.c — bindings to libghostty-vt             │
├─────────────────────────────────────────────────────────┤
│ libghostty-vt.a (Zig, prebuilt per ABI)                 │
│  VT parser, screen state, render state, key encoder     │
└─────────────────────────────────────────────────────────┘
```

Native code is limited to what Java cannot do: PTY syscalls and the Ghostty
C API. Everything else (rendering, tabs, input, key toolbar) is Java.

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

## Java layer

| Class | Role |
|---|---|
| `TerminalNative` | `static native` declarations, `System.loadLibrary` |
| `TerminalEmulator` | Owns the native terminal handle; feed/resize/snapshot/encode under one lock |
| `ScreenSnapshot` | Reusable flat-array copy of the viewport + cursor for rendering |
| `TerminalSession` | PTY fd + shell pid + reader thread; writes input; reports exit |
| `SessionManager` | Process-wide session list; survives Activity recreation |
| `TerminalView` | Canvas grid renderer, IME connection, scroll gestures |
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
- **No appcompat/material dependency.** All views are custom-drawn anyway;
  plain `android.app.Activity` keeps the dependency graph and build minimal.
