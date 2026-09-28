/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SourceFilePositionsTest {

    @Test
    void lineColumnMatchesScanForLfAndCrlf() {
        assertMatchesScan("");
        assertMatchesScan("abc");
        assertMatchesScan("ab\ncd\n");
        assertMatchesScan("\n\n");
        assertMatchesScan("ab\r\ncd\r\n");
        assertMatchesScan("\r\n");
        assertMatchesScan("a\r\n");
        assertMatchesScan(acrossStrides());
    }

    @Test
    void cursorAgreesWithDirectLookupIncludingBackwardJump() {
        String text = "ab\ncd\r\n" + acrossStrides();
        SourceFile source = new SourceFile("mem://T.java", text);
        SourceFile.Cursor cursor = source.cursor();
        for (int offset = 0; offset <= text.length(); offset += 17) {
            assertEquals(source.lineColumn(offset), cursor.lineColumn(offset), "forward " + offset);
        }
        assertEquals(source.lineColumn(0), cursor.lineColumn(0));
        assertEquals(source.lineColumn(3), cursor.lineColumn(3));
        assertEquals(source.lineColumn(text.length()), cursor.lineColumn(text.length()));
        assertEquals(source.lineColumn(-4), cursor.lineColumn(-4));
    }

    @Test
    void offsetAtIndexesTheLineStart() {
        SourceFile lf = new SourceFile("mem://T.java", "ab\ncd\n");
        assertEquals(0, lf.offsetAt(0, 0));
        assertEquals(2, lf.offsetAt(0, 2));
        assertEquals(3, lf.offsetAt(1, 0));
        assertEquals(5, lf.offsetAt(1, 2));
        assertEquals(-1, lf.offsetAt(3, 0));
        assertEquals(-1, lf.offsetAt(-1, 0));
        assertEquals(-1, lf.offsetAt(0, -1));

        SourceFile crlf = new SourceFile("mem://T.java", "ab\r\ncd");
        assertEquals(0, crlf.offsetAt(0, 0));
        assertEquals(4, crlf.offsetAt(1, 0));
        assertEquals(-1, crlf.offsetAt(2, 0));
    }

    @Test
    void manyLookupsOnALargeBufferStayFast() {
        int lines = 50_000;
        StringBuilder buf = new StringBuilder(lines * 40);
        for (int i = 0; i < lines; i++) {
            buf.append("    String value").append(i).append(";\n");
        }
        String text = buf.toString();
        SourceFile source = new SourceFile("mem://Big.java", text);
        int[] offsets = new int[4_000];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = (int) ((long) i * text.length() / offsets.length);
        }

        SourceFile.Cursor cursor = source.cursor();
        long t0 = System.nanoTime();
        SourceFile.LineColumn[] viaCursor = new SourceFile.LineColumn[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            viaCursor[i] = cursor.lineColumn(offsets[i]);
        }
        SourceFile.LineColumn[] direct = new SourceFile.LineColumn[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            direct[i] = source.lineColumn(offsets[i]);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs < 1_000, "lookups took " + elapsedMs + "ms");

        for (int i = 0; i < offsets.length; i++) {
            assertEquals(direct[i], viaCursor[i], "offset " + offsets[i]);
        }
        assertEquals(scan(text, 0), source.lineColumn(0));
        assertEquals(scan(text, text.length() / 2), source.lineColumn(text.length() / 2));
        assertEquals(scan(text, text.length()), source.lineColumn(text.length()));
    }

    private static void assertMatchesScan(String text) {
        SourceFile source = new SourceFile("mem://T.java", text);
        SourceFile.Cursor cursor = source.cursor();
        for (int offset = -1; offset <= text.length() + 1; offset++) {
            SourceFile.LineColumn expected = scan(text, offset);
            assertEquals(expected, source.lineColumn(offset), "direct " + offset + " in " + escape(text));
            assertEquals(expected, cursor.lineColumn(offset), "cursor " + offset + " in " + escape(text));
        }
    }

    /** More than two strides, with a CRLF pair on either side of a boundary. */
    private static String acrossStrides() {
        StringBuilder buf = new StringBuilder(600);
        while (buf.length() < 250) buf.append("xxxx\n");
        buf.append("ab\r\n");
        while (buf.length() < 510) buf.append("yyyy\n");
        buf.append("cd\r\n");
        buf.append("z\n");
        return buf.toString();
    }

    /** Previous linear scan, kept as the oracle for LF and CRLF text. */
    private static SourceFile.LineColumn scan(String text, int offset) {
        int bounded = Math.max(0, Math.min(text.length(), offset));
        int line = 0;
        int column = 0;
        for (int i = 0; i < bounded; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                column = 0;
            } else if (c != '\r') {
                column++;
            }
        }
        return new SourceFile.LineColumn(line, column);
    }

    private static String escape(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
