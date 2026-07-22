/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.util.Base64;

/**
 * Passive side-scanner for the OSC sequences libghostty-vt recognizes but does
 * not surface through any callback: OSC 52 (clipboard) and OSC 9;4 (ConEmu
 * progress). It taps the raw PTY byte stream in parallel with the VT engine — it
 * never alters what the engine is fed — and reports each completed sequence to
 * an {@link OscSink}.
 *
 * The parser is a small {@code ESC ] … (BEL | ST)} state machine. State is
 * carried across {@link #scan} calls because a sequence can straddle PTY read
 * boundaries; a payload longer than {@link #MAX_PAYLOAD} is dropped (and the
 * scanner resynchronizes at its terminator) rather than buffered without bound.
 *
 * Not thread-safe: only the PTY reader thread calls {@link #scan}, and the
 * {@link OscSink} callbacks fire synchronously on that thread — implementors
 * marshal to wherever they need.
 */
final class OscSideScanner {

    interface OscSink {
        /** OSC 52 set-clipboard: the decoded bytes for selection targets {@code sel}. */
        void onClipboardWrite(String sel, byte[] data);

        /** OSC 52 clipboard query ({@code ?}); the app may answer with the clipboard. */
        void onClipboardQuery(String sel);

        /** OSC 9;4 progress: {@code state} 0..4, {@code value} 0..100 (0 when absent). */
        void onProgress(int state, int value);
    }

    /**
     * Cap on a buffered OSC payload. OSC 52 base64 for a large clipboard can be
     * sizeable, so this is generous; a longer payload is skipped, not grown.
     */
    private static final int MAX_PAYLOAD = 1 << 20;

    private static final int ST_GROUND = 0;    // outside any sequence
    private static final int ST_ESC = 1;       // saw ESC, awaiting ']'
    private static final int ST_OSC = 2;       // collecting OSC payload
    private static final int ST_OSC_ESC = 3;   // inside OSC, saw ESC (maybe ST)

    private final OscSink sink;
    private int state = ST_GROUND;
    private final StringBuilder payload = new StringBuilder();
    private boolean overflow;

    OscSideScanner(OscSink sink) {
        this.sink = sink;
    }

    /** Feeds one PTY read through the scanner. Emits to the sink as sequences complete. */
    void scan(byte[] buf, int len) {
        for (int i = 0; i < len; i++) {
            int b = buf[i] & 0xFF;
            switch (state) {
                case ST_GROUND:
                    if (b == 0x1b) state = ST_ESC;
                    break;
                case ST_ESC:
                    if (b == 0x5d) {            // ']' → OSC introducer
                        state = ST_OSC;
                        payload.setLength(0);
                        overflow = false;
                    } else {
                        // Some other escape; a fresh ESC stays pending.
                        state = b == 0x1b ? ST_ESC : ST_GROUND;
                    }
                    break;
                case ST_OSC:
                    if (b == 0x07) {           // BEL terminates
                        finish();
                    } else if (b == 0x1b) {    // maybe ST (ESC '\')
                        state = ST_OSC_ESC;
                    } else {
                        append(b);
                    }
                    break;
                case ST_OSC_ESC:
                    if (b == 0x5c) {           // '\' → ST terminates
                        finish();
                    } else if (b == 0x1b) {    // ESC ESC: abort, keep new ESC pending
                        reset();
                        state = ST_ESC;
                    } else {                   // ESC + other: not ST; abort and reprocess b
                        reset();
                        i--;
                    }
                    break;
            }
        }
    }

    private void append(int b) {
        if (b == 0) return;                    // NUL ignored (matches the engine's NUL strip)
        if (payload.length() >= MAX_PAYLOAD) {
            overflow = true;
            return;
        }
        payload.append((char) b);
    }

    private void reset() {
        state = ST_GROUND;
        payload.setLength(0);
        overflow = false;
    }

    private void finish() {
        if (!overflow) dispatch(payload.toString());
        reset();
    }

    private void dispatch(String s) {
        if (s.startsWith("52;")) {
            parseClipboard(s.substring(3));
        } else if (s.startsWith("9;4;")) {
            parseProgress(s.substring(4));
        } else if (s.equals("9;4")) {
            parseProgress("");
        }
    }

    /** {@code <selection> ; <base64 | ?>} — the body after {@code 52;}. */
    private void parseClipboard(String rest) {
        int semi = rest.indexOf(';');
        if (semi < 0) return;                  // malformed: no data field
        String sel = rest.substring(0, semi);
        String data = rest.substring(semi + 1);
        if (sel.isEmpty()) sel = "c";          // xterm default target
        if (data.equals("?")) {
            sink.onClipboardQuery(sel);
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return;                            // not valid base64
        }
        if (decoded != null) sink.onClipboardWrite(sel, decoded);
    }

    /** {@code <state> [ ; <value> ]} — the body after {@code 9;4;}. */
    private void parseProgress(String rest) {
        String stStr = rest;
        String valStr = "";
        int semi = rest.indexOf(';');
        if (semi >= 0) {
            stStr = rest.substring(0, semi);
            valStr = rest.substring(semi + 1);
            int semi2 = valStr.indexOf(';');   // ignore any trailing params
            if (semi2 >= 0) valStr = valStr.substring(0, semi2);
        }
        int st;
        try {
            st = Integer.parseInt(stStr.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (st < 0 || st > 4) return;
        int val = 0;
        if (!valStr.trim().isEmpty()) {
            try {
                val = Integer.parseInt(valStr.trim());
            } catch (NumberFormatException e) {
                val = 0;
            }
        }
        if (val < 0) val = 0;
        else if (val > 100) val = 100;
        sink.onProgress(st, val);
    }
}
