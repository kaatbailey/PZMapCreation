package pzformat;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * world_X_Y.lotpack, Build 42.
 *
 * CONFIRMED header against retail 42.20 (cell 49_6):
 *   char[4]   "LOTP"
 *   int32     version        (1)
 *   int32     chunkCount     (1024 = 32x32, matching the lotheader grid)
 *   int64     offset[chunkCount]     <- 64-bit, unlike B41's 32-bit table
 *
 * Header size 12 + 8*1024 = 8204, which is exactly the first chunk offset.
 *
 * Chunk bodies are parsed with the B41 scheme (int32 count per square, -1
 * introducing a run of empty squares, otherwise a room id plus count-1 tile
 * indices) and are accepted only if a chunk ends exactly where the next begins.
 */
public final class LotPackAnalysis {

    public static final String MAGIC = "LOTP";

    public static void run(Path packFile, Path headerFile) throws Exception {
        LotHeader h = LotHeader.read(headerFile);
        byte[] data = Files.readAllBytes(packFile);
        LE r = new LE(data);

        System.out.println("== .lotpack: " + packFile.getFileName()
                + "  (" + data.length + " bytes)");
        System.out.println("header: " + h.tileNames.size() + " tiles, " + h.rooms.size()
                + " rooms, levels " + h.levelsAbove + "/" + h.levelsBelow);

        String magic = new String(r.bytes(4), java.nio.charset.StandardCharsets.ISO_8859_1);
        int version = r.i32();
        int chunkCount = r.i32();
        System.out.println("\nmagic=\"" + magic + "\"  version=" + version
                + "  chunkCount=" + chunkCount);
        if (!MAGIC.equals(magic)) { System.out.println("unexpected magic; stopping"); return; }

        int headerSize = 12 + chunkCount * 8;
        long[] offsets = new long[chunkCount];
        for (int i = 0; i < chunkCount; i++) offsets[i] = r.i64();

        boolean monotonic = true, inRange = true;
        for (int i = 0; i < chunkCount; i++) {
            if (offsets[i] < headerSize || offsets[i] >= data.length) inRange = false;
            if (i > 0 && offsets[i] <= offsets[i - 1]) monotonic = false;
        }
        System.out.println("offset table (int64):");
        System.out.println("   header size      " + headerSize);
        System.out.println("   first offset     " + offsets[0]
                + (offsets[0] == headerSize ? "   MATCHES header size" : "   MISMATCH"));
        System.out.println("   last offset      " + offsets[chunkCount - 1]
                + "   file length " + data.length);
        System.out.println("   strictly increasing: " + monotonic + "    all in range: " + inRange);
        int side = (int) Math.round(Math.sqrt(chunkCount));
        if (side * side == chunkCount)
            System.out.println("   " + side + "x" + side + " chunks => chunk size "
                    + (256 / side) + " tiles");

        System.out.println("\nparsing chunk bodies (8x8 squares) at candidate level counts:");
        int bestLevels = -1;
        for (int levels : new int[]{8, 16, 32}) {
            int exact = 0, failed = 0;
            String firstFail = null;
            int probe = Math.min(chunkCount - 1, 64);
            for (int c = 0; c < probe; c++) {
                long end = offsets[c + 1];
                String res = tryChunk(data, (int) offsets[c], (int) end, 8, levels,
                        h.tileNames.size(), h.rooms.size());
                if (res == null) exact++;
                else { failed++; if (firstFail == null) firstFail = "chunk " + c + ": " + res; }
            }
            System.out.printf("   levels=%-3d  exact %d/%d%s%n", levels, exact, probe,
                    firstFail == null ? "   <-- ALL CHUNKS FIT" : "   " + firstFail);
            if (exact == probe && bestLevels < 0) bestLevels = levels;
        }

        if (bestLevels > 0) {
            System.out.println("\n>>> CHUNK BODY LAYOUT CONFIRMED, levels=" + bestLevels + " <<<");
            dumpSample(data, (int) offsets[0], (int) offsets[1], 8, bestLevels, h);
        } else {
            System.out.println("\nNo level count fit. First 48 int32s of chunk 0:");
            LE t = new LE(data);
            t.seek((int) offsets[0]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 48; i++) {
                sb.append(String.format("[%2d]%-8d", i, t.i32()));
                if (i % 6 == 5) { System.out.println("   " + sb); sb.setLength(0); }
            }
        }
    }

    /** Returns null on an exact fit, or a reason string. */
    static String tryChunk(byte[] data, int start, int end, int chunkSize, int levels,
                           int tileCount, int roomCount) {
        try {
            LE r = new LE(data);
            r.seek(start);
            int skip = 0;
            for (int z = 0; z < levels; z++)
                for (int x = 0; x < chunkSize; x++)
                    for (int y = 0; y < chunkSize; y++) {
                        if (skip > 0) { skip--; continue; }
                        if (r.pos() >= end) return "ran past end at z=" + z;
                        int count = r.i32();
                        if (count == -1) { skip = r.i32() - 1; continue; }
                        if (count < 1 || count > 256) return "count " + count + " at z=" + z;
                        int room = r.i32();
                        if (room < -1 || room > roomCount) return "room id " + room;
                        for (int i = 0; i < count - 1; i++) {
                            int idx = r.i32();
                            if (idx < 0 || idx >= tileCount) return "tile index " + idx;
                        }
                    }
            return r.pos() == end ? null : "ends at " + r.pos() + ", next chunk at " + end;
        } catch (LE.ParseException e) { return e.getMessage(); }
    }

    static void dumpSample(byte[] data, int start, int end, int chunkSize, int levels, LotHeader h) {
        LE r = new LE(data);
        r.seek(start);
        int skip = 0, shown = 0;
        System.out.println("chunk 0, first non-empty squares:");
        for (int z = 0; z < levels && shown < 6; z++)
            for (int x = 0; x < chunkSize && shown < 6; x++)
                for (int y = 0; y < chunkSize && shown < 6; y++) {
                    if (skip > 0) { skip--; continue; }
                    if (r.pos() >= end) return;
                    int count = r.i32();
                    if (count == -1) { skip = r.i32() - 1; continue; }
                    int room = r.i32();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < count - 1; i++) {
                        int idx = r.i32();
                        sb.append(idx < h.tileNames.size() ? h.tileNames.get(idx) : "?" + idx).append("  ");
                    }
                    System.out.printf("   z=%d (%d,%d) room=%-5d %s%n", z, x, y, room, sb.toString().trim());
                    shown++;
                }
    }
}
