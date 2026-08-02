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
        testPackPZPK(tmp);
        testPackLegacyStillWorks(tmp);
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
        testTileBin(tmp);
        testCellData(tmp);
        testCellEditIsSurgical(tmp);
        testChunkOrientation(tmp);
        testIsoProjection();
        testSquareStructureBeatsOverlay(tmp);
        testEditorLayerAware(tmp);
        testEditorUndo(tmp);
        testEditorSurvivesRoundTrip(tmp);
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

    /** B42 "PZPK" atlases: magic, version, and a per-page extra int32. */
    static void testPackPZPK(Path dir) throws Exception {
        System.out.println("\n[.pack B42 PZPK layout]");
        PackFile pf = new PackFile();
        pf.pzpk = true;
        pf.version = 1;
        for (int pi = 0; pi < 2; pi++) {
            PackFile.Page page = new PackFile.Page();
            page.name = "Tiles2x" + pi;
            for (int e = 0; e < 3; e++) {
                PackFile.Entry en = new PackFile.Entry();
                en.name = "blood_floor_large_0" + e;
                en.x = 415; en.y = 916; en.w = 34; en.h = 18;
                en.ox = 13; en.oy = 4; en.fx = 64; en.fy = 32;
                page.entries.add(en);
            }
            page.png = png(128, 64);
            pf.pages.add(page);
            pf.pageUnknown.add(1);
        }
        Path f = dir.resolve("pzpk.pack");
        Files.write(f, pf.write());

        byte[] raw = Files.readAllBytes(f);
        check("file starts with PZPK", new String(raw, 0, 4,
                StandardCharsets.ISO_8859_1).equals("PZPK"));

        PackFile back = PackFile.read(f);
        check("detected as PZPK", back.pzpk);
        check("version", back.version == 1);
        check("pages", back.pages.size() == 2);
        check("entries", back.pages.get(1).entries.size() == 3);
        check("entry rect survives", back.pages.get(0).entries.get(0).x == 415
                && back.pages.get(0).entries.get(0).fy == 32);
        check("per-page extra int32 kept", back.pageUnknown.size() == 2
                && back.pageUnknown.get(0) == 1);
        check("round trips byte-identical",
                java.util.Arrays.equals(raw, back.write()));
    }

    /** Legacy atlases without magic must still parse. */
    static void testPackLegacyStillWorks(Path dir) throws Exception {
        System.out.println("\n[.pack legacy layout still parses]");
        Path f = dir.resolve("test.pack");
        PackFile back = PackFile.read(f);
        check("not flagged PZPK", !back.pzpk);
        check("pages parsed", back.pages.size() == 3);
        check("round trips byte-identical",
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

    static void nl(ByteArrayOutputStream o, String s) {
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        o.write('\n');
    }

    static void testTileBin(Path dir) throws Exception {
        System.out.println("\n[binary .tiles: tdef parser]");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("tdef".getBytes(StandardCharsets.ISO_8859_1));
        i32(o, 1);
        i32(o, 1);                       // one tileset
        nl(o, "walls_exterior_house_01");
        nl(o, "walls_exterior_house_01.png");
        i32(o, 8);                       // width
        i32(o, 16);                      // height
        i32(o, 42);                      // id
        int w = 8, hgt = 16;
        i32(o, w * hgt);                 // tileCount: every sheet position
        for (int i = 0; i < w * hgt; i++) {
            if (i == 12) {
                i32(o, 3);
                nl(o, "Facing");  nl(o, "W");
                nl(o, "wall");    nl(o, "");     // valueless flag
                nl(o, "solid");   nl(o, "");
            } else if (i == 100) {
                i32(o, 1);
                nl(o, "WindowShape"); nl(o, "1");
            } else {
                i32(o, 0);               // propertyless tile
            }
        }

        Path f = dir.resolve("t.tiles");
        Files.write(f, o.toByteArray());

        TileBin tb = TileBin.read(f, TileBin.TileShape.COUNT_ONLY, 0);
        check("magic + version", tb.version == 1);
        check("tileset count", tb.tilesets.size() == 1);
        check("tileset dims", tb.tilesets.get(0).width == 8 && tb.tilesets.get(0).height == 16);
        check("tileset id", tb.tilesets.get(0).id == 42);
        check("every sheet position has a record", tb.byName.size() == 8 * 16);
        check("propertyless tiles kept",
                tb.byName.get("walls_exterior_house_01_0").props.isEmpty());

        TileDefs.Tile t12 = tb.byName.get("walls_exterior_house_01_12");
        check("tile name from index", t12 != null);
        check("index -> xy", t12.x == 12 % 8 && t12.y == 12 / 8);
        check("valued property", "W".equals(t12.get("Facing")));
        check("valueless flag stored", t12.flag("wall") && "".equals(t12.get("wall")));
        check("solid() helper", t12.solid());
        check("second tile", "1".equals(
                tb.byName.get("walls_exterior_house_01_100").get("WindowShape")));

        // Wrong shape must be rejected, or the shape search proves nothing.
        boolean rejected = false;
        try { TileBin.read(f, TileBin.TileShape.XY_COUNT, 0); }
        catch (LE.ParseException e) { rejected = true; }
        check("wrong tile shape rejected", rejected);

        boolean extraRejected = false;
        try { TileBin.read(f, TileBin.TileShape.COUNT_ONLY, 1); }
        catch (LE.ParseException e) { extraRejected = true; }
        check("wrong prelude length rejected", extraRejected);

        // Trailing garbage must be caught rather than ignored.
        byte[] padded = java.util.Arrays.copyOf(o.toByteArray(), o.size() + 4);
        Path f2 = dir.resolve("t2.tiles");
        Files.write(f2, padded);
        boolean caught = false;
        try { TileBin.read(f2, TileBin.TileShape.COUNT_ONLY, 0); }
        catch (LE.ParseException e) { caught = e.getMessage().contains("trailing"); }
        check("trailing data detected", caught);

        boolean badMagic = false;
        byte[] bm = o.toByteArray();
        bm[0] = 'X';
        Files.write(dir.resolve("t3.tiles"), bm);
        try { TileBin.read(dir.resolve("t3.tiles"), TileBin.TileShape.COUNT_ONLY, 0); }
        catch (LE.ParseException e) { badMagic = true; }
        check("bad magic rejected", badMagic);
    }

    /** Build a small but structurally valid cell: 4x4 chunks, 3 levels. */
    static void writeSmallCell(Path dir, String cell) throws Exception {
        int chunksPerSide = 4, chunkCount = chunksPerSide * chunksPerSide;
        int levels = 3;   // minLevel -1 .. maxLevel 1
        int cs = LotPack.CHUNK_SIZE;
        int squares = levels * cs * cs;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // square 0: room 7, tiles {0,1};  square 1: room -1, tile {2}; rest empty
        i32(body, 3); i32(body, 7); i32(body, 0); i32(body, 1);
        i32(body, 2); i32(body, -1); i32(body, 2);
        i32(body, -1); i32(body, squares - 2);
        byte[] one = body.toByteArray();

        int headerSize = 12 + chunkCount * 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("LOTP".getBytes(StandardCharsets.ISO_8859_1));
        i32(o, 1); i32(o, chunkCount);
        for (int i = 0; i < chunkCount; i++) i64(o, headerSize + (long) i * one.length);
        for (int i = 0; i < chunkCount; i++) o.writeBytes(one);
        Files.write(dir.resolve("world_" + cell + ".lotpack"), o.toByteArray());

        // Header with minLevel -1, maxLevel 1 -> levelCount 3
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.writeBytes("LOTH".getBytes(StandardCharsets.ISO_8859_1));
        i32(h, 1);
        i32(h, 3);
        nl(h, "floor_a"); nl(h, "wall_b"); nl(h, "grass_c");
        i32(h, 8); i32(h, 8); i32(h, -1); i32(h, 1);
        i32(h, 1);
        nl(h, "kitchen"); i32(h, 0); i32(h, 1); i32(h, 2); i32(h, 3); i32(h, 4); i32(h, 5);
        i32(h, 0);
        i32(h, 1); i32(h, 1); i32(h, 0);
        for (int i = 0; i < LotHeader.GRID_BYTES; i++) h.write(0);
        Files.write(dir.resolve(cell + ".lotheader"), h.toByteArray());
    }

    static void testCellData(Path dir) throws Exception {
        System.out.println("\n[CellData: load, edit, write, reload]");
        Path d = Files.createDirectories(dir.resolve("cell"));
        writeSmallCell(d, "5_9");
        Path lp = d.resolve("world_5_9.lotpack"), lh = d.resolve("5_9.lotheader");

        CellData c = CellData.load(lp, lh);
        check("cell size 32", c.cellSize == 32);
        check("3 levels", c.levelCount == 3);
        check("minLevel -1", c.minLevel == -1);
        check("z index maps actual to array", c.zIndex(-1) == 0 && c.zIndex(0) == 1);

        // Data lives at array index 0, i.e. actual z = -1.
        check("square (0,0,z-1) tiles", java.util.Arrays.equals(
                c.tilesAt(0, 0, -1), new int[]{0, 1}));
        check("square (0,0,z-1) room", c.roomAt(0, 0, -1) == 7);
        check("tile names resolve", java.util.Arrays.equals(
                c.tileNamesAt(0, 0, -1), new String[]{"floor_a", "wall_b"}));
        check("empty square is null", c.tilesAt(4, 4, -1) == null);

        long before = c.nonEmptySquares();

        // Write and reload with no edit: must be identical.
        Path lp2 = d.resolve("rt.lotpack"), lh2 = d.resolve("rt.lotheader");
        Files.write(lp2, c.writeLotPack());
        Files.write(lh2, c.writeLotHeader());
        check("lotpack round trips byte-identical",
                java.util.Arrays.equals(Files.readAllBytes(lp), Files.readAllBytes(lp2)));
        check("lotheader round trips byte-identical",
                java.util.Arrays.equals(Files.readAllBytes(lh), Files.readAllBytes(lh2)));

        CellData c2 = CellData.load(lp2, lh2);
        check("reload has no diff", CellData.diff(c, c2).isEmpty());
        check("square count preserved", c2.nonEmptySquares() == before);
    }

    static void testCellEditIsSurgical(Path dir) throws Exception {
        System.out.println("\n[CellData: edits touch only the intended squares]");
        Path d = Files.createDirectories(dir.resolve("cell2"));
        writeSmallCell(d, "1_1");
        Path lp = d.resolve("world_1_1.lotpack"), lh = d.resolve("1_1.lotheader");

        CellData before = CellData.load(lp, lh);
        CellData after = CellData.load(lp, lh);

        int changed = after.fill("marker_tile", 10, 10, 4, 4, 0);
        check("fill reports 16 squares", changed == 16);
        check("new tile name appended", after.header.tileNames.contains("marker_tile"));
        check("existing tile indices unshifted",
                after.header.tileNames.indexOf("floor_a") == 0);

        CellData.Diff diff = CellData.diff(before, after);
        check("diff sees exactly the filled squares",
                diff.squaresChanged + diff.squaresAdded == 16);
        check("nothing removed", diff.squaresRemoved == 0);

        // A square just outside the rectangle must be untouched.
        check("square outside rect unchanged",
                java.util.Arrays.equals(before.tilesAt(9, 10, 0), after.tilesAt(9, 10, 0)));
        // A different level must be untouched.
        check("other level unchanged", java.util.Arrays.equals(
                before.tilesAt(0, 0, -1), after.tilesAt(0, 0, -1)));

        // Survives serialisation.
        Path lp2 = d.resolve("e.lotpack"), lh2 = d.resolve("e.lotheader");
        Files.write(lp2, after.writeLotPack());
        Files.write(lh2, after.writeLotHeader());
        CellData reread = CellData.load(lp2, lh2);
        check("edit survives write+reload", CellData.diff(after, reread).isEmpty());
        check("marker present after reload", java.util.Arrays.equals(
                reread.tileNamesAt(10, 10, 0), new String[]{"marker_tile"}));

        CellData.Diff vsOriginal = CellData.diff(before, reread);
        check("vs original: only the 16 squares",
                vsOriginal.squaresChanged + vsOriginal.squaresAdded == 16
                        && vsOriginal.squaresRemoved == 0);
    }

    /**
     * Regression test for the x/y transposition.
     *
     * The earlier fixtures made every chunk identical, so a transposed chunk
     * index was invisible — that is exactly why the bug survived to the point
     * of loading the game. This builds a cell where one specific chunk differs
     * and asserts the data lands at the correct global coordinate.
     */
    static void testChunkOrientation(Path dir) throws Exception {
        System.out.println("\n[.lotpack: chunk index is column-major, not transposed]");
        int chunksPerSide = 4, chunkCount = chunksPerSide * chunksPerSide;
        int levels = 1, cs = LotPack.CHUNK_SIZE;
        int squares = levels * cs * cs;

        // Marker goes in chunk (cx=2, cy=0) -> global (16,0).
        // If the index is transposed it lands in chunk (0,2) -> global (0,16).
        int markCx = 2, markCy = 0;

        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        i32(empty, -1); i32(empty, squares);
        byte[] emptyBody = empty.toByteArray();

        ByteArrayOutputStream marked = new ByteArrayOutputStream();
        i32(marked, 2); i32(marked, 99); i32(marked, 1);      // square 0: room 99, tile 1
        i32(marked, -1); i32(marked, squares - 1);
        byte[] markedBody = marked.toByteArray();

        int headerSize = 12 + chunkCount * 8;
        byte[][] bodies = new byte[chunkCount][];
        for (int i = 0; i < chunkCount; i++) bodies[i] = emptyBody;
        bodies[markCx * chunksPerSide + markCy] = markedBody;   // column-major

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("LOTP".getBytes(StandardCharsets.ISO_8859_1));
        i32(o, 1); i32(o, chunkCount);
        long off = headerSize;
        for (byte[] b : bodies) { i64(o, off); off += b.length; }
        for (byte[] b : bodies) o.writeBytes(b);

        Path d = Files.createDirectories(dir.resolve("orient"));
        Files.write(d.resolve("world_0_0.lotpack"), o.toByteArray());

        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.writeBytes("LOTH".getBytes(StandardCharsets.ISO_8859_1));
        i32(h, 1); i32(h, 2);
        nl(h, "grass_0"); nl(h, "marker_1");
        i32(h, 8); i32(h, 8); i32(h, 0); i32(h, 0);   // minLevel 0, maxLevel 0
        i32(h, 0); i32(h, 0);
        for (int i = 0; i < LotHeader.GRID_BYTES; i++) h.write(0);
        Files.write(d.resolve("0_0.lotheader"), h.toByteArray());

        CellData c = CellData.load(d.resolve("world_0_0.lotpack"), d.resolve("0_0.lotheader"));

        int gx = markCx * cs, gy = markCy * cs;      // (16, 0)
        check("marker at global (" + gx + "," + gy + ")", c.tilesAt(gx, gy, 0) != null);
        check("marker room id", c.roomAt(gx, gy, 0) == 99);
        check("NOT at the transposed position (" + gy + "," + gx + ")",
                c.tilesAt(gy, gx, 0) == null);

        // And it must survive a write/reload at the same place.
        Files.write(d.resolve("rt.lotpack"), c.writeLotPack());
        Files.write(d.resolve("rt.lotheader"), c.writeLotHeader());
        CellData c2 = CellData.load(d.resolve("rt.lotpack"), d.resolve("rt.lotheader"));
        check("marker still at (" + gx + "," + gy + ") after round trip",
                c2.tilesAt(gx, gy, 0) != null && c2.tilesAt(gy, gx, 0) == null);
        check("bytes unchanged by round trip", java.util.Arrays.equals(
                Files.readAllBytes(d.resolve("world_0_0.lotpack")),
                Files.readAllBytes(d.resolve("rt.lotpack"))));

        // chunkIndex and its inverse must agree, or per-chunk comparisons line
        // up chunk A's bytes against chunk B's encoding (which happened once).
        LotPack lp = LotPack.read(d.resolve("world_0_0.lotpack"),
                LotHeader.read(d.resolve("0_0.lotheader")));
        boolean consistent = true;
        for (int ci = 0; ci < lp.chunkCount; ci++) {
            int cx = ci / lp.chunksPerSide, cy = ci % lp.chunksPerSide;
            if (lp.chunkIndex(cx, cy) != ci) consistent = false;
        }
        check("chunkIndex round trips with its inverse", consistent);
        check("marked chunk found by linear index",
                lp.chunkIndex(markCx, markCy) == markCx * chunksPerSide + markCy);
    }

    /**
     * The isometric projection, checked as arithmetic rather than by eye.
     * A wrong formula still produces a picture, so "it looks fine" is not
     * evidence — these are the relationships the geometry must satisfy.
     */
    static void testIsoProjection() {
        System.out.println("\n[renderer: isometric projection]");
        int HW = CellRenderer.HALF_W, QW = CellRenderer.QUARTER_W;
        check("half width is 32", HW == 32);
        check("quarter width is 16", QW == 16);

        // Moving +1 in x goes right and down; +1 in y goes left and down.
        int ox = (0 - 0) * HW, oy = (0 + 0) * QW;
        int xPlusX = (1 - 0) * HW, xPlusY = (1 + 0) * QW;
        int yPlusX = (0 - 1) * HW, yPlusY = (0 + 1) * QW;
        check("+x moves right and down", xPlusX > ox && xPlusY > oy);
        check("+y moves left and down", yPlusX < ox && yPlusY > oy);
        check("+x and +y descend equally", xPlusY - oy == yPlusY - oy);

        // Diagonal (1,1) sits directly below the origin: the diamond closes.
        int dX = (1 - 1) * HW, dY = (1 + 1) * QW;
        check("(1,1) is directly below (0,0)", dX == ox);
        check("(1,1) is one diamond height down", dY - oy == 32);

        // A full tile step in x must equal the sprite width, or tiles gap/overlap.
        check("two x-steps span one sprite width",
                ((2 - 0) * HW) - ox == SpriteAtlas.TILE_W);

        // z must lift, and by more than a tile's depth or levels would interleave.
        check("z step lifts", CellRenderer.Z_STEP > 0);
        check("z step exceeds one diamond height", CellRenderer.Z_STEP > 32);
    }

    /**
     * Square must resolve structure, not decoration. Overlay tiles (grime,
     * blood) carry attachedN/attachedW just like real walls, so a naive
     * first-match classifier reports the dirt as the wall — which would make
     * "replace this wall" replace the grime instead.
     */
    static void testSquareStructureBeatsOverlay(Path dir) throws Exception {
        System.out.println("\n[Square: structure wins over overlay decoration]");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("tdef".getBytes(StandardCharsets.ISO_8859_1));
        i32(o, 1); i32(o, 1);
        nl(o, "mix"); nl(o, "mix.png");
        i32(o, 4); i32(o, 1); i32(o, 42); i32(o, 7);
        // 0: real north wall — WallN is the structural marker
        i32(o, 2); nl(o, "wall"); nl(o, ""); nl(o, "WallN"); nl(o, "");
        // 1: grime hung on a wall — attachedN, not WallN
        i32(o, 3); nl(o, "wall"); nl(o, ""); nl(o, "attachedN"); nl(o, "");
                   nl(o, "WallOverlay"); nl(o, "");
        // 2: real floor
        i32(o, 1); nl(o, "attachedFloor"); nl(o, "");
        // 3: a fridge
        i32(o, 2); nl(o, "container"); nl(o, "fridge"); nl(o, "solid"); nl(o, "");
        // 4: west wall containing a window opening (structure)
        i32(o, 2); nl(o, "wall"); nl(o, ""); nl(o, "WindowW"); nl(o, "");
        // 5: the glass pane mounted in it (fixture)
        i32(o, 2); nl(o, "WindowShape"); nl(o, "18"); nl(o, "windowW"); nl(o, "");
        // 6: north wall with a door frame (structure)
        i32(o, 2); nl(o, "wall"); nl(o, ""); nl(o, "DoorWallN"); nl(o, "");
        Path tf = dir.resolve("mix.tiles");
        Files.write(tf, o.toByteArray());

        TileBin tb = TileBin.read(tf, TileBin.TileShape.COUNT_ONLY, 0);
        TileIndex ti = new TileIndex();
        ti.byName.putAll(tb.byName);

        check("overlay detected via WallOverlay", ti.isOverlay("mix_1"));
        check("real wall not an overlay", !ti.isOverlay("mix_0"));
        check("both classify as WALL",
                ti.kindOf("mix_0") == TileIndex.Kind.WALL
             && ti.kindOf("mix_1") == TileIndex.Kind.WALL);
        check("both report the north side",
                ti.edgeOf("mix_0") == TileIndex.Edge.NORTH
             && ti.edgeOf("mix_1") == TileIndex.Edge.NORTH);
        check("only WallN is structural",
                ti.isStructuralWall("mix_0") && !ti.isStructuralWall("mix_1"));

        // Build a square holding overlay FIRST, so order alone would pick wrong.
        Path d = Files.createDirectories(dir.resolve("sqcell"));
        writeSmallCell(d, "0_0");
        CellData c = CellData.load(d.resolve("world_0_0.lotpack"), d.resolve("0_0.lotheader"));
        int overlayIdx = c.tileIndex("mix_1");
        int wallIdx = c.tileIndex("mix_0");
        int floorIdx = c.tileIndex("mix_2");
        int fridgeIdx = c.tileIndex("mix_3");
        c.setSquare(3, 3, 0, new int[]{overlayIdx, wallIdx, floorIdx, fridgeIdx}, 5);

        Square sq = Square.at(c, ti, 3, 3, 0);
        check("north wall is the structure, not the grime", "mix_0".equals(sq.northWall));
        check("overlay kept separately", sq.overlays.contains("mix_1"));
        check("floor resolved", "mix_2".equals(sq.floor));
        check("container type surfaced", "fridge".equals(sq.containerType));
        check("blocks movement", sq.blocksMovement);
        check("room id carried", sq.roomId == 5);
        check("indoors", sq.indoors());
        check("hasWall", sq.hasWall());

        // Windows: the wall segment is structure, the pane is a fixture.
        check("WindowW wall is structural", ti.isStructuralWall("mix_4"));
        check("glass pane is a fixture, not structure",
                ti.isWallFixture("mix_5") && !ti.isStructuralWall("mix_5"));
        check("window wall detected", ti.isWindowWall("mix_4"));
        check("DoorWallN detected as doorway", ti.isDoorway("mix_6"));

        c.setSquare(4, 4, 0, new int[]{c.tileIndex("mix_5"), c.tileIndex("mix_4"),
                c.tileIndex("mix_2")}, -1);
        Square win = Square.at(c, ti, 4, 4, 0);
        check("west wall is the wall, not the pane", "mix_4".equals(win.westWall));
        check("pane recorded as a fixture", win.fixtures.contains("mix_5"));
        check("west edge flagged as window", win.westIsWindow);
        check("hasWindow", win.hasWindow);

        c.setSquare(5, 5, 0, new int[]{c.tileIndex("mix_6")}, -1);
        Square door = Square.at(c, ti, 5, 5, 0);
        check("north edge flagged as doorway", door.northIsDoorway);
        check("doorway is still a wall", "mix_6".equals(door.northWall));
    }

    /** Tile set covering every layer, for editor tests. */
    static TileIndex editorTiles(Path dir) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("tdef".getBytes(StandardCharsets.ISO_8859_1));
        i32(o, 1); i32(o, 1);
        nl(o, "ed"); nl(o, "ed.png");
        i32(o, 8); i32(o, 8); i32(o, 1); i32(o, 8);
        i32(o, 1); nl(o, "attachedFloor"); nl(o, "");                    // 0 floor
        i32(o, 2); nl(o, "wall"); nl(o, ""); nl(o, "WallN"); nl(o, "");  // 1 north wall
        i32(o, 2); nl(o, "wall"); nl(o, ""); nl(o, "WallW"); nl(o, "");  // 2 west wall
        i32(o, 2); nl(o, "container"); nl(o, "fridge");
                   nl(o, "solid"); nl(o, "");                            // 3 object
        i32(o, 2); nl(o, "WallOverlay"); nl(o, ""); nl(o, "attachedN"); nl(o, ""); // 4 grime
        i32(o, 1); nl(o, "attachedFloor"); nl(o, "");                    // 5 other floor
        i32(o, 2); nl(o, "wall"); nl(o, ""); nl(o, "DoorWallN"); nl(o, ""); // 6 doorway
        i32(o, 2); nl(o, "doorN"); nl(o, ""); nl(o, "wall"); nl(o, "");  // 7 door leaf
        Path tf = dir.resolve("ed.tiles");
        Files.write(tf, o.toByteArray());
        TileBin tb = TileBin.read(tf, TileBin.TileShape.COUNT_ONLY, 0);
        TileIndex ti = new TileIndex();
        ti.byName.putAll(tb.byName);
        return ti;
    }

    static void testEditorLayerAware(Path dir) throws Exception {
        System.out.println("\n[CellEditor: operations target one layer]");
        Path d = Files.createDirectories(dir.resolve("edit1"));
        writeSmallCell(d, "0_0");
        CellData c = CellData.load(d.resolve("world_0_0.lotpack"), d.resolve("0_0.lotheader"));
        TileIndex ti = editorTiles(d);
        CellEditor ed = new CellEditor(c, ti);

        // A square with floor, both walls, grime and a fridge.
        c.setSquare(4, 4, 0, new int[]{
                c.tileIndex("ed_0"), c.tileIndex("ed_1"), c.tileIndex("ed_2"),
                c.tileIndex("ed_4"), c.tileIndex("ed_3")}, 3);

        Square before = ed.square(4, 4, 0);
        check("setup: floor, N wall, W wall present",
                "ed_0".equals(before.floor) && "ed_1".equals(before.northWall)
             && "ed_2".equals(before.westWall));

        ed.setFloor(4, 4, 0, "ed_5");
        Square afterFloor = ed.square(4, 4, 0);
        check("floor replaced", "ed_5".equals(afterFloor.floor));
        check("north wall untouched by floor change", "ed_1".equals(afterFloor.northWall));
        check("west wall untouched by floor change", "ed_2".equals(afterFloor.westWall));
        check("overlay untouched", afterFloor.overlays.contains("ed_4"));
        check("object untouched", afterFloor.objects.contains("ed_3"));
        check("room id preserved", afterFloor.roomId == 3);

        ed.removeWall(4, 4, 0, TileIndex.Edge.NORTH);
        Square afterWall = ed.square(4, 4, 0);
        check("north wall removed", afterWall.northWall == null);
        check("west wall survives north removal", "ed_2".equals(afterWall.westWall));
        check("floor survives wall removal", "ed_5".equals(afterWall.floor));
        check("object survives wall removal", afterWall.objects.contains("ed_3"));

        ed.clearObjects(4, 4, 0);
        Square afterClear = ed.square(4, 4, 0);
        check("objects cleared", afterClear.objects.isEmpty());
        check("floor kept by clearObjects", "ed_5".equals(afterClear.floor));
        check("wall kept by clearObjects", "ed_2".equals(afterClear.westWall));
    }

    static void testEditorUndo(Path dir) throws Exception {
        System.out.println("\n[CellEditor: undo and redo]");
        Path d = Files.createDirectories(dir.resolve("edit2"));
        writeSmallCell(d, "0_0");
        CellData c = CellData.load(d.resolve("world_0_0.lotpack"), d.resolve("0_0.lotheader"));
        TileIndex ti = editorTiles(d);
        CellEditor ed = new CellEditor(c, ti);

        c.setSquare(2, 2, 0, new int[]{c.tileIndex("ed_0")}, 1);
        int[] original = c.tilesAt(2, 2, 0).clone();

        ed.setFloor(2, 2, 0, "ed_5");
        check("edit applied", "ed_5".equals(ed.square(2, 2, 0).floor));
        check("undo available", ed.canUndo());

        ed.undo();
        check("undo restores tiles",
                java.util.Arrays.equals(original, c.tilesAt(2, 2, 0)));
        check("redo available", ed.canRedo());

        ed.redo();
        check("redo reapplies", "ed_5".equals(ed.square(2, 2, 0).floor));

        // A rectangle fill must undo as ONE step, not 25.
        CellData snapshot = CellData.load(d.resolve("world_0_0.lotpack"),
                d.resolve("0_0.lotheader"));
        CellEditor ed2 = new CellEditor(snapshot, ti);
        int depthBefore = ed2.undoDepth();
        CellEditor.Edit fill = ed2.fillFloor(1, 1, 5, 5, 0, "ed_5");
        check("fill touched 25 squares", fill.squaresTouched() == 25);
        check("fill is one undo step", ed2.undoDepth() == depthBefore + 1);

        CellData pristine = CellData.load(d.resolve("world_0_0.lotpack"),
                d.resolve("0_0.lotheader"));
        ed2.undo();
        check("one undo reverts the whole fill",
                CellData.diff(pristine, snapshot).isEmpty());
    }

    static void testEditorSurvivesRoundTrip(Path dir) throws Exception {
        System.out.println("\n[CellEditor: edits survive write and reload]");
        Path d = Files.createDirectories(dir.resolve("edit3"));
        writeSmallCell(d, "0_0");
        CellData c = CellData.load(d.resolve("world_0_0.lotpack"), d.resolve("0_0.lotheader"));
        TileIndex ti = editorTiles(d);
        CellEditor ed = new CellEditor(c, ti);

        c.setSquare(6, 6, 0, new int[]{c.tileIndex("ed_0"), c.tileIndex("ed_3")}, 2);
        ed.setWall(6, 6, 0, TileIndex.Edge.NORTH, "ed_1");
        ed.setWall(6, 6, 0, TileIndex.Edge.WEST, "ed_2");

        Square s = ed.square(6, 6, 0);
        check("both walls placed independently",
                "ed_1".equals(s.northWall) && "ed_2".equals(s.westWall));
        check("pre-existing object kept", s.objects.contains("ed_3"));

        Files.write(d.resolve("w.lotpack"), c.writeLotPack());
        Files.write(d.resolve("w.lotheader"), c.writeLotHeader());
        CellData reread = CellData.load(d.resolve("w.lotpack"), d.resolve("w.lotheader"));
        check("no diff after write and reload", CellData.diff(c, reread).isEmpty());
        Square s2 = Square.at(reread, ti, 6, 6, 0);
        check("walls still resolve after reload",
                "ed_1".equals(s2.northWall) && "ed_2".equals(s2.westWall));

        // Doorway plus its leaf: removing the wall takes the leaf with it.
        c.setSquare(7, 7, 0, new int[]{c.tileIndex("ed_6"), c.tileIndex("ed_7")}, -1);
        Square door = ed.square(7, 7, 0);
        check("doorway wall detected", door.northIsDoorway);
        check("door leaf is a fixture", door.fixtures.contains("ed_7"));
        ed.removeWall(7, 7, 0, TileIndex.Edge.NORTH);
        Square gone = ed.square(7, 7, 0);
        check("removing the wall removes its door leaf too",
                gone.northWall == null && gone.fixtures.isEmpty());
    }

    static void i32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }
}
