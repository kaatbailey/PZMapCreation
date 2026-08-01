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
        testB42RejectsBadIndex(tmp);
        testB41StillWorks(tmp);
        testLotPackOffsets(tmp);
        testHeaderWriterRoundTrip(tmp);
        testWriterCatchesDroppedField(tmp);
        testLotPackWriterRoundTrip(tmp);
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

    /** Full B42 layout as confirmed against retail 42.20 data. */
    static byte[] buildLotHeaderB42(String[] tiles, String[] roomNames,
                                    int[][] buildings, int unknown12) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("LOTH".getBytes(StandardCharsets.ISO_8859_1));
        i32(o, 1);
        i32(o, tiles.length);
        for (String t : tiles) {
            o.writeBytes(t.getBytes(StandardCharsets.ISO_8859_1));
            o.write('\n');
        }
        i32(o, 8);            // levelsAbove
        i32(o, 8);            // levelsBelow
        i32(o, -2);           // minLevel
        i32(o, unknown12);
        i32(o, roomNames.length);
        for (int i = 0; i < roomNames.length; i++) {
            o.writeBytes(roomNames[i].getBytes(StandardCharsets.ISO_8859_1));
            o.write('\n');
            i32(o, 0);                 // floor
            i32(o, 2);                 // rectCount
            i32(o, 10 + i); i32(o, 150 + i); i32(o, 6); i32(o, 4);
            i32(o, 20 + i); i32(o, 160 + i); i32(o, 3); i32(o, 3);
            i32(o, i == 1 ? 2 : 0);    // objectCount
            if (i == 1) { i32(o,1); i32(o,2); i32(o,3); i32(o,4); i32(o,5); i32(o,6); }
        }
        i32(o, buildings.length);
        for (int[] b : buildings) {
            i32(o, b.length);
            for (int idx : b) i32(o, idx);
        }
        for (int i = 0; i < LotHeader.GRID_BYTES; i++) o.write(i % 5);
        return o.toByteArray();
    }

    static void testLotHeaderB42(Path dir) throws Exception {
        System.out.println("\n[.lotheader B42 full structure]");
        String[] tiles = {"blends_grassoverlays_01_0", "walls_exterior_house_01_12"};
        String[] rooms = {"bank", "security", "office"};
        int[][] buildings = {{0, 1}, {2}};
        Path f = dir.resolve("b42.lotheader");
        Files.write(f, buildLotHeaderB42(tiles, rooms, buildings, 7));
        LotHeader h = LotHeader.read(f);
        check("detected as B42", h.b42);
        check("version 1", h.version == 1);
        check("tile names newline-separated", h.tileNames.size() == 2
                && h.tileNames.get(0).equals("blends_grassoverlays_01_0"));
        check("levelsAbove/Below = 8", h.levelsAbove == 8 && h.levelsBelow == 8);
        check("negative minLevel", h.minLevel == -2);
        check("unknown12 preserved", h.unknown12 == 7);
        check("3 rooms", h.rooms.size() == 3);
        check("room names", h.rooms.get(1).name.equals("security"));
        check("room rects", h.rooms.get(0).rects.size() == 2
                && java.util.Arrays.equals(h.rooms.get(0).rects.get(0), new int[]{10,150,6,4}));
        check("room objects", h.rooms.get(1).objects.size() == 2);
        check("2 buildings", h.buildings.size() == 2);
        check("building room indices",
                java.util.Arrays.equals(h.buildings.get(0), new int[]{0,1}));
        check("refs == rooms", h.roomRefs() == h.rooms.size());
        check("grid is 1024 bytes", h.chunkGrid.length == 1024);
        check("consumed to exact end", h.fullyConsumed);

        // leftover = 1 + buildings + refs  (the identity the layout was fitted on)
        int leftoverInts = 1 + h.buildings.size() + h.roomRefs();
        check("size identity holds", leftoverInts == 1 + 2 + 3);
    }

    static void testB42RejectsBadIndex(Path dir) throws Exception {
        System.out.println("\n[.lotheader B42 rejects out-of-range room index]");
        String[] tiles = {"t_0"};
        String[] rooms = {"bank"};
        int[][] buildings = {{5}};      // room 5 does not exist
        Path f = dir.resolve("bad42.lotheader");
        Files.write(f, buildLotHeaderB42(tiles, rooms, buildings, 1));
        boolean threw = false;
        try { LotHeader.read(f); } catch (LE.ParseException e) { threw = true; }
        check("throws rather than accepting garbage", threw);
    }

    static void testB41StillWorks(Path dir) throws Exception {
        System.out.println("\n[.lotheader B41 legacy still parses after B42 support]");
        Path f = dir.resolve("legacy.lotheader");
        Files.write(f, buildLotHeader(0, 300, 8, 0));
        LotHeader h = LotHeader.read(f);
        check("not flagged B42", !h.b42);
        check("cell 300", h.width == 300);
        check("rooms parsed", h.rooms.size() == 1);
    }

    // ---------------- .lotpack ----------------

    static void i64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) o.write((int) ((v >> (8 * i)) & 0xFF));
    }

    /** Build a valid B42 LOTP file: magic, version, chunkCount, int64 table, bodies. */
    static void testLotPackOffsets(Path dir) throws Exception {
        System.out.println("\n[.lotpack B42: LOTP magic, int64 offset table]");
        int chunksPerSide = 4, chunkCount = chunksPerSide * chunksPerSide;
        int cs = LotPack.CHUNK_SIZE;
        // Encode exactly ONE level, matching what a minimal-levels encoder
        // produces for a chunk whose only data is at z=0. The fixture must be
        // self-consistent or no policy can reproduce it.
        int levels = 1;
        int squares = levels * cs * cs;

        // Body per chunk: one square with 2 tiles, then skip the remainder.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        i32(body, 3); i32(body, 42); i32(body, 0); i32(body, 1);
        i32(body, -1); i32(body, squares - 1);
        byte[] one = body.toByteArray();

        int headerSize = 12 + chunkCount * 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("LOTP".getBytes(StandardCharsets.ISO_8859_1));
        i32(o, 1);
        i32(o, chunkCount);
        for (int i = 0; i < chunkCount; i++) i64(o, headerSize + (long) i * one.length);
        for (int i = 0; i < chunkCount; i++) o.writeBytes(one);

        Path f = dir.resolve("world_0_0.lotpack");
        Files.write(f, o.toByteArray());

        String[] tiles = {"floor_a", "wall_b"};
        Path hf = dir.resolve("0_0.lotheader");
        Files.write(hf, buildLotHeaderB42(tiles, new String[]{"bank"}, new int[][]{{0}}, 0));
        LotHeader h = LotHeader.read(hf);

        LotPack lp = LotPack.read(f, h);
        check("magic + version parsed", lp.version == 1);
        check("chunk count", lp.chunkCount == chunkCount);
        check("chunks per side", lp.chunksPerSide == chunksPerSide);
        check("first offset == header size", lp.offsets[0] == headerSize);

        LotPack.Chunk c = lp.chunk(0, 0);
        check("square (0,0,0) room id", c.room[0][0][0] == 42);
        check("square (0,0,0) tiles", java.util.Arrays.equals(c.tiles[0][0][0], new int[]{0, 1}));
        check("square (0,0,1) empty via skip run", c.tiles[0][0][1] == null);
        check("last square of level 0 empty", c.tiles[0][cs - 1][cs - 1] == null);
        check("levels above the encoded one are empty",
                c.tiles[LotPack.LEVELS - 1][0][0] == null);
        check("last chunk parses", lp.chunk(chunksPerSide - 1, chunksPerSide - 1) != null);
        check("tile name lookup", java.util.Arrays.equals(
                lp.tileNamesAt(0, 0, 0), new String[]{"floor_a", "wall_b"}));

        // Corrupt the magic: must be rejected, not silently misread.
        byte[] raw = Files.readAllBytes(f);
        raw[1] = 'X';
        Path bad = dir.resolve("bad.lotpack");
        Files.write(bad, raw);
        boolean threw = false;
        try { LotPack.read(bad, h); } catch (LE.ParseException e) { threw = true; }
        check("rejects wrong magic", threw);
    }

    static void testHeaderWriterRoundTrip(Path dir) throws Exception {
        System.out.println("\n[.lotheader writer: byte-identical round trip]");
        String[] tiles = {"blends_grassoverlays_01_0", "walls_exterior_house_01_12", "tree_9"};
        String[] rooms = {"bank", "security", "office"};
        int[][] buildings = {{0, 1}, {2}};
        Path f = dir.resolve("rt.lotheader");
        byte[] original = buildLotHeaderB42(tiles, rooms, buildings, 7);
        Files.write(f, original);

        LotHeader h = LotHeader.read(f);
        byte[] rewritten = h.write();
        check("same length", rewritten.length == original.length);
        check("byte identical", java.util.Arrays.equals(original, rewritten));
        check("reparses to same room count", LotHeader.read(new LE(rewritten)).rooms.size() == 3);
        check("levelCount = maxLevel - minLevel + 1", h.levelCount() == 7 - (-2) + 1);
    }

    /**
     * A writer that silently drops a field still round-trips its own output.
     * This checks the writer against bytes it did NOT produce, which is the
     * only version of the test that catches dropped data.
     */
    static void testWriterCatchesDroppedField(Path dir) throws Exception {
        System.out.println("\n[.lotheader writer: detects dropped data]");
        String[] tiles = {"a_0", "b_1"};
        String[] rooms = {"kitchen"};
        int[][] buildings = {{0}};
        byte[] original = buildLotHeaderB42(tiles, rooms, buildings, 3);
        Path f = dir.resolve("drop.lotheader");
        Files.write(f, original);

        LotHeader h = LotHeader.read(f);
        // Room 0 in the fixture carries 2 rects and 0 objects; room 1 carries
        // objects. Drop a rect and confirm the writer's output diverges.
        int before = h.rooms.get(0).rects.size();
        h.rooms.get(0).rects.remove(0);
        boolean diverged = !java.util.Arrays.equals(original, h.write());
        check("dropping a rect changes the bytes", diverged && before == 2);
    }

    static void testLotPackWriterRoundTrip(Path dir) throws Exception {
        System.out.println("\n[.lotpack writer: policy reproduces its own encoding]");
        Path f = dir.resolve("world_0_0.lotpack");
        Path hf = dir.resolve("0_0.lotheader");
        LotHeader h = LotHeader.read(hf);
        LotPack lp = LotPack.read(f, h);

        // The fixture was built with runs that span to the end of the chunk,
        // encoding one level only -> SPAN_LEVELS_MINIMAL should reproduce it.
        int exact = 0;
        for (int i = 0; i < lp.chunkCount; i++) {
            LotPack.Chunk c = lp.chunk(i % lp.chunksPerSide, i / lp.chunksPerSide);
            byte[] enc = lp.encodeChunk(c, LotPack.Policy.SPAN_LEVELS_MINIMAL);
            if (java.util.Arrays.equals(lp.rawChunk(i), enc)) exact++;
        }
        check("all chunks reproduced under SPAN_LEVELS_MINIMAL", exact == lp.chunkCount);

        byte[] rebuilt = lp.write(LotPack.Policy.SPAN_LEVELS_MINIMAL);
        check("whole file byte identical",
                java.util.Arrays.equals(Files.readAllBytes(f), rebuilt));

        // Rebuilt output must be re-readable, with offsets recomputed correctly.
        Path f2 = dir.resolve("world_0_1.lotpack");
        Files.write(f2, rebuilt);
        LotPack lp2 = LotPack.read(f2, h);
        check("rebuilt file reparses", lp2.chunkCount == lp.chunkCount);
        check("rebuilt tile data survives", java.util.Arrays.equals(
                lp2.chunk(0, 0).tiles[0][0][0], new int[]{0, 1}));
        check("rebuilt room id survives", lp2.chunk(0, 0).room[0][0][0] == 42);

        // Policies must be distinguishable, or the harness proves nothing.
        LotPack.Chunk c0 = lp.chunk(0, 0);
        byte[] span = lp.encodeChunk(c0, LotPack.Policy.SPAN_LEVELS_MINIMAL);
        byte[] full = lp.encodeChunk(c0, LotPack.Policy.SPAN_LEVELS_FULL);
        check("MINIMAL and FULL differ", !java.util.Arrays.equals(span, full));
    }

    static void i32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }
}
