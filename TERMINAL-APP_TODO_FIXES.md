# Bug-fix tasks

Findings from a full review of `app/src/main/` (Java + JNI C, 2026-07). Every task
names the file and the failure it causes. Items also listed in
`TERMINAL-APP_TODO.md` § "Correctness" are cross-referenced, not duplicated.

Reviewed and found clean (no tasks): `kitty_unicode.c`, `png_decode.c`,
`ScreenSnapshot`, `SessionManager`, `TerminalEmulator` locking, the tar
path-escape guard in `DebianRootfs.extractTar`, `StorageBindings`,
`ThemeStore`/`TerminalTheme`/`ThemePresets`, `BackgroundImageStore`,
`TerminalFontStore`, `SearchBarView`, `TabStripView`, `ExtraKeysView`,
`ExtraKeysConfig`, `Setting`/`SettingsDialog`, `ColorPickerDialog`,
`ThemePreviewView`, `Glyphs`, and the `TerminalNative` ↔ `terminal_jni.c` ↔
Ghostty-header constant mirrors (all verified value-by-value).

## Confirmed bugs

- [ ] **Search results go stale after a resize.** `terminalFeed` sets
  `c->search_dirty` (terminal_jni.c:337) so navigation re-scans after new
  output, but `terminalResize` (terminal_jni.c:389) does not — and a resize
  reflows the primary screen, shifting the screen-coordinate matches. With the
  search bar open, rotating the device or toggling the keyboard then pressing
  next/prev highlights the wrong text (or silently nothing, when the stale
  point no longer resolves). Fix: set `c->search_dirty = true` in
  `terminalResize`.

- [ ] **Restore can destroy both rootfs trees on a failed rename.**
  `DebianRootfs.publish()` (DebianRootfs.java:194) is delete-then-rename: it
  `deleteRecursively(root)` (the user's current rootfs), then `renameTo`; if
  the rename fails it also deletes the staging tree. A failure between the
  delete and the rename — or a failed rename itself — loses the old rootfs
  *and* the newly extracted one, despite `replaceFromTar`'s "non-destructive
  on failure" contract. Fix: three-phase swap — rename `debian` →
  `debian.old`, rename staging → `debian`, delete `debian.old`; on a failed
  second rename, rename `debian.old` back. (While here: `runRestore`
  (MainActivity.java:860) starts extracting immediately after the async
  `sessions.closeAll()` — PRoot gets SIGQUIT + a 1.5 s SIGKILL fallback, so a
  slow-dying tracee can still be writing into the tree during extraction. The
  long extract masks it in practice; a short wait-for-exit before
  `replaceFromTar` closes it properly.)

- [ ] **Backup silently corrupts archives containing a file ≥ 8 GiB.** The tar
  size field is 11 octal digits (max 8 GiB − 1); `putOctal`
  (RootfsBackup.java:586) writes the low bits of a larger value with no range
  check, so the header lies about the data length and every subsequent entry
  desyncs — the archive is garbage from that point, and the reader
  (`DebianRootfs.octal`, DebianRootfs.java:626) rejects the base-256 encoding
  that could express it. A Debian guest can easily hold such a file (DB, disk
  image). Fix: fail the backup with a clear message naming the file, or emit
  GNU base-256 sizes and accept them in the reader.

- [ ] **Cancelled/failed backup leaves a plausible-looking truncated archive.**
  `runBackup` (MainActivity.java:828) streams into the SAF document and on
  `InterruptedIOException`/`IOException` just shows a toast — the partial
  `.tar.gz` stays at the destination, where the user may later mistake it for
  a good backup (restore of it fails safely, but the real backup they thought
  they had doesn't exist). Fix: delete the document via
  `DocumentsContract.deleteDocument` on cancel/failure. Same block: the
  `GZIPOutputStream`/`GZIPInputStream` deflater native buffers are only
  reclaimed by the finalizer when an exception skips `finish()` — close them
  in a `finally`.

- [ ] **Non-BMP text is mishandled at two JNI string boundaries.**
  (a) `terminalTitle` (terminal_jni.c:384) passes raw UTF-8 to `NewStringUTF`,
  which requires *Modified* UTF-8 — a title with an emoji (`printf
  '\033]2;🚀\a'`) is invalid MUTF-8: CheckJNI (debug builds) aborts the
  process, release builds depend on undocumented ART leniency. The codebase
  already knows this trap — `terminalSelectionText` returns bytes for exactly
  this reason; do the same here (return `byte[]`, decode in
  `TerminalSession`).
  (b) `terminalEncodeKey` (terminal_jni.c:1292) uses `GetStringUTFChars`,
  which *produces* MUTF-8 (CESU-8 surrogate pairs for non-BMP), and the
  encoder can echo that text to the PTY — mojibake for e.g. a sticky-Ctrl +
  emoji dispatch (`TerminalView.dispatchText` single-codepoint path). Pass
  proper UTF-8 bytes from Java instead.

- [ ] **PAX record lengths counted in chars, not bytes.** `parsePax`
  (DebianRootfs.java:663) decodes the whole PAX data block to a `String` and
  then treats each record's leading length as a *character* offset — the spec
  counts bytes. Any non-ASCII `path=` value (a foreign archive with UTF-8
  filenames; our own writer uses GNU L/K so self-backups are unaffected)
  shifts the record boundary, corrupting or dropping the path override, so
  the entry restores under the wrong name. Parse on the raw bytes and decode
  each value individually.

## Likely bugs (timing-dependent)

- [ ] **Foreground-service start/stop race can crash.**
  `SessionService.refresh()` uses `startForegroundService` (a
  must-call-startForeground obligation) and `SessionService.stop()` calls
  `Context.stopService` (SessionService.java:52-59). Create-then-quickly-close
  a tab (MainActivity.closeTab → stop, MainActivity.java:467) and the service
  can be stopped before its pending `onStartCommand` runs —
  `ForegroundServiceDidNotStartInTimeException`. Fix: replace
  `Context.stopService` with a `START`-like stop action so every command path
  runs through `onStartCommand`, calls `startForeground()` first, and then
  `stopForeground`/`stopSelf`s itself (the ACTION_EXIT branch at
  SessionService.java:70 has the same hole — it exits without ever calling
  `startForeground` for the command that started it).

- [ ] **Progress dialog dismissed after activity destruction.** The
  backup/restore worker completion does `ui.dismiss()` *before* the
  `isFinishing()/isDestroyed()` guard (MainActivity.java:845-847, 891-893).
  Rotation is covered by `configChanges`, but locale/fontScale changes are
  not — the activity is destroyed mid-backup, the dialog window leaks, and the
  later `dismiss()` on the dead window can throw. Move the guard above the
  dismiss and try/catch the dismiss.

## Cross-referenced from TERMINAL-APP_TODO.md (§ Correctness)

- [ ] In-band resize (mode 2048) replies sit in `TermCtx.out` until the next
  feed — `terminalResize` never drains the write-pty buffer.
- [ ] No `onKeyUp`: key release events are never encoded (kitty keyboard
  protocol report-events).
- [ ] `map_keycode` lacks the numpad, so keypad-application-mode (DECKPAM)
  encodings can't be produced.
- [ ] `MAX_VPLACE` (32) silently drops virtual Kitty placements.

## Minor / hardening

- [ ] **`spawn_on_pty` error-path leaks.** On `fork()` failure
  (pty_jni.c:83-87) the function throws without freeing `argv`/`envp` or
  releasing the `cmd`/`cwd` string chars; `to_cstr_array` (pty_jni.c:37)
  checks neither `calloc` nor `strdup`, so OOM yields a NULL argv slot that
  truncates the exec args. One-shot leak per failed spawn; tidy the error
  path and null-check the allocations.

- [ ] **`DebianRootfs.command` ignores `tmp.mkdirs()` failure**
  (DebianRootfs.java:243-244): if `proot-tmp` can't be created,
  `PROOT_TMP_DIR` points at a missing directory and PRoot fails at runtime
  with an unrelated-looking error. Throw a descriptive IOException instead.

- [ ] **Symlink extraction can't replace a non-empty directory.** In
  `extractTar` case `'2'` (DebianRootfs.java:459-476), `target.delete()`
  fails on a non-empty dir and `Os.symlink` then throws EEXIST, aborting the
  whole restore. GNU tar handles this (dir → symlink transitions occur in
  hand-rolled archives). Delete recursively when the existing entry is a
  directory.
