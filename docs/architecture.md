# Architecture

Terminal is a terminal emulator for Android built on the
[Ghostty](https://github.com/ghostty-org/ghostty) VT engine (`libghostty-vt`).
It runs a full Linux distribution userland under `arm64chroot` — a bundled
from-scratch AArch64 Linux user-space emulator — (when a rootfs is bundled)
or the stock `/system/bin/sh`, supports multiple sessions in tabs, and shows
a special-key toolbar above the touch keyboard.

## Layering

```
┌─────────────────────────────────────────────────────────┐
│ Java (UI + session management)                          │
│  MainActivity ─ TabStripView ─ TerminalView ─ ExtraKeys │
│  SessionManager ─ TerminalSession ─ TerminalEmulator    │
│  UserlandRootfs (rootfs install + arm64chroot command)  │
├──────────────────────── JNI ────────────────────────────┤
│ libterm.so (C, built by NDK/CMake)                      │
│  pty_jni.c      — PTY create/resize, fork + exec sh     │
│                   or fork + arm64chroot_main()          │
│  terminal_jni.c — bindings to libghostty-vt             │
│  arm64chroot    — AArch64 emulator, linked in static    │
├─────────────────────────────────────────────────────────┤
│ libghostty-vt.a (Zig, prebuilt per ABI)                 │
│  VT parser, screen state, render state, key encoder     │
└─────────────────────────────────────────────────────────┘
```

Native code is limited to what Java cannot do: PTY syscalls, the Ghostty
C API, and arm64chroot's emulator. Everything else (rendering, tabs,
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

### Linux userland under arm64chroot

`arm64chroot` is a from-scratch AArch64 Linux user-space emulator: it
**emulates the guest instruction set** (an interpreter, or a translating
`--jit`) and rewrites guest paths so the Debian or other rootfs (extracted to
`filesDir/userland`) appears as `/`. Unlike the PRoot it replaces it uses **no
`ptrace` and no privilege**, and because it emulates the ISA it runs the
**aarch64** rootfs on both arm64-v8a (JIT arm64→arm64) and x86_64 (JIT
arm64→x86_64) hosts. The integration:

1. **arm64chroot is linked into `libterm.so`** (vendored from a sibling
   project into `native/arm64chroot/`; its `main()` becomes
   `arm64chroot_main()` under `#ifdef ANDROID_JNI`). The fork()ed PTY
   child calls it directly and `_exit()`s its return, so atexit handlers and
   DSO destructors inherited from the Android runtime never run.
2. **Nothing is exec'd — there is no loader.** arm64chroot is a pure
   emulator: a guest `execve` is an *in-process ELF reload*, not a host
   `execve`, so no host binary is ever run. That sidesteps Android's W^X rule
   (targetSdk ≥ 29 cannot `execve()` under app data) with no loader trick at
   all — the old `libproot-loader.so` / `useLegacyPackaging` machinery is
   gone. The `--jit` code cache is W^X-aware: RWX anon → `memfd` dual-map under
   SELinux `execmem` → interpreter fallback.
3. **The rootfs is an optional APK asset — one per bundled distro.**
   Tarballs named `<id>_<version>_aarch64_rootfs.tar.xz` (produced by
   `scripts/build-debian-rootfs.sh` / `scripts/build-alpine-rootfs.sh` into
   `UserlandRootfs/` at the repo root; never committed) ride along as assets
   when present. `UserlandDistro` maps the asset names back to choosable
   distributions for the onboarding chooser, and `UserlandRootfs.install`
   extracts the chosen one with a minimal tar reader over `org.tukaani:xz`.
   It is always an aarch64 rootfs — the x86_64
   host runs it emulated. Hard-link entries are copied (apps cannot `link(2)`);
   device nodes are skipped. At runtime `--link2symlink` translates guest hard
   links and `--fake-id` fakes uid 0, which keeps dpkg/apt working; arm64chroot
   synthesizes a guest-correct `/proc` and whitelists the host `/dev`, so only
   `/sys` is bound in explicitly (`--bind /sys:/sys`).
4. **The rootfs can be backed up and restored** (Settings → Back up /
   Restore Userland). `RootfsBackup` walks the live tree with `lstat`/`readlink`
   and streams it to a user-chosen file (SAF) as a gzip-compressed,
   GNU-tar-dialect archive preserving file bytes, directory layout, symlink
   targets, and mode bits (sticky/setuid included). Guest hard links — which
   arm64chroot's `--link2symlink` stores as same-directory symlinks to a
   `.l2s.<ino>` backing file (plus a `.l2s.<ino>.<count>` marker) — are
   **inlined**: each is emitted as a plain regular file holding the backing
   content (the proot-distro approach), and the raw `.l2s.*` entries are dropped,
   so the archive is self-contained and portable rather than a web of symlinks
   that dangle on `tar xzf`.
   A pre-pass (`ensureReadable`) first grants the owner the minimum bits to read
   every entry so a deliberately unreadable file/dir isn't silently lost. The
   format is the exact subset `UserlandRootfs.extractTar` already reads, so restore
   reuses that reader: it extracts into `debian.tmp` and only the final atomic
   rename (`publish`) swaps it onto `debian`, leaving the existing rootfs intact
   if a restore fails or is cancelled. Restore also accepts foreign rootfs
   tarballs, not just our own backups: compression is autodetected (`tarStream`
   sniffs the leading magic and inflates gzip or xz — the codec the bundled
   rootfs ships in, via the same `org.tukaani:xz` decoder — on the fly, otherwise
   reads a plain uncompressed `.tar`), and a first pass probes the member names
   (`probeStripCount`/`detectStripCount`, ported from proot-distro) to detect how
   many wrapper directories to strip so a nested rootfs (e.g. under `distro/`)
   lands at the root, then a second pass extracts with that strip.
   It is hardened against an arbitrary picked file: the reader confines every
   write inside `debian.tmp` (a planted `evil -> /` symlink can't redirect a
   later `evil/x` onto the host, while usrmerge symlinks that resolve within the
   tree still work). A corrupt/non-tar file fails extraction and leaves the old
   rootfs intact, but a structurally valid archive is installed *as given* — if
   its login shell (`/bin/bash`) is missing it's kept and the user is warned
   rather than blocked, so custom images still install. (The trusted
   bundled-asset install skips the per-entry guard and the strip.) Restore tears
   down all sessions first (no live emulator may hold the tree being replaced) and
   skips the install-time `writeGuestDefaults` so the archive is reproduced
   verbatim. Both directions drive a determinate
   progress bar: backup against a pre-pass `measure` of the payload bytes
   (`archived / total`), restore against the picker-reported archive size,
   counted on the raw stream *below* any decompression (`consumed / size`) since
   the uncompressed total isn't known until the read finishes.

The session command is `arm64chroot --jit --fake-id --link2symlink
--work-dir /root --bind /sys:/sys -E HOME=/root … <rootfs> /bin/bash --login`.
The guest starts with a clean environment (only TERM/COLORTERM are inherited
from the host), so the userland login env is set explicitly with `-E` flags
instead of an `env -i` wrapper, and the shell runs directly. `--work-dir /root`
lands the login shell in `/root`; it is added only when that directory exists,
so a restored rootfs without it falls back to the fork()ed child's cwd (mapped
into the guest when it lies inside the rootfs, else `/`).

## Java layer

| Class | Role |
|---|---|
| `TerminalNative` | `static native` declarations, `System.loadLibrary` |
| `TerminalEmulator` | Owns the native terminal handle; feed/resize/snapshot/encode under one lock |
| `ScreenSnapshot` | Reusable flat-array copy of the viewport + cursor for rendering |
| `SessionCommand` | What to spawn: execve command or arm64chroot argv, env, cwd, tab label |
| `UserlandDistro` | Maps bundled rootfs asset names (`<id>_<version>_aarch64_rootfs.tar.xz`) to choosable distributions |
| `UserlandRootfs` | Rootfs asset detection + tar.xz install + atomic publish + arm64chroot command construction |
| `RootfsBackup` | Streams the rootfs to/from a gzip-tar file (Settings backup/restore), reusing `UserlandRootfs`'s tar reader/publish |
| `TerminalSession` | PTY fd + shell pid + reader thread; writes input; reports exit |
| `SessionManager` | Process-wide session list; survives Activity recreation |
| `TerminalView` | Canvas grid renderer, IME connection, scroll + pinch-zoom gestures |
| `ExtraKeysView` | ESC/CTRL/ALT/TAB/arrows… toolbar; CTRL/ALT are sticky modifiers |
| `TabStripView` | Horizontal session tabs + new-tab button |
| `MainActivity` | Wires the above, handles window insets |
| `OnboardingActivity` | First-run intro + distro chooser + rootfs install wizard (also reachable later in setup-only mode) |

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

Grapheme clusters — a base codepoint plus combining marks or ZWJ joins that
render as one glyph — ride out-of-band. The snapshot keeps the base codepoint
in the per-cell `codepoints` array (so width, wrap, cursor, and selection
accounting stay codepoint-based) and ships the full cluster in a separate
self-describing overflow buffer that `ScreenSnapshot.graphemeAt` decodes into a
sparse cell→string map. A cheap row-level flag (`GHOSTTY_ROW_DATA_GRAPHEME`)
gates the per-cell probe, so non-grapheme rows — nearly all of them — pay
nothing. The renderer breaks its batched run at a cluster cell and hands the
whole string to `Canvas.drawText`, letting the platform text stack shape the
combining marks / emoji sequences.

Zero-width combining marks attach to their base cell unconditionally, but full
grapheme *segmentation* — multi-consonant Indic conjuncts (consonant + virama +
consonant, e.g. `स्व`), and treating ZWJ emoji sequences as one cell — happens
only under DEC mode 2027. Programs can request it (`CSI ? 2027 h`), and a
**Combine grapheme clusters** setting force-enables it on every session
(`AppSettings.graphemeClustering` → `TerminalEmulator.setGraphemeClustering`,
applied in `MainActivity.applyTheme`). With it on, the engine groups the whole
cluster into a single cell — a conjunct becomes a *wide* (two-column) cell whose
second consonant's slot is a spacer tail — and the existing `graphemeAt`
renderer shapes it correctly. It's off by default because mode 2027 changes
column-width accounting (a cluster counts as one unit), which programs that
measure strings with libc `wcwidth` may not match. The mode is per-terminal
state that a program's RIS reset clears, so the JNI `terminalFeed` re-asserts it
after each feed while the setting is on.

#### Unicode width and bidi: what's not supported

**Ambiguous-width characters are always narrow, and that isn't
app-configurable.** East Asian "ambiguous" codepoints (eaw=A — Greek, Cyrillic,
box-drawing, and others) can be one or two cells depending on locale.
libghostty-vt resolves width internally and exposes only a binary `NARROW`/
`WIDE` classification through the C API — there is no ambiguous state and no
width-mode option. Width here is *column accounting*, not just drawing: by the
time a cell reaches the renderer the engine has already advanced the cursor one
column for an ambiguous char and placed the next char in the adjacent cell, so
widening it in the renderer alone would overlap the neighbor and desync from
the reported terminal size, cursor position, and reflow. Treating ambiguous as
double-width *correctly* means patching Ghostty's width tables and rebuilding
`libghostty-vt.a` (Zig 0.15.x, per ABI) — and likely upstream work, since
Ghostty ships no ambiguous-wide toggle. So a Settings switch for it cannot be a
pure Android-side change.

**No bidirectional (right-to-left) text.** Rendering is strictly left-to-right,
one codepoint (or cluster) per advancing cell, in logical = visual order. RTL
scripts (Arabic, Hebrew) and the Unicode bidi algorithm are not applied: the
engine stores cells in logical order and the renderer draws them L-to-R, so RTL
runs appear in reversed visual order and without contextual joining /
shaping. This is inherent to the cell-grid VT model — true bidi needs per-line
reordering and shaping that neither libghostty-vt nor the cell renderer
performs, and retrofitting it would break the 1:1 cell↔column mapping the
cursor, selection, and mouse all depend on. As with most terminal emulators,
bidi is left to the application running inside.

### Theming

Colors are a property of the terminal, not the renderer. `TerminalEmulator.
setColors` (→ `terminalSetColors`) pushes a default foreground, background,
cursor, and full 256-entry palette into libghostty-vt via the
`GHOSTTY_TERMINAL_OPT_COLOR_*` options; the render state then resolves every
cell's fg/bg through them, so the snapshot already carries final ARGB (the
Java side still does no palette logic). Defaults are exactly that — a
program's OSC 4/10/11/12 overrides still win. The effective cursor color rides
in `meta[15]` (0 = unset → renderer falls back to the foreground).

The UI side lives in `ui`: `TerminalTheme` (an immutable 19-color value object
that expands to the 256-entry palette — ANSI 0–15 plus the standard xterm cube
and grayscale), `ThemePresets` (built-in read-only themes), and `ThemeStore`
(the selection plus user themes, persisted as JSON in its own
SharedPreferences). `ThemeActivity` edits a working copy with a live
`ThemePreviewView` and `ColorPickerDialog`; `MainActivity.applyTheme` pushes
the selected theme to every open session and repaints, on session create and
in `onResume` (so edits made in the editor land when you return).

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

Selection has several entry points, all landing on the same terminal-owned
selection. **Long-press** selects the word under the finger and keeps the
finger down to drag-extend it. **Double-tap** selects the word and **triple-tap**
the whole (soft-wrap-joined) line — quick gestures with no drag (Ghostty's
`ghostty_terminal_select_word` / `ghostty_terminal_select_line`; a blank
cell/line falls back to selecting just that cell so the gesture always yields
a paste anchor). Taps are counted in `TerminalView` itself — GestureDetector's
own double-tap detection is disabled (`setOnDoubleTapListener(null)`) because it
diverts the second tap's up to `onDoubleTapEvent`, hiding it from the counter;
instead every `onSingleTapUp` advances a run of taps that are within the
platform double-tap window and slop, and acts on each as it lands, so the
single tap (keyboard / link) keeps its instant response and a third tap can
upgrade word→line. A **Select all** toolbar action
(`ghostty_terminal_select_all`) grows the selection to the whole buffer and
leaves the toolbar up. Mouse-reporting taps bypass the counter entirely (a
double click there is just two clicks). The selection is
installed as the *terminal's* active selection
(`GHOSTTY_TERMINAL_OPT_SELECTION`), where Ghostty converts it to tracked
grid refs — so it stays glued to its text across scrolling, new output,
and resize/reflow with no Java-side bookkeeping. The snapshot reports
selected cells with fg/bg swapped (inverse video, Ghostty's default
selection style — the Java renderer needed zero changes) and the
forward-ordered endpoint viewport coordinates in `meta[9..13]` for handle
placement.

`TerminalView` draws the system `textSelectHandleLeft/Right` drawables
under the endpoints — each with its pointer tip on the endpoint and its bulb
hanging outward, but flipping to the mirror-image drawable so the bulb hangs
*inward* when an endpoint sits against a screen edge, so the tip stays on the
character instead of the whole handle being shoved inward — and shows a
floating `ActionMode` toolbar: Copy and
Select all always, Paste only when the clipboard advertises text (checked via
`ClipDescription` so the button itself doesn't trigger Android's
clipboard-access toast). Select all keeps the toolbar open (it only grows the
selection); Copy and Paste dismiss it. Dragging a handle first reorders the selection so
the grabbed endpoint is the logical end (`terminalSelectionAnchor`), then
moves only that end (`terminalSelectionDrag`) — dragging across the other
endpoint flips the selection naturally, and dragging past the top/bottom
edge scrolls the viewport. Copy extracts text with
`ghostty_terminal_selection_format_alloc` (unwrapped, trimmed); Paste runs
the clip through `ghostty_paste_encode`, which strips unsafe control bytes
and applies bracketed-paste markers (mode 2004) or newline→CR. Typing, a
tap outside the handles, or switching sessions dismisses the selection.

### Search

libghostty-vt has no built-in string search, so the 🔍 find bar
(`SearchBarView`, toggled from the top bar) drives a native scan. The search
state — query, case flag, the match list, and the current index — lives in
the `TermCtx`, and two operations drive it: `terminalSearchSet` (new query)
and `terminalSearchStep` (next/previous). Each **scans and highlights in one
locked call**, so the scan can never race with PTY output landing between two
JNI calls; `terminalSearchClear` frees it.

The scan walks the whole active screen (scrollback + active area), reading
each row via one untracked grid ref per row whose column is advanced by
setting `ref.x` (the grid-ref traverse pattern), and concatenates
soft-wrapped rows into logical lines so matches that straddle a wrap are
found. The current match is installed as the terminal selection —
**reusing the selection slot** for the highlight and scroll-into-view — so it
renders as plain inverse video with no handles/Copy toolbar (the view never
enters `selecting` for search) and follows its text via tracked refs.

Navigation does not re-scan every time: `terminalFeed` sets a dirty flag, so
`terminalSearchStep` re-scans only when the buffer actually changed since the
last scan — idle next/previous is just a viewport move, not an O(buffer)
walk. Case folding (the "Aa" toggle) is *simple* (1:1) Unicode folding: ASCII
inline, other BMP letters via a generated table (`case_fold.h`, produced by
`scripts/gen-case-fold.py` from Python's Unicode database — Latin, Greek,
Cyrillic, …). It folds to lowercase, so search is case- but not
accent-insensitive (É == é, é ≠ e), and multi-character folds (ß → ss) are
omitted to keep the one-codepoint-per-cell match mapping. The whole buffer is always
scanned for an honest total count, but only the most recent `MAX_MATCHES`
hits are kept navigable (a ring buffer rotated into order after the scan) —
this bounds memory on a pathological single-character query while keeping the
newest output reachable, which is what a terminal user at the bottom of the
buffer wants. The UI numbers the current match globally, so the count stays
truthful even past the cap.

### Hyperlinks (OSC 8)

Programs mark text as a hyperlink with OSC 8 (`ESC ] 8 ; params ; URI ST`);
libghostty-vt stores the URI on each spanned cell. Two paths surface it, both
gated on the **Tap to open links** setting (`AppSettings.tapToOpenLinks` →
`TerminalView.setTapToOpenLinks`, default on):

- **Affordance.** The snapshot's per-cell probe reads
  `GHOSTTY_CELL_DATA_HAS_HYPERLINK` behind the cheap per-row
  `GHOSTTY_ROW_DATA_HYPERLINK` short-circuit (the same pattern as the grapheme
  row flag), packing an `ATTR_HYPERLINK` bit (bit 8, clear of the underline
  field) into the `attrs` array. `drawUnderline` underlines link cells that
  carry no SGR underline of their own — the only "this is tappable" cue
  available without a hover.

- **Tap.** A single tap that lands on a link cell (mapped to a viewport cell
  like the selection gesture) calls `TerminalEmulator.hyperlinkAt`, which
  resolves the cell to a `GhosttyGridRef` (the `viewport_ref` helper reused
  from selection) and reads its URI via `ghostty_grid_ref_hyperlink_uri`
  (query-then-fill; bytes, not a jstring, to keep arbitrary UTF-8 intact). The
  view then shows a preview dialog with the full destination and Open / Copy —
  deliberately a second, explicit tap, because OSC 8 lets a program's visible
  link text differ from the real target, and an accidental tap should not
  silently launch an app. Opening is a guarded `ACTION_VIEW`. Taps fall through
  to the keyboard when the cell has no link, and the mouse-reporting path still
  wins first so a program that tracks the mouse gets the click.

### Shell prompts (OSC 133)

libghostty-vt parses OSC 133 semantic-prompt sequences itself and tags each row
with its prompt state, so this is read-only from the engine — no custom parsing.
The feature is navigation-only: no inline mark is drawn (an early left-gutter bar
read as a stray `|` glyph against the first column, so it was dropped).
Navigation (`terminalPromptNav`) walks screen rows via `GHOSTTY_POINT_TAG_SCREEN`
+ `ghostty_grid_ref_row`, checking each row's `GHOSTTY_ROW_DATA_SEMANTIC_PROMPT`
(via the `screen_row_is_prompt` helper) for the nearest primary prompt
(`GHOSTTY_ROW_SEMANTIC_PROMPT`, not a continuation) above/below the viewport top,
and scrolls it to the top — the same screen-scan and delta-scroll shape as
search's `show_match`. The top-bar ⌃/⌄ buttons drive it; they surface only while
scrolled into history (via `TerminalView.ScrollStateListener`, so the tab strip
keeps full width at the live bottom) and are gated on the **Shell prompt
navigation** setting (`AppSettings.promptNav`, default off). Command exit status
(OSC 133;D) is deliberately not surfaced: the row/cell API exposes prompt regions
but not exit codes, which would need a separate parser.

### Clipboard and progress (OSC 52 / OSC 9;4)

These two are the sequences libghostty-vt recognizes but does **not** surface
through any callback, and whose payloads its OSC parser doesn't expose, so a
small stateful side-scanner (`OscSideScanner`) parses them directly. It taps the
raw PTY stream in `TerminalSession.readLoop` *before* `emulator.feed` — a passive
read, never mutating what the engine sees — with an `ESC ] … (BEL | ST)` state
machine that carries partial sequences across PTY reads (an OSC can straddle a
read boundary) and drops a payload over 1 MiB. Detected events hop the reader
thread to the main thread via the session's `Listener`, mirroring the bell/title
effects:

- **OSC 52 clipboard.** A set request (`52 ; <sel> ; <base64>`) decodes to the
  Android clipboard, gated on **Programs can set clipboard**
  (`AppSettings.clipboardWrite`, default on). A read request (`52 ; <sel> ; ?`)
  is answered with the clipboard base64-encoded back to the PTY only when
  **Programs can read clipboard** (`clipboardRead`, default **off**) is enabled —
  it lets any program exfiltrate the clipboard, and Android restricts background
  clipboard reads besides. The reply is written via
  `TerminalSession.sendClipboardResponse`, which does not count as user input.

- **OSC 9;4 progress.** A ConEmu-style report (`9 ; 4 ; <state> ; <pct>`) updates
  the session's stored progress and drives a per-tab indicator in `TabStripView`
  (a thin bar under the pill: determinate accent for normal, red for error, amber
  for paused, animated for indeterminate, hidden when cleared). Gated on
  **Progress reporting** (`AppSettings.showProgress`, default on). Ticks update
  the one tab in place (`TabStripView.setProgress`) rather than rebuilding the
  strip.

### Keyboard and extra keys

The view's `InputConnection` uses `TYPE_NULL` so soft keyboards deliver
plain key events and `commitText` instead of rich editing — the standard
trick for terminal apps. Printable text is written to the PTY as UTF-8;
navigation/function keys go through the Ghostty key encoder so applications
that switch modes (e.g. vi's DECCKM cursor keys) get correct sequences.

**Rich keyboard input** (off by default; toggled in the settings menu) is an
opt-in third path for users who want suggestions, autocorrect and swipe
typing. When enabled *and* the terminal is in a plain line-editing state, the
view instead returns a `TYPE_CLASS_TEXT` composing connection backed by a
local `Editable`. Every IME edit reconciles that buffer against what has
already been forwarded (`richSent`): it emits backspaces back to the longest
common prefix, then the new tail, so plain typing, whole-word swipe commits,
and autocorrect replacements all map onto the remote line uniformly. This
only holds while the remote cursor sits at the end of the line, so it is a
best-effort mirror — any special key, line submit (Enter), session switch, or
terminal mode change resets it. The snapshot reports when a full-screen or
raw-key program is running (alt-screen or DECCKM, packed into `meta[14]` by
`ghostty_terminal_mode_get`); rich input disables itself there and falls back
to the `TYPE_NULL` path, restarting the IME on the transition.

`ExtraKeysView` sits between the terminal and the IME. The activity is
edge-to-edge (targetSdk 36 enforces it); an insets listener pads the root by
`max(ime, navigationBars)` so the toolbar always rides directly above the
soft keyboard.

**Button glyphs are vectors, not font glyphs.** Symbol labels (arrows, Enter,
Backspace, close, search, settings, drag handle, the `⌥`/`⇧` combo prefixes…)
would otherwise depend on the device's system font and render differently — or
as tofu — per OEM. `Glyphs` keeps every button a plain `TextView` and, at build
time, swaps each known symbol codepoint for a tinted `ImageSpan` backed by an
`ic_glyph_*` vector drawable (a centered span that floors the line at the text's
own height, so a glyph-only button is the same height as a text button —
otherwise `ALIGN_CENTER` shrinks the line to the icon and stunts the button;
hence minSdk 29); letters/digits/ASCII punctuation stay as text. This handles composite labels
(a `Ctrl-←` combo shows the caret as text and the arrow as an icon) and is
self-falling-back — an unmapped glyph is left as the font glyph. Both arrow
styles in the catalog (filled triangles ▲▼◀▶ and thin ←→↑↓) map to one icon
set. No PNG fallback is needed: minSdk 29 guarantees framework VectorDrawable.
Call sites: `ExtraKeysView`, `TabStripView`, `SearchBarView`, `MainActivity`
(top bar), `ExtraKeysActivity` (editor).

### Sessions and tabs

`SessionManager` is a process singleton, so rotation/recreation keeps shells
alive. Sessions end when the process is killed (no foreground service —
a deliberate scope cut, documented in the README). Closing the last tab
finishes the activity.

When a userland rootfs is installed, new tabs default to userland and
long-pressing `+` opens an Android `/system/bin/sh` tab (and vice versa
when it isn't). On first launch (no rootfs installed, onboarding never
completed) `MainActivity` holds the first spawn back and runs
`OnboardingActivity`: an intro, a chooser over the bundled distro assets
(`UserlandDistro.bundled`, Alpine preselected) plus an "Android shell only"
opt-out, and an install step with determinate progress (tracked against the
compressed asset size, which is known — the uncompressed total isn't). The
wizard persists the outcome (`AppSettings.onboardingCompleted`, the chosen
asset, and the derived login shell/home — e.g. `/bin/ash -l` on Alpine) the
moment the install finishes, and `MainActivity` spawns the first session on
its result. Completing with "shell only" is remembered; backing out is not,
so the intro returns next launch. The same wizard reopens in setup-only mode
(chooser + install, no intro) from long-pressing `+` or the Settings
"Install Linux" row while no rootfs is installed and assets are bundled.
A rootfs already on disk marks onboarding done — existing installs never see
the intro, and there is deliberately no switch-distro path over an installed
rootfs (it holds user data; backup/restore covers replacement).
`MainActivity.EXTRA_FORCE_SHELL` pins the default to the Android shell and
skips onboarding — a test seam so UI tests don't depend on whether a rootfs
is bundled.

The rootfs directory itself is the install marker: `install` extracts into a
staging dir (`debian.tmp`) and renames it onto `debian/` only once complete,
so `debian/` exists atomically and never names a half-written rootfs (a crash
mid-extract leaves only the staging dir, discarded next time). There is no
separate marker file. The userland session type is gated on
`UserlandRootfs.isUsable`, not just `isInstalled`: the rootfs lives under
`filesDir`, which a session's own `rm -rf` (or another app) can gut, leaving
the directory but no shell. `isUsable` re-checks that `/bin/bash` actually
resolves, and `command()` refuses to spawn the emulator otherwise. Reinstalling is
gated on `isInstalled`, not `isUsable`: a rootfs that is present but broken is
deliberately *not* wiped and rebuilt — it may hold the user's data — so the
app falls back to the Android shell and leaves it alone. Two guards keep a broken rootfs from
taking the app down with it: a userland `IOException` at spawn falls back to
`/system/bin/sh`, and a userland session that exits before the user ever
typed into it (`TerminalSession.userInteracted`) as the last tab — i.e.
the emulator/bash never came up — reopens as a shell rather than `finish()`ing the
activity. The signal is the user-interaction flag, not a timeout, so it
doesn't misfire on a slow device or a fast quit.

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
- **arm64chroot runs in a fork()ed child, never exec'd.** W^X leaves
  nowhere in app data to exec from, and it is a pure emulator anyway (guest
  `execve` is an in-process ELF reload), so linking it into `libterm.so` and
  calling `arm64chroot_main()` after `fork()` needs no exec and no loader.
  The child only uses fork-safe machinery, and the emulator installs its own
  signal handlers after the fork.
- **The rootfs is an optional asset, not a download.** Builds stay
  hermetic and offline-testable; a missing tarball just disables userland
  (CI builds this way). The cost — a fatter APK — is acceptable for a
  development project. The tar reader is local code over `org.tukaani:xz`
  rather than commons-compress: the rootfs only needs files, dirs and
  links, and the dependency graph stays small.
