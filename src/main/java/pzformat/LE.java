package pzformat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Little-endian reader over a byte[]. All Project Zomboid binary formats are
 * little-endian (confirmed: PZwiki "File formats").
 *
 * Tracks position so a failed parse can report *where* it went wrong, which is
 * the whole point of the probe workflow.
 */
public final class LE {
    private final byte[] b;
    private int p;

    public LE(byte[] data) { this.b = data; this.p = 0; }

    public static LE of(Path file) throws IOException { return new LE(Files.readAllBytes(file)); }

    public int pos() { return p; }
    public void seek(int newPos) { this.p = newPos; }
    public int remaining() { return b.length - p; }
    public int length() { return b.length; }
    public boolean eof() { return p >= b.length; }

    public int u8() {
        require(1);
        return b[p++] & 0xFF;
    }

    public int i32() {
        require(4);
        int v = (b[p] & 0xFF)
              | ((b[p + 1] & 0xFF) << 8)
              | ((b[p + 2] & 0xFF) << 16)
              | ((b[p + 3] & 0xFF) << 24);
        p += 4;
        return v;
    }

    public long i64() {
        require(8);
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[p + i] & 0xFFL);
        p += 8;
        return v;
    }

    /** Peek an i32 without advancing. */
    public int peekI32() { int save = p; int v = i32(); p = save; return v; }

    public byte[] bytes(int n) {
        require(n);
        byte[] out = new byte[n];
        System.arraycopy(b, p, out, 0, n);
        p += n;
        return out;
    }

    /**
     * Length-prefixed string: int32 char count, then that many single-byte chars.
     * This is the form used by .pack entry/page names.
     */
    public String lenString() {
        int n = i32();
        if (n < 0 || n > 1 << 20) {
            throw new ParseException("implausible string length " + n + " at offset " + (p - 4));
        }
        return new String(bytes(n), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * Null-terminated string. Used by .lotheader's tile-name table.
     * Also stops at \r or \n, which some writers emit as separators.
     */
    public String cString() {
        StringBuilder sb = new StringBuilder();
        while (!eof()) {
            int c = u8();
            if (c == 0 || c == '\n' || c == '\r') break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private void require(int n) {
        if (p + n > b.length) {
            throw new ParseException("read of " + n + " bytes at offset " + p
                    + " exceeds file length " + b.length);
        }
    }

    /** Hex + ASCII dump of n bytes starting at offset, for eyeballing unknown regions. */
    public String hexDump(int offset, int n) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(offset + n, b.length);
        for (int i = offset; i < end; i += 16) {
            sb.append(String.format("%08X  ", i));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < end) {
                    int v = b[i + j] & 0xFF;
                    sb.append(String.format("%02X ", v));
                    ascii.append(v >= 32 && v < 127 ? (char) v : '.');
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(' ');
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString();
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String m) { super(m); }
    }
}
