package pzformat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test one hypothesis about the legacy .pack layout, derived by hand from
 * JumboTrees1x.pack:
 *
 *   int32  pageCount
 *   page:
 *     int32  nameLen; char name[nameLen]
 *     int32  entryCount
 *     int32  unknown            (1 in the observed file)
 *     entry x entryCount:
 *       int32  nameLen; char name[nameLen]
 *       int32  x, y             position in atlas
 *       int32  w, h             sprite size
 *       int32  ox, oy           offset within frame
 *       int32  fx, fy           full frame size
 *     PNG bytes
 *
 * The check that can FAIL: after walking entryCount entries, the read offset
 * must land exactly on a PNG magic. If it lands anywhere else the record shape
 * is wrong, and the run reports by how much — which says whether a field is
 * missing or extra.
 *
 * This is deliberately not a parser. It answers whether the shape is right
 * before anything is built on it.
 *
 *   java -cp out pzformat.LegacyPackProbe "$PZ/media/texturepacks/JumboTrees1x.pack"
 *   java -cp out pzformat.LegacyPackProbe "$PZ/media/texturepacks"
 */
public final class LegacyPackProbe {

    static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: LegacyPackProbe <file.pack | texturepacks dir>");
            return;
        }
        Path p = Path.of(args[0]);
        if (Files.isDirectory(p)) {
            List<Path> files = new ArrayList<>();
            try (var ds = Files.newDirectoryStream(p, "*.pack")) {
                for (Path f : ds) files.add(f);
            }
            files.sort(null);
            for (Path f : files) {
                probe(f, false);
            }
        } else {
            probe(p, true);
        }
    }

    static void probe(Path file, boolean verbose) throws IOException {
        byte[] b = Files.readAllBytes(file);
        System.out.println("== " + file.getFileName() + "  (" + b.length + " bytes)");

        List<Integer> pngAt = new ArrayList<>();
        for (int i = 0; i + PNG_MAGIC.length <= b.length; i++) {
            boolean hit = true;
            for (int k = 0; k < PNG_MAGIC.length; k++) {
                if (b[i + k] != PNG_MAGIC[k]) { hit = false; break; }
            }
            if (hit) pngAt.add(i);
        }
        System.out.println("   PNG magics: " + pngAt.size()
                + (pngAt.isEmpty() ? "" : "  first at " + pngAt.get(0)));

        Cursor c = new Cursor(b);
        try {
            int pageCount = c.i32();
            System.out.println("   pageCount: " + pageCount);
            if (pageCount < 1 || pageCount > 64) {
                System.out.println("   -> implausible, not this layout");
                return;
            }

            for (int page = 0; page < pageCount; page++) {
                String pageName = c.str();
                int entryCount = c.i32();
                int unknown = c.i32();
                System.out.printf("   page %d: \"%s\"  entries=%d  unknown=%d  (starts @%d)%n",
                        page, pageName, entryCount, unknown, c.pos);

                if (entryCount < 0 || entryCount > 100_000) {
                    System.out.println("   -> implausible entry count, hypothesis dead");
                    return;
                }

                for (int e = 0; e < entryCount; e++) {
                    String name = c.str();
                    int x = c.i32(), y = c.i32(), w = c.i32(), h = c.i32();
                    int ox = c.i32(), oy = c.i32(), fx = c.i32(), fy = c.i32();
                    if (verbose && e < 3) {
                        System.out.printf("      %-34s xy=%d,%d  wh=%dx%d  off=%d,%d  full=%dx%d%n",
                                name, x, y, w, h, ox, oy, fx, fy);
                    }
                    if (w < 0 || h < 0 || w > 8192 || h > 8192) {
                        System.out.println("      -> entry " + e + " has implausible size "
                                + w + "x" + h + "; hypothesis dead at offset " + c.pos);
                        return;
                    }
                }

                int landed = c.pos;
                Integer nearest = null;
                for (int off : pngAt) {
                    if (nearest == null || Math.abs(off - landed) < Math.abs(nearest - landed)) {
                        nearest = off;
                    }
                }
                if (nearest != null && nearest == landed) {
                    System.out.println("      landed exactly on PNG magic @" + landed + "  CONFIRMED");
                } else if (nearest != null) {
                    System.out.println("      landed @" + landed + ", nearest PNG @" + nearest
                            + "  (off by " + (nearest - landed) + " bytes)");
                    return;
                } else {
                    System.out.println("      landed @" + landed + ", no PNG found");
                    return;
                }

                // Skip the PNG: walk chunks to IEND rather than guessing a length.
                c.pos = endOfPng(b, landed);
                if (c.pos >= 0 && c.pos + 4 <= b.length) {
                    int save = c.pos;
                    if (c.i32() != 0xDEADBEEF) c.pos = save;
                }
                if (c.pos < 0) {
                    System.out.println("      could not find IEND; stopping");
                    return;
                }
            }
            System.out.println("   all pages walked, ended @" + c.pos + " of " + b.length
                    + (c.pos == b.length ? "  CLEAN" : "  (" + (b.length - c.pos) + " trailing bytes)"));
        } catch (RuntimeException ex) {
            System.out.println("   walk failed at offset " + c.pos + ": " + ex.getMessage());
        }
        System.out.println();
    }

    /** Offset just past a PNG's IEND chunk, or -1. */
    static int endOfPng(byte[] b, int start) {
        int p = start + 8;
        while (p + 8 <= b.length) {
            int len = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                    | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            String type = new String(b, p + 4, 4, StandardCharsets.US_ASCII);
            p += 8 + len + 4;
            if (type.equals("IEND")) return p;
            if (len < 0 || p > b.length) return -1;
        }
        return -1;
    }

    static final class Cursor {
        final byte[] b;
        int pos;

        Cursor(byte[] b) { this.b = b; }

        int i32() {
            if (pos + 4 > b.length) throw new RuntimeException("ran off the end");
            int v = (b[pos] & 0xFF) | ((b[pos + 1] & 0xFF) << 8)
                    | ((b[pos + 2] & 0xFF) << 16) | ((b[pos + 3] & 0xFF) << 24);
            pos += 4;
            return v;
        }

        String str() {
            int n = i32();
            if (n < 0 || n > 4096 || pos + n > b.length) {
                throw new RuntimeException("implausible string length " + n);
            }
            String s = new String(b, pos, n, StandardCharsets.US_ASCII);
            pos += n;
            return s;
        }
    }
}
