package pzformat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Little-endian writer. Mirrors LE's read conventions exactly, so that
 * read(write(x)) == x by construction and write(read(bytes)) == bytes is a
 * meaningful test of whether the read model retained everything.
 */
public final class LEW {

    private final ByteArrayOutputStream o = new ByteArrayOutputStream();

    public int size() { return o.size(); }
    public byte[] toByteArray() { return o.toByteArray(); }

    public LEW u8(int v) { o.write(v & 0xFF); return this; }

    public LEW i32(int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
        return this;
    }

    public LEW i64(long v) {
        for (int i = 0; i < 8; i++) o.write((int) ((v >> (8 * i)) & 0xFF));
        return this;
    }

    public LEW bytes(byte[] b) { o.writeBytes(b); return this; }

    public LEW ascii(String s) {
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        return this;
    }

    /** Length-prefixed string, as used by .pack entry and page names. */
    public LEW lenString(String s) {
        byte[] raw = s.getBytes(StandardCharsets.ISO_8859_1);
        i32(raw.length);
        return bytes(raw);
    }

    /** Newline-terminated string, as used by B42 tile and room names. */
    public LEW nlString(String s) {
        ascii(s);
        return u8('\n');
    }
}
