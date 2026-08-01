package pzformat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds synthetic fixtures matching the assumed layouts and checks that the
 * readers recover them. This proves the CODE is correct given the assumed
 * layout. It does NOT prove the assumed layout matches Project Zomboid --
 * only Probe against real game files can do that.
 */
public class SelfTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("pzfmt");
        testPackRoundTrip(tmp);
        testPackDetectsMisparse(tmp);
        testLotHeaderAlignment(tmp, 0);
        testLotHeaderAlignment(tmp, 1);
        testLotHeaderB42(tmp);
        testLotPackOffsets(tmp);
        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String name, boolean cond) {
        System.out.println((cond ? "  pass  " : "  FAIL  ") + name);
        if (!cond) failures++;
    }

    // ---------------- .pack ----------------

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, 0xFF000000 | (x * 7 + y * 13));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    static void testPackRoundTrip(Path dir) throws Exception {
        System.out.println("\n[.pack round trip]");
        PackFile pf = new PackFile();
        for (int pi = 0; pi < 3; pi++) {
            PackFile.Page page = new PackFile.Page();
            page.name = "walls_" + pi;
            for (int e = 0; e < 5; e++) {
                PackFile.Entry en = new PackFile.Entry();
                en.name = "walls_" + pi + "_" + e;
                en.x = e * 64; en.y = 0; en.w = 64; en.h = 128;
                en.ox = 1; en.oy = 2; en.fx = 64; en.fy = 128;
                page.entries.add(en);
            }
            page.png = png(320, 128);
            pf.pages.add(page);
        }
        Path f = dir.resolve("test.pack");
        Files.write(f, pf.write());

        PackFile back = PackFile.read(f);
        check("page count", back.pages.size() == 3);
        check("entry count", back.pages.get(1).entries.size() == 5);
        check("entry name", back.pages.get(1).entries.get(3).name.equals("walls_1_3"));
        check("entry rect", back.pages.get(2).entries.get(4).x == 256);
        check("png magic verified", back.pages.get(0).png.length > 8);
        check("atlas dims from png header",
                back.pages.get(0).pngWidth() == 320 && back.pages.get(0).pngHeight() == 128);
        check("re-serialise byte identical",
                java.util.Arrays.equals(Files.readAllBytes(f), back.write()));
    }

    static void testPackDetectsMisparse(Path dir) throws Exception {
        System.out.println("\n[.pack detects corrupted entry table]");
        Path f = dir.resolve("test.pack");
        byte[] raw = Files.readAllBytes(f);
        raw[4] = (byte) (raw[4] + 3); // corrupt first page-name length
        Path bad = dir.resolve("bad.pack");
        Files.write(bad, raw);
        boolean threw = false;
        String msg = "";
        try { PackFile.read(bad); } catch (LE.ParseException e) { threw = true; msg = e.getMessage(); }
        check("throws on corruption rather than returning garbage", threw);
        check("error message names the offset", msg.contains("offset") || msg.contains("implausible"));
    }

    // ---------------- .lotheader ----------------

    static byte[] buildLotHeader(int version, int cellSize, int levels, int padBytes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        i32(o, version);
        String[] tiles = {"blends_natural_01_0", "walls_exterior_house_01_12", "floors_interior_tilesandwood_01_3"};
        i32(o, tiles.length);
        for (String t : tiles) { o.writeBytes(t.getBytes(StandardCharsets.ISO_8859_1)); o.write(0); }
        for (int i = 0; i < padBytes; i++) o.write(0);
        i32(o, cellSize); i32(o, cellSize); i32(o, levels);
        // one room, one rect, no objects
        i32(o, 1);
        o.writeBytes("kitchen".getBytes(StandardCharsets.ISO_8859_1)); o.write(0);
        i32(o, 0);          // floor
        i32(o, 1);          // rectCount
        i32(o, 10); i32(o, 12); i32(o, 6); i32(o, 8);
        i32(o, 0);          // objectCount
        // one building referencing room 0
        i32(o, 1); i32(o, 1); i32(o, 0);
        // density grid
        int cells = (cellSize / 10) * (cellSize / 10);
        for (int i = 0; i < cells; i++) o.write(i % 7);
        return o.toByteArray();
    }

    static void testLotHeaderAlignment(Path dir, int pad) throws Exception {
        System.out.println("\n[.lotheader B41 layout, " + pad + " pad byte(s)]");
        Path f = dir.resolve("h" + pad + ".lotheader");
        Files.write(f, buildLotHeader(0, 300, 8, pad));
        LotHeader h = LotHeader.read(f);
        check("cell size 300", h.width == 300 && h.height == 300);
        check("levels 8", h.levels == 8);
        check("detected pad = " + pad, h.padBytesSkipped == pad);
        check("tile names", h.tileNames.size() == 3
                && h.tileNames.get(1).equals("walls_exterior_house_01_12"));
        check("room parsed", h.rooms.size() == 1 && h.rooms.get(0).name.equals("kitchen"));
        check("room rect", java.util.Arrays.equals(h.rooms.get(0).rects.get(0), new int[]{10, 12, 6, 8}));
        check("building parsed", h.buildings.size() == 1 && h.buildings.get(0)[0] == 0);
        check("density grid 900 bytes", h.zombieDensity != null && h.zombieDensity.length == 900);
        check("no warnings", h.warnings.isEmpty());
    }

    static void testLotHeaderB42(Path dir) throws Exception {
        System.out.println("\n[.lotheader B42-shaped: 256 cell, 32 levels]");
        Path f = dir.resolve("b42.lotheader");
        Files.write(f, buildLotHeader(1, 256, 32, 0));
        LotHeader h = LotHeader.read(f);
        check("cell size 256 detected", h.width == 256);
        check("levels 32", h.levels == 32);
        check("version 1", h.version == 1);
    }

    // ---------------- .lotpack ----------------

    static void testLotPackOffsets(Path dir) throws Exception {
        System.out.println("\n[.lotpack offset table + chunk body]");
        int cell = 300, chunk = 10, levels = 8;
        int cw = cell / chunk, chh = cell / chunk;   // 30x30 chunks
        int nChunks = cw * chh;
        int headerSize = 4 + nChunks * 4;

        // Each chunk body: one "skip everything" run.
        ByteArrayOutputStream bodies = new ByteArrayOutputStream();
        int[] offsets = new int[nChunks];
        for (int i = 0; i < nChunks; i++) {
            offsets[i] = headerSize + bodies.size();
            ByteArrayOutputStream c = new ByteArrayOutputStream();
            // first square: 3 entries (room + 2 tiles)
            i32(c, 3); i32(c, 42); i32(c, 0); i32(c, 1);
            // then skip the rest of the chunk
            int rest = levels * chunk * chunk - 1;
            i32(c, -1); i32(c, rest);
            bodies.writeBytes(c.toByteArray());
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        i32(o, 0);
        for (int off : offsets) i32(o, off);
        o.writeBytes(bodies.toByteArray());

        Path f = dir.resolve("world_0_0.lotpack");
        Files.write(f, o.toByteArray());

        LotPack lp = LotPack.read(f, cell, cell, levels, chunk);
        check("offset table validates clean", lp.offsetsLookValid());
        if (!lp.offsetsLookValid()) lp.warnings.forEach(w -> System.out.println("        ! " + w));
        LotPack.Chunk c = lp.readChunk(0, 0);
        check("square (0,0,0) room id", c.room[0][0][0] == 42);
        check("square (0,0,0) tiles", java.util.Arrays.equals(c.tiles[0][0][0], new int[]{0, 1}));
        check("square (0,0,1) empty via skip run", c.tiles[0][0][1] == null);
        check("last square empty", c.tiles[levels - 1][chunk - 1][chunk - 1] == null);

        // wrong chunk size must be rejected, not silently accepted
        boolean rejected;
        try {
            LotPack bad = LotPack.read(f, cell, cell, levels, 4);
            rejected = !bad.offsetsLookValid();
            if (!rejected) { try { bad.readChunk(0, 0); rejected = false; }
                             catch (LE.ParseException e) { rejected = true; } }
        } catch (LE.ParseException e) { rejected = true; }
        check("wrong chunk size is rejected", rejected);
    }

    static void i32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }
}
