/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

/**
 * Unit tests for the OSC 52 / OSC 9;4 side-scanner. In the {@code term} package
 * so it can reach the package-private {@link OscSideScanner}; runs instrumented
 * because it decodes base64 through {@code android.util.Base64}. Sequences use
 * ESC introducers and BEL or ST (ESC backslash) terminators.
 */
@RunWith(AndroidJUnit4.class)
public class OscSideScannerTest {

    private static final class Sink implements OscSideScanner.OscSink {
        String writeSel;
        byte[] writeData;
        String querySel;
        int progressState = -1;
        int progressValue = -1;

        @Override
        public void onClipboardWrite(String sel, byte[] data) {
            writeSel = sel;
            writeData = data;
        }

        @Override
        public void onClipboardQuery(String sel) {
            querySel = sel;
        }

        @Override
        public void onProgress(int state, int value) {
            progressState = state;
            progressValue = value;
        }
    }

    private Sink sink;
    private OscSideScanner scanner;

    @Before
    public void setUp() {
        sink = new Sink();
        scanner = new OscSideScanner(sink);
    }

    private void scan(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        scanner.scan(b, b.length);
    }

    @Test
    public void clipboardWriteBelTerminated() {
        // "hi" base64-encodes to "aGk="; BEL terminates the OSC.
        scan("\u001b]52;c;aGk=\u0007");
        assertEquals("c", sink.writeSel);
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), sink.writeData);
        assertNull(sink.querySel);
    }

    @Test
    public void clipboardWriteStTerminated() {
        // ST (ESC \) also terminates. Empty selection defaults to "c".
        scan("\u001b]52;;aGk=\u001b\\");
        assertEquals("c", sink.writeSel);
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), sink.writeData);
    }

    @Test
    public void clipboardQuery() {
        scan("\u001b]52;p;?\u001b\\");
        assertEquals("p", sink.querySel);
        assertNull(sink.writeData);
    }

    @Test
    public void progressNormalWithValue() {
        scan("\u001b]9;4;1;42\u0007");
        assertEquals(1, sink.progressState);
        assertEquals(42, sink.progressValue);
    }

    @Test
    public void progressClearNoValue() {
        scan("\u001b]9;4;0\u001b\\");
        assertEquals(0, sink.progressState);
        assertEquals(0, sink.progressValue);
    }

    @Test
    public void progressValueClamped() {
        scan("\u001b]9;4;1;250\u0007");
        assertEquals(1, sink.progressState);
        assertEquals(100, sink.progressValue);
    }

    @Test
    public void splitAcrossReads() {
        // A single OSC 52 delivered in three chunks straddling reads.
        byte[] a = "\u001b]52;c;a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "Gk".getBytes(StandardCharsets.UTF_8);
        byte[] c = "=\u0007".getBytes(StandardCharsets.UTF_8);
        scanner.scan(a, a.length);
        scanner.scan(b, b.length);
        scanner.scan(c, c.length);
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), sink.writeData);
    }

    @Test
    public void unrelatedOscIgnored() {
        // OSC 0 (title) is not ours; nothing should fire.
        scan("\u001b]0;my title\u0007");
        assertNull(sink.writeData);
        assertNull(sink.querySel);
        assertEquals(-1, sink.progressState);
    }

    @Test
    public void invalidBase64Ignored() {
        scan("\u001b]52;c;@@not-base64@@\u0007");
        assertNull(sink.writeData);
    }
}
