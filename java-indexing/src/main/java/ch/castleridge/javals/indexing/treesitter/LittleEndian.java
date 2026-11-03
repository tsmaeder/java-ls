/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.treesitter;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Little-endian binary helpers for the {@code tree_sitter_javals} wire protocol. */
final class LittleEndian {

    private LittleEndian() {}

    static int readU32(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException("Unexpected EOF reading u32");
        }
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    static void writeU32(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    static byte[] readBytes(InputStream in, int len) throws IOException {
        if (len < 0) {
            throw new IOException("Negative length: " + len);
        }
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException("Unexpected EOF reading " + len + " bytes");
            }
            off += n;
        }
        return buf;
    }

    static String readString(InputStream in) throws IOException {
        int len = readU32(in);
        if (len < 0) {
            throw new IOException("Negative string length");
        }
        return new String(readBytes(in, len), StandardCharsets.UTF_8);
    }

    static void writeString(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeU32(out, bytes.length);
        out.write(bytes);
    }

    static void writeBytes(OutputStream out, byte[] bytes) throws IOException {
        writeU32(out, bytes.length);
        out.write(bytes);
    }

    /** Reads a u32 from a byte array at {@code offset}; advances are caller's responsibility. */
    static int u32At(byte[] buf, int offset) {
        return (buf[offset] & 0xff)
                | ((buf[offset + 1] & 0xff) << 8)
                | ((buf[offset + 2] & 0xff) << 16)
                | ((buf[offset + 3] & 0xff) << 24);
    }
}
