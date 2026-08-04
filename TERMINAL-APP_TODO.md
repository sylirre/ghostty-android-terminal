# Terminal app TODO

Improvement tasks from a survey of `app/src/` (2026-07). The "unwired feature"
sections come from diffing every `GHOSTTY_*`/`ghostty_*` symbol used in
`app/src/main/cpp/terminal_jni.c` against the vendored headers in
`native/ghostty-vt/include/ghostty/vt/`, so each item names the exact API that
already exists in the prebuilt library.

## Correctness / likely bugs

- [ ] **Flush write-pty bytes emitted during resize.** `ghostty_terminal_resize`
  sends an in-band size report (mode 2048) and re-enables synchronized output;
  those bytes land in `TermCtx.out` via `on_write_pty`, but `terminalResize`
  (`app/src/main/cpp/terminal_jni.c`) never drains them — they sit until the
  *next* PTY output triggers `terminalFeed`. Programs that enable mode 2048
  (neovim ≥ 0.10, recent tmux) wait on that report after a resize. Fix: make
  `terminalResize` return the pending out-bytes (same contract as
  `terminalFeed`) and have `TerminalSession.resize`
  (`app/src/main/java/sh/easycli/proot/term/TerminalSession.java`) write them
  to the PTY.
- [ ] **Send key release events when the kitty keyboard protocol asks for
  them.** `TerminalView` has no `onKeyUp`, and `terminalEncodeKey` hardcodes
  `GHOSTTY_KEY_ACTION_PRESS`. Apps that set the kitty "report event types"
  flag never see releases. Needs an `action` parameter on
  `terminalEncodeKey`/`TerminalEmulator.encodeKey` plus an `onKeyUp` override
  in `TerminalView` (encode only when the terminal's kitty flags request it —
  the encoder already returns nothing otherwise). Also consider
  `GHOSTTY_KEY_ACTION_REPEAT` for `KeyEvent.getRepeatCount() > 0`.
- [ ] **Fill `map_keycode` gaps** (`terminal_jni.c`): numpad keys
  (`AKEYCODE_NUMPAD_0..9`, `NUMPAD_ADD/SUBTRACT/MULTIPLY/DIVIDE/DOT/COMMA/EQUALS`),
  `AKEYCODE_CAPS_LOCK`, `AKEYCODE_SCROLL_LOCK`, `AKEYCODE_BREAK`,
  `AKEYCODE_SYSRQ` (print screen). Unmapped keys currently rely on the utf8
  fallback, which loses keypad-application-mode encodings (DECKPAM).
- [ ] **Virtual Kitty placements silently cap at 32.** `MAX_VPLACE` in
  `terminal_jni.c` bounds pass 1 of `terminalGraphics`; a screen with more
  virtual placements (e.g. a tmux session full of placeholder images) drops
  the rest without any signal. Either grow dynamically or document the cap
  next to `KITTY_STORAGE_LIMIT_BYTES`.

## Unwired libghostty-vt features

- [ ] **OSC 8 hyperlinks.** The engine tracks them
  (`GHOSTTY_CELL_DATA_HAS_HYPERLINK`, `GHOSTTY_ROW_DATA_HYPERLINK`,
  `ghostty_grid_ref_hyperlink_uri`) but the snapshot drops them. Export a
  has-hyperlink attr bit in `attrs[]`, add a JNI call resolving viewport (x,y)
  → URI, and let `TerminalView` open the URI on tap/long-press (chooser via
  `ACTION_VIEW`) and underline linked cells.
- [ ] **Working-directory tracking (OSC 7/9/1337).**
  `GHOSTTY_TERMINAL_OPT_PWD_CHANGED` + `GHOSTTY_TERMINAL_DATA_PWD` are unused.
  Wire the callback into the buffered-event pattern (new `EVENT_PWD` bit),
  expose `TerminalSession.pwd()`, then: spawn "new tab in current directory"
  (map the `file://` URI / guest path through PRoot's rootfs prefix in
  `DebianRootfs`), optionally show the basename in the tab strip. Needs the
  rootfs shell to emit OSC 7 (add to the bundled bashrc; plain `/system/bin/sh`
  won't emit it).
- [ ] **Focus reporting (DEC mode 1004).** `ghostty_focus_encode` is unused.
  Send CSI I/O to the PTY on `onWindowFocusChanged` and on tab switch when
  `GHOSTTY_MODE_FOCUS_EVENT` is set (gate in native code like
  `terminalEncodeMouse` gates on tracking mode). Vim/tmux use this for
  autoread/dim-inactive.
- [ ] **Color-scheme reporting (CSI ? 996 n + mode 2031).**
  `GHOSTTY_TERMINAL_OPT_COLOR_SCHEME` is unused. Report
  `GHOSTTY_COLOR_SCHEME_LIGHT/DARK` from the active theme's background
  luminance (`TerminalTheme`), and push a refreshed value on theme change so
  mode-2031 subscribers (neovim `background=auto`) get the notification.
- [ ] **XTVERSION identity.** `GHOSTTY_TERMINAL_OPT_XTVERSION` unused; the
  default reply advertises "libghostty". Report the app name + `BuildConfig`
  version.
- [ ] **Underline color (SGR 58) and overline (SGR 53).** `GhosttyStyle`
  carries `underline_color` and `overline` but `terminalSnapshot` drops both —
  curly underlines (spell-check red in editors) render in the foreground
  color. Needs a per-cell underline-color channel in `ScreenSnapshot` (or a
  packed attr scheme) and drawing support in `TerminalView.drawUnderline`;
  an overline is a one-line stroke at the cell top.
- [ ] **Richer selection gestures.** Only `ghostty_terminal_select_word` is
  wired. Available and unused: `ghostty_terminal_select_line` (triple-tap or
  second long-press), `ghostty_terminal_select_all` ("Select all" in the
  ActionMode menu next to Copy/Paste in `TerminalView.selectionActions`),
  `ghostty_terminal_select_word_between` (word-granularity handle drag),
  `ghostty_terminal_selection_adjust` (hardware-keyboard selection), and the
  `GhosttySelection.rectangle` flag (block selection).
- [ ] **Formatter-based export.** `ghostty_formatter_*` (PLAIN/VT/HTML,
  whole-screen when `selection == NULL`) is unused. Tasks: "Copy all" /
  "Share transcript" (plain text of scrollback + screen), HTML export with
  styling, and — bigger — session save/restore across process death by
  serializing FORMAT_VT with the `extra` state flags and re-feeding it into a
  fresh terminal at restore.
- [ ] **"Reset terminal" action.** `ghostty_terminal_reset` (RIS) is unused;
  add a UI affordance (tab long-press menu or settings row) for recovering a
  wedged terminal, plus a "clear scrollback" variant.
- [ ] **Semantic prompt support (OSC 133).** Cells/rows carry
  `GHOSTTY_CELL_DATA_SEMANTIC_CONTENT` / `GHOSTTY_ROW_DATA_SEMANTIC_PROMPT`,
  and `ghostty_terminal_select_output` selects a command's output. Enables
  "copy last command output" and previous/next-prompt scroll jumps. Requires
  shell integration in the Debian rootfs emitting OSC 133 (same bashrc work as
  the OSC 7 task).
- [ ] **Kitty keyboard flags in the input-mode snapshot.**
  `GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS` is unused. Add an
  `INPUT_MODE_KITTY_KB` bit to `meta[14]` (`terminal_jni.c` +
  `TerminalNative`/`ScreenSnapshot`) so the rich keyboard disables itself when
  a program enables the protocol on the primary screen — today it only checks
  alt-screen/DECCKM, and kitty-protocol apps expect encoded keys, not raw
  UTF-8 from the diff mirror.
- [ ] **Password-input hint.** `GHOSTTY_RENDER_STATE_DATA_CURSOR_PASSWORD_INPUT`
  is unused. Surface it in `meta[]`; while set, force the TYPE_NULL input path
  (no IME learning/suggestions of secrets) and optionally draw a lock hint.
- [ ] **OSC 52 clipboard (harder).** The terminal effects API has no clipboard
  callback, so the engine silently ignores OSC 52. Supporting copy-from-remote
  (tmux `set-clipboard`, neovim over ssh) means intercepting OSC 52 ourselves —
  the standalone `ghostty_osc_*` parser can decode sequences we split out of
  the feed stream in `terminalFeed`. Decide on read-clipboard policy
  separately (paste-to-remote is a security prompt at minimum).
- [ ] **Kitty image file mediums (investigate first).**
  `GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE/TEMP_FILE/SHARED_MEM` are left
  disabled, so only direct (escape-stream) transmission works. File paths sent
  by guest programs are PRoot *guest* paths; libghostty-vt would open them in
  the host namespace and fail, so enabling requires guest→host path
  translation (prefix with the rootfs dir, reject escapes) — or a decision to
  keep them off, documented here.
- [ ] **Glyph protocol (investigate).** `GHOSTTY_TERMINAL_OPT_GLYPH_PROTOCOL`
  (APC-based custom glyph glossary) is off. Check what the render state
  exposes for registered glyphs before promising renderer support.
- [ ] **Show libghostty version.** `ghostty_build_info` /
  `GHOSTTY_BUILD_INFO_VERSION_STRING` unused — surface engine version (and the
  pinned commit from `scripts/fetch-ghostty.sh`) in settings/about for bug
  reports.

## Input & hardware support

- [ ] **Physical mouse / trackpad.** `TerminalView` has no
  `onGenericMotionEvent`: a Bluetooth mouse or DeX/Chromebook trackpad can't
  scroll (ACTION_SCROLL is ignored), hover never produces any-event-mode
  (1003) motion reports, and right/middle clicks do nothing. The native side
  already encodes arbitrary buttons via `terminalEncodeMouse` — add
  ACTION_SCROLL → wheel notches (buttons 4–7, or local scrollback when no
  tracking mode), right/middle button reporting (with right-click → paste as
  the no-tracking fallback, xterm-style), and hover MOTION events. Also pass
  real modifiers instead of the hardcoded `mods = 0` in
  `ghostty_mouse_event_set_mods`.
- [ ] **Hardware-keyboard shortcuts.** None exist: add Ctrl+Shift+C/V
  (copy/paste), Ctrl+Shift+N or Ctrl+Shift+T (new session), Ctrl+Shift+F
  (search bar), Ctrl+plus/minus/0 (font size), and Ctrl+Shift+arrow or
  Ctrl+Tab (tab switching), intercepted in `TerminalView.onKeyDown` before
  encoding.

## Performance (minor)

- [ ] **Dirty tracking.** `GHOSTTY_RENDER_STATE_DATA_DIRTY` and
  `GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY` are unused: every coalesced `onUpdate`
  copies the whole viewport and redraws. Skipping clean snapshots (and
  eventually per-row copies) would cut work during bursty output; measure
  first — the flat-array copy is already cheap.
- [ ] **Batch snapshot queries.** `terminalSnapshot` makes ~6 separate
  `ghostty_terminal_mode_get`/`_get` calls per frame for `meta[14]`;
  `ghostty_terminal_get_multi` exists for exactly this. Micro-optimization,
  bundle with other JNI work.

## Considered, not worth doing

- **ENQ answerback** (`GHOSTTY_TERMINAL_OPT_ENQUIRY`): nothing modern sends
  ENQ; the silent default is correct.
- **Custom device attributes** (`GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES`):
  the built-in DA1/DA2/DA3 replies are appropriate for what we render.
- **APC byte-limit overrides** (`GHOSTTY_TERMINAL_OPT_APC_MAX_BYTES*`): the
  engine defaults already bound memory; the Kitty storage limit is set
  separately in `terminalNew`.
